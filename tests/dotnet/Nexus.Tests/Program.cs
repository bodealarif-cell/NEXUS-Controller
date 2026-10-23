using Nexus.Protocol;
using NexusReceiver;

int passed = 0; var failed = new List<string>();
void Test(string name, Action body) { try { body(); passed++; Console.WriteLine("PASS  " + name); } catch (Exception e) { failed.Add(name); Console.WriteLine($"FAIL  {name} -> {e.Message}"); } }
void Ok(bool c, string m) { if (!c) throw new Exception(m); }
void Eq<T>(T a, T b, string m = "") { if (!EqualityComparer<T>.Default.Equals(a, b)) throw new Exception($"{m} expected <{b}> got <{a}>"); }
void Near(float a, float b, float eps = 0.001f) { if (MathF.Abs(a - b) > eps) throw new Exception($"expected ~{b} got {a}"); }

var full = new ControllerState { Buttons = P.BtnA | P.BtnStart | P.BtnNitro, Dpad = P.DpadUp | P.DpadRight, Flags = P.FlagRacing, Lx = -1f, Ly = 0.5f, Rx = 0.25f, Ry = -0.75f, Lt = 1f, Rt = 0.5f, Steering = -0.4f, Accel = 1f, Brake = 0f, Handbrake = 1f, Gear = P.GearUp };

Test("cross-language golden vector (must equal Kotlin output)", () =>
{
    var hex = Convert.ToHexString(P.EncodeInput(full, 513, 123456));
    Console.WriteLine("      golden=" + hex);
    Eq(hex, GoldenHex.Value);
});
Test("round trip", () =>
{
    Ok(P.TryDecodeInput(P.EncodeInput(full, 513, 123456), out var p, out _), "decode");
    Eq(p.Seq, 513); Eq(p.TickMs, 123456u); Eq(p.State.Buttons, full.Buttons); Eq(p.State.Dpad, full.Dpad); Near(p.State.Lx, -1f); Near(p.State.Ry, -0.75f); Near(p.State.Steering, -0.4f); Eq(p.State.Gear, P.GearUp);
});
Test("size is 33", () => Eq(P.EncodeInput(full, 1, 1).Length, 33));
Test("clamps and NaN safe", () =>
{
    Ok(P.TryDecodeInput(P.EncodeInput(new ControllerState { Lx = 9f, Ly = float.NaN, Lt = 5f, Accel = -3f }, 1, 1), out var p, out _), "d");
    Near(p.State.Lx, 1f); Near(p.State.Ly, 0f); Near(p.State.Lt, 1f); Near(p.State.Accel, 0f);
});
Test("rejects wrong lengths", () => { var b = P.EncodeInput(full, 1, 1); Ok(!P.TryDecodeInput(b.AsSpan(0, 32), out _, out _), "short"); Ok(!P.TryDecodeInput(Array.Empty<byte>(), out _, out _), "empty"); Ok(!P.TryDecodeInput(new byte[40], out _, out _), "long"); });
Test("every single bit flip rejected", () =>
{
    for (int i = 0; i < 32; i++) { var b = P.EncodeInput(full, 1, 1); b[i] ^= 1; Ok(!P.TryDecodeInput(b, out _, out _), $"flip {i}"); }
});
Test("rejects unsupported version / reserved dpad", () =>
{
    var b = P.EncodeInput(full, 1, 1); b[2] = 9; b[32] = P.Crc8(b.AsSpan(0, 32)); Ok(!P.TryDecodeInput(b, out _, out _), "ver");
    b = P.EncodeInput(full, 1, 1); b[14] = 0x30; b[32] = P.Crc8(b.AsSpan(0, 32)); Ok(!P.TryDecodeInput(b, out _, out _), "dpad");
});
Test("opposing dpad cancels", () => { Ok(P.TryDecodeInput(P.EncodeInput(new ControllerState { Dpad = 3 | P.DpadLeft }, 1, 1), out var p, out _), "d"); Eq(p.State.Dpad, P.DpadLeft); });
Test("fuzz 20000 random packets: none accepted, none throw", () =>
{
    var r = new Random(42); int acc = 0;
    for (int i = 0; i < 20000; i++) { var b = new byte[r.Next(48)]; r.NextBytes(b); if (P.TryDecodeInput(b, out _, out _)) acc++; }
    Eq(acc, 0);
});
Test("sequence filter", () =>
{
    var f = new SequenceFilter(); Ok(f.Accept(10), "a"); Ok(!f.Accept(10), "dup"); Ok(f.Accept(11), "b"); Ok(!f.Accept(9), "old"); Ok(f.Accept(14), "gap"); Eq(f.Gaps, 2L); Eq(f.Dropped, 2L);
    var w = new SequenceFilter(); w.Accept(65534); Ok(w.Accept(65535), "x"); Ok(w.Accept(0), "wrap"); Ok(!w.Accept(65535), "old");
});
Test("watchdog", () => { var w = new Watchdog(250); Ok(w.IsExpired(0), "unfed"); w.Feed(1000); Ok(!w.IsExpired(1200), "alive"); Ok(w.IsExpired(1300), "exp"); });

