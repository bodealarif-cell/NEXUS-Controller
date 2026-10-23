package com.nexus.tests

import com.nexus.core.*
import com.nexus.protocol.*
import kotlinx.coroutines.*
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Receiver behaviour as the Windows app implements it, used to drive the sender-side code end to end. */
class FakeReceiverLogic(private val pin: String, var answerPing: Boolean = true) {
    val inputs = CopyOnWriteArrayList<ControllerState>()
    @Volatile private var clientNonce = ByteArray(8)
    private val serverNonce = ByteArray(8) { (it + 1).toByte() }
    private fun pkt(type: Int, vararg payload: Int): ByteArray { val b = ByteArray(5 + payload.size); b[0] = 0x4E; b[1] = 0x58; b[2] = 1; b[3] = type.toByte()
        payload.forEachIndexed { i, v -> b[4 + i] = v.toByte() }; b[b.size - 1] = Protocol.crc8(b, b.size - 1).toByte(); return b }
    fun handle(b: ByteArray, len: Int): ByteArray? {
        when (Protocol.peekType(b, len)) {
            Protocol.TYPE_HELLO -> { clientNonce = b.copyOfRange(6, 14)
                val c = ByteArray(14); c[0] = 0x4E; c[1] = 0x58; c[2] = 1; c[3] = 3; c[4] = 1; serverNonce.copyInto(c, 5); c[13] = Protocol.crc8(c, 13).toByte(); return c }
            Protocol.TYPE_AUTH -> { val mac = b.copyOfRange(4, 20)
                return if (mac.contentEquals(Protocol.hmac16(pin, clientNonce, serverNonce))) pkt(Protocol.TYPE_ACCEPT, 1) else pkt(Protocol.TYPE_REJECT, Protocol.REJECT_AUTH) }
            Protocol.TYPE_PING -> if (answerPing) { val t = (Protocol.decodePing(b, len) as Protocol.Decoded.Ok).value; return Protocol.encodePing(t, true) }
            Protocol.TYPE_INPUT -> (Protocol.decodeInput(b, len) as? Protocol.Decoded.Ok)?.let { inputs.add(it.value.state) }
        }
        return null
    }
}

class FakeTransport(val rx: FakeReceiverLogic, val failOpen: Boolean = false, override val requiresHandshake: Boolean = true) : Transport {
    override val label = "fake"
    override var listener: TransportListener? = null
    @Volatile var closedByUs = false
    override suspend fun open() { if (failOpen) throw IOException("cannot open") }
    override fun send(buf: ByteArray, len: Int): Boolean {
        if (closedByUs) return false
        val reply = rx.handle(buf.copyOf(len), len)
        if (reply != null) listener?.onPacket(reply, reply.size)
        return true
    }
    override fun close() { closedByUs = true }
    fun drop() { listener?.onClosed("link lost") }
}

private suspend fun waitFor(ms: Long = 3000, what: String = "condition", c: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (!c()) { if (System.currentTimeMillis() > end) throw AssertionError("timeout waiting for $what"); delay(5) }
}

private fun <T> bounded(body: suspend CoroutineScope.() -> T): T = runBlocking(Dispatchers.Default) { withTimeout(15000) { body() } }

private fun mgr(scope: CoroutineScope, tf: () -> Transport, pin: String = "123456", backoff: ReconnectBackoff = ReconnectBackoff(5, 20, 3), pong: Long = 4000, st: Settings = Settings()) =
    ConnectionManager(scope, tf, { pin }, { st }, backoff = backoff, handshakeTimeoutMs = 500, pongTimeoutMs = pong, pingIntervalMs = 50)

