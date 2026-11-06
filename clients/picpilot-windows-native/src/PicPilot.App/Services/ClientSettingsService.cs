using System.IO;
using System.Text.Json;

namespace PicPilot.App.Services;

public sealed record ClientSettings(bool AutoDownloadImages, string DownloadDirectory, bool? MinimizeToTray = null)
{
    public static ClientSettings Default => new(false, ClientSettingsService.DefaultDownloadDirectory(), true);
    public bool EffectiveMinimizeToTray => MinimizeToTray ?? true;
}

public sealed class ClientSettingsService
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    private readonly SemaphoreSlim _writeGate = new(1, 1);
    private readonly string _settingsPath;

    public ClientSettingsService(string? dataRoot = null)
    {
        var root = AppDataPaths.ResolveDataRoot(dataRoot);
        _settingsPath = Path.Combine(root, "client-settings.json");
    }

    public ClientSettings Current { get; private set; } = ClientSettings.Default;

    public async Task LoadAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            if (!File.Exists(_settingsPath)) return;
            await using var stream = File.OpenRead(_settingsPath);
            var settings = await JsonSerializer.DeserializeAsync<ClientSettings>(stream, Json, cancellationToken);
            if (settings is not null) Current = Normalize(settings);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or JsonException)
        {
            Current = ClientSettings.Default;
        }
    }

    public async Task SaveAsync(bool autoDownloadImages, string? downloadDirectory, bool? minimizeToTray = null, CancellationToken cancellationToken = default)
    {
        Current = Normalize(new ClientSettings(autoDownloadImages, downloadDirectory ?? "", minimizeToTray ?? Current.EffectiveMinimizeToTray));
        await _writeGate.WaitAsync(cancellationToken);
        try
        {
            var folder = Path.GetDirectoryName(_settingsPath)!;
            Directory.CreateDirectory(folder);
            var temporary = _settingsPath + ".tmp";
            await File.WriteAllTextAsync(temporary, JsonSerializer.Serialize(Current, Json), cancellationToken);
            File.Move(temporary, _settingsPath, overwrite: true);
        }
        finally
        {
            _writeGate.Release();
        }
    }

    public static string DefaultDownloadDirectory()
    {
        var pictures = Environment.GetFolderPath(Environment.SpecialFolder.MyPictures);
        if (string.IsNullOrWhiteSpace(pictures)) pictures = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return Path.Combine(pictures, "PicPilot");
    }

    private static ClientSettings Normalize(ClientSettings settings)
    {
        var directory = string.IsNullOrWhiteSpace(settings.DownloadDirectory)
            ? DefaultDownloadDirectory()
            : Path.GetFullPath(Environment.ExpandEnvironmentVariables(settings.DownloadDirectory.Trim()));
        return settings with { DownloadDirectory = directory };
    }
}
