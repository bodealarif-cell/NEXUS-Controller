using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using Nexus.Protocol;

namespace NexusReceiver;

public sealed class MainForm : Form
{
    readonly AppSettings _settings = AppSettings.Load();
    readonly ReceiverHub _hub;
    readonly BleReceiver _ble;
    readonly UdpReceiver _udp;
    readonly Label _status = new() { AutoSize = true, Font = new Font("Segoe UI", 14, FontStyle.Bold) };
    readonly Label _pin = new() { AutoSize = true, Font = new Font("Consolas", 28, FontStyle.Bold), ForeColor = Color.FromArgb(0, 160, 220) };
    readonly Label _ips = new() { AutoSize = true };
    readonly CheckBox _bleBox = new() { Text = "Enable Bluetooth LE receiver", AutoSize = true };
    readonly CheckBox _udpBox = new() { Text = "Enable Wi-Fi (UDP) receiver — unencrypted, LAN only", AutoSize = true };
    readonly CheckBox _padBox = new() { Text = "Enable virtual Xbox 360 controller (requires ViGEmBus driver you install yourself)", AutoSize = true };
    readonly NumericUpDown _failsafe = new() { Minimum = 100, Maximum = 10000, Increment = 100, Width = 80 };
    readonly TextBox _diag = new() { Multiline = true, ReadOnly = true, Font = new Font("Consolas", 10), Dock = DockStyle.Fill, ScrollBars = ScrollBars.Vertical };
    readonly TextBox _log = new() { Multiline = true, ReadOnly = true, Font = new Font("Consolas", 9), Dock = DockStyle.Fill, ScrollBars = ScrollBars.Vertical };
    readonly System.Windows.Forms.Timer _ui = new() { Interval = 50 };
    readonly System.Windows.Forms.Timer _tick = new() { Interval = 10 };
    long _lastPackets; DateTime _lastRate = DateTime.UtcNow; double _rate;

    public MainForm()
    {
        Text = "NEXUS Receiver"; Width = 760; Height = 760; StartPosition = FormStartPosition.CenterScreen;
        _hub = new ReceiverHub(_settings.FailsafeMs);
        _hub.Log += m => BeginInvoke(() => AppendLog(m));
        _ble = new BleReceiver(_hub); _udp = new UdpReceiver(_hub);
        _failsafe.Value = _settings.FailsafeMs;

        var regen = new Button { Text = "New PIN", AutoSize = true }; regen.Click += (_, _) => _hub.NewPin();
        var disc = new Button { Text = "Disconnect device", AutoSize = true }; disc.Click += (_, _) => _hub.Disconnect();
        var top = new FlowLayoutPanel { Dock = DockStyle.Top, FlowDirection = FlowDirection.TopDown, AutoSize = true, Padding = new Padding(12) };
        var row = new FlowLayoutPanel { AutoSize = true }; row.Controls.AddRange(new Control[] { regen, disc });
        var fsRow = new FlowLayoutPanel { AutoSize = true }; fsRow.Controls.AddRange(new Control[] { new Label { Text = "Fail-safe timeout (ms):", AutoSize = true, Padding = new Padding(0, 6, 0, 0) }, _failsafe });
        top.Controls.AddRange(new Control[] { _status, new Label { Text = "Pairing PIN (enter it on the phone):", AutoSize = true }, _pin, _ips, row, _bleBox, _udpBox, _padBox, fsRow,
            new Label { Text = "No game input is created unless you enable the virtual controller. Diagnostics below always work.", AutoSize = true, ForeColor = Color.Gray } });

        var split = new SplitContainer { Dock = DockStyle.Fill, Orientation = Orientation.Horizontal };
        split.Panel1.Controls.Add(_diag); split.Panel2.Controls.Add(_log);
        Controls.Add(split); Controls.Add(top);

        _bleBox.CheckedChanged += async (_, _) =>
        {
            if (_bleBox.Checked) { var err = await _ble.StartAsync(); if (err != null) { AppendLog("BLE: " + err); MessageBox.Show(err, "Bluetooth LE", MessageBoxButtons.OK, MessageBoxIcon.Warning); _bleBox.Checked = false; } else AppendLog("BLE receiver advertising."); }
            else { _ble.Stop(); AppendLog("BLE receiver stopped."); }
        };
        _udpBox.CheckedChanged += (_, _) =>
        {
            try { if (_udpBox.Checked) { _udp.Start(_settings.UdpPort); AppendLog($"UDP receiver listening on port {_settings.UdpPort}."); } else { _udp.Stop(); AppendLog("UDP receiver stopped."); } }
            catch (Exception e) { AppendLog("UDP: " + e.Message); _udpBox.Checked = false; }
        };
        _padBox.CheckedChanged += (_, _) =>
        {
            if (_padBox.Checked)
            {
                var pad = new ViGemPad();
                if (pad.TryStart(out var err)) { _hub.SetPad(pad); AppendLog("Virtual Xbox 360 controller created."); }
                else { AppendLog(err); MessageBox.Show(err + "\n\nSee docs/WINDOWS.md. Diagnostic mode remains active.", "Virtual controller", MessageBoxButtons.OK, MessageBoxIcon.Warning); _padBox.Checked = false; }
            }
            else { _hub.SetPad(null); AppendLog("Virtual controller removed."); }
        };
        _failsafe.ValueChanged += (_, _) => { _hub.FailsafeMs = (long)_failsafe.Value; _settings.FailsafeMs = (int)_failsafe.Value; _settings.Save(); };

        _tick.Tick += (_, _) => _hub.Tick(); _tick.Start();
        _ui.Tick += (_, _) => Refresh_(); _ui.Start();
        FormClosing += (_, _) => { _tick.Stop(); _ui.Stop(); _ble.Dispose(); _udp.Dispose(); _hub.Shutdown(); };
    }

