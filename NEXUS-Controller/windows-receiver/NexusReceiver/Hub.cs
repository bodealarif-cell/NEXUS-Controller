using System.Diagnostics;
using Nexus.Protocol;

namespace NexusReceiver;

public interface IVirtualPad : IDisposable
{
    bool TryStart(out string error);
    void Submit(in GamepadReport report);
}

/// <summary>Transport-independent receiver core: auth/session, rate limiting, fail-safe, virtual pad output. No Windows dependencies.</summary>
public sealed class ReceiverHub
{
    readonly object _lock = new();
    readonly Func<long> _now;
    ReceiverSession _session;
    readonly InputGate _gate = new();
    readonly Watchdog _failsafe;
    IVirtualPad? _pad;
    long _windowStart; int _windowCount;
    const int MaxPacketsPerSecond = 600;
    const long SessionInactivityMs = 5000;

    public string Pin { get; private set; }
    public long Packets { get; private set; }
    public long Rejected { get; private set; }
    public long RateLimited { get; private set; }
    public event Action<string>? Log;

    public ReceiverHub(long failsafeMs = 500, Func<long>? clock = null, string? pin = null)
    {
        var sw = Stopwatch.StartNew();
        _now = clock ?? (() => sw.ElapsedMilliseconds);
        _failsafe = new Watchdog(failsafeMs);
        Pin = pin ?? NewRandomPin();
        _session = new ReceiverSession(Pin, SessionInactivityMs);
    }

    public static string NewRandomPin() => System.Security.Cryptography.RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");

    public long FailsafeMs { get { lock (_lock) return _failsafe.TimeoutMs; } set { lock (_lock) _failsafe.TimeoutMs = Math.Clamp(value, 100, 10_000); } }
    public ControllerState Current { get { lock (_lock) return _gate.Current; } }
    public bool IsConnected { get { lock (_lock) return _session.State == ReceiverSession.Phase.Active; } }
    public string? ConnectedSource { get { lock (_lock) return _session.State == ReceiverSession.Phase.Active ? _session.Source : null; } }
    public (long gaps, long dropped) SequenceStats { get { lock (_lock) return (_session.Sequence.Gaps, _session.Sequence.Dropped); } }
    public bool PadActive { get { lock (_lock) return _pad != null; } }

    public void SetPad(IVirtualPad? pad)
    {
        lock (_lock) { var old = _pad; _pad = pad; old?.Submit(default); old?.Dispose(); pad?.Submit(default); }
    }

    public void NewPin() { lock (_lock) { Pin = NewRandomPin(); ReleaseLocked(); _session = new ReceiverSession(Pin, SessionInactivityMs); } Log?.Invoke("New PIN generated; active session closed."); }
    public void Disconnect() { lock (_lock) { _session.Reset(); ReleaseLocked(); } Log?.Invoke("Device disconnected by user."); }

    /// <summary>Entry point for every transport. [reply] sends bytes back to the same source.</summary>
    public void OnPacket(string source, ReadOnlySpan<byte> data, Action<byte[]> reply)
    {
        if (data.Length == 0 || data.Length > P.MaxPacket) { Rejected++; return; }
        byte[]? toSend = null; string? note = null;
        lock (_lock)
        {
            long now = _now();
            if (now - _windowStart >= 1000) { _windowStart = now; _windowCount = 0; }
            if (++_windowCount > MaxPacketsPerSecond) { RateLimited++; return; }
            var wasActive = _session.State == ReceiverSession.Phase.Active;
            var r = _session.Process(source, data, now);
            toSend = r.Reply; note = r.Note;
            if (r.Input is { } pkt)
            {
                Packets++; _failsafe.Feed(now); _gate.Apply(pkt.State); _pad?.Submit(XInputMapper.Map(pkt.State));
            }
            else if (note != null && note.StartsWith("invalid")) Rejected++;
            if (!wasActive && _session.State == ReceiverSession.Phase.Active) { _failsafe.Feed(now); }
        }
        if (note is "authenticated" or "wrong pin" or "locked out" or "busy" or "version mismatch") Log?.Invoke($"[{source}] {note}");
        if (toSend != null) reply(toSend);
    }

    /// <summary>Call every ~10 ms from a timer thread.</summary>
    public void Tick()
    {
        string? msg = null;
        lock (_lock)
        {
            long now = _now();
            if (_session.Tick(now)) { ReleaseLocked(); msg = "Session timed out; all controls released."; }
            else if (_session.State == ReceiverSession.Phase.Active && !_gate.Neutralized && _failsafe.IsExpired(now))
            { ReleaseLocked(); msg = "Fail-safe: no input within timeout; all controls released."; }
        }
        if (msg != null) Log?.Invoke(msg);
    }

    void ReleaseLocked() { _gate.Release(); _pad?.Submit(default); _failsafe.Reset(); }

    /// <summary>Called on application shutdown.</summary>
    public void Shutdown() { lock (_lock) { ReleaseLocked(); var p = _pad; _pad = null; p?.Dispose(); } }
}
