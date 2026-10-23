package com.nexus.controller

import android.app.Application
import android.content.Context
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.controller.transport.*
import com.nexus.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

class NexusViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("nexus", Context.MODE_PRIVATE)
    private fun profilePrefs(i: Int) = getApplication<Application>().getSharedPreferences("nexus_profile_$i", Context.MODE_PRIVATE)

    private fun loadProfile(i: Int): Settings =
        Settings.fromMap(profilePrefs(i).all.mapValues { it.value.toString() }).copy(profile = i).sanitized()

    private val _settings = MutableStateFlow(loadProfile(prefs.getInt("active_profile", 0)))
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** The PIN is deliberately never persisted or logged. */
    val pin = MutableStateFlow("")
    val racingMode = MutableStateFlow(false)

    private val scanner = BleScanner(app)
    val devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val scanning = MutableStateFlow(false)
    val scanMessage = MutableStateFlow<String?>(null)
    val selectedAddress = MutableStateFlow(_settings.value.lastDeviceId)
    val battery = MutableStateFlow<Int?>(null)

    // Network and Bluetooth I/O must never run on the main thread (UDP sockets throw NetworkOnMainThreadException there).
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val manager = ConnectionManager(
        scope = ioScope,
        newTransport = { makeTransport() },
        pin = { pin.value },
        settings = { _settings.value }
    )

    val bleSupported get() = scanner.supported
    val bleEnabled get() = scanner.enabled
    val hidAvailable get() = HidTransport.available(getApplication())

    init {
        viewModelScope.launch {
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            while (true) {
                val v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                battery.value = if (v in 0..100) v else null
                delay(30_000)
            }
        }
    }

    private fun makeTransport(): Transport {
        val s = _settings.value
        return when (s.transport) {
            TransportKind.UDP -> {
                if (s.udpHost.isBlank()) throw IOException("Enter the receiver's IP address (shown in the Windows app).")
                UdpTransport(s.udpHost, s.udpPort)
            }
            TransportKind.BLE -> {
                val addr = selectedAddress.value
                if (addr.isBlank()) throw IOException("Select a receiver first (tap Scan).")
                val dev = scanner.remote(addr) ?: throw IOException("Bluetooth is unavailable.")
                BleTransport(getApplication(), dev)
            }
            TransportKind.HID -> HidTransport(getApplication())
        }
    }

    fun updateSettings(f: (Settings) -> Settings) {
        val n = f(_settings.value).sanitized(); _settings.value = n; persist(n)
    }

    private fun persist(s: Settings) {
        val e = profilePrefs(s.profile).edit(); s.toMap().forEach { (k, v) -> e.putString(k, v) }; e.apply()
    }

    fun selectProfile(i: Int) { prefs.edit().putInt("active_profile", i).apply(); _settings.value = loadProfile(i) }
    fun restoreDefaults() { val s = Settings(profile = _settings.value.profile, transport = _settings.value.transport); _settings.value = s; persist(s) }

    fun exportSettings(): String = Settings.export(_settings.value)
    /** Returns false if [text] is not a NEXUS settings export. */
    fun importSettings(text: String): Boolean {
        val s = Settings.import(text, _settings.value) ?: return false
        val n = s.copy(profile = _settings.value.profile).sanitized(); _settings.value = n; persist(n); return true
    }

    fun startScan() {
        devices.value = emptyList(); scanMessage.value = null; scanning.value = true
        scanner.start(onFound = { d -> devices.update { list -> if (list.any { it.address == d.address }) list.map { if (it.address == d.address) d else it } else list + d } },
            onDone = { err -> scanning.value = false; scanMessage.value = err ?: if (devices.value.isEmpty()) "No NEXUS receiver found. Make sure the Windows app is running with Bluetooth LE enabled." else null })
    }
    fun stopScan() { scanner.stop(); scanning.value = false }

    fun select(d: FoundDevice) { selectedAddress.value = d.address; updateSettings { it.copy(lastDeviceId = d.address, lastDeviceName = d.name) } }

    fun connect() { stopScan(); manager.raw.update { it.copy(racing = racingMode.value) }; manager.connect() }
    fun setMode(racing: Boolean) { racingMode.value = racing; manager.raw.update { RawInput(racing = racing) } }

    override fun onCleared() { manager.disconnect(); scanner.stop(); ioScope.cancel() }
}
