package com.nexus.core

import com.nexus.protocol.Protocol
import com.nexus.protocol.StickConfig

enum class TransportKind { BLE, UDP, HID }
enum class SteerMode { WHEEL, TILT }

/** All user-tunable settings. Values are always clamped by [sanitized]; imports from text cannot produce illegal values. */
data class Settings(
    val profile: Int = 0,
    val stickDeadzone: Float = 0.12f, val stickSensitivity: Float = 1f, val stickCurve: Float = 1f,
    val invertX: Boolean = false, val invertY: Boolean = false,
    val triggerSensitivity: Float = 1f, val triggerLow: Float = 0f, val triggerHigh: Float = 1f,
    val haptics: Boolean = true, val layoutScale: Float = 1f, val opacity: Float = 0.85f, val swapSides: Boolean = false,
    val faceSouth: Int = Protocol.BTN_A, val faceEast: Int = Protocol.BTN_B, val faceWest: Int = Protocol.BTN_X, val faceNorth: Int = Protocol.BTN_Y,
    val steerMode: SteerMode = SteerMode.WHEEL, val steerSensitivity: Float = 1f, val steerDeadzone: Float = 0.03f,
    val steerReturnSpeed: Float = 3f, val steerInvert: Boolean = false, val tiltMaxDeg: Float = 40f, val tiltOffsetDeg: Float = 0f,
    val pedalSensitivity: Float = 1f, val pedalScale: Float = 1f, val manualTransmission: Boolean = false,
    val updateHz: Int = 60, val performanceMode: Boolean = false, val timeoutMs: Long = 3000,
    val transport: TransportKind = TransportKind.BLE, val udpHost: String = "", val udpPort: Int = 47810,
    val lastDeviceId: String = "", val lastDeviceName: String = ""
) {
    val stick get() = StickConfig(stickDeadzone, stickSensitivity, stickCurve, invertX, invertY)
    val effectiveHz get() = if (performanceMode) minOf(updateHz, 60) else updateHz

    fun sanitized(): Settings {
        val faces = listOf(faceSouth, faceEast, faceWest, faceNorth)
        val validFaces = faces.all { it != 0 && it and (it - 1) == 0 && it and Protocol.BUTTON_MASK != 0 } && faces.toSet().size == 4
        return copy(
            profile = profile.coerceIn(0, 2),
            stickDeadzone = stickDeadzone.fin(0f, 0.5f, 0.12f), stickSensitivity = stickSensitivity.fin(0.3f, 2f, 1f), stickCurve = stickCurve.fin(0.5f, 3f, 1f),
            triggerSensitivity = triggerSensitivity.fin(0.5f, 2f, 1f), triggerLow = triggerLow.fin(0f, 0.9f, 0f), triggerHigh = triggerHigh.fin(0.1f, 1f, 1f),
            layoutScale = layoutScale.fin(0.6f, 1.5f, 1f), opacity = opacity.fin(0.3f, 1f, 0.85f),
            faceSouth = if (validFaces) faceSouth else Protocol.BTN_A, faceEast = if (validFaces) faceEast else Protocol.BTN_B,
            faceWest = if (validFaces) faceWest else Protocol.BTN_X, faceNorth = if (validFaces) faceNorth else Protocol.BTN_Y,
            steerSensitivity = steerSensitivity.fin(0.3f, 2.5f, 1f), steerDeadzone = steerDeadzone.fin(0f, 0.3f, 0.03f), steerReturnSpeed = steerReturnSpeed.fin(0.5f, 10f, 3f),
            tiltMaxDeg = tiltMaxDeg.fin(15f, 90f, 40f), tiltOffsetDeg = tiltOffsetDeg.fin(-90f, 90f, 0f),
            pedalSensitivity = pedalSensitivity.fin(0.5f, 2f, 1f), pedalScale = pedalScale.fin(0.6f, 1.6f, 1f),
            updateHz = if (updateHz in listOf(30, 60, 90, 120)) updateHz else 60, timeoutMs = timeoutMs.coerceIn(1000, 15000),
            udpPort = udpPort.coerceIn(1024, 65535), udpHost = udpHost.trim().take(64).filter { it.isLetterOrDigit() || it in ".-:" }
        )
    }

    private fun Float.fin(lo: Float, hi: Float, def: Float) = if (isNaN() || isInfinite()) def else coerceIn(lo, hi)

    /** Flat key/value form used for SharedPreferences and for text export/import. */
    fun toMap(): Map<String, String> = linkedMapOf(
        "profile" to "$profile", "stickDeadzone" to "$stickDeadzone", "stickSensitivity" to "$stickSensitivity", "stickCurve" to "$stickCurve",
        "invertX" to "$invertX", "invertY" to "$invertY", "triggerSensitivity" to "$triggerSensitivity", "triggerLow" to "$triggerLow", "triggerHigh" to "$triggerHigh",
        "haptics" to "$haptics", "layoutScale" to "$layoutScale", "opacity" to "$opacity", "swapSides" to "$swapSides",
        "faceSouth" to "$faceSouth", "faceEast" to "$faceEast", "faceWest" to "$faceWest", "faceNorth" to "$faceNorth",
        "steerMode" to steerMode.name, "steerSensitivity" to "$steerSensitivity", "steerDeadzone" to "$steerDeadzone", "steerReturnSpeed" to "$steerReturnSpeed",
        "steerInvert" to "$steerInvert", "tiltMaxDeg" to "$tiltMaxDeg", "tiltOffsetDeg" to "$tiltOffsetDeg", "pedalSensitivity" to "$pedalSensitivity",
        "pedalScale" to "$pedalScale", "manualTransmission" to "$manualTransmission", "updateHz" to "$updateHz", "performanceMode" to "$performanceMode",
        "timeoutMs" to "$timeoutMs", "transport" to transport.name, "udpHost" to udpHost, "udpPort" to "$udpPort",
        "lastDeviceId" to lastDeviceId, "lastDeviceName" to lastDeviceName
    )

    companion object {
        fun fromMap(m: Map<String, String>, base: Settings = Settings()): Settings {
            fun f(k: String, d: Float) = m[k]?.toFloatOrNull() ?: d
            fun i(k: String, d: Int) = m[k]?.toIntOrNull() ?: d
            fun b(k: String, d: Boolean) = m[k]?.toBooleanStrictOrNull() ?: d
            return Settings(
                i("profile", base.profile), f("stickDeadzone", base.stickDeadzone), f("stickSensitivity", base.stickSensitivity), f("stickCurve", base.stickCurve),
                b("invertX", base.invertX), b("invertY", base.invertY), f("triggerSensitivity", base.triggerSensitivity), f("triggerLow", base.triggerLow), f("triggerHigh", base.triggerHigh),
                b("haptics", base.haptics), f("layoutScale", base.layoutScale), f("opacity", base.opacity), b("swapSides", base.swapSides),
                i("faceSouth", base.faceSouth), i("faceEast", base.faceEast), i("faceWest", base.faceWest), i("faceNorth", base.faceNorth),
                m["steerMode"]?.let { runCatching { SteerMode.valueOf(it) }.getOrNull() } ?: base.steerMode,
                f("steerSensitivity", base.steerSensitivity), f("steerDeadzone", base.steerDeadzone), f("steerReturnSpeed", base.steerReturnSpeed), b("steerInvert", base.steerInvert),
                f("tiltMaxDeg", base.tiltMaxDeg), f("tiltOffsetDeg", base.tiltOffsetDeg), f("pedalSensitivity", base.pedalSensitivity), f("pedalScale", base.pedalScale),
                b("manualTransmission", base.manualTransmission), i("updateHz", base.updateHz), b("performanceMode", base.performanceMode), (m["timeoutMs"]?.toLongOrNull() ?: base.timeoutMs),
                m["transport"]?.let { runCatching { TransportKind.valueOf(it) }.getOrNull() } ?: base.transport, m["udpHost"] ?: base.udpHost, i("udpPort", base.udpPort),
                m["lastDeviceId"] ?: base.lastDeviceId, m["lastDeviceName"] ?: base.lastDeviceName
            ).sanitized()
        }

        fun export(s: Settings): String = "NEXUS-SETTINGS-V1\n" + s.toMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        /** Returns null if the text is not a NEXUS settings export. Unknown keys are ignored; bad values fall back to defaults. */
        fun import(text: String, base: Settings = Settings()): Settings? {
            val lines = text.trim().lines()
            if (lines.firstOrNull()?.trim() != "NEXUS-SETTINGS-V1") return null
            val m = lines.drop(1).mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
            return fromMap(m, base)
        }
    }
}
