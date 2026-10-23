namespace Nexus.Protocol;

public sealed class SequenceFilter
{
    int _last = -1;
    public long Dropped { get; private set; }
    public long Gaps { get; private set; }
    public bool Accept(int seq)
    {
        int s = seq & 0xFFFF;
        if (_last < 0) { _last = s; return true; }
        int diff = (s - _last) & 0xFFFF;
        if (diff is >= 1 and <= 0x7FFF) { if (diff > 1) Gaps += diff - 1; _last = s; return true; }
        Dropped++; return false;
    }
    public void Reset() => _last = -1;
}

public sealed class Watchdog
{
    long _last = long.MinValue;
    public long TimeoutMs { get; set; }
    public Watchdog(long timeoutMs) => TimeoutMs = timeoutMs;
    public void Feed(long now) => _last = now;
    public bool IsExpired(long now) => _last == long.MinValue || now - _last > TimeoutMs;
    public void Reset() => _last = long.MinValue;
}

/// <summary>Receiver-side session: handshake, PIN auth with lockout, source pinning, input filtering, fail-safe.</summary>
public sealed class ReceiverSession
{
    public enum Phase { Idle, Challenged, Active }
    public readonly record struct Result(byte[]? Reply, InputPacket? Input, string? Note);

    readonly string _pin; readonly Func<byte[]> _nonce; readonly long _timeoutMs;
    readonly SequenceFilter _seq = new();
    byte[] _clientNonce = Array.Empty<byte>(), _serverNonce = Array.Empty<byte>();
    int _version; int _failures; long _lockUntil; long _lastActivity;

    public Phase State { get; private set; } = Phase.Idle;
    public string? Source { get; private set; }
    public SequenceFilter Sequence => _seq;
    public int NegotiatedVersion => _version;
    public const int MaxFailures = 5; public const long LockoutMs = 30_000;

    public ReceiverSession(string pin, long inactivityTimeoutMs, Func<byte[]>? nonceFactory = null)
    {
        _pin = pin; _timeoutMs = inactivityTimeoutMs;
        _nonce = nonceFactory ?? (() => System.Security.Cryptography.RandomNumberGenerator.GetBytes(P.NonceSize));
    }

    /// <summary>Call periodically. Returns true when an active session just timed out (caller must release all controls).</summary>
    public bool Tick(long now)
    {
        if (State != Phase.Idle && now - _lastActivity > _timeoutMs) { Reset(); return true; }
        return false;
    }

    public void Reset() { State = Phase.Idle; Source = null; _seq.Reset(); }

    public Result Process(string source, ReadOnlySpan<byte> data, long now)
    {
        int type = P.PeekType(data);
        switch (type)
        {
            case P.TypeHello:
            {
                if (now < _lockUntil) return new(P.EncodeReject(P.RejectLocked), null, "locked out");
                if (State != Phase.Idle && Source != source && now - _lastActivity <= _timeoutMs) return new(P.EncodeReject(P.RejectBusy), null, "busy");
                if (!P.TryDecodeHello(data, out int min, out int max, out var cn)) return new(null, null, "bad hello");
                int chosen = Math.Min(max, P.Version);
                if (chosen < Math.Max(min, P.MinVersion)) return new(P.EncodeReject(P.RejectVersion), null, "version mismatch");
                Reset(); _clientNonce = cn; _serverNonce = _nonce(); _version = chosen; Source = source; State = Phase.Challenged; _lastActivity = now;
                return new(P.EncodeChallenge(chosen, _serverNonce), null, "challenge sent");
            }
            case P.TypeAuth:
            {
                if (State != Phase.Challenged || Source != source) return new(null, null, "unexpected auth");
                if (!P.TryDecodeAuth(data, out var mac)) return new(null, null, "bad auth");
                if (System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(mac, P.Hmac16(_pin, _clientNonce, _serverNonce)))
                { State = Phase.Active; _failures = 0; _lastActivity = now; _seq.Reset(); return new(P.EncodeAccept(_version), null, "authenticated"); }
                if (++_failures >= MaxFailures) { _lockUntil = now + LockoutMs; _failures = 0; }
                Reset(); return new(P.EncodeReject(P.RejectAuth), null, "wrong pin");
            }
            case P.TypeInput:
            {
                if (State != Phase.Active || Source != source) return new(null, null, "input before auth ignored");
                if (!P.TryDecodeInput(data, out var pkt, out var err)) return new(null, null, "invalid: " + err);
                if (!_seq.Accept(pkt.Seq)) return new(null, null, "stale/duplicate");
                _lastActivity = now; return new(null, pkt, null);
            }
            case P.TypePing:
            {
                if (State != Phase.Active || Source != source || !P.TryDecodePing(data, out uint t)) return new(null, null, "ping ignored");
                _lastActivity = now; return new(P.EncodePing(t, true), null, null);
            }
            default: return new(null, null, "unknown packet");
        }
    }
}

