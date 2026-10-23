package com.nexus.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

interface TransportListener {
    fun onPacket(buf: ByteArray, len: Int)
    /** Called when the link is lost or closed by the peer. Not called after an explicit close(). */
    fun onClosed(reason: String)
}

interface Transport {
    val label: String
    /** false for transports that are standard HID (no NEXUS handshake/ping), true for NEXUS-protocol receivers. */
    val requiresHandshake: Boolean get() = true
    var listener: TransportListener?
    /** Opens the link and suspends until it is usable. Throws IOException with a user-readable message on failure. */
    suspend fun open()
    /** Non-blocking best-effort send. Returns false if the packet could not be queued. */
    fun send(buf: ByteArray, len: Int): Boolean
    fun close()
}

/** Wi-Fi transport over UDP. The receiver must authenticate us via the NEXUS PIN handshake before it accepts input. */
class UdpTransport(private val host: String, private val port: Int) : Transport {
    override val label get() = "Wi-Fi (UDP) $host:$port"
    override var listener: TransportListener? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var closing = false
    private val txPacket = DatagramPacket(ByteArray(0), 0)

    override suspend fun open() {
        val addr = try { InetAddress.getByName(host) } catch (e: Exception) { throw IOException("Cannot resolve host '$host'") }
        val s = DatagramSocket()
        s.connect(InetSocketAddress(addr, port))
        socket = s
        Thread({
            val buf = ByteArray(128); val p = DatagramPacket(buf, buf.size)
            while (!closing) {
                try { p.length = buf.size; s.receive(p); listener?.onPacket(buf, p.length) }
                catch (e: IOException) { if (!closing) listener?.onClosed("Network error: ${e.message}"); break }
            }
        }, "nexus-udp-rx").apply { isDaemon = true; start() }
    }

    override fun send(buf: ByteArray, len: Int): Boolean {
        val s = socket ?: return false
        return try { synchronized(txPacket) { txPacket.setData(buf, 0, len); s.send(txPacket) }; true } catch (e: IOException) { false }
    }

    override fun close() { closing = true; socket?.close(); socket = null }
}
