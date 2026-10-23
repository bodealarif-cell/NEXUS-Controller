using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;
using Nexus.Protocol;

namespace NexusReceiver;

/// <summary>Virtual Xbox 360 controller via ViGEmBus. Optional: if the driver is not installed, TryStart fails and diagnostic mode keeps working.</summary>
public sealed class ViGemPad : IVirtualPad
{
    ViGEmClient? _client; IXbox360Controller? _pad;

    public bool TryStart(out string error)
    {
        try
        {
            _client = new ViGEmClient();
            _pad = _client.CreateXbox360Controller();
            _pad.AutoSubmitReport = false;
            _pad.Connect();
            error = ""; return true;
        }
        catch (Exception e)
        {
            error = "ViGEmBus driver not available: " + e.Message; Dispose(); return false;
        }
    }

    public void Submit(in GamepadReport r)
    {
        var p = _pad; if (p == null) return;
        p.SetButtonsFull(r.Buttons);
        p.SetSliderValue(Xbox360Slider.LeftTrigger, r.LeftTrigger);
        p.SetSliderValue(Xbox360Slider.RightTrigger, r.RightTrigger);
        p.SetAxisValue(Xbox360Axis.LeftThumbX, r.LX); p.SetAxisValue(Xbox360Axis.LeftThumbY, r.LY);
        p.SetAxisValue(Xbox360Axis.RightThumbX, r.RX); p.SetAxisValue(Xbox360Axis.RightThumbY, r.RY);
        p.SubmitReport();
    }

    public void Dispose()
    {
        try { _pad?.Disconnect(); } catch { }
        _pad = null; _client?.Dispose(); _client = null;
    }
}
