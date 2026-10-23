package com.nexus.controller.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.controller.NexusViewModel
import com.nexus.core.*
import kotlinx.coroutines.flow.update
import com.nexus.protocol.AxisMath
import com.nexus.protocol.ButtonMap
import com.nexus.protocol.Protocol
import kotlin.math.atan2

enum class Screen { MENU, GAMEPAD, RACING, SETTINGS, CALIBRATION, CAPABILITIES }

fun Context.findActivity(): Activity? { var c = this; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }

@Composable
fun NexusApp(vm: NexusViewModel) {
    var screen by remember { mutableStateOf(Screen.MENU) }
    val activity = LocalContext.current.findActivity()
    val landscape = screen == Screen.GAMEPAD || screen == Screen.RACING
    DisposableEffect(landscape) {
        activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        onDispose { }
    }
    BackHandler(screen != Screen.MENU) { vm.manager.releaseAll(); screen = if (screen == Screen.CALIBRATION) Screen.SETTINGS else Screen.MENU }
    // Prevent stuck inputs when the app loses focus or the OS interrupts touch handling.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.manager.releaseAll() }
    Surface(color = Navy, modifier = Modifier.fillMaxSize()) {
        when (screen) {
            Screen.MENU -> MenuScreen(vm, { vm.setMode(false); screen = Screen.GAMEPAD }, { vm.setMode(true); screen = Screen.RACING }, { screen = Screen.SETTINGS }, { screen = Screen.CAPABILITIES })
            Screen.GAMEPAD -> GamepadScreen(vm) { screen = Screen.MENU }
            Screen.RACING -> RacingScreen(vm) { screen = Screen.MENU }
            Screen.SETTINGS -> SettingsScreen(vm, { screen = Screen.MENU }, { screen = Screen.CALIBRATION })
            Screen.CALIBRATION -> CalibrationScreen(vm) { screen = Screen.SETTINGS }
            Screen.CAPABILITIES -> CapabilitiesScreen(vm) { screen = Screen.MENU }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Accent, fontSize = 14.sp); content()
        }
    }
}

private fun stateColor(s: LinkState) = when (s) {
    LinkState.CONNECTED -> Good; LinkState.FAILED -> Danger; LinkState.DISCONNECTED -> Muted; else -> Warn
}

