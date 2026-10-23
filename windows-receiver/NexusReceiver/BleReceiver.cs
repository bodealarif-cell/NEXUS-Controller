using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace NexusReceiver;

/// <summary>BLE GATT server (peripheral role). Requires a Bluetooth 4.0+ adapter that supports the peripheral role.</summary>
public sealed class BleReceiver : IDisposable
{
    public static readonly Guid ServiceUuid = Guid.Parse("7d0e0001-4e58-4d1c-9a3b-5a0e6a9c0001");
    public static readonly Guid InputUuid = Guid.Parse("7d0e0002-4e58-4d1c-9a3b-5a0e6a9c0001");   // Write / WriteWithoutResponse (sender -> receiver)
    public static readonly Guid NotifyUuid = Guid.Parse("7d0e0003-4e58-4d1c-9a3b-5a0e6a9c0001");  // Notify (receiver -> sender)

    readonly ReceiverHub _hub;
    GattServiceProvider? _provider; GattLocalCharacteristic? _notify;
    public BleReceiver(ReceiverHub hub) => _hub = hub;
    public bool Running => _provider != null;

    /// <summary>Returns null on success, otherwise a human-readable error (never fakes success).</summary>
    public async Task<string?> StartAsync()
    {
        if (_provider != null) return null;
        var adapter = await BluetoothAdapter.GetDefaultAsync();
        if (adapter == null) return "No Bluetooth adapter found.";
        if (!adapter.IsLowEnergySupported) return "This Bluetooth adapter does not support Bluetooth Low Energy.";
        if (!adapter.IsPeripheralRoleSupported) return "This Bluetooth adapter does not support the BLE peripheral role required to act as a receiver. Use the Wi-Fi transport or a different adapter.";

        var created = await GattServiceProvider.CreateAsync(ServiceUuid);
        if (created.Error != BluetoothError.Success) return "Could not create GATT service: " + created.Error;
        var provider = created.ServiceProvider;

        var inRes = await provider.Service.CreateCharacteristicAsync(InputUuid, new GattLocalCharacteristicParameters
        {
            CharacteristicProperties = GattCharacteristicProperties.Write | GattCharacteristicProperties.WriteWithoutResponse,
            WriteProtectionLevel = GattProtectionLevel.Plain,
            UserDescription = "NEXUS input"
        });
        if (inRes.Error != BluetoothError.Success) return "Could not create input characteristic: " + inRes.Error;
        var nRes = await provider.Service.CreateCharacteristicAsync(NotifyUuid, new GattLocalCharacteristicParameters
        {
            CharacteristicProperties = GattCharacteristicProperties.Notify,
            UserDescription = "NEXUS control"
        });
        if (nRes.Error != BluetoothError.Success) return "Could not create notify characteristic: " + nRes.Error;

        _notify = nRes.Characteristic;
        inRes.Characteristic.WriteRequested += OnWriteRequested;
        provider.StartAdvertising(new GattServiceProviderAdvertisingParameters { IsDiscoverable = true, IsConnectable = true });
        _provider = provider;
        return null;
    }

    async void OnWriteRequested(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        var deferral = args.GetDeferral();
        try
        {
            var req = await args.GetRequestAsync();
            if (req == null) return;
            var data = new byte[req.Value.Length];
            if (data.Length > 0 && data.Length <= 64) DataReader.FromBuffer(req.Value).ReadBytes(data);
            string src = args.Session.DeviceId.Id;
            if (data.Length is > 0 and <= 64) _hub.OnPacket(src, data, bytes => Notify(src, bytes));
            if (req.Option == GattWriteOption.WriteWithResponse) req.Respond();
        }
        catch { /* never let a malformed write crash the receiver */ }
        finally { deferral.Complete(); }
    }

    void Notify(string deviceId, byte[] bytes)
    {
        var n = _notify; if (n == null) return;
        try
        {
            var target = n.SubscribedClients.FirstOrDefault(c => c.Session.DeviceId.Id == deviceId);
            if (target == null) return; // sender has not enabled notifications; never broadcast to other devices
            _ = n.NotifyValueAsync(bytes.AsBuffer(), target);
        }
        catch { }
    }

    public void Stop()
    {
        try { _provider?.StopAdvertising(); } catch { }
        _provider = null; _notify = null;
    }
    public void Dispose() => Stop();
}
