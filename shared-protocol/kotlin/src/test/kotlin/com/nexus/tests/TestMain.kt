package com.nexus.tests

import com.nexus.protocol.*
import kotlin.system.exitProcess

internal var passed = 0
internal val failures = mutableListOf<String>()
internal fun test(name: String, body: () -> Unit) {
    try { body(); passed++; println("PASS  $name") }
    catch (t: Throwable) { failures += name; println("FAIL  $name -> ${t.message}") }
}
internal fun eq(a: Any?, b: Any?, m: String = "") { if (a != b) throw AssertionError("$m expected <$b> got <$a>") }
internal fun near(a: Float, b: Float, eps: Float = 0.001f, m: String = "") { if (kotlin.math.abs(a - b) > eps) throw AssertionError("$m expected ~$b got $a") }
internal fun ok(c: Boolean, m: String) { if (!c) throw AssertionError(m) }

fun main() {
    val full = ControllerState(buttons = Protocol.BTN_A or Protocol.BTN_START or Protocol.BTN_NITRO, dpad = Protocol.DPAD_UP or Protocol.DPAD_RIGHT,
        flags = Protocol.FLAG_RACING, lx = -1f, ly = 0.5f, rx = 0.25f, ry = -0.75f, lt = 1f, rt = 0.5f,
        steering = -0.4f, accel = 1f, brake = 0f, handbrake = 1f, gear = Protocol.GEAR_UP)

    test("encode/decode round trip") {
        val p = (Protocol.decodeInput(Protocol.encodeInput(full, 513, 123456L), Protocol.INPUT_SIZE) as Protocol.Decoded.Ok).value
        eq(p.seq, 513); eq(p.tickMs, 123456L); eq(p.state.buttons, full.buttons); eq(p.state.dpad, full.dpad)
        near(p.state.lx, -1f); near(p.state.ly, 0.5f, 0.001f); near(p.state.ry, -0.75f, 0.001f)
        near(p.state.rt, 0.5f, 0.005f); near(p.state.steering, -0.4f); eq(p.state.gear, Protocol.GEAR_UP)
    }
    test("cross-language golden vector (identical bytes to C# implementation)") {
        val hex = Protocol.encodeInput(full, 513, 123456L).joinToString("") { "%02X".format(it) }
        eq(hex, "4E580101010240E2010081040000090101800040002001A0FF80CDCCFF00FF01D0")
    }
    test("packet size is 33 bytes") { eq(Protocol.encodeInput(full, 1, 1).size, 33) }
    test("sequence wraps at 16 bits") {
        val p = (Protocol.decodeInput(Protocol.encodeInput(full, 65536 + 7, 0), 33) as Protocol.Decoded.Ok).value; eq(p.seq, 7)
    }
    test("axes are clamped on encode") {
        val p = (Protocol.decodeInput(Protocol.encodeInput(ControllerState(lx = 9f, ly = -9f, lt = 5f, accel = -3f), 1, 1), 33) as Protocol.Decoded.Ok).value
        near(p.state.lx, 1f); near(p.state.ly, -1f); near(p.state.lt, 1f); near(p.state.accel, 0f)
    }
    test("raw -32768 decodes to -1.0") {
        val b = Protocol.encodeInput(ControllerState(), 1, 1); b[16] = 0x00; b[17] = 0x80.toByte(); b[32] = Protocol.crc8(b, 32).toByte()
        near(((Protocol.decodeInput(b, 33)) as Protocol.Decoded.Ok).value.state.lx, -1f)
    }
    test("rejects short, long and empty packets") {
        val b = Protocol.encodeInput(full, 1, 1)
        ok(Protocol.decodeInput(b, 32) is Protocol.Decoded.Err, "short"); ok(Protocol.decodeInput(b, 0) is Protocol.Decoded.Err, "empty")
        ok(Protocol.decodeInput(b.copyOf(40), 40) is Protocol.Decoded.Err, "long")
    }
    test("rejects bad magic") { val b = Protocol.encodeInput(full, 1, 1); b[0] = 0; ok(Protocol.decodeInput(b, 33) is Protocol.Decoded.Err, "magic") }
    test("rejects corrupted checksum / flipped bit") {
        for (i in 0 until 32) { val b = Protocol.encodeInput(full, 1, 1); b[i] = (b[i].toInt() xor 1).toByte()
            ok(Protocol.decodeInput(b, 33) is Protocol.Decoded.Err, "bit flip at $i accepted") }
    }
    test("rejects unsupported version") {
        val b = Protocol.encodeInput(full, 1, 1); b[2] = 9; b[32] = Protocol.crc8(b, 32).toByte(); ok(Protocol.decodeInput(b, 33) is Protocol.Decoded.Err, "ver")
    }
    test("rejects reserved dpad bits") {
        val b = Protocol.encodeInput(full, 1, 1); b[14] = 0x30; b[32] = Protocol.crc8(b, 32).toByte(); ok(Protocol.decodeInput(b, 33) is Protocol.Decoded.Err, "dpad")
    }
    test("opposing dpad directions cancel") {
        val p = (Protocol.decodeInput(Protocol.encodeInput(ControllerState(dpad = 3 or Protocol.DPAD_LEFT), 1, 1), 33) as Protocol.Decoded.Ok).value
        eq(p.state.dpad, Protocol.DPAD_LEFT)
    }
    test("fuzz: random bytes never crash and are never accepted unless valid") {
        val r = java.util.Random(42); var accepted = 0
        repeat(20000) { val n = r.nextInt(48); val b = ByteArray(n).also { r.nextBytes(it) }
            if (Protocol.decodeInput(b, n) is Protocol.Decoded.Ok) accepted++ }
        eq(accepted, 0)
    }
    test("encodeInputInto reuses buffer") {
        val buf = ByteArray(64); Protocol.encodeInputInto(buf, full, 5, 9)
        eq((Protocol.decodeInput(buf, 33) as Protocol.Decoded.Ok).value.seq, 5)
    }
    test("ping/pong round trip") { eq((Protocol.decodePing(Protocol.encodePing(777), 9) as Protocol.Decoded.Ok).value, 777L)
        ok(Protocol.decodePing(Protocol.encodePing(1, true), 9, false) is Protocol.Decoded.Err, "type confusion") }

    test("sequence filter: duplicates and out-of-order dropped") {
        val f = SequenceFilter(); ok(f.accept(10), "first"); ok(!f.accept(10), "dup"); ok(f.accept(11), "next"); ok(!f.accept(9), "old"); ok(f.accept(14), "gap")
        eq(f.gaps, 2L); eq(f.dropped, 2L)
    }
    test("sequence filter: wrap-around accepted") { val f = SequenceFilter(); f.accept(65534); ok(f.accept(65535), "a"); ok(f.accept(0), "wrap"); ok(!f.accept(65535), "old after wrap") }

    test("watchdog expires and recovers") {
        val w = Watchdog(250); ok(w.isExpired(0), "never fed"); w.feed(1000); ok(!w.isExpired(1200), "alive"); ok(w.isExpired(1300), "expired"); w.feed(1300); ok(!w.isExpired(1400), "recovered")
    }
    test("backoff doubles, caps, bounded") {
        val b = ReconnectBackoff(500, 3000, 5); val d = generateSequence { b.next() }.toList()
        eq(d, listOf(500L, 1000L, 2000L, 3000L, 3000L)); ok(b.exhausted(), "exhausted"); b.reset(); eq(b.next(), 500L)
    }

    test("stick: dead zone zeroes small input") { eq(AxisMath.stick(0.05f, 0.05f, StickConfig()), 0f to 0f) }
    test("stick: full deflection maps to 1.0 and keeps direction") {
        val (x, y) = AxisMath.stick(1f, 0f, StickConfig()); near(x, 1f); near(y, 0f)
        val (a, b) = AxisMath.stick(0.7f, 0.7f, StickConfig(deadzone = 0f)); near(a, b)
    }
    test("stick: inversion and clamping") {
        val (x, y) = AxisMath.stick(1f, 1f, StickConfig(deadzone = 0f, sensitivity = 3f, invertX = true)); ok(x < 0 && y > 0, "invert"); ok(kotlin.math.hypot(x, y) <= 1.0001f, "unit circle")
    }
    test("stick: curve flattens low deflection") {
        val lin = AxisMath.stick(0.5f, 0f, StickConfig(deadzone = 0f, curve = 1f)).first; val cur = AxisMath.stick(0.5f, 0f, StickConfig(deadzone = 0f, curve = 2f)).first; ok(cur < lin, "curve")
    }
    test("trigger calibration and clamp") { near(AxisMath.trigger(0.5f, 1f, 0.2f, 0.8f), 0.5f); near(AxisMath.trigger(0.1f, 1f, 0.2f, 0.8f), 0f); near(AxisMath.trigger(2f), 1f) }
    test("steering: dead zone, sensitivity, invert") {
        near(AxisMath.linearAxis(0.05f, 0.1f, 1f, false), 0f); near(AxisMath.linearAxis(1f, 0.1f, 1f, false), 1f); near(AxisMath.linearAxis(0.5f, 0f, 1f, true), -0.5f)
    }
    test("steering return-to-center never overshoots") {
        var s = 0.5f; repeat(10) { s = RacingMath.returnToCenter(s, 1f, 0.1f) }; near(s, 0f); near(RacingMath.returnToCenter(-0.02f, 1f, 0.1f), 0f)
    }
    test("tilt: offset calibration and limits") {
        near(RacingMath.tilt(10f, 10f, 45f, 0f, 1f, false), 0f); near(RacingMath.tilt(100f, 0f, 45f, 0f, 1f, false), 1f); near(RacingMath.tilt(-45f, 0f, 45f, 0f, 1f, false), -1f)
    }
    test("gear bits: up+down simultaneously cancel") { eq(RacingMath.gearBits(true, true, false), 0); eq(RacingMath.gearBits(true, false, true), Protocol.GEAR_UP or Protocol.GEAR_MANUAL) }
    test("button map: remap swaps to stay bijective, rejects invalid") {
        val m = ButtonMap(); ok(m.remap("south", Protocol.BTN_B), "remap"); eq(m.bit("south"), Protocol.BTN_B); eq(m.bit("east"), Protocol.BTN_A)
        ok(!m.remap("south", 0), "zero"); ok(!m.remap("south", 3), "multi-bit"); ok(!m.remap("nope", 1), "unknown slot")
        eq(m.snapshot().values.toSet().size, 4)
    }
    test("neutral keeps racing flag only") { val n = full.neutralKeepingFlags(); eq(n.buttons, 0); eq(n.flags, Protocol.FLAG_RACING); near(n.steering, 0f) }

    test("handshake: correct PIN reaches ACTIVE with negotiated version") {
        val c = ClientHandshake("123456"); val hello = c.start(); eq(Protocol.peekType(hello, hello.size), Protocol.TYPE_HELLO)
        val sn = ByteArray(8) { it.toByte() }; val ch = ByteArray(14); ch[0] = 0x4E; ch[1] = 0x58; ch[2] = 1; ch[3] = 3; ch[4] = 1; sn.copyInto(ch, 5); ch[13] = Protocol.crc8(ch, 13).toByte()
        val auth = c.onPacket(ch, 14); ok(auth != null && auth.size == 21, "auth sent")
        val acc = ByteArray(6); acc[0] = 0x4E; acc[1] = 0x58; acc[2] = 1; acc[3] = 5; acc[4] = 1; acc[5] = Protocol.crc8(acc, 5).toByte()
        c.onPacket(acc, 6); eq(c.state, ClientHandshake.State.ACTIVE); eq(c.negotiatedVersion, 1)
    }
    test("handshake: ACCEPT ignored before AUTH (no skipping)") {
        val c = ClientHandshake("1"); c.start(); val acc = ByteArray(6); acc[0] = 0x4E; acc[1] = 0x58; acc[2] = 1; acc[3] = 5; acc[4] = 1; acc[5] = Protocol.crc8(acc, 5).toByte()
        c.onPacket(acc, 6); ok(c.state != ClientHandshake.State.ACTIVE, "must not activate")
    }
    test("hmac differs for different PIN / nonce") {
        val n1 = ByteArray(8); val n2 = ByteArray(8) { 1 }
        ok(!Protocol.hmac16("111111", n1, n2).contentEquals(Protocol.hmac16("111112", n1, n2)), "pin"); ok(!Protocol.hmac16("111111", n1, n2).contentEquals(Protocol.hmac16("111111", n2, n1)), "nonce")
    }

    runCoreTests()
    println("\n${passed} passed, ${failures.size} failed")
    if (failures.isNotEmpty()) { println("FAILED: $failures"); exitProcess(1) }
}