private fun requiredPermissions(t: TransportKind): List<String> = when {
    t == TransportKind.UDP -> emptyList()
    Build.VERSION.SDK_INT >= 31 -> if (t == TransportKind.BLE) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else listOf(Manifest.permission.BLUETOOTH_CONNECT)
    t == TransportKind.BLE -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    else -> emptyList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(vm: NexusViewModel, onGamepad: () -> Unit, onRacing: () -> Unit, onSettings: () -> Unit, onCaps: () -> Unit) {
    val ctx = LocalContext.current
    val st by vm.manager.status.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val pin by vm.pin.collectAsStateWithLifecycle()
    val racing by vm.racingMode.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanMsg by vm.scanMessage.collectAsStateWithLifecycle()
    val selected by vm.selectedAddress.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    var permDenied by remember { mutableStateOf(false) }
    val perms = requiredPermissions(s.transport)
    val granted = remember(tick, s.transport) { perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED } }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> permDenied = r.values.any { !it }; tick++ }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val btNeeded = s.transport != TransportKind.UDP
    val btOn = remember(tick, st.state) { vm.bleEnabled }
    val pinOk = pin.length == 6 && pin.all { it.isDigit() }
    val busy = st.state in listOf(LinkState.CONNECTING, LinkState.HANDSHAKING, LinkState.RECONNECTING)
    val canConnect = !busy && st.state != LinkState.CONNECTED && granted && (!btNeeded || btOn) && when (s.transport) {
        TransportKind.UDP -> s.udpHost.isNotBlank() && pinOk
        TransportKind.BLE -> selected.isNotBlank() && pinOk && vm.bleSupported
        TransportKind.HID -> vm.hidAvailable
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Accent), contentAlignment = Alignment.Center) { Text("N", color = Navy, fontWeight = FontWeight.Black, fontSize = 26.sp) }
            Spacer(Modifier.width(12.dp))
            Column { Text("NEXUS Controller", fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("Phone as wireless game controller", color = Muted, fontSize = 12.sp) }
        }

        SectionCard("STATUS") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(stateColor(st.state)))
                Spacer(Modifier.width(8.dp)); Text(st.state.name.lowercase().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold)
            }
            if (st.detail.isNotBlank()) Text(st.detail, color = if (st.state == LinkState.FAILED) Danger else Muted, fontSize = 13.sp)
            Text("Receiver: ${st.device ?: "—"}", fontSize = 13.sp)
            Text("Round-trip latency: ${st.rttMs?.let { "$it ms (measured)" } ?: "— (not measurable${if (s.transport == TransportKind.HID) " in HID mode" else " yet"})"}", fontSize = 13.sp)
            Text("Phone battery: ${battery?.let { "$it%" } ?: "not available"}    Packets sent: ${st.packetsSent}", fontSize = 13.sp)
        }

        SectionCard("PROFILE") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (0..2).forEach { i -> FilterChip(selected = s.profile == i, onClick = { vm.selectProfile(i) }, label = { Text("Profile ${i + 1}") }) } }
        }

        SectionCard("CONNECTION") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.transport == TransportKind.BLE, { vm.updateSettings { it.copy(transport = TransportKind.BLE) } }, { Text("Bluetooth LE") })
                FilterChip(s.transport == TransportKind.UDP, { vm.updateSettings { it.copy(transport = TransportKind.UDP) } }, { Text("Wi-Fi") })
                FilterChip(s.transport == TransportKind.HID, { vm.updateSettings { it.copy(transport = TransportKind.HID) } }, { Text("HID (exp.)") })
            }
            when (s.transport) {
                TransportKind.BLE -> Text("Sends to the NEXUS Receiver on Windows over Bluetooth LE. Pairing PIN is shown in the Windows app.", color = Muted, fontSize = 12.sp)
                TransportKind.UDP -> Text("Sends to the NEXUS Receiver over your local network (unencrypted; use a network you trust).", color = Muted, fontSize = 12.sp)
                TransportKind.HID -> Text("EXPERIMENTAL: appears as a standard Bluetooth gamepad to a tablet/PC that pairs with this phone. Not supported on all phones. No PIN is used; only pair with your own devices.", color = Warn, fontSize = 12.sp)
            }
            if (!granted) {
                Text(if (permDenied) "Permission denied. Bluetooth access is required to find and connect to your receiver. Open app settings to allow it." else "This mode needs Bluetooth permission to find and connect to your receiver.", color = Warn, fontSize = 13.sp)
                Button(onClick = {
                    if (permDenied && Build.VERSION.SDK_INT >= 23 && perms.none { ctx.findActivity()?.shouldShowRequestPermissionRationale(it) == true })
                        ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    else permLauncher.launch(perms.toTypedArray())
                }) { Text(if (permDenied) "Open settings / retry" else "Grant permission") }
            }
            if (btNeeded && granted && !btOn) {
                Text(if (!vm.bleSupported) "This device does not support Bluetooth Low Energy." else "Bluetooth is turned off.", color = Warn, fontSize = 13.sp)
                if (vm.bleSupported) Button(onClick = { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Turn on Bluetooth") }
            }
            if (s.transport == TransportKind.UDP) {
                OutlinedTextField(s.udpHost, { v -> vm.updateSettings { it.copy(udpHost = v) } }, label = { Text("Receiver IP address") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
            if (s.transport == TransportKind.BLE && granted && btOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { if (scanning) vm.stopScan() else vm.startScan() }) { Text(if (scanning) "Stop scan" else "Scan for receivers") }
                    if (scanning) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                }
                scanMsg?.let { Text(it, color = Warn, fontSize = 12.sp) }
                if (selected.isNotBlank() && devices.none { it.address == selected }) Text("Last used: ${s.lastDeviceName.ifBlank { selected }} (tap Scan to refresh)", color = Muted, fontSize = 12.sp)
                devices.forEach { d ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (d.address == selected) AccentDim else Charcoal).clickable { vm.select(d) }.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(d.name, fontWeight = FontWeight.Bold); Text(d.address, color = Muted, fontSize = 11.sp) }
                        Text("${d.rssi} dBm", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            if (s.transport != TransportKind.HID) {
                OutlinedTextField(pin, { v -> vm.pin.value = v.filter { it.isDigit() }.take(6) }, label = { Text("6-digit PIN from the receiver") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.connect() }, enabled = canConnect) { Text("Connect") }
                OutlinedButton(onClick = { vm.manager.disconnect() }, enabled = st.state != LinkState.DISCONNECTED) { Text("Disconnect") }
                OutlinedButton(onClick = { vm.connect() }, enabled = canConnect || st.state == LinkState.FAILED && granted) { Text("Reconnect") }
            }
        }

        SectionCard("CONTROLLER") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onGamepad, modifier = Modifier.weight(1f)) { Text("Gamepad") }
                Button(onClick = onRacing, modifier = Modifier.weight(1f)) { Text("Racing") }
            }
            if (st.state != LinkState.CONNECTED) Text("Not connected: controls will work on screen but nothing is sent.", color = Muted, fontSize = 12.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) { Text("Settings & calibration") }
            OutlinedButton(onClick = onCaps, modifier = Modifier.weight(1f)) { Text("Device capabilities") }
        }
    }
}