public readonly record struct GamepadReport(ushort Buttons, byte LeftTrigger, byte RightTrigger, short LX, short LY, short RX, short RY);

/// <summary>Maps a NEXUS state onto XInput (Xbox 360) layout. Racing mode: steering→LX, accel→RT, brake→LT, gears→RB/LB, handbrake→X.</summary>
public static class XInputMapper
{
    const ushort Up = 1, Down = 2, Left = 4, Right = 8, Start = 0x10, Back = 0x20, L3 = 0x40, R3 = 0x80, LB = 0x100, RB = 0x200, Guide = 0x400, A = 0x1000, B = 0x2000, X = 0x4000, Y = 0x8000;
    static short Ax(float v) => (short)P.AxisToI16(v);
    static byte Tr(float v) => (byte)P.UnitToU8(v);

    public static GamepadReport Map(in ControllerState s)
    {
        int b = 0;
        if ((s.Buttons & P.BtnA) != 0) b |= A; if ((s.Buttons & P.BtnB) != 0) b |= B; if ((s.Buttons & P.BtnX) != 0) b |= X; if ((s.Buttons & P.BtnY) != 0) b |= Y;
        if ((s.Buttons & P.BtnLB) != 0) b |= LB; if ((s.Buttons & P.BtnRB) != 0) b |= RB; if ((s.Buttons & P.BtnSelect) != 0) b |= Back; if ((s.Buttons & P.BtnStart) != 0) b |= Start;
        if ((s.Buttons & P.BtnL3) != 0) b |= L3; if ((s.Buttons & P.BtnR3) != 0) b |= R3; if ((s.Buttons & P.BtnGuide) != 0) b |= Guide; if ((s.Buttons & P.BtnNitro) != 0) b |= Y;
        if ((s.Dpad & P.DpadUp) != 0) b |= Up; if ((s.Dpad & P.DpadDown) != 0) b |= Down; if ((s.Dpad & P.DpadLeft) != 0) b |= Left; if ((s.Dpad & P.DpadRight) != 0) b |= Right;
        if ((s.Flags & P.FlagRacing) == 0)
            return new((ushort)b, Tr(s.Lt), Tr(s.Rt), Ax(s.Lx), Ax(s.Ly), Ax(s.Rx), Ax(s.Ry));
        if ((s.Gear & P.GearUp) != 0) b |= RB; if ((s.Gear & P.GearDown) != 0) b |= LB; if (s.Handbrake > 0.5f) b |= X;
        return new((ushort)b, Tr(s.Brake), Tr(s.Accel), Ax(s.Steering), Ax(s.Ly), Ax(s.Rx), Ax(s.Ry));
    }
}

/// <summary>Holds the live state and enforces fail-safe: after Release() everything is neutral until fresh input arrives.</summary>
public sealed class InputGate
{
    public ControllerState Current { get; private set; }
    public bool Neutralized { get; private set; } = true;
    public void Apply(in ControllerState s) { Current = s; Neutralized = false; }
    public void Release() { Current = ControllerState.Neutral; Neutralized = true; }
}
