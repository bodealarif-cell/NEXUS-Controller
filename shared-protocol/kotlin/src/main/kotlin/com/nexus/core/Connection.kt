package com.nexus.core

import com.nexus.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

enum class LinkState { DISCONNECTED, CONNECTING, HANDSHAKING, CONNECTED, RECONNECTING, FAILED }

data class ConnectionStatus(
    val state: LinkState = LinkState.DISCONNECTED, val detail: String = "", val attempt: Int = 0,
    val rttMs: Long? = null, val device: String? = null, val packetsSent: Long = 0
)

/**
 * Owns the connection lifecycle: link open -> handshake -> streaming; bounded exponential-backoff reconnect;
 * heartbeat + RTT measurement; neutral-state release on disconnect. Never switches transport on its own.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val newTransport: () -> Transport,
    private val pin: () -> String,
    private val settings: () -> Settings,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val handshakeTimeoutMs: Long = 3000,
    private val pongTimeoutMs: Long = 4000,
    private val pingIntervalMs: Long = 1000
) {
    private val _status = MutableStateFlow(ConnectionStatus())
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()
    val raw = MutableStateFlow(RawInput())

    @Volatile private var transport: Transport? = null
    private var job: Job? = null
    private val seq = AtomicInteger(0)
    @Volatile private var lastPong = 0L
    private val sent = AtomicInteger(0)

    private fun update(f: (ConnectionStatus) -> ConnectionStatus) = _status.update(f)

    fun connect() {
        job?.cancel(); closeTransport(); backoff.reset()
        job = scope.launch { runLoop() }
    }

    /** User-initiated disconnect: release every control, tell the receiver, close, never auto-reconnect. */
    fun disconnect() {
        job?.cancel(); job = null
        sendNeutralBestEffort(); closeTransport()
        raw.value = RawInput(racing = raw.value.racing)
        update { ConnectionStatus(LinkState.DISCONNECTED, "Disconnected") }
    }

    /** Immediately returns every control to neutral (app paused, screen left, ...). The sender loop transmits it right away. */
    fun releaseAll() { raw.value = RawInput(racing = raw.value.racing) }

    private fun closeTransport() { val t = transport; transport = null; t?.listener = null; t?.close() }

    private fun sendNeutralBestEffort() {
        val t = transport ?: return
        val buf = ByteArray(Protocol.INPUT_SIZE)
        val neutral = StateBuilder.build(RawInput(racing = raw.value.racing), settings())
        repeat(2) { Protocol.encodeInputInto(buf, neutral, seq.getAndIncrement(), nowMs()); t.send(buf, Protocol.INPUT_SIZE) }
    }

    private class LinkLost(msg: String) : IOException(msg)

    private suspend fun runLoop() {
        var firstAttempt = true
        while (true) {
            var failure = ""
            try {
                update { it.copy(state = if (firstAttempt) LinkState.CONNECTING else LinkState.RECONNECTING, detail = if (firstAttempt) "Connecting…" else "Reconnecting…") }
                firstAttempt = false
                val t = newTransport(); transport = t
                val hs = if (t.requiresHandshake) ClientHandshake(pin()) else null
                val handshakeDone = CompletableDeferred<Unit>()
                val closed = CompletableDeferred<String>()
                t.listener = object : TransportListener {
                    override fun onPacket(buf: ByteArray, len: Int) {
                        val h = hs ?: return
                        when (Protocol.peekType(buf, len)) {
                            Protocol.TYPE_PONG -> (Protocol.decodePing(buf, len, true) as? Protocol.Decoded.Ok)?.let {
                                val rtt = ((nowMs() and 0xFFFFFFFFL) - it.value) and 0xFFFFFFFFL
                                lastPong = nowMs(); if (rtt < 60_000) update { s -> s.copy(rttMs = rtt) }
                            }
                            else -> {
                                val reply = synchronized(h) { h.onPacket(buf, len) }
                                if (reply != null) t.send(reply, reply.size)
                                if (h.state == ClientHandshake.State.ACTIVE || h.state == ClientHandshake.State.REJECTED) handshakeDone.complete(Unit)
                            }
                        }
                    }
                    override fun onClosed(reason: String) { closed.complete(reason) }
                }
                t.open()
                if (hs != null) {
                    update { it.copy(state = LinkState.HANDSHAKING, detail = "Verifying PIN…") }
                    val hello = hs.start(); t.send(hello, hello.size)
                    withTimeoutOrNull(handshakeTimeoutMs) { handshakeDone.await() } ?: throw IOException("Receiver did not answer (check it is running and in range)")
                    if (hs.state == ClientHandshake.State.REJECTED) {
                        val msg = when (hs.rejectReason) {
                            Protocol.REJECT_AUTH -> "Wrong PIN."
                            Protocol.REJECT_BUSY -> "Receiver is already connected to another device."
                            Protocol.REJECT_VERSION -> "Receiver uses an incompatible protocol version."
                            4 -> "Too many wrong PINs. Wait 30 seconds."
                            else -> "Receiver rejected the connection."
                        }
                        closeTransport(); raw.value = RawInput(racing = raw.value.racing)
                        update { it.copy(state = LinkState.FAILED, detail = msg, rttMs = null) }
                        return // do not retry: retrying a wrong PIN would only trigger the receiver lockout
                    }
                }
                lastPong = nowMs(); backoff.reset()
                update { it.copy(state = LinkState.CONNECTED, detail = "Connected", attempt = 0, device = t.label, rttMs = null) }
                coroutineScope {
                    launch { senderLoop(t) }
                    if (hs != null) launch { pingLoop(t) }
                    throw LinkLost(closed.await())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failure = e.message ?: "Link error"
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            }
            closeTransport(); raw.value = RawInput(racing = raw.value.racing)
            val d = backoff.next()
            if (d == null) { update { it.copy(state = LinkState.FAILED, detail = "$failure. Tap Reconnect to try again.", rttMs = null) }; return }
            update { it.copy(state = LinkState.RECONNECTING, detail = "$failure. Retrying in ${d / 1000.0}s (attempt ${backoff.attempt}/${backoff.maxAttempts})", attempt = backoff.attempt, rttMs = null) }
            delay(d)
        }
    }

    private suspend fun senderLoop(t: Transport) {
        val buf = ByteArray(Protocol.INPUT_SIZE); val prev = ByteArray(Protocol.INPUT_SIZE)
        var havePrev = false; var lastSendAt = 0L
        while (currentCoroutineContext().isActive) {
            val s = settings(); val st = StateBuilder.build(raw.value, s); val now = nowMs()
            Protocol.encodeInputInto(buf, st, seq.get(), now)
            var changed = !havePrev
            if (havePrev) for (i in 10..31) if (buf[i] != prev[i]) { changed = true; break }
            val idle = st == st.neutralKeepingFlags()
            val refresh = now - lastSendAt >= (if (idle) 200 else 100)
            if (changed || refresh) {
                Protocol.encodeInputInto(buf, st, seq.getAndIncrement(), now)
                if (t.send(buf, Protocol.INPUT_SIZE)) { buf.copyInto(prev); havePrev = true; lastSendAt = now; _status.update { it.copy(packetsSent = it.packetsSent + 1) } }
                else havePrev = false // retry next tick so the latest state is never lost
            }
            delay(if (changed || !idle) (1000L / s.effectiveHz).coerceAtLeast(4) else if (s.performanceMode) 100 else 50)
        }
    }

    private suspend fun pingLoop(t: Transport) {
        while (currentCoroutineContext().isActive) {
            val now = nowMs()
            if (now - lastPong > pongTimeoutMs) throw IOException("Receiver stopped responding")
            val p = Protocol.encodePing(now and 0xFFFFFFFFL); t.send(p, p.size)
            delay(pingIntervalMs)
        }
    }
}