@Composable
private fun StatusStrip(vm: NexusViewModel, onBack: () -> Unit, extra: @Composable RowScope.() -> Unit = {}) {
    val st by vm.manager.status.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("‹ Menu", color = Accent, modifier = Modifier.clickable { vm.manager.releaseAll(); onBack() }.padding(horizontal = 8.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
        Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor(st.state)))
        Text(if (st.state == LinkState.CONNECTED) "Connected · ${st.rttMs?.let { "$it ms RTT" } ?: "RTT n/a"}" else if (st.state == LinkState.DISCONNECTED || st.state == LinkState.FAILED) "NOT CONNECTED — ${st.detail}" else st.detail, fontSize = 12.sp, color = TextMain, maxLines = 1, modifier = Modifier.weight(1f))
        extra()
    }
}

@Composable
private fun KeepScreenOn() { val v = LocalView.current; DisposableEffect(Unit) { v.keepScreenOn = true; onDispose { v.keepScreenOn = false } } }

private fun faceLabel(bit: Int) = when (bit) { Protocol.BTN_A -> "A"; Protocol.BTN_B -> "B"; Protocol.BTN_X -> "X"; Protocol.BTN_Y -> "Y"; else -> "?" }

@Composable
fun GamepadScreen(vm: NexusViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val haptic = rememberHaptic(s.haptics)
    val u = s.layoutScale; val a = s.opacity
    fun raw(f: (RawInput) -> RawInput) = vm.manager.raw.update(f)
    fun bit(b: Int, on: Boolean) = raw { it.copy(buttons = if (on) it.buttons or b else it.buttons and b.inv()) }
    KeepScreenOn()
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 8.dp)) {
        StatusStrip(vm, onBack)
        CompositionLocalProvider(LocalLayoutDirection provides if (s.swapSides) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Box(Modifier.fillMaxSize().padding(bottom = 8.dp)) {
                Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnalogBar("LT", (54 * u).dp, (110 * u).dp, alpha = a, haptic = haptic) { v -> raw { it.copy(lt = v) } }
                    PillButton("LB", Modifier.padding(top = 4.dp), alpha = a, haptic = haptic) { bit(Protocol.BTN_LB, it) }
                }
                Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("RB", Modifier.padding(top = 4.dp), alpha = a, haptic = haptic) { bit(Protocol.BTN_RB, it) }
                    AnalogBar("RT", (54 * u).dp, (110 * u).dp, alpha = a, haptic = haptic) { v -> raw { it.copy(rt = v) } }
                }
                Column(Modifier.align(Alignment.CenterStart).padding(start = 24.dp, top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AnalogStick((140 * u).dp, alpha = a, haptic = haptic) { x, y -> raw { it.copy(lsx = x, lsy = y) } }
                    PillButton("L3", Modifier.padding(top = 4.dp), 48.dp, 26.dp, a, haptic) { bit(Protocol.BTN_L3, it) }
                }
                DPad((118 * u).dp, Modifier.align(Alignment.BottomStart).padding(start = (150 * u).dp), a, haptic) { b -> raw { it.copy(dpad = b) } }
                Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("SELECT", width = 76.dp, alpha = a, haptic = haptic) { bit(Protocol.BTN_SELECT, it) }
                    PillButton("START", width = 76.dp, alpha = a, haptic = haptic) { bit(Protocol.BTN_START, it) }
                }
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 24.dp, bottom = 20.dp).size((164 * u).dp)) {
                    val d = (54 * u).dp
                    GameButton(faceLabel(s.faceNorth), Modifier.align(Alignment.TopCenter), d, alpha = a, haptic = haptic) { bit(s.faceNorth, it) }
                    GameButton(faceLabel(s.faceSouth), Modifier.align(Alignment.BottomCenter), d, alpha = a, haptic = haptic) { bit(s.faceSouth, it) }
                    GameButton(faceLabel(s.faceWest), Modifier.align(Alignment.CenterStart), d, alpha = a, haptic = haptic) { bit(s.faceWest, it) }
                    GameButton(faceLabel(s.faceEast), Modifier.align(Alignment.CenterEnd), d, alpha = a, haptic = haptic) { bit(s.faceEast, it) }
                }
                Column(Modifier.align(Alignment.BottomEnd).padding(end = (170 * u).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AnalogStick((120 * u).dp, alpha = a, haptic = haptic) { x, y -> raw { it.copy(rsx = x, rsy = y) } }
                    PillButton("R3", Modifier.padding(top = 4.dp), 48.dp, 26.dp, a, haptic) { bit(Protocol.BTN_R3, it) }
                }
            }
        }
    }
}

