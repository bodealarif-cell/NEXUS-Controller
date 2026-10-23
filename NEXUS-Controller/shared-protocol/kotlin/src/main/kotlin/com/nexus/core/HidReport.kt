package com.nexus.core

import com.nexus.protocol.ControllerState
import com.nexus.protocol.Protocol
import kotlin.math.roundToInt

/**
 * Report ID 1, 9 payload bytes: buttons(16 bits) | hat(4)+pad(4) | X, Y, Z, Rz (int8) | Rx, Ry (uint8 triggers).
 * Racing mode mirrors the Windows XInput mapping: steering->X, accel->Ry (RT), brake->Rx (LT), gears->RB/LB, handbrake->X button.
 * EXPERIMENTAL: how a host maps these usages to "left stick / triggers" differs between Android versions and manufacturers.
 */
object HidReport {
    const val REPORT_ID = 1
    const val SIZE = 9

    val DESCRIPTOR: ByteArray = intArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, REPORT_ID,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x10, 0x81, 0x02,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, 0x65, 0x00,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
        0x09, 0x33, 0x09, 0x34, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
        0xC0
    ).map { it.toByte() }.toByteArray()

    private fun axis8(v: Float) = (v.coerceIn(-1f, 1f) * 127f).roundToInt()
    private fun trig8(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()

    fun hat(dpad: Int): Int {
        val u = dpad and Protocol.DPAD_UP != 0; val d = dpad and Protocol.DPAD_DOWN != 0
        val l = dpad and Protocol.DPAD_LEFT != 0; val r = dpad and Protocol.DPAD_RIGHT != 0
        return when {
            u && r -> 2; d && r -> 4; d && l -> 6; u && l -> 8
            u -> 1; r -> 3; d -> 5; l -> 7
            else -> 0
        }
    }

    fun fromState(s: ControllerState): ByteArray {
        var b = s.buttons and Protocol.BUTTON_MASK
        val racing = s.flags and Protocol.FLAG_RACING != 0
        var lx = s.lx; var lt = s.lt; var rt = s.rt
        if (racing) {
            lx = s.steering; lt = s.brake; rt = s.accel
            if (s.gear and Protocol.GEAR_UP != 0) b = b or Protocol.BTN_RB
            if (s.gear and Protocol.GEAR_DOWN != 0) b = b or Protocol.BTN_LB
            if (s.handbrake > 0.5f) b = b or Protocol.BTN_X
        }
        return byteArrayOf(
            b.toByte(), (b shr 8).toByte(), hat(s.dpad).toByte(),
            axis8(lx).toByte(), axis8(-s.ly).toByte(), axis8(s.rx).toByte(), axis8(-s.ry).toByte(),
            trig8(lt).toByte(), trig8(rt).toByte()
        )
    }
}
