package com.nexus.protocol

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/** Accepts only packets that are newer than the last accepted one (16-bit serial arithmetic). */
class SequenceFilter {
    private var last = -1
    var dropped = 0L; private set
    var gaps = 0L; private set
    fun accept(seq: Int): Boolean {
        val s = seq and 0xFFFF
        if (last < 0) { last = s; return true }
        val diff = (s - last) and 0xFFFF
        if (diff in 1..0x7FFF) { if (diff > 1) gaps += diff - 1; last = s; return true }
        dropped++; return false
    }
    fun reset() { last = -1 }
}

/** Fail-safe: reports expiry when no valid input has been fed within [timeoutMs]. */
class Watchdog(var timeoutMs: Long) {
    private var lastFeed = Long.MIN_VALUE
    fun feed(nowMs: Long) { lastFeed = nowMs }
    fun isExpired(nowMs: Long) = lastFeed == Long.MIN_VALUE || nowMs - lastFeed > timeoutMs
    fun reset() { lastFeed = Long.MIN_VALUE }
}

/** Bounded exponential backoff: base * 2^attempt, capped, with a maximum attempt count. */
class ReconnectBackoff(private val baseMs: Long = 500, private val maxMs: Long = 15_000, val maxAttempts: Int = 8) {
    var attempt = 0; private set
    fun exhausted() = attempt >= maxAttempts
    /** Returns the delay for the next attempt and advances, or null when exhausted. */
    fun next(): Long? {
        if (exhausted()) return null
        val d = min(maxMs, baseMs shl min(attempt, 20)); attempt++; return d
    }
    fun reset() { attempt = 0 }
}

data class StickConfig(
    val deadzone: Float = 0.12f, val sensitivity: Float = 1f, val curve: Float = 1f,
    val invertX: Boolean = false, val invertY: Boolean = false
)

object AxisMath {
    /** Radial dead zone + response curve; direction is preserved. Output within the unit circle. */
    fun stick(x: Float, y: Float, c: StickConfig): Pair<Float, Float> {
        val mag = hypot(x, y)
        if (mag < c.deadzone.coerceIn(0f, 0.95f) || mag == 0f) return 0f to 0f
        val dz = c.deadzone.coerceIn(0f, 0.95f)
        val scaled = ((min(mag, 1f) - dz) / (1f - dz)).coerceIn(0f, 1f)
        val out = (scaled.pow(c.curve.coerceIn(0.3f, 4f)) * c.sensitivity.coerceIn(0.1f, 3f)).coerceIn(0f, 1f)
        var ox = x / mag * out; var oy = y / mag * out
        if (c.invertX) ox = -ox
        if (c.invertY) oy = -oy
        return ox to oy
    }

    fun linearAxis(v: Float, deadzone: Float, sensitivity: Float, invert: Boolean): Float {
        val a = abs(v)
        val dz = deadzone.coerceIn(0f, 0.95f)
        if (a <= dz) return 0f
        val s = (((a - dz) / (1f - dz)) * sensitivity.coerceIn(0.1f, 3f)).coerceIn(0f, 1f)
        return sign(v) * s * (if (invert) -1f else 1f)
    }

    fun trigger(v: Float, sensitivity: Float = 1f, lowCal: Float = 0f, highCal: Float = 1f): Float {
        val span = max(highCal - lowCal, 0.01f)
        return (((v - lowCal) / span) * sensitivity.coerceIn(0.1f, 3f)).coerceIn(0f, 1f)
    }
}

object RacingMath {
    /** Moves [current] toward 0 at [speedPerSec] (units/second). */
    fun returnToCenter(current: Float, speedPerSec: Float, dtSec: Float): Float {
        val step = speedPerSec * dtSec
        return if (abs(current) <= step) 0f else current - sign(current) * step
    }

    /** Converts device roll (degrees) into steering in [-1, 1]. */
    fun tilt(rollDeg: Float, offsetDeg: Float, maxDeg: Float, deadzone: Float, sensitivity: Float, invert: Boolean): Float {
        val n = ((rollDeg - offsetDeg) / maxDeg.coerceAtLeast(5f)).coerceIn(-1f, 1f)
        return AxisMath.linearAxis(n, deadzone, sensitivity, invert)
    }

    fun gearBits(up: Boolean, down: Boolean, manual: Boolean): Int =
        (if (up && !down) Protocol.GEAR_UP else 0) or (if (down && !up) Protocol.GEAR_DOWN else 0) or
            (if (manual) Protocol.GEAR_MANUAL else 0)
}

/** Remappable face buttons: logical slot -> wire bit. Rejects duplicate targets. */
class ButtonMap(initial: Map<String, Int> = DEFAULT) {
    private val map = initial.toMutableMap()
    fun bit(slot: String) = map[slot] ?: 0
    fun remap(slot: String, bit: Int): Boolean {
        if (slot !in map || bit == 0 || bit and (bit - 1) != 0 || bit and Protocol.BUTTON_MASK == 0) return false
        val other = map.entries.firstOrNull { it.value == bit && it.key != slot }
        if (other != null) { map[other.key] = map[slot]!! } // swap to keep mapping bijective
        map[slot] = bit; return true
    }
    fun snapshot(): Map<String, Int> = map.toMap()
    companion object {
        val DEFAULT = mapOf("south" to Protocol.BTN_A, "east" to Protocol.BTN_B, "west" to Protocol.BTN_X, "north" to Protocol.BTN_Y)
    }
}

/** Sender-side handshake: HELLO -> CHALLENGE -> AUTH -> ACCEPT. */
class ClientHandshake(private val pin: String, private val rng: java.security.SecureRandom = java.security.SecureRandom()) {
    enum class State { IDLE, WAIT_CHALLENGE, WAIT_ACCEPT, ACTIVE, REJECTED }
    var state = State.IDLE; private set
    var negotiatedVersion = 0; private set
    var rejectReason = 0; private set
    private val clientNonce = ByteArray(Protocol.NONCE_SIZE)

    fun start(): ByteArray { rng.nextBytes(clientNonce); state = State.WAIT_CHALLENGE
        return Protocol.encodeHello(Protocol.MIN_VERSION, Protocol.VERSION, clientNonce) }

    /** Feeds a packet from the receiver; returns bytes to send back, or null. */
    fun onPacket(b: ByteArray, len: Int): ByteArray? {
        when (Protocol.peekType(b, len)) {
            Protocol.TYPE_CHALLENGE -> if (state == State.WAIT_CHALLENGE) {
                val r = Protocol.decodeChallenge(b, len) as? Protocol.Decoded.Ok ?: return null
                negotiatedVersion = r.value.version
                if (negotiatedVersion !in Protocol.MIN_VERSION..Protocol.VERSION) { state = State.REJECTED; rejectReason = Protocol.REJECT_VERSION; return null }
                state = State.WAIT_ACCEPT
                return Protocol.encodeAuth(Protocol.hmac16(pin, clientNonce, r.value.serverNonce))
            }
            Protocol.TYPE_ACCEPT -> if (state == State.WAIT_ACCEPT && Protocol.decodeAccept(b, len) is Protocol.Decoded.Ok) state = State.ACTIVE
            Protocol.TYPE_REJECT -> if (state != State.ACTIVE) (Protocol.decodeReject(b, len) as? Protocol.Decoded.Ok)?.let { rejectReason = it.value; state = State.REJECTED }
        }
        return null
    }
}