@Composable
fun TiltSensor(enabled: Boolean, onRoll: (Float) -> Unit) {
    val ctx = LocalContext.current
    val cb by rememberUpdatedState(onRoll)
    DisposableEffect(enabled) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var filtered = 0f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val ax = e.values[0]; val ay = e.values[1]
                val roll = Math.toDegrees((if (ax >= 0) atan2(ay, ax) else atan2(-ay, -ax)).toDouble()).toFloat()
                filtered += 0.25f * (roll - filtered); cb(filtered)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (enabled && sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l); if (enabled) cb(0f) }
    }
}

@Composable
fun RacingScreen(vm: NexusViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val raw by vm.manager.raw.collectAsStateWithLifecycle()
    val haptic = rememberHaptic(s.haptics)
    val u = s.layoutScale; val a = s.opacity; val p = s.pedalScale
    fun upd(f: (RawInput) -> RawInput) = vm.manager.raw.update(f)
    fun bit(b: Int, on: Boolean) = upd { it.copy(buttons = if (on) it.buttons or b else it.buttons and b.inv()) }
    KeepScreenOn()
    TiltSensor(s.steerMode == SteerMode.TILT) { r -> upd { it.copy(tiltDeg = r) } }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 8.dp)) {
        StatusStrip(vm, onBack) {
            Text(if (s.manualTransmission) "MANUAL" else "AUTO", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier.clickable { vm.updateSettings { it.copy(manualTransmission = !it.manualTransmission) } }.padding(8.dp))
            Text(if (s.steerMode == SteerMode.TILT) "TILT" else "WHEEL", color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier.clickable { vm.updateSettings { it.copy(steerMode = if (it.steerMode == SteerMode.TILT) SteerMode.WHEEL else SteerMode.TILT) } }.padding(8.dp))
        }
        CompositionLocalProvider(LocalLayoutDirection provides if (s.swapSides) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Box(Modifier.fillMaxSize().padding(bottom = 8.dp)) {
                if (s.steerMode == SteerMode.WHEEL) {
                    SteeringWheel((300 * u).dp, (190 * u).dp, s.steerReturnSpeed, Modifier.align(Alignment.BottomStart).padding(start = 12.dp), a, haptic) { v -> upd { it.copy(wheel = v) } }
                } else {
                    val steer = StateBuilder.build(raw, s).steering
                    Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp).width((300 * u).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Tilt steering active — hold the phone like a wheel", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { (steer + 1f) / 2f }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape))
                        Text("steering ${"%.2f".format(steer)}", fontSize = 12.sp)
                    }
                }
                Row(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                    AnalogBar("BRAKE", (84 * p).dp, (190 * p).dp, alpha = a, haptic = haptic) { v -> upd { it.copy(brake = v) } }
                    AnalogBar("GAS", (100 * p).dp, (220 * p).dp, alpha = a, haptic = haptic) { v -> upd { it.copy(accel = v) } }
                }
                Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("HANDBRAKE", width = 104.dp, alpha = a, haptic = haptic) { on -> upd { it.copy(handbrake = if (on) 1f else 0f) } }
                    PillButton("NITRO", width = 76.dp, alpha = a, haptic = haptic) { bit(Protocol.BTN_NITRO, it) }
                }
                if (s.manualTransmission) Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("GEAR −", width = 84.dp, height = 44.dp, alpha = a, haptic = haptic) { on -> upd { it.copy(gearDown = on) } }
                    PillButton("GEAR +", width = 84.dp, height = 44.dp, alpha = a, haptic = haptic) { on -> upd { it.copy(gearUp = on) } }
                }
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String = "%.2f", onChange: (Float) -> Unit) {
    Column { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, fontSize = 13.sp); Text(fmt.format(value), color = Muted, fontSize = 13.sp) }
        Slider(value.coerceIn(range), onChange, valueRange = range) }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(label, fontSize = 13.sp); Switch(checked, onChange) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: NexusViewModel, onBack: () -> Unit, onCalibrate: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    fun u(f: (Settings) -> Settings) = vm.updateSettings(f)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("‹ Back", color = Accent, modifier = Modifier.clickable(onClick = onBack).padding(4.dp), fontWeight = FontWeight.Bold)
        Text("Settings — Profile ${s.profile + 1}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        SectionCard("STICKS") {
            SliderRow("Dead zone", s.stickDeadzone, 0f..0.5f) { v -> u { it.copy(stickDeadzone = v) } }
            SliderRow("Sensitivity", s.stickSensitivity, 0.3f..2f) { v -> u { it.copy(stickSensitivity = v) } }
            SliderRow("Response curve (1 = linear)", s.stickCurve, 0.5f..3f) { v -> u { it.copy(stickCurve = v) } }
            SwitchRow("Invert X", s.invertX) { v -> u { it.copy(invertX = v) } }
            SwitchRow("Invert Y", s.invertY) { v -> u { it.copy(invertY = v) } }
        }
        SectionCard("TRIGGERS") {
            SliderRow("Sensitivity", s.triggerSensitivity, 0.5f..2f) { v -> u { it.copy(triggerSensitivity = v) } }
            SliderRow("Released point", s.triggerLow, 0f..0.9f) { v -> u { it.copy(triggerLow = v) } }
            SliderRow("Fully-pressed point", s.triggerHigh, 0.1f..1f) { v -> u { it.copy(triggerHigh = v) } }
        }
        SectionCard("FACE BUTTON MAPPING") {
            val slots = listOf("north" to s.faceNorth, "west" to s.faceWest, "east" to s.faceEast, "south" to s.faceSouth)
            slots.forEach { (slot, bit) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(slot.replaceFirstChar { it.uppercase() }, Modifier.width(60.dp), fontSize = 13.sp)
                    listOf(Protocol.BTN_A, Protocol.BTN_B, Protocol.BTN_X, Protocol.BTN_Y).forEach { target ->
                        FilterChip(bit == target, {
                            val m = ButtonMap(mapOf("south" to s.faceSouth, "east" to s.faceEast, "west" to s.faceWest, "north" to s.faceNorth)); m.remap(slot, target)
                            val n = m.snapshot(); u { it.copy(faceSouth = n["south"]!!, faceEast = n["east"]!!, faceWest = n["west"]!!, faceNorth = n["north"]!!) }
                        }, { Text(faceLabel(target)) })
                    }
                }
            }
        }
        SectionCard("RACING") {
            SliderRow("Steering sensitivity", s.steerSensitivity, 0.3f..2.5f) { v -> u { it.copy(steerSensitivity = v) } }
            SliderRow("Steering dead zone", s.steerDeadzone, 0f..0.3f) { v -> u { it.copy(steerDeadzone = v) } }
            SliderRow("Return-to-center speed", s.steerReturnSpeed, 0.5f..10f) { v -> u { it.copy(steerReturnSpeed = v) } }
            SliderRow("Tilt range (degrees)", s.tiltMaxDeg, 15f..90f, "%.0f") { v -> u { it.copy(tiltMaxDeg = v) } }
            SliderRow("Pedal sensitivity", s.pedalSensitivity, 0.5f..2f) { v -> u { it.copy(pedalSensitivity = v) } }
            SliderRow("Pedal size", s.pedalScale, 0.6f..1.6f) { v -> u { it.copy(pedalScale = v) } }
            SwitchRow("Invert steering", s.steerInvert) { v -> u { it.copy(steerInvert = v) } }
            SwitchRow("Tilt steering (instead of wheel)", s.steerMode == SteerMode.TILT) { v -> u { it.copy(steerMode = if (v) SteerMode.TILT else SteerMode.WHEEL) } }
            SwitchRow("Manual transmission", s.manualTransmission) { v -> u { it.copy(manualTransmission = v) } }
            OutlinedButton(onClick = onCalibrate) { Text("Calibration & test…") }
        }
        SectionCard("LAYOUT & FEEDBACK") {
            SliderRow("Control size", s.layoutScale, 0.6f..1.5f) { v -> u { it.copy(layoutScale = v) } }
            SliderRow("Control opacity", s.opacity, 0.3f..1f) { v -> u { it.copy(opacity = v) } }
            SwitchRow("Mirror layout (left-handed)", s.swapSides) { v -> u { it.copy(swapSides = v) } }
            SwitchRow("Haptic feedback", s.haptics) { v -> u { it.copy(haptics = v) } }
        }
        SectionCard("PERFORMANCE & CONNECTION") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Update rate", fontSize = 13.sp, modifier = Modifier.width(90.dp))
                listOf(30, 60, 90, 120).forEach { hz -> FilterChip(s.updateHz == hz, { u { it.copy(updateHz = hz) } }, { Text("$hz") }) }
            }
            SwitchRow("Performance mode (cap 60 Hz, slower idle polling)", s.performanceMode) { v -> u { it.copy(performanceMode = v) } }
            Text("Receiver fail-safe timeout is set in the Windows app. Packets are only sent when inputs change, plus a keep-alive.", color = Muted, fontSize = 12.sp)
        }
        SectionCard("BACKUP") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NEXUS settings", vm.exportSettings())); message = "Settings copied to clipboard." }) { Text("Export") }
                OutlinedButton(onClick = {
                    val t = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    message = if (vm.importSettings(t)) "Settings imported." else "Clipboard does not contain NEXUS settings."
                }) { Text("Import") }
                OutlinedButton(onClick = { confirmReset = true }) { Text("Restore defaults") }
            }
            message?.let { Text(it, color = Accent, fontSize = 13.sp) }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Restore defaults?") }, text = { Text("This resets all settings of the current profile.") },
        confirmButton = { TextButton(onClick = { vm.restoreDefaults(); confirmReset = false }) { Text("Restore") } }, dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } })
}

