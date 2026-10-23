using System.Security.Cryptography;

namespace Nexus.Protocol;

/// <summary>NEXUS wire protocol v1 (little-endian). Mirrors the Kotlin implementation; see docs/PROTOCOL.md.</summary>
public static class P
{
    public const int Version = 1, MinVersion = 1, Magic0 = 0x4E, Magic1 = 0x58;
    public const int TypeInput = 1, TypeHello = 2, TypeChallenge = 3, TypeAuth = 4, TypeAccept = 5, TypePing = 6, TypePong = 7, TypeReject = 8;
    public const int InputSize = 33, PingSize = 9, HelloSize = 15, ChallengeSize = 14, AuthSize = 21, AcceptSize = 6, RejectSize = 6, MaxPacket = 64, NonceSize = 8, MacSize = 16;
    public const int BtnA = 1, BtnB = 2, BtnX = 4, BtnY = 8, BtnLB = 16, BtnRB = 32, BtnSelect = 64, BtnStart = 128, BtnL3 = 256, BtnR3 = 512, BtnNitro = 1024, BtnGuide = 2048, ButtonMask = 0x0FFF;
    public const int DpadUp = 1, DpadDown = 2, DpadLeft = 4, DpadRight = 8;
    public const int FlagRacing = 1, FlagCalibrating = 2;
    public const int GearUp = 1, GearDown = 2, GearManual = 4;
    public const int RejectVersion = 1, RejectAuth = 2, RejectBusy = 3, RejectLocked = 4;

    public static byte Crc8(ReadOnlySpan<byte> d)
    {
        int crc = 0;
        foreach (var b in d) { crc ^= b; for (int i = 0; i < 8; i++) crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF; }
        return (byte)crc;
    }

    public static byte[] Hmac16(string pin, ReadOnlySpan<byte> clientNonce, ReadOnlySpan<byte> serverNonce)
    {
        var msg = new byte[NonceSize * 2]; clientNonce.CopyTo(msg); serverNonce.CopyTo(msg.AsSpan(NonceSize));
        return HMACSHA256.HashData(System.Text.Encoding.UTF8.GetBytes(pin), msg)[..MacSize];
    }

