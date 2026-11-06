namespace PicPilot.App.Services;

internal static class ClientDiagnostics
{
    private static readonly object Sync = new();

    public static void RecordFailure(Exception exception)
    {
        try
        {
            var line = $"{DateTimeOffset.UtcNow:O}\ttype={exception.GetType().FullName}\tinner={exception.InnerException?.GetType().FullName ?? "none"}\thresult={exception.HResult}{Environment.NewLine}";
            lock (Sync) AppDataPaths.AppendBoundedLog("client-operations.log", line);
        }
        catch
        {
            // Diagnostics must never change user-visible behavior.
        }
    }
}