@Composable
fun CalibrationScreen(vm: NexusViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    var roll by remember { mutableFloatStateOf(0f) }
    var wheel by remember { mutableFloatStateOf(0f) }
    var trig by remember { mutableFloatStateOf(0f) }
    TiltSensor(true) { roll = it }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("‹ Back", color = Accent, modifier = Modifier.clickable(onClick = onBack).padding(4.dp), fontWeight = FontWeight.Bold)
        Text("Calibration & test", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Nothing here is sent to the receiver; these are local previews of the values your settings produce.", color = Muted, fontSize = 12.sp)
        SectionCard("TILT") {
            Text("Hold the phone in your driving position, then tap the button to make it the centre.", fontSize = 13.sp)
            Text("Current roll: ${"%.1f".format(roll)}°   centre: ${"%.1f".format(s.tiltOffsetDeg)}°", fontSize = 13.sp)
            val steer = com.nexus.protocol.RacingMath.tilt(roll, s.tiltOffsetDeg, s.tiltMaxDeg, s.steerDeadzone, s.steerSensitivity, s.steerInvert)
            LinearProgressIndicator(progress = { (steer + 1f) / 2f }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape))
            Text("Resulting steering: ${"%.2f".format(steer)}", fontSize = 13.sp)
            Button(onClick = { vm.updateSettings { it.copy(tiltOffsetDeg = roll) } }) { Text("Set current tilt as centre") }
        }
        SectionCard("STEERING WHEEL TEST") {
            SteeringWheel(300.dp, 170.dp, s.steerReturnSpeed) { wheel = it }
            Text("Resulting steering: ${"%.2f".format(AxisMath.linearAxis(wheel, s.steerDeadzone, s.steerSensitivity, s.steerInvert))}", fontSize = 13.sp)
        }
        SectionCard("TRIGGER / PEDAL TEST") {
            AnalogBar("TOUCH", 120.dp, 140.dp) { trig = it }
            Text("Trigger output: ${"%.2f".format(AxisMath.trigger(trig, s.triggerSensitivity, s.triggerLow, s.triggerHigh))}   Pedal output: ${"%.2f".format(AxisMath.trigger(trig, s.pedalSensitivity))}", fontSize = 13.sp)
        }
    }
}

