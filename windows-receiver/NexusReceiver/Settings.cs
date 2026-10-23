using System.Text.Json;
namespace NexusReceiver;

public sealed class AppSettings
{
    public int FailsafeMs { get; set; } = 500;
    public int UdpPort { get; set; } = UdpReceiver.DefaultPort;

    static string PathFile => System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "NexusReceiver", "settings.json");
    public static AppSettings Load() { try { return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(PathFile)) ?? new(); } catch { return new(); } }
    public void Save() { try { Directory.CreateDirectory(System.IO.Path.GetDirectoryName(PathFile)!); File.WriteAllText(PathFile, JsonSerializer.Serialize(this)); } catch { } }
}
