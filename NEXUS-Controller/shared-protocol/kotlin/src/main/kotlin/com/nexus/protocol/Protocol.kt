package com.nexus.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** NEXUS wire protocol v1 (little-endian). See docs/PROTOCOL.md. */
object Protocol {
    const val VERSION = 1
    const val MIN_VERSION = 1
    const val MAGIC0 = 0x4E // 'N'
    const val MAGIC1 = 0x58 // 'X'

    const val TYPE_INPUT = 1
    const val TYPE_HELLO = 2
    const val TYPE_CHALLENGE = 3
    const val TYPE_AUTH = 4
    const val TYPE_ACCEPT = 5
    const val TYPE_PING = 6
    const val TYPE_PONG = 7
    const val TYPE_REJECT = 8

    const val INPUT_SIZE = 33
    const val PING_SIZE = 9
    const val HELLO_SIZE = 15
    const val CHALLENGE_SIZE = 14
    const val AUTH_SIZE = 21
    const val ACCEPT_SIZE = 6
    const val REJECT_SIZE = 6
    const val MAX_PACKET = 64
    const val NONCE_SIZE = 8
    const val MAC_SIZE = 16

    // Button bits
    const val BTN_A = 1 shl 0
    const val BTN_B = 1 shl 1
    const val BTN_X = 1 shl 2
    const val BTN_Y = 1 shl 3
    const val BTN_LB = 1 shl 4
    const val BTN_RB = 1 shl 5
    const val BTN_SELECT = 1 shl 6
    const val BTN_START = 1 shl 7
    const val BTN_L3 = 1 shl 8
    const val BTN_R3 = 1 shl 9
    const val BTN_NITRO = 1 shl 10
    const val BTN_GUIDE = 1 shl 11
    const val BUTTON_MASK = 0x0FFF

    // D-pad bits
    const val DPAD_UP = 1
    const val DPAD_DOWN = 2
    const val DPAD_LEFT = 4
    const val DPAD_RIGHT = 8

    // Flags
    const val FLAG_RACING = 1
    const val FLAG_CALIBRATING = 2

    // Gear bits
    const val GEAR_UP = 1
    const val GEAR_DOWN = 2
    const val GEAR_MANUAL = 4

    const val REJECT_VERSION = 1
    const val REJECT_AUTH = 2
    const val REJECT_BUSY = 3