const string Pin = "482915";
(ReceiverSession s, byte[] cn) Handshake(string pin, string src, long now, out byte[]? accept)
{
    var s = new ReceiverSession(Pin, 500, () => Enumerable.Range(1, 8).Select(i => (byte)i).ToArray());
    var cn = Enumerable.Range(50, 8).Select(i => (byte)i).ToArray();
    var hello = new byte[15]; hello[0] = 0x4E; hello[1] = 0x58; hello[2] = 1; hello[3] = P.TypeHello; hello[4] = 1; hello[5] = 1; cn.CopyTo(hello, 6); hello[14] = P.Crc8(hello.AsSpan(0, 14));
    var r = s.Process(src, hello, now); Ok(r.Reply != null && P.PeekType(r.Reply) == P.TypeChallenge, "challenge");
    var auth = new byte[21]; auth[0] = 0x4E; auth[1] = 0x58; auth[2] = 1; auth[3] = P.TypeAuth; P.Hmac16(pin, cn, r.Reply!.AsSpan(5, 8)).CopyTo(auth, 4); auth[20] = P.Crc8(auth.AsSpan(0, 20));
    accept = s.Process(src, auth, now + 1).Reply; return (s, cn);
}
Test("session: correct PIN activates, input flows", () =>
{
    var (s, _) = Handshake(Pin, "dev1", 0, out var acc); Ok(acc != null && P.PeekType(acc) == P.TypeAccept, "accept"); Eq(s.State, ReceiverSession.Phase.Active);
    var r = s.Process("dev1", P.EncodeInput(full, 1, 1), 10); Ok(r.Input.HasValue, "input"); Eq(r.Input!.Value.State.Buttons, full.Buttons);
});
Test("session: wrong PIN rejected, no input accepted", () =>
{
    var (s, _) = Handshake("000000", "dev1", 0, out var rej); Ok(rej != null && P.PeekType(rej) == P.TypeReject, "reject"); Eq(s.State, ReceiverSession.Phase.Idle);
    Ok(!s.Process("dev1", P.EncodeInput(full, 1, 1), 5).Input.HasValue, "must ignore");
});
Test("session: input before auth and from other source ignored", () =>
{
    var s = new ReceiverSession(Pin, 500); Ok(!s.Process("x", P.EncodeInput(full, 1, 1), 0).Input.HasValue, "pre-auth");
    var (s2, _) = Handshake(Pin, "dev1", 0, out _); Ok(!s2.Process("attacker", P.EncodeInput(full, 2, 2), 5).Input.HasValue, "other source");
});
Test("session: second device gets BUSY while first is alive", () =>
{
    var (s, cn) = Handshake(Pin, "dev1", 0, out _); s.Process("dev1", P.EncodeInput(full, 1, 1), 100);
    var hello = new byte[15]; hello[0] = 0x4E; hello[1] = 0x58; hello[2] = 1; hello[3] = P.TypeHello; hello[4] = 1; hello[5] = 1; hello[14] = P.Crc8(hello.AsSpan(0, 14));
    var r = s.Process("dev2", hello, 200); Eq(P.PeekType(r.Reply!), P.TypeReject); Eq(s.Source, "dev1");
});
Test("session: lockout after 5 wrong PINs", () =>
{
    var s = new ReceiverSession(Pin, 500, () => new byte[8]); var cn = new byte[8]; long t = 0; byte[]? last = null;
    for (int i = 0; i < 5; i++)
    {
        var hello = new byte[15]; hello[0] = 0x4E; hello[1] = 0x58; hello[2] = 1; hello[3] = P.TypeHello; hello[4] = 1; hello[5] = 1; hello[14] = P.Crc8(hello.AsSpan(0, 14)); s.Process("a", hello, t);
        var auth = new byte[21]; auth[0] = 0x4E; auth[1] = 0x58; auth[2] = 1; auth[3] = P.TypeAuth; P.Hmac16("bad" + i, cn, new byte[8]).CopyTo(auth, 4); auth[20] = P.Crc8(auth.AsSpan(0, 20)); last = s.Process("a", auth, t + 1).Reply; t += 10;
    }
    var h = new byte[15]; h[0] = 0x4E; h[1] = 0x58; h[2] = 1; h[3] = P.TypeHello; h[4] = 1; h[5] = 1; h[14] = P.Crc8(h.AsSpan(0, 14));
    var r = s.Process("a", h, t); Eq((int)r.Reply![4], P.RejectLocked); Ok(s.Process("a", h, t + ReceiverSession.LockoutMs + 1).Reply![3] == P.TypeChallenge, "unlock");
});
Test("session: version mismatch rejected", () =>
{
    var s = new ReceiverSession(Pin, 500); var h = new byte[15]; h[0] = 0x4E; h[1] = 0x58; h[2] = 5; h[3] = P.TypeHello; h[4] = 5; h[5] = 9; h[14] = P.Crc8(h.AsSpan(0, 14));
    Eq((int)s.Process("a", h, 0).Reply![4], P.RejectVersion);
});
Test("session: duplicate/out-of-order input dropped", () =>
{
    var (s, _) = Handshake(Pin, "d", 0, out _); Ok(s.Process("d", P.EncodeInput(full, 5, 1), 1).Input.HasValue, "5"); Ok(!s.Process("d", P.EncodeInput(full, 5, 1), 2).Input.HasValue, "dup"); Ok(!s.Process("d", P.EncodeInput(full, 4, 1), 3).Input.HasValue, "old"); Ok(s.Process("d", P.EncodeInput(full, 6, 1), 4).Input.HasValue, "6");
});
Test("session: ping answered with pong echoing tick", () =>
{
    var (s, _) = Handshake(Pin, "d", 0, out _); var r = s.Process("d", P.EncodePing(4242, false), 5); Eq(P.PeekType(r.Reply!), P.TypePong);
    Eq(BitConverter.ToUInt32(r.Reply!, 4), 4242u);
});
Test("fail-safe: inactivity timeout resets session and gate neutralizes", () =>
{
    var (s, _) = Handshake(Pin, "d", 0, out _); var g = new InputGate(); g.Apply(full); Ok(!s.Tick(400), "not yet"); Ok(s.Tick(700), "timeout"); g.Release();
    Eq(g.Current, ControllerState.Neutral); Ok(g.Neutralized, "neutral"); Eq(s.State, ReceiverSession.Phase.Idle);
    Ok(!s.Process("d", P.EncodeInput(full, 99, 1), 800).Input.HasValue, "must re-auth after timeout");
});
Test("xinput mapping: gamepad", () =>
{
    var r = XInputMapper.Map(new ControllerState { Buttons = P.BtnA | P.BtnStart | P.BtnLB, Dpad = P.DpadLeft, Lx = 1f, Ly = -1f, Lt = 1f, Rt = 0f });
    Eq((int)r.Buttons, 0x1000 | 0x10 | 0x100 | 4); Eq((int)r.LX, 32767); Eq((int)r.LY, -32767); Eq((int)r.LeftTrigger, 255); Eq((int)r.RightTrigger, 0);
});
Test("xinput mapping: racing (steer→LX, accel→RT, brake→LT, gears, handbrake)", () =>
{
    var r = XInputMapper.Map(new ControllerState { Flags = P.FlagRacing, Steering = -0.5f, Accel = 1f, Brake = 0.5f, Gear = P.GearUp, Handbrake = 1f, Buttons = P.BtnNitro });
    Ok(r.LX < -16000 && r.LX > -16500, "steer"); Eq((int)r.RightTrigger, 255); Eq((int)r.LeftTrigger, 128); Ok((r.Buttons & 0x200) != 0, "RB"); Ok((r.Buttons & 0x4000) != 0, "X"); Ok((r.Buttons & 0x8000) != 0, "Y nitro");
});
Test("xinput mapping: neutral is all zero", () => Eq(XInputMapper.Map(ControllerState.Neutral), new GamepadReport(0, 0, 0, 0, 0, 0, 0)));