@Composable
fun CapabilitiesScreen(vm: NexusViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("‹ Back", color = Accent, modifier = Modifier.clickable(onClick = onBack).padding(4.dp), fontWeight = FontWeight.Bold)
        Text("Device capabilities & limits", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        SectionCard("THIS PHONE") {
            Text("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", fontSize = 13.sp)
            Text("Bluetooth Low Energy: ${if (vm.bleSupported) "supported" else "NOT supported"}", fontSize = 13.sp)
            Text("Bluetooth HID gamepad mode: ${if (vm.hidAvailable) "API present (actual support is only known when you connect)" else "unavailable (needs Android 9+)"}", fontSize = 13.sp)
        }
        SectionCard("THREE DIFFERENT THINGS") {
            Text("1. Bluetooth/Wi-Fi transport between two NEXUS apps — implemented for Windows (NEXUS Receiver).", fontSize = 13.sp)
            Text("2. Bluetooth HID gamepad emulation — experimental; the phone pretends to be a standard gamepad. Depends on the phone's Bluetooth stack.", fontSize = 13.sp)
            Text("3. System-wide input injection into other Android apps — NOT possible for an ordinary app and NOT provided. Messages over BLE or Wi-Fi do not automatically become gamepad input in Android games.", fontSize = 13.sp, color = Warn)
        }
        SectionCard("ANDROID TABLET AS RECEIVER") {
            Text("The most compatible path on a stock tablet is HID mode: pair this phone from the tablet's Bluetooth settings and the tablet sees a normal controller. A NEXUS-protocol receiver app for Android is not part of this release. Games may ignore virtual or unusual controllers, and button/axis mapping can differ per device.", fontSize = 13.sp)
        }
    }
}
