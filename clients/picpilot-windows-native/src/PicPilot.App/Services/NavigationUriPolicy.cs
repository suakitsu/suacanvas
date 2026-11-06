namespace PicPilot.App.Services;

public static class NavigationUriPolicy
{
    public static bool TryResolve(string baseUrl, string? requestedUrl, out Uri uri)
    {
        uri = null!;
        var value = requestedUrl?.Trim();
        if (string.IsNullOrWhiteSpace(value)) return false;

        if (Uri.TryCreate(value, UriKind.Absolute, out var absolute))
        {
            if (absolute.Scheme is not ("https" or "http")) return false;
            uri = absolute;
            return true;
        }

        if (!value.StartsWith('/') || value.StartsWith("//", StringComparison.Ordinal)) return false;
        if (!Uri.TryCreate(baseUrl.TrimEnd('/') + "/", UriKind.Absolute, out var baseUri)) return false;
        if (baseUri.Scheme is not ("https" or "http")) return false;

        uri = new Uri(baseUri, value.TrimStart('/'));
        return true;
    }
}