    void AppendLog(string m) { _log.AppendText($"{DateTime.Now:HH:mm:ss}  {m}{Environment.NewLine}"); }

    static string LocalIps() => string.Join(", ", NetworkInterface.GetAllNetworkInterfaces()
        .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
        .SelectMany(n => n.GetIPProperties().UnicastAddresses).Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork).Select(a => a.Address.ToString()));

    void Refresh_()
    {
        var src = _hub.ConnectedSource;
        _status.Text = src != null ? $"Connected: {src}" : "Waiting for a device…";
        _status.ForeColor = src != null ? Color.SeaGreen : Color.DimGray;
        _pin.Text = _hub.Pin;
        _ips.Text = "This PC (for Wi-Fi mode): " + LocalIps() + $"  port {_settings.UdpPort}";
        var now = DateTime.UtcNow; var dt = (now - _lastRate).TotalSeconds;
        if (dt >= 0.5) { _rate = (_hub.Packets - _lastPackets) / dt; _lastPackets = _hub.Packets; _lastRate = now; }
        var s = _hub.Current; var (gaps, dropped) = _hub.SequenceStats;
        var sb = new StringBuilder();
        sb.AppendLine($"Mode: {((s.Flags & P.FlagRacing) != 0 ? "Racing" : "Gamepad")}    Virtual pad: {(_hub.PadActive ? "ACTIVE" : "off (diagnostic only)")}");
        sb.AppendLine($"Rate: {_rate:F0} pkt/s   total: {_hub.Packets}   missed(seq gaps): {gaps}   stale dropped: {dropped}   invalid: {_hub.Rejected}   rate-limited: {_hub.RateLimited}");
        sb.AppendLine();
        sb.AppendLine($"LS  x={s.Lx,6:F2} y={s.Ly,6:F2}     RS  x={s.Rx,6:F2} y={s.Ry,6:F2}");
        sb.AppendLine($"LT  {s.Lt,4:F2}   RT {s.Rt,4:F2}      Steer {s.Steering,6:F2}  Accel {s.Accel,4:F2}  Brake {s.Brake,4:F2}  Handbrake {s.Handbrake,4:F2}");
        sb.AppendLine($"DPad: {((s.Dpad & 1) != 0 ? "U" : "-")}{((s.Dpad & 2) != 0 ? "D" : "-")}{((s.Dpad & 4) != 0 ? "L" : "-")}{((s.Dpad & 8) != 0 ? "R" : "-")}   Gear: {((s.Gear & 1) != 0 ? "UP " : "")}{((s.Gear & 2) != 0 ? "DOWN" : "")}");
        string[] names = { "A", "B", "X", "Y", "LB", "RB", "Select", "Start", "L3", "R3", "Nitro", "Guide" };
        sb.Append("Buttons: "); for (int i = 0; i < names.Length; i++) if ((s.Buttons & (1 << i)) != 0) sb.Append(names[i]).Append(' ');
        _diag.Text = sb.ToString();
    }
}
