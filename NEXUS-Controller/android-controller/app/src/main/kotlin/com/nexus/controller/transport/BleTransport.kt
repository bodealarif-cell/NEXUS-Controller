package com.nexus.controller.transport

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.nexus.core.Transport
import com.nexus.core.TransportListener
import com.nexus.protocol.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

object NexusUuids {
    val SERVICE: UUID = UUID.fromString("7d0e0001-4e58-4d1c-9a3b-5a0e6a9c0001")
    val INPUT: UUID = UUID.fromString("7d0e0002-4e58-4d1c-9a3b-5a0e6a9c0001")
    val NOTIFY: UUID = UUID.fromString("7d0e0003-4e58-4d1c-9a3b-5a0e6a9c0001")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

data class FoundDevice(val address: String, val name: String, val rssi: Int)

/** BLE GATT client. Permissions (BLUETOOTH_CONNECT / SCAN) are verified by the UI before any call here. */
@SuppressLint("MissingPermission")
class BleTransport(private val ctx: Context, private val device: BluetoothDevice) : Transport {
    override val label: String get() = "Bluetooth LE ${runCatching { device.name }.getOrNull() ?: device.address}"
    override var listener: TransportListener? = null
    private var gatt: BluetoothGatt? = null
    private var input: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false
    @Volatile private var closing = false
    private val opened = CompletableDeferred<Unit>()

    private fun fail(msg: String) {
        if (!opened.isCompleted) opened.completeExceptionally(IOException(msg))
        else if (!closing) listener?.onClosed(msg)
        runCatching { gatt?.close() }
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(64)) fail("Could not negotiate Bluetooth packet size")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                ready = false
                fail(if (opened.isCompleted) "Bluetooth link lost" else "Bluetooth connection failed (status $status). Make sure the receiver is running and Bluetooth is on.")
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || mtu - 3 < Protocol.INPUT_SIZE) { fail("Receiver allows only ${mtu - 3} bytes per packet; ${Protocol.INPUT_SIZE} are required."); return }
            if (!g.discoverServices()) fail("Could not start Bluetooth service discovery")
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(NexusUuids.SERVICE) ?: return fail("This device does not provide the NEXUS service. Is it the NEXUS Receiver?")
            val inp = svc.getCharacteristic(NexusUuids.INPUT)
            val notify = svc.getCharacteristic(NexusUuids.NOTIFY)
            if (inp == null || notify == null) return fail("The NEXUS service on this device is incomplete.")
            input = inp
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(NexusUuids.CCCD) ?: return fail("Receiver does not support notifications.")
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            }
            if (!ok) fail("Could not enable receiver notifications")
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) { ready = true; opened.complete(Unit) } else fail("Could not enable receiver notifications (status $status)")
        }
        @Deprecated("Used on Android 12 and lower")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value ?: return
            listener?.onPacket(v, v.size)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            listener?.onPacket(value, value.size)
        }
    }

    override suspend fun open() {
        gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) ?: throw IOException("Could not start Bluetooth connection")
        try { withTimeout(12_000) { opened.await() } }
        catch (e: TimeoutCancellationException) { close(); throw IOException("Bluetooth connection timed out") }
        catch (e: IOException) { close(); throw e }
    }

    override fun send(buf: ByteArray, len: Int): Boolean {
        val g = gatt
        val c = input
        if (!ready || g == null || c == null) return false
        val data = buf.copyOf(len)
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; c.value = data; g.writeCharacteristic(c) }
            }
        } catch (e: SecurityException) { false }
    }

    override fun close() {
        closing = true; ready = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }
}

@SuppressLint("MissingPermission")
class BleScanner(private val ctx: Context) {
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var callback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    val supported get() = adapter != null && ctx.packageManager.hasSystemFeature("android.hardware.bluetooth_le")
    val enabled get() = adapter?.isEnabled == true
    fun remote(address: String): BluetoothDevice? = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    /** Scans for devices advertising the NEXUS service for [durationMs]. */
    fun start(durationMs: Long = 10_000, onFound: (FoundDevice) -> Unit, onDone: (String?) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return onDone("Bluetooth is off or unavailable.")
        stop()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onFound(FoundDevice(result.device.address, runCatching { result.device.name }.getOrNull() ?: "NEXUS Receiver", result.rssi))
            }
            override fun onScanFailed(errorCode: Int) { callback = null; onDone("Scan failed (error $errorCode).") }
        }
        callback = cb
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NexusUuids.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, cb)
        handler.postDelayed({ if (callback === cb) { stop(); onDone(null) } }, durationMs)
    }

    fun stop() {
        val c = callback ?: return
        callback = null
        handler.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(c) }
    }
}
