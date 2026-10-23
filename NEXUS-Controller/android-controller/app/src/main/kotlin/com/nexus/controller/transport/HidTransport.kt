package com.nexus.controller.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import com.nexus.core.HidReport
import com.nexus.core.Transport
import com.nexus.core.TransportListener
import com.nexus.protocol.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.Executors

/**
 * EXPERIMENTAL. Registers this phone as a standard Bluetooth HID gamepad using the Android BluetoothHidDevice API (Android 9+).
 * Support depends on the phone's Bluetooth stack; many devices do not implement it. No receiver app is needed on the host,
 * but the host decides how it interprets the report. This is NOT system-wide input injection: the host sees a gamepad.
 */
@SuppressLint("MissingPermission")
class HidTransport(private val ctx: Context) : Transport {
    override val label = "Bluetooth HID gamepad (experimental)"
    override val requiresHandshake = false
    override var listener: TransportListener? = null
    private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var closing = false
    private val executor = Executors.newSingleThreadExecutor()
    private val connected = CompletableDeferred<Unit>()

    companion object {
        fun available(ctx: Context): Boolean =
            Build.VERSION.SDK_INT >= 28 && (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter != null
    }

    override suspend fun open() {
        if (Build.VERSION.SDK_INT < 28) throw IOException("Bluetooth HID gamepad mode needs Android 9 or newer.")
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: throw IOException("No Bluetooth adapter.")
        val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                if (!registered && !connected.isCompleted) connected.completeExceptionally(IOException("This phone's Bluetooth stack did not accept the HID gamepad registration."))
            }
            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) { host = device; connected.complete(Unit) }
                else if (state == BluetoothProfile.STATE_DISCONNECTED && device == host) { host = null; if (!closing) listener?.onClosed("The host disconnected.") }
            }
        }
        val gotProxy = adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings("NEXUS Gamepad", "Phone gamepad", "NEXUS", BluetoothHidDevice.SUBCLASS2_GAMEPAD, HidReport.DESCRIPTOR)
                if (!h.registerApp(sdp, null, null, executor, callback) && !connected.isCompleted) connected.completeExceptionally(IOException("HID registration failed to start."))
            }
            override fun onServiceDisconnected(profile: Int) { hid = null; if (!closing) listener?.onClosed("Bluetooth HID service stopped.") }
        }, BluetoothProfile.HID_DEVICE)
        if (!gotProxy) throw IOException("Bluetooth HID Device profile is not available on this phone.")
        try { withTimeout(90_000) { connected.await() } }
        catch (e: TimeoutCancellationException) { close(); throw IOException("No host connected within 90 seconds. Pair this phone from the tablet's Bluetooth settings, then try again.") }
    }

    override fun send(buf: ByteArray, len: Int): Boolean {
        val h = hid ?: return false
        val d = host ?: return false
        val pkt = (Protocol.decodeInput(buf, len) as? Protocol.Decoded.Ok)?.value ?: return false
        return try { h.sendReport(d, HidReport.REPORT_ID, HidReport.fromState(pkt.state)) } catch (e: SecurityException) { false }
    }

    override fun close() {
        closing = true
        val h = hid
        runCatching { host?.let { h?.disconnect(it) } }
        runCatching { h?.unregisterApp() }
        runCatching { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
        hid = null; host = null; executor.shutdown()
    }
}
