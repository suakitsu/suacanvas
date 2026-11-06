using System.IO;

namespace PicPilot.App.Services;

internal static class AppDataPaths
{
    private const long DefaultMaxLogBytes = 512 * 1024;

    public static string ResolveDataRoot(string? explicitRoot = null)
    {
        var configured = string.IsNullOrWhiteSpace(explicitRoot)
            ? Environment.GetEnvironmentVariable("PICPILOT_DATA_DIR")
            : explicitRoot;

        if (!string.IsNullOrWhiteSpace(configured))
        {
            return Path.GetFullPath(Environment.ExpandEnvironmentVariables(configured.Trim()));
        }

        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "PicPilot");
    }

    public static string? ResolveLogRoot()
    {
        var configured = Environment.GetEnvironmentVariable("PICPILOT_LOG_DIR");
        if (!string.IsNullOrWhiteSpace(configured))
        {
            return Path.GetFullPath(Environment.ExpandEnvironmentVariables(configured.Trim()));
        }

        // File diagnostics are opt-in. When only the data root is explicitly moved,
        // keep the small bounded diagnostics beside it.
        var dataRoot = Environment.GetEnvironmentVariable("PICPILOT_DATA_DIR");
        return string.IsNullOrWhiteSpace(dataRoot)
            ? null
            : Path.Combine(ResolveDataRoot(dataRoot), "Logs");
    }

    public static void AppendBoundedLog(string fileName, string line, long maxBytes = DefaultMaxLogBytes)
    {
        var folder = ResolveLogRoot();
        if (folder is null) return;

        Directory.CreateDirectory(folder);
        var path = Path.Combine(folder, Path.GetFileName(fileName));
        if (File.Exists(path) && new FileInfo(path).Length >= maxBytes)
        {
            File.WriteAllText(path, string.Empty);
        }
        File.AppendAllText(path, line);
    }
}
