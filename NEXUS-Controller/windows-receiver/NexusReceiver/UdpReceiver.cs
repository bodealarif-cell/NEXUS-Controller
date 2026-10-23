using System.Net;
using System.Net.Sockets;

namespace NexusReceiver;

/// <summary>Optional Wi-Fi transport. Off by default; every sender must complete the PIN handshake before input is accepted. No encryption (LAN only).</summary>
public sealed class UdpReceiver : IDisposable
{
    readonly ReceiverHub _hub; UdpClient? _udp; CancellationTokenSource? _cts;
    public const int DefaultPort = 47810;
    public UdpReceiver(ReceiverHub hub) => _hub = hub;
    public bool Running => _udp != null;

    public void Start(int port = DefaultPort)
    {
        if (_udp != null) return;
        _udp = new UdpClient(new IPEndPoint(IPAddress.Any, port)); _cts = new CancellationTokenSource();
        var udp = _udp; var ct = _cts.Token;
        _ = Task.Run(async () =>
        {
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    var res = await udp.ReceiveAsync(ct);
                    var ep = res.RemoteEndPoint;
                    _hub.OnPacket(ep.ToString(), res.Buffer, bytes => { try { udp.Send(bytes, bytes.Length, ep); } catch { } });
                }
                catch (OperationCanceledException) { break; }
                catch (ObjectDisposedException) { break; }
                catch (SocketException) { /* ICMP unreachable etc.: keep listening */ }
            }
        }, ct);
    }

    public void Stop() { _cts?.Cancel(); _udp?.Dispose(); _udp = null; _cts = null; }
    public void Dispose() => Stop();
}