    static ushort U16(ReadOnlySpan<byte> b, int o) => (ushort)(b[o] | (b[o + 1] << 8));
    static short I16(ReadOnlySpan<byte> b, int o) => unchecked((short)U16(b, o));
    static uint U32(ReadOnlySpan<byte> b, int o) => (uint)(b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24));
    static void Put16(Span<byte> b, int o, int v) { b[o] = (byte)v; b[o + 1] = (byte)(v >> 8); }
    static void Put32(Span<byte> b, int o, uint v) { for (int i = 0; i < 4; i++) b[o + i] = (byte)(v >> (8 * i)); }
    static void Header(Span<byte> b, int type) { b[0] = Magic0; b[1] = Magic1; b[2] = Version; b[3] = (byte)type; }
    static byte[] Seal(byte[] b) { b[^1] = Crc8(b.AsSpan(0, b.Length - 1)); return b; }

    public static int AxisToI16(float v) => (int)MathF.Round(Math.Clamp(float.IsNaN(v) ? 0 : v, -1f, 1f) * 32767f);
    public static float I16ToAxis(int v) => Math.Clamp(v, -32767, 32767) / 32767f;
    public static int UnitToU8(float v) => (int)MathF.Round(Math.Clamp(float.IsNaN(v) ? 0 : v, 0f, 1f) * 255f);
    public static float U8ToUnit(int v) => v / 255f;

    static string? Check(ReadOnlySpan<byte> b, int type, int size)
    {
        if (b.Length != size) return $"bad length {b.Length} (expected {size})";
        if (b[0] != Magic0 || b[1] != Magic1) return "bad magic";
        if (b[3] != type) return $"unexpected type {b[3]}";
        if (Crc8(b[..(size - 1)]) != b[size - 1]) return "bad checksum";
        return null;
    }

    public static int PeekType(ReadOnlySpan<byte> b) => b.Length is >= 4 and <= MaxPacket && b[0] == Magic0 && b[1] == Magic1 ? b[3] : -1;

    public static byte[] EncodeInput(in ControllerState s, int seq, uint tickMs)
    {
        var b = new byte[InputSize]; Header(b, TypeInput);
        Put16(b, 4, seq & 0xFFFF); Put32(b, 6, tickMs); Put32(b, 10, (uint)(s.Buttons & ButtonMask));
        b[14] = (byte)(s.Dpad & 0x0F); b[15] = (byte)s.Flags;
        Put16(b, 16, AxisToI16(s.Lx)); Put16(b, 18, AxisToI16(s.Ly)); Put16(b, 20, AxisToI16(s.Rx)); Put16(b, 22, AxisToI16(s.Ry));
        b[24] = (byte)UnitToU8(s.Lt); b[25] = (byte)UnitToU8(s.Rt); Put16(b, 26, AxisToI16(s.Steering));
        b[28] = (byte)UnitToU8(s.Accel); b[29] = (byte)UnitToU8(s.Brake); b[30] = (byte)UnitToU8(s.Handbrake); b[31] = (byte)(s.Gear & 7);
        return Seal(b);
    }

    public static bool TryDecodeInput(ReadOnlySpan<byte> b, out InputPacket pkt, out string? error)
    {
        pkt = default; error = Check(b, TypeInput, InputSize);
        if (error != null) return false;
        int ver = b[2];
        if (ver < MinVersion || ver > Version) { error = $"unsupported version {ver}"; return false; }
        int dpad = b[14];
        if ((dpad & 0xF0) != 0) { error = "reserved dpad bits set"; return false; }
        if ((b[31] & 0xF8) != 0) { error = "reserved gear bits set"; return false; }
        if ((dpad & 3) == 3) dpad &= ~3;
        if ((dpad & 12) == 12) dpad &= ~12;
        var st = new ControllerState
        {
            Buttons = (int)(U32(b, 10) & ButtonMask), Dpad = dpad, Flags = b[15],
            Lx = I16ToAxis(I16(b, 16)), Ly = I16ToAxis(I16(b, 18)), Rx = I16ToAxis(I16(b, 20)), Ry = I16ToAxis(I16(b, 22)),
            Lt = U8ToUnit(b[24]), Rt = U8ToUnit(b[25]), Steering = I16ToAxis(I16(b, 26)),
            Accel = U8ToUnit(b[28]), Brake = U8ToUnit(b[29]), Handbrake = U8ToUnit(b[30]), Gear = b[31]
        };
        pkt = new InputPacket(ver, U16(b, 4), U32(b, 6), st); return true;
    }

    public static byte[] EncodePing(uint tick, bool pong) { var b = new byte[PingSize]; Header(b, pong ? TypePong : TypePing); Put32(b, 4, tick); return Seal(b); }
    public static bool TryDecodePing(ReadOnlySpan<byte> b, out uint tick) { tick = 0; if (Check(b, TypePing, PingSize) != null) return false; tick = U32(b, 4); return true; }

    public static bool TryDecodeHello(ReadOnlySpan<byte> b, out int minVer, out int maxVer, out byte[] nonce)
    {
        minVer = maxVer = 0; nonce = Array.Empty<byte>();
        if (Check(b, TypeHello, HelloSize) != null) return false;
        minVer = b[4]; maxVer = b[5]; nonce = b.Slice(6, NonceSize).ToArray(); return true;
    }
    public static byte[] EncodeChallenge(int chosen, ReadOnlySpan<byte> serverNonce) { var b = new byte[ChallengeSize]; Header(b, TypeChallenge); b[4] = (byte)chosen; serverNonce.CopyTo(b.AsSpan(5)); return Seal(b); }
    public static bool TryDecodeAuth(ReadOnlySpan<byte> b, out byte[] mac) { mac = Array.Empty<byte>(); if (Check(b, TypeAuth, AuthSize) != null) return false; mac = b.Slice(4, MacSize).ToArray(); return true; }
    public static byte[] EncodeAccept(int ver) { var b = new byte[AcceptSize]; Header(b, TypeAccept); b[4] = (byte)ver; return Seal(b); }
    public static byte[] EncodeReject(int reason) { var b = new byte[RejectSize]; Header(b, TypeReject); b[4] = (byte)reason; return Seal(b); }
}

public readonly record struct InputPacket(int Version, int Seq, uint TickMs, ControllerState State);

public readonly record struct ControllerState
{
    public int Buttons { get; init; } public int Dpad { get; init; } public int Flags { get; init; }
    public float Lx { get; init; } public float Ly { get; init; } public float Rx { get; init; } public float Ry { get; init; }
    public float Lt { get; init; } public float Rt { get; init; }
    public float Steering { get; init; } public float Accel { get; init; } public float Brake { get; init; } public float Handbrake { get; init; }
    public int Gear { get; init; }
    public static ControllerState Neutral => default;
}