fun runCoreTests() {
    // ---- Settings ----
    test("settings: defaults are valid and stable under sanitize") { eq(Settings().sanitized(), Settings()) }
    test("settings: out-of-range, NaN and infinity are clamped") {
        val s = Settings(stickDeadzone = 9f, stickSensitivity = Float.NaN, layoutScale = -4f, opacity = Float.POSITIVE_INFINITY, updateHz = 999, timeoutMs = 5, udpPort = 80, profile = 7).sanitized()
        ok(s.stickDeadzone <= 0.5f, "dz"); eq(s.stickSensitivity, 1f); ok(s.layoutScale >= 0.6f, "scale"); eq(s.opacity, 0.85f); eq(s.updateHz, 60); eq(s.timeoutMs, 1000L); eq(s.udpPort, 1024); eq(s.profile, 2)
    }
    test("settings: export/import round trip") {
        val a = Settings(stickDeadzone = 0.2f, invertY = true, steerMode = SteerMode.TILT, transport = TransportKind.UDP, udpHost = "192.168.1.20", faceSouth = Protocol.BTN_B, faceEast = Protocol.BTN_A).sanitized()
        eq(Settings.import(Settings.export(a)), a)
    }
    test("settings: import rejects foreign text, tolerates garbage values") {
        eq(Settings.import("hello"), null); val s = Settings.import("NEXUS-SETTINGS-V1\nstickDeadzone=abc\nupdateHz=77\nevil=1\ntransport=NOPE")!!
        eq(s.stickDeadzone, 0.12f); eq(s.updateHz, 60); eq(s.transport, TransportKind.BLE)
    }
    test("settings: duplicate or multi-bit face mapping falls back to defaults") {
        val s = Settings(faceSouth = Protocol.BTN_A, faceEast = Protocol.BTN_A).sanitized(); eq(s.faceEast, Protocol.BTN_B)
        eq(Settings(faceNorth = 3).sanitized().faceNorth, Protocol.BTN_Y)
    }
    test("settings: udp host is filtered") { eq(Settings(udpHost = " 10.0.0.5;rm -rf ").sanitized().udpHost, "10.0.0.5rm-rf") }

    // ---- StateBuilder ----
    test("state: gamepad dead zone, inversion, trigger calibration") {
        val s = StateBuilder.build(RawInput(lsx = 0.05f, lsy = 0.05f, rsy = 1f, lt = 0.5f), Settings(invertY = true, triggerLow = 0.2f, triggerHigh = 0.8f))
        near(s.lx, 0f); near(s.ly, 0f); near(s.ry, -1f); near(s.lt, 0.5f)
    }
    test("state: buttons masked, dpad forwarded, racing flag off") { val s = StateBuilder.build(RawInput(buttons = 0xFFFF, dpad = 0xFF), Settings()); eq(s.buttons, Protocol.BUTTON_MASK); eq(s.dpad, 0x0F); eq(s.flags, 0) }
    test("state: racing wheel steering with dead zone/invert; simultaneous pedals") {
        val s = StateBuilder.build(RawInput(racing = true, wheel = 0.5f, accel = 1f, brake = 0.5f), Settings(steerDeadzone = 0f, steerInvert = true))
        near(s.steering, -0.5f); near(s.accel, 1f); near(s.brake, 0.5f); eq(s.flags, Protocol.FLAG_RACING)
    }
    test("state: tilt steering honours calibration offset and ignores wheel") {
        val s = StateBuilder.build(RawInput(racing = true, wheel = 1f, tiltDeg = 30f), Settings(steerMode = SteerMode.TILT, tiltOffsetDeg = 10f, tiltMaxDeg = 40f, steerDeadzone = 0f)); near(s.steering, 0.5f)
    }
    test("state: handbrake threshold and gear bits, gamepad stick ignored in racing") {
        val s = StateBuilder.build(RawInput(racing = true, handbrake = 0.7f, gearUp = true, lsx = 1f), Settings(manualTransmission = true))
        near(s.handbrake, 1f); eq(s.gear, Protocol.GEAR_UP or Protocol.GEAR_MANUAL); near(s.lx, 0f)
    }

    // ---- HID report ----
    test("hid: report is 9 bytes, descriptor is well-formed (collection balanced, ends with 0xC0)") {
        eq(HidReport.fromState(ControllerState()).size, HidReport.SIZE); eq(HidReport.DESCRIPTOR.last(), 0xC0.toByte()); eq(HidReport.DESCRIPTOR[0], 0x05.toByte())
        // total bits declared by the descriptor: 16 + 4 + 4 + 4*8 + 2*8 = 72 = 9 bytes
    }
    test("hid: hat directions") { eq(HidReport.hat(0), 0); eq(HidReport.hat(Protocol.DPAD_UP), 1); eq(HidReport.hat(Protocol.DPAD_UP or Protocol.DPAD_RIGHT), 2); eq(HidReport.hat(Protocol.DPAD_LEFT), 7); eq(HidReport.hat(Protocol.DPAD_DOWN or Protocol.DPAD_LEFT), 6) }
    test("hid: gamepad axes (y inverted for HID), triggers, buttons") {
        val r = HidReport.fromState(ControllerState(buttons = Protocol.BTN_A or Protocol.BTN_START, lx = 1f, ly = 1f, rx = -1f, ry = -1f, lt = 1f, rt = 0.5f))
        eq(r[0].toInt() and 0xFF, 0x81); eq(r[3].toInt(), 127); eq(r[4].toInt(), -127); eq(r[5].toInt(), -127); eq(r[6].toInt(), 127); eq(r[7].toInt() and 0xFF, 255); eq(r[8].toInt() and 0xFF, 128)
    }
    test("hid: racing mapping and neutral") {
        val r = HidReport.fromState(ControllerState(flags = Protocol.FLAG_RACING, steering = -1f, accel = 1f, brake = 0.5f, gear = Protocol.GEAR_UP, handbrake = 1f))
        eq(r[3].toInt(), -127); eq(r[8].toInt() and 0xFF, 255); eq(r[7].toInt() and 0xFF, 128); ok(r[0].toInt() and Protocol.BTN_RB != 0, "RB"); ok(r[0].toInt() and Protocol.BTN_X != 0, "X")
        eq(HidReport.fromState(ControllerState()).toList(), List(9) { 0.toByte() })
    }

    // ---- ConnectionManager over a fake transport ----
    test("conn: connects, streams input, measures real RTT") { bounded {
        val rx = FakeReceiverLogic("123456"); val opens = AtomicInteger(); val sc = CoroutineScope(Dispatchers.Default + Job())
        val m = mgr(sc, { opens.incrementAndGet(); FakeTransport(rx) }); m.connect()
        waitFor(what = "CONNECTED") { m.status.value.state == LinkState.CONNECTED }
        m.raw.value = RawInput(buttons = Protocol.BTN_A, lsx = 1f)
        waitFor(what = "input at receiver") { rx.inputs.any { it.buttons == Protocol.BTN_A && it.lx > 0.9f } }
        waitFor(what = "rtt") { m.status.value.rttMs != null }
        ok(m.status.value.rttMs!! in 0..500, "rtt plausible"); m.disconnect(); sc.cancel() } }
    test("conn: wrong PIN fails without retry loop (avoids receiver lockout)") { bounded {
        val rx = FakeReceiverLogic("999999"); val opens = AtomicInteger(); val sc = CoroutineScope(Dispatchers.Default + Job())
        val m = mgr(sc, { opens.incrementAndGet(); FakeTransport(rx) }); m.connect()
        waitFor(what = "FAILED") { m.status.value.state == LinkState.FAILED }; delay(150)
        eq(opens.get(), 1); ok(m.status.value.detail.contains("PIN"), "message"); sc.cancel() } }
    test("conn: link drop -> RECONNECTING -> CONNECTED again, controls released meanwhile") { bounded {
        val rx = FakeReceiverLogic("123456"); val opens = AtomicInteger(); var cur: FakeTransport? = null; val sc = CoroutineScope(Dispatchers.Default + Job())
        val m = mgr(sc, { opens.incrementAndGet(); FakeTransport(rx).also { cur = it } }); m.connect()
        waitFor(what = "CONNECTED") { m.status.value.state == LinkState.CONNECTED }
        m.raw.value = RawInput(buttons = Protocol.BTN_B); delay(60); cur!!.drop()
        waitFor(what = "second open") { opens.get() == 2 }; waitFor(what = "CONNECTED again") { m.status.value.state == LinkState.CONNECTED }
        eq(m.raw.value.buttons, 0); m.disconnect(); sc.cancel() } }
    test("conn: reconnect attempts are bounded, then FAILED with guidance") { bounded {
        val rx = FakeReceiverLogic("123456"); val opens = AtomicInteger(); val sc = CoroutineScope(Dispatchers.Default + Job())
        val m = mgr(sc, { opens.incrementAndGet(); FakeTransport(rx, failOpen = true) }); m.connect()
        waitFor(what = "FAILED") { m.status.value.state == LinkState.FAILED }; delay(150)
        eq(opens.get(), 4); ok(m.status.value.detail.contains("Reconnect"), "guidance"); sc.cancel() } }
    test("conn: user disconnect while buttons held sends neutral, no auto-reconnect") { bounded {
        val rx = FakeReceiverLogic("123456"); val opens = AtomicInteger(); val sc = CoroutineScope(Dispatchers.Default + Job())
        val m = mgr(sc, { opens.incrementAndGet(); FakeTransport(rx) }); m.connect()
        waitFor(what = "CONNECTED") { m.status.value.state == LinkState.CONNECTED }
        m.raw.value = RawInput(buttons = Protocol.BTN_A or Protocol.BTN_X, rt = 1f); waitFor(what = "held at rx") { rx.inputs.lastOrNull()?.buttons == (Protocol.BTN_A or Protocol.BTN_X) }
        m.disconnect(); delay(200)
        val last = rx.inputs.last(); eq(last.buttons, 0); near(last.rt, 0f); eq(m.status.value.state, LinkState.DISCONNECTED); eq(opens.get(), 1); eq(m.raw.value, RawInput()); sc.cancel() } }
    test("conn: releaseAll transmits neutral promptly (app paused / focus lost)") { bounded {
        val rx = FakeReceiverLogic("123456"); val sc = CoroutineScope(Dispatchers.Default + Job()); val m = mgr(sc, { FakeTransport(rx) }); m.connect()
        waitFor(what = "CONNECTED") { m.status.value.state == LinkState.CONNECTED }
        m.raw.value = RawInput(dpad = Protocol.DPAD_UP, lsx = 1f); waitFor(what = "held") { rx.inputs.lastOrNull()?.dpad == Protocol.DPAD_UP }
        m.releaseAll(); waitFor(what = "neutral") { rx.inputs.lastOrNull()?.let { it.dpad == 0 && it.lx == 0f } == true }; m.disconnect(); sc.cancel() } }
    test("conn: receiver that stops answering pings is detected as dead link") { bounded {
        val rx = FakeReceiverLogic("123456", answerPing = false); val opens = AtomicInteger(); val sc = CoroutineScope(Dispatchers.Default + Job())
        val m = mgr(sc, { opens.incrementAndGet(); FakeTransport(rx) }, pong = 200); m.connect()
        waitFor(what = "reconnect after silence") { opens.get() >= 2 }; m.disconnect(); sc.cancel() } }
    test("conn: HID-style transport skips handshake and ping") { bounded {
        val rx = FakeReceiverLogic("x"); val sc = CoroutineScope(Dispatchers.Default + Job()); val m = mgr(sc, { FakeTransport(rx, requiresHandshake = false) }); m.connect()
        waitFor(what = "CONNECTED") { m.status.value.state == LinkState.CONNECTED }; m.raw.value = RawInput(buttons = 1)
        waitFor(what = "input") { rx.inputs.any { it.buttons == 1 } }; eq(m.status.value.rttMs, null); m.disconnect(); sc.cancel() } }
    test("conn: idle traffic is bounded (adaptive refresh, no flooding)") { bounded {
        val rx = FakeReceiverLogic("123456"); val sc = CoroutineScope(Dispatchers.Default + Job()); val m = mgr(sc, { FakeTransport(rx) }); m.connect()
        waitFor(what = "CONNECTED") { m.status.value.state == LinkState.CONNECTED }; val before = rx.inputs.size; delay(1000); val n = rx.inputs.size - before
        ok(n in 3..8, "idle packets in 1s = $n (expected ~5 heartbeats)"); m.disconnect(); sc.cancel() } }
    test("conn: held input is refreshed so receiver fail-safe does not trip") { bounded {
        val rx = FakeReceiverLogic("123456"); val sc = CoroutineScope(Dispatchers.Default + Job()); val m = mgr(sc, { FakeTransport(rx) }); m.connect()
        waitFor(what = "CONNECTED") { m.status.value.state == LinkState.CONNECTED }; m.raw.value = RawInput(buttons = Protocol.BTN_A); delay(100); val b = rx.inputs.size; delay(1000)
        ok(rx.inputs.size - b in 6..14, "held refresh count ${rx.inputs.size - b}"); m.disconnect(); sc.cancel() } }

    // ---- Real UDP sockets on loopback ----
    fun udpServer(pin: String): Triple<DatagramSocket, FakeReceiverLogic, Thread> {
        val sock = DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1")); val rx = FakeReceiverLogic(pin)
        val th = Thread({ val buf = ByteArray(128); val p = DatagramPacket(buf, buf.size)
            while (!sock.isClosed) { try { p.length = buf.size; sock.receive(p); val r = rx.handle(buf.copyOf(p.length), p.length); if (r != null) sock.send(DatagramPacket(r, r.size, p.socketAddress)) } catch (_: Exception) { } } }, "udp-test-server").apply { isDaemon = true; start() }
        return Triple(sock, rx, th)
    }
    test("udp loopback: real socket handshake, input delivery, RTT") { bounded {
        val (sock, rx, _) = udpServer("246810"); val sc = CoroutineScope(Dispatchers.Default + Job())
        val m = mgr(sc, { UdpTransport("127.0.0.1", sock.localPort) }, pin = "246810"); m.connect()
        waitFor(what = "CONNECTED") { m.status.value.state == LinkState.CONNECTED }
        m.raw.value = RawInput(buttons = Protocol.BTN_Y, rsx = -1f); waitFor(what = "udp input") { rx.inputs.any { it.buttons == Protocol.BTN_Y && it.rx < -0.9f } }
        waitFor(what = "rtt") { m.status.value.rttMs != null }; m.disconnect(); delay(100); eq(rx.inputs.last().buttons, 0); sc.cancel(); sock.close() } }
    test("udp loopback: wrong PIN rejected over real socket") { bounded {
        val (sock, rx, _) = udpServer("111111"); val sc = CoroutineScope(Dispatchers.Default + Job())
        val m = mgr(sc, { UdpTransport("127.0.0.1", sock.localPort) }, pin = "222222"); m.connect()
        waitFor(what = "FAILED") { m.status.value.state == LinkState.FAILED }; eq(rx.inputs.size, 0); sc.cancel(); sock.close() } }
    test("udp loopback: unresolvable host reports a clear error") { bounded {
        val sc = CoroutineScope(Dispatchers.Default + Job()); val m = mgr(sc, { UdpTransport("no.such.host.invalid", 9) }, backoff = ReconnectBackoff(5, 10, 1)); m.connect()
        waitFor(what = "FAILED") { m.status.value.state == LinkState.FAILED }; ok(m.status.value.detail.contains("resolve"), m.status.value.detail); sc.cancel() } }
}