    fun crc8(data: ByteArray, len: Int): Int {
        var crc = 0
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    fun hmac16(pin: String, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(clientNonce); mac.update(serverNonce)
        return mac.doFinal().copyOf(MAC_SIZE)
    }

    private fun header(buf: ByteArray, type: Int, version: Int = VERSION) {
        buf[0] = MAGIC0.toByte(); buf[1] = MAGIC1.toByte(); buf[2] = version.toByte(); buf[3] = type.toByte()
    }

    private fun seal(buf: ByteArray, size: Int): ByteArray { buf[size - 1] = crc8(buf, size - 1).toByte(); return buf }

    private fun ByteArray.putU16(o: Int, v: Int) { this[o] = v.toByte(); this[o + 1] = (v shr 8).toByte() }
    private fun ByteArray.putU32(o: Int, v: Long) { for (i in 0..3) this[o + i] = (v shr (8 * i)).toByte() }
    private fun ByteArray.u8(o: Int) = this[o].toInt() and 0xFF
    private fun ByteArray.u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
    private fun ByteArray.i16(o: Int) = u16(o).toShort().toInt()
    private fun ByteArray.u32(o: Int): Long { var v = 0L; for (i in 0..3) v = v or (u8(o + i).toLong() shl (8 * i)); return v }

    fun axisToI16(v: Float): Int = (v.coerceIn(-1f, 1f) * 32767f).let { Math.round(it) }
    fun i16ToAxis(v: Int): Float = (v.coerceIn(-32767, 32767)) / 32767f
    fun unitToU8(v: Float): Int = Math.round(v.coerceIn(0f, 1f) * 255f)
    fun u8ToUnit(v: Int): Float = v / 255f

    /** Encodes into [out] (>= INPUT_SIZE bytes) without allocating. Returns INPUT_SIZE. */
    fun encodeInputInto(out: ByteArray, s: ControllerState, seq: Int, tickMs: Long): Int {
        header(out, TYPE_INPUT)
        out.putU16(4, seq and 0xFFFF)
        out.putU32(6, tickMs and 0xFFFFFFFFL)
        out.putU32(10, (s.buttons and BUTTON_MASK).toLong())
        out[14] = (s.dpad and 0x0F).toByte()
        out[15] = s.flags.toByte()
        out.putU16(16, axisToI16(s.lx)); out.putU16(18, axisToI16(s.ly))
        out.putU16(20, axisToI16(s.rx)); out.putU16(22, axisToI16(s.ry))
        out[24] = unitToU8(s.lt).toByte(); out[25] = unitToU8(s.rt).toByte()
        out.putU16(26, axisToI16(s.steering))
        out[28] = unitToU8(s.accel).toByte(); out[29] = unitToU8(s.brake).toByte()
        out[30] = unitToU8(s.handbrake).toByte(); out[31] = (s.gear and 0x07).toByte()
        seal(out, INPUT_SIZE)
        return INPUT_SIZE
    }

    fun encodeInput(s: ControllerState, seq: Int, tickMs: Long) =
        ByteArray(INPUT_SIZE).also { encodeInputInto(it, s, seq, tickMs) }

    sealed class Decoded<out T> {
        data class Ok<T>(val value: T) : Decoded<T>()
        data class Err(val reason: String) : Decoded<Nothing>()
    }

    private fun check(b: ByteArray, len: Int, type: Int, size: Int): String? {
        if (len != size || b.size < size) return "bad length $len (expected $size)"
        if (b.u8(0) != MAGIC0 || b.u8(1) != MAGIC1) return "bad magic"
        if (b.u8(3) != type) return "unexpected type ${b.u8(3)}"
        if (crc8(b, size - 1) != b.u8(size - 1)) return "bad checksum"
        return null
    }

    fun peekType(b: ByteArray, len: Int): Int =
        if (len >= 4 && len <= MAX_PACKET && b.u8(0) == MAGIC0 && b.u8(1) == MAGIC1) b.u8(3) else -1

    fun decodeInput(b: ByteArray, len: Int): Decoded<InputPacket> {
        check(b, len, TYPE_INPUT, INPUT_SIZE)?.let { return Decoded.Err(it) }
        val ver = b.u8(2)
        if (ver < MIN_VERSION || ver > VERSION) return Decoded.Err("unsupported version $ver")
        val dpad = b.u8(14)
        if (dpad and 0xF0 != 0) return Decoded.Err("reserved dpad bits set")
        if (b.u8(31) and 0xF8 != 0) return Decoded.Err("reserved gear bits set")
        var d = dpad
        if (d and 3 == 3) d = d and 3.inv()          // up+down cancel
        if (d and 12 == 12) d = d and 12.inv()       // left+right cancel
        val st = ControllerState(
            buttons = (b.u32(10) and BUTTON_MASK.toLong()).toInt(), dpad = d, flags = b.u8(15),
            lx = i16ToAxis(b.i16(16)), ly = i16ToAxis(b.i16(18)), rx = i16ToAxis(b.i16(20)), ry = i16ToAxis(b.i16(22)),
            lt = u8ToUnit(b.u8(24)), rt = u8ToUnit(b.u8(25)), steering = i16ToAxis(b.i16(26)),
            accel = u8ToUnit(b.u8(28)), brake = u8ToUnit(b.u8(29)), handbrake = u8ToUnit(b.u8(30)), gear = b.u8(31)
        )
        return Decoded.Ok(InputPacket(ver, b.u16(4), b.u32(6), st))
    }

    fun encodePing(tickMs: Long, pong: Boolean = false): ByteArray {
        val b = ByteArray(PING_SIZE); header(b, if (pong) TYPE_PONG else TYPE_PING)
        b.putU32(4, tickMs and 0xFFFFFFFFL); return seal(b, PING_SIZE)
    }

    fun decodePing(b: ByteArray, len: Int, pong: Boolean = false): Decoded<Long> {
        check(b, len, if (pong) TYPE_PONG else TYPE_PING, PING_SIZE)?.let { return Decoded.Err(it) }
        return Decoded.Ok(b.u32(4))
    }

    fun encodeHello(minVer: Int, maxVer: Int, clientNonce: ByteArray): ByteArray {
        require(clientNonce.size == NONCE_SIZE)
        val b = ByteArray(HELLO_SIZE); header(b, TYPE_HELLO)
        b[4] = minVer.toByte(); b[5] = maxVer.toByte(); clientNonce.copyInto(b, 6); return seal(b, HELLO_SIZE)
    }

    fun encodeAuth(mac: ByteArray): ByteArray {
        require(mac.size == MAC_SIZE)
        val b = ByteArray(AUTH_SIZE); header(b, TYPE_AUTH); mac.copyInto(b, 4); return seal(b, AUTH_SIZE)
    }

    data class Challenge(val version: Int, val serverNonce: ByteArray)

    fun decodeChallenge(b: ByteArray, len: Int): Decoded<Challenge> {
        check(b, len, TYPE_CHALLENGE, CHALLENGE_SIZE)?.let { return Decoded.Err(it) }
        return Decoded.Ok(Challenge(b.u8(4), b.copyOfRange(5, 5 + NONCE_SIZE)))
    }

    fun decodeAccept(b: ByteArray, len: Int): Decoded<Int> {
        check(b, len, TYPE_ACCEPT, ACCEPT_SIZE)?.let { return Decoded.Err(it) }
        return Decoded.Ok(b.u8(4))
    }

    fun decodeReject(b: ByteArray, len: Int): Decoded<Int> {
        check(b, len, TYPE_REJECT, REJECT_SIZE)?.let { return Decoded.Err(it) }
        return Decoded.Ok(b.u8(4))
    }
}

data class InputPacket(val version: Int, val seq: Int, val tickMs: Long, val state: ControllerState)

data class ControllerState(
    val buttons: Int = 0, val dpad: Int = 0, val flags: Int = 0,
    val lx: Float = 0f, val ly: Float = 0f, val rx: Float = 0f, val ry: Float = 0f,
    val lt: Float = 0f, val rt: Float = 0f,
    val steering: Float = 0f, val accel: Float = 0f, val brake: Float = 0f, val handbrake: Float = 0f,
    val gear: Int = 0
) {
    companion object { val NEUTRAL = ControllerState() }
    /** Preserves the mode flag so a neutral state still tells the receiver which layout is active. */
    fun neutralKeepingFlags() = ControllerState(flags = flags and Protocol.FLAG_RACING)
}
