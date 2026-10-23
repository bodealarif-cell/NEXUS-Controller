package com.nexus.core

import com.nexus.protocol.*

/** Raw values as produced by touch/sensor widgets (y axis: +1 = up). */
data class RawInput(
    val buttons: Int = 0, val dpad: Int = 0,
    val lsx: Float = 0f, val lsy: Float = 0f, val rsx: Float = 0f, val rsy: Float = 0f,
    val lt: Float = 0f, val rt: Float = 0f,
    val wheel: Float = 0f, val tiltDeg: Float = 0f, val accel: Float = 0f, val brake: Float = 0f, val handbrake: Float = 0f,
    val gearUp: Boolean = false, val gearDown: Boolean = false, val racing: Boolean = false
)

object StateBuilder {
    /** Applies dead zones, curves, inversion, calibration and mode mapping. Pure function. */
    fun build(r: RawInput, s: Settings): ControllerState {
        val flags = if (r.racing) Protocol.FLAG_RACING else 0
        if (!r.racing) {
            val (lx, ly) = AxisMath.stick(r.lsx, r.lsy, s.stick)
            val (rx, ry) = AxisMath.stick(r.rsx, r.rsy, s.stick)
            return ControllerState(
                buttons = r.buttons and Protocol.BUTTON_MASK, dpad = r.dpad and 0x0F, flags = flags, lx = lx, ly = ly, rx = rx, ry = ry,
                lt = AxisMath.trigger(r.lt, s.triggerSensitivity, s.triggerLow, s.triggerHigh), rt = AxisMath.trigger(r.rt, s.triggerSensitivity, s.triggerLow, s.triggerHigh)
            )
        }
        val steer = if (s.steerMode == SteerMode.TILT)
            RacingMath.tilt(r.tiltDeg, s.tiltOffsetDeg, s.tiltMaxDeg, s.steerDeadzone, s.steerSensitivity, s.steerInvert)
        else AxisMath.linearAxis(r.wheel, s.steerDeadzone, s.steerSensitivity, s.steerInvert)
        return ControllerState(
            buttons = r.buttons and Protocol.BUTTON_MASK, flags = flags, steering = steer,
            accel = AxisMath.trigger(r.accel, s.pedalSensitivity), brake = AxisMath.trigger(r.brake, s.pedalSensitivity),
            handbrake = if (r.handbrake > 0.5f) 1f else 0f, gear = RacingMath.gearBits(r.gearUp, r.gearDown, s.manualTransmission)
        )
    }
}