// ---------- ReceiverHub (windows-receiver core, compiled into this test project) ----------
(ReceiverHub hub, FakePad pad, Func<long> t, Action<long> adv, List<byte[]> replies) NewHub(long failsafe = 500)
{
    long now = 0; var pad = new FakePad(); var hub = new ReceiverHub(failsafe, () => now, "111111"); hub.SetPad(pad);
    return (hub, pad, () => now, v => now = v, new List<byte[]>());
}
void HubHandshake(ReceiverHub hub, string src, Func<long> t, List<byte[]> replies, string pin = "111111")
{
    var cn = Enumerable.Range(1, 8).Select(i => (byte)i).ToArray();
    var hello = new byte[15]; hello[0] = 0x4E; hello[1] = 0x58; hello[2] = 1; hello[3] = P.TypeHello; hello[4] = 1; hello[5] = 1; cn.CopyTo(hello, 6); hello[14] = P.Crc8(hello.AsSpan(0, 14));
    hub.OnPacket(src, hello, replies.Add);
    var ch = replies[^1]; var auth = new byte[21]; auth[0] = 0x4E; auth[1] = 0x58; auth[2] = 1; auth[3] = P.TypeAuth; P.Hmac16(pin, cn, ch.AsSpan(5, 8)).CopyTo(auth, 4); auth[20] = P.Crc8(auth.AsSpan(0, 20));
    hub.OnPacket(src, auth, replies.Add);
}
Test("hub: input reaches virtual pad with correct mapping", () =>
{
    var (hub, pad, t, adv, rep) = NewHub(); HubHandshake(hub, "p", t, rep); Ok(hub.IsConnected, "connected");
    hub.OnPacket("p", P.EncodeInput(new ControllerState { Buttons = P.BtnA, Lx = 1f }, 1, 1), rep.Add);
    var last = pad.Reports[^1]; Eq((int)last.Buttons, 0x1000); Eq((int)last.LX, 32767);
});
Test("hub: fail-safe releases held controls after timeout, resumes on new input", () =>
{
    var (hub, pad, t, adv, rep) = NewHub(500); HubHandshake(hub, "p", t, rep);
    adv(100); hub.OnPacket("p", P.EncodeInput(new ControllerState { Buttons = P.BtnA | P.BtnB, Rt = 1f }, 1, 1), rep.Add); hub.Tick();
    Ok(pad.Reports[^1].Buttons != 0, "held");
    adv(400); hub.Tick(); Ok(pad.Reports[^1].Buttons != 0, "still within timeout");
    adv(700); hub.Tick(); Eq(pad.Reports[^1], default(GamepadReport)); Eq(hub.Current, ControllerState.Neutral); Ok(hub.IsConnected, "session stays up");
    adv(710); hub.OnPacket("p", P.EncodeInput(new ControllerState { Buttons = P.BtnX }, 2, 2), rep.Add); Eq((int)pad.Reports[^1].Buttons, 0x4000);
});
Test("hub: session timeout ends connection and neutralizes", () =>
{
    var (hub, pad, t, adv, rep) = NewHub(); HubHandshake(hub, "p", t, rep); adv(10); hub.OnPacket("p", P.EncodeInput(new ControllerState { Buttons = P.BtnA }, 1, 1), rep.Add);
    adv(6000); hub.Tick(); Ok(!hub.IsConnected, "disconnected"); Eq(pad.Reports[^1], default(GamepadReport));
});
Test("hub: user disconnect releases everything", () =>
{
    var (hub, pad, t, adv, rep) = NewHub(); HubHandshake(hub, "p", t, rep); hub.OnPacket("p", P.EncodeInput(new ControllerState { Buttons = P.BtnA }, 1, 1), rep.Add);
    hub.Disconnect(); Ok(!hub.IsConnected, "disc"); Eq(pad.Reports[^1], default(GamepadReport));
});
Test("hub: shutdown releases and disposes pad", () =>
{
    var (hub, pad, t, adv, rep) = NewHub(); HubHandshake(hub, "p", t, rep); hub.OnPacket("p", P.EncodeInput(new ControllerState { Buttons = P.BtnA }, 1, 1), rep.Add);
    hub.Shutdown(); Eq(pad.Reports[^1], default(GamepadReport)); Ok(pad.Disposed, "disposed");
});
Test("hub: rate limiter drops floods but stays alive", () =>
{
    var (hub, pad, t, adv, rep) = NewHub(); HubHandshake(hub, "p", t, rep);
    for (int i = 0; i < 2000; i++) hub.OnPacket("p", P.EncodeInput(new ControllerState(), i + 1, 1), rep.Add);
    Ok(hub.RateLimited > 1000, "limited"); adv(1500); hub.OnPacket("p", P.EncodeInput(new ControllerState { Buttons = 1 }, 5000, 1), rep.Add); Ok(hub.Current.Buttons == 1, "recovers next second");
});
Test("hub: oversized and garbage packets rejected without effect", () =>
{
    var (hub, pad, t, adv, rep) = NewHub(); HubHandshake(hub, "p", t, rep); long before = hub.Packets;
    hub.OnPacket("p", new byte[500], rep.Add); hub.OnPacket("p", new byte[0], rep.Add); hub.OnPacket("p", new byte[] { 1, 2, 3 }, rep.Add); Eq(hub.Packets, before);
});
Test("hub: new PIN invalidates active session", () =>
{
    var (hub, pad, t, adv, rep) = NewHub(); HubHandshake(hub, "p", t, rep); hub.NewPin(); Ok(!hub.IsConnected, "closed");
    hub.OnPacket("p", P.EncodeInput(new ControllerState { Buttons = 1 }, 9, 1), rep.Add); Eq(hub.Current, ControllerState.Neutral);
});

Console.WriteLine($"\n{passed} passed, {failed.Count} failed");
return failed.Count == 0 ? 0 : 1;

sealed class FakePad : IVirtualPad
{
    public List<GamepadReport> Reports = new(); public bool Disposed;
    public bool TryStart(out string error) { error = ""; return true; }
    public void Submit(in GamepadReport r) => Reports.Add(r);
    public void Dispose() => Disposed = true;
}
