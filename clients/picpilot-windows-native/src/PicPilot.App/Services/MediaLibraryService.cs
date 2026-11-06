using PicPilot.Core;
using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace PicPilot.App.Services;

public sealed record ImageAssetCandidate(string Url, string SuggestedName);

public sealed class MediaLibraryService : IDisposable
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    private readonly ClientSettingsService _settings;
    private readonly ITokenStore _tokenStore;
    private readonly Uri _baseUri;
    private readonly bool _allowHttp;
    private readonly HttpClient _http;
    private readonly string _indexPath;
    private readonly string _seenPath;
    private readonly SemaphoreSlim _stateGate = new(1, 1);
    private readonly SemaphoreSlim _downloadGate = new(2, 2);
    private readonly ConcurrentDictionary<string, Lazy<Task<string>>> _downloads = new(StringComparer.OrdinalIgnoreCase);
    private ConcurrentDictionary<string, string> _index = new(StringComparer.OrdinalIgnoreCase);
    private HashSet<string> _seen = new(StringComparer.OrdinalIgnoreCase);

    public MediaLibraryService(
        ClientSettingsService settings,
        ITokenStore tokenStore,
        Uri baseUri,
        bool allowHttp,
        string? dataRoot = null,
        HttpMessageHandler? handler = null)
    {
        _settings = settings;
        _tokenStore = tokenStore;
        _baseUri = new Uri(baseUri.ToString().TrimEnd('/') + "/");
        _allowHttp = allowHttp;
        var root = AppDataPaths.ResolveDataRoot(dataRoot);
        _indexPath = Path.Combine(root, "media-index.json");
        _seenPath = Path.Combine(root, "media-seen.json");

        if (handler is null)
        {
            handler = new HttpClientHandler
            {
#pragma warning disable SYSLIB0014
                Proxy = WebRequest.GetSystemWebProxy(),
#pragma warning restore SYSLIB0014
                UseProxy = true,
                AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate | DecompressionMethods.Brotli
            };
        }
        _http = new HttpClient(handler, disposeHandler: true) { Timeout = Timeout.InfiniteTimeSpan };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("PicPilotWindows/2.0.2");
    }

    public event EventHandler? AssetCached;

    public async Task InitializeAsync(CancellationToken cancellationToken = default)
    {
        _index = new ConcurrentDictionary<string, string>(
            await LoadDictionaryAsync(_indexPath, cancellationToken),
            StringComparer.OrdinalIgnoreCase);
        _seen = await LoadSetAsync(_seenPath, cancellationToken);
        RemoveMissingEntries();
    }

    public Uri ToAbsoluteUri(string value)
    {
        if (Uri.TryCreate(value, UriKind.Absolute, out var absolute)) return absolute;
        return new Uri(_baseUri, (value ?? "").TrimStart('/'));
    }

    public string? TryGetLocalPath(Uri remoteUri)
    {
        var key = Canonical(remoteUri);
        if (!_index.TryGetValue(key, out var path)) return null;
        if (File.Exists(path)) return path;
        _index.TryRemove(key, out _);
        return null;
    }

    public Uri ResolveDisplayUri(string value)
    {
        var remote = ToAbsoluteUri(value);
        var local = TryGetLocalPath(remote);
        return local is null ? remote : new Uri(local, UriKind.Absolute);
    }

    public async Task<string> GetForPreviewAsync(Uri remoteUri, string suggestedName, CancellationToken cancellationToken = default)
    {
        var local = TryGetLocalPath(remoteUri);
        if (local is not null) return local;
        return _settings.Current.AutoDownloadImages
            ? await SaveToLibraryAsync(remoteUri, suggestedName, cancellationToken)
            : await DownloadTemporaryAsync(remoteUri, cancellationToken);
    }

    public async Task<string> SaveToLibraryAsync(Uri remoteUri, string suggestedName, CancellationToken cancellationToken = default)
    {
        ValidateRemoteUri(remoteUri);
        var key = Canonical(remoteUri);
        var local = TryGetLocalPath(remoteUri);
        if (local is not null) return local;
        var operation = _downloads.GetOrAdd(key, _ => new Lazy<Task<string>>(
            () => SaveToLibraryCoreAsync(remoteUri, suggestedName, CancellationToken.None),
            LazyThreadSafetyMode.ExecutionAndPublication));
        try
        {
            return await operation.Value.WaitAsync(cancellationToken);
        }
        finally
        {
            if (_downloads.TryGetValue(key, out var current) && ReferenceEquals(current, operation))
                _downloads.TryRemove(key, out _);
        }
    }

    public async Task RegisterLocalFileAsync(Uri remoteUri, string localPath, CancellationToken cancellationToken = default)
    {
        if (!File.Exists(localPath)) return;
        await _stateGate.WaitAsync(cancellationToken);
        try
        {
            _index[Canonical(remoteUri)] = Path.GetFullPath(localPath);
            await SaveDictionaryAsync(_indexPath, _index, cancellationToken);
        }
        finally
        {
            _stateGate.Release();
        }
        AssetCached?.Invoke(this, EventArgs.Empty);
    }

    public async Task ObserveAsync(IEnumerable<ImageAssetCandidate> candidates, CancellationToken cancellationToken = default)
    {
        var fresh = new List<ImageAssetCandidate>();
        await _stateGate.WaitAsync(cancellationToken);
        try
        {
            foreach (var candidate in Normalize(candidates))
            {
                var key = Canonical(ToAbsoluteUri(candidate.Url));
                if (_seen.Add(key)) fresh.Add(candidate);
            }
            if (fresh.Count > 0) await SaveSetAsync(_seenPath, _seen, cancellationToken);
        }
        finally
        {
            _stateGate.Release();
        }

        if (!_settings.Current.AutoDownloadImages) return;
        await CacheCandidatesAsync(fresh, cancellationToken);
    }

    public Task CacheAllAsync(IEnumerable<ImageAssetCandidate> candidates, CancellationToken cancellationToken = default) =>
        CacheCandidatesAsync(Normalize(candidates), cancellationToken);

    private async Task CacheCandidatesAsync(IEnumerable<ImageAssetCandidate> candidates, CancellationToken cancellationToken)
    {
        var tasks = candidates.Select(async candidate =>
        {
            try { await SaveToLibraryAsync(ToAbsoluteUri(candidate.Url), candidate.SuggestedName, cancellationToken); }
            catch (Exception exception) when (exception is IOException or HttpRequestException or UnauthorizedAccessException or ApiException or InvalidDataException)
            {
                // Auto-download is best effort. Preview still falls back to the website asset.
            }
        });
        await Task.WhenAll(tasks);
    }

    private async Task<string> SaveToLibraryCoreAsync(Uri remoteUri, string suggestedName, CancellationToken cancellationToken)
    {
        var key = Canonical(remoteUri);
        await _downloadGate.WaitAsync(cancellationToken);
        try
        {
            var downloaded = await DownloadAsync(remoteUri, _settings.Current.DownloadDirectory, suggestedName, persistent: true, cancellationToken);
            await _stateGate.WaitAsync(cancellationToken);
            try
            {
                _index[key] = downloaded;
                await SaveDictionaryAsync(_indexPath, _index, cancellationToken);
            }
            finally
            {
                _stateGate.Release();
            }
            AssetCached?.Invoke(this, EventArgs.Empty);
            return downloaded;
        }
        finally
        {
            _downloadGate.Release();
        }
    }

    private async Task<string> DownloadTemporaryAsync(Uri remoteUri, CancellationToken cancellationToken)
    {
        ValidateRemoteUri(remoteUri);
        var folder = Path.Combine(Path.GetTempPath(), "PicPilot", "MediaPreview");
        return await DownloadAsync(remoteUri, folder, Guid.NewGuid().ToString("N"), persistent: false, cancellationToken);
    }

    private async Task<string> DownloadAsync(Uri remoteUri, string folder, string suggestedName, bool persistent, CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(folder);
        using var request = new HttpRequestMessage(HttpMethod.Get, remoteUri);
        if (IsFirstParty(remoteUri))
        {
            var token = await _tokenStore.ReadAsync(cancellationToken);
            if (!string.IsNullOrWhiteSpace(token)) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        }
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(90));
        using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        response.EnsureSuccessStatusCode();

        const long maxBytes = 200L * 1024 * 1024;
        if (response.Content.Headers.ContentLength > maxBytes) throw new InvalidDataException("Asset too large");
        var extension = ExtensionFor(response.Content.Headers.ContentType?.MediaType, remoteUri.AbsolutePath);
        var safeName = ImageFilePolicy.SanitizeDownloadName(suggestedName);
        safeName = Path.GetFileNameWithoutExtension(safeName);
        if (persistent) safeName += "-" + ShortHash(Canonical(remoteUri));
        var finalPath = Path.Combine(folder, safeName + extension);
        if (persistent && File.Exists(finalPath)) return finalPath;
        var partialPath = finalPath + ".partial";

        try
        {
            await using var input = await response.Content.ReadAsStreamAsync(timeout.Token);
            {
                await using var output = new FileStream(partialPath, FileMode.Create, FileAccess.Write, FileShare.None, 81920, FileOptions.Asynchronous | FileOptions.SequentialScan);
                var buffer = new byte[81920];
                long total = 0;
                while (true)
                {
                    var read = await input.ReadAsync(buffer, timeout.Token);
                    if (read == 0) break;
                    total += read;
                    if (total > maxBytes) throw new InvalidDataException("Asset too large");
                    await output.WriteAsync(buffer.AsMemory(0, read), timeout.Token);
                }
                await output.FlushAsync(timeout.Token);
            }
            File.Move(partialPath, finalPath, overwrite: persistent);
            return finalPath;
        }
        catch
        {
            try { File.Delete(partialPath); } catch { }
            if (!persistent) try { File.Delete(finalPath); } catch { }
            throw;
        }
    }

    private bool IsFirstParty(Uri uri) =>
        uri.Host.Equals(_baseUri.Host, StringComparison.OrdinalIgnoreCase) && uri.Port == _baseUri.Port;

    private void ValidateRemoteUri(Uri uri)
    {
        if (uri.Scheme == Uri.UriSchemeHttps) return;
        if (_allowHttp && uri.Scheme == Uri.UriSchemeHttp) return;
        throw new InvalidOperationException("Unsupported asset scheme");
    }

    private static IEnumerable<ImageAssetCandidate> Normalize(IEnumerable<ImageAssetCandidate> candidates) =>
        candidates
            .Where(x => !string.IsNullOrWhiteSpace(x.Url))
            .GroupBy(x => x.Url.Trim(), StringComparer.OrdinalIgnoreCase)
            .Select(x => x.First());

    private void RemoveMissingEntries()
    {
        foreach (var item in _index.Where(x => !File.Exists(x.Value)).ToArray()) _index.TryRemove(item.Key, out _);
    }

    private static string Canonical(Uri uri) => uri.AbsoluteUri;
    private static string ShortHash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)))[..10].ToLowerInvariant();

    private static string ExtensionFor(string? mime, string path) => mime?.ToLowerInvariant() switch
    {
        "image/jpeg" => ".jpg",
        "image/png" => ".png",
        "image/webp" => ".webp",
        "image/gif" => ".gif",
        "video/mp4" => ".mp4",
        "video/quicktime" => ".mov",
        _ => string.IsNullOrWhiteSpace(Path.GetExtension(path)) ? ".bin" : Path.GetExtension(path)
    };

    private static async Task<Dictionary<string, string>> LoadDictionaryAsync(string path, CancellationToken cancellationToken)
    {
        try
        {
            if (!File.Exists(path)) return new(StringComparer.OrdinalIgnoreCase);
            await using var stream = File.OpenRead(path);
            var data = await JsonSerializer.DeserializeAsync<Dictionary<string, string>>(stream, Json, cancellationToken);
            return data is null ? new(StringComparer.OrdinalIgnoreCase) : new(data, StringComparer.OrdinalIgnoreCase);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or JsonException)
        {
            return new(StringComparer.OrdinalIgnoreCase);
        }
    }

    private static async Task<HashSet<string>> LoadSetAsync(string path, CancellationToken cancellationToken)
    {
        try
        {
            if (!File.Exists(path)) return new(StringComparer.OrdinalIgnoreCase);
            await using var stream = File.OpenRead(path);
            var data = await JsonSerializer.DeserializeAsync<HashSet<string>>(stream, Json, cancellationToken);
            return data is null ? new(StringComparer.OrdinalIgnoreCase) : new(data, StringComparer.OrdinalIgnoreCase);
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or JsonException)
        {
            return new(StringComparer.OrdinalIgnoreCase);
        }
    }

    private static async Task SaveDictionaryAsync(string path, IReadOnlyDictionary<string, string> data, CancellationToken cancellationToken) =>
        await SaveJsonAsync(path, data.ToDictionary(x => x.Key, x => x.Value, StringComparer.OrdinalIgnoreCase), cancellationToken);

    private static async Task SaveSetAsync(string path, HashSet<string> data, CancellationToken cancellationToken) =>
        await SaveJsonAsync(path, data, cancellationToken);

    private static async Task SaveJsonAsync<T>(string path, T data, CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + ".tmp";
        await File.WriteAllTextAsync(temporary, JsonSerializer.Serialize(data, Json), cancellationToken);
        File.Move(temporary, path, overwrite: true);
    }

    public void Dispose()
    {
        _http.Dispose();
        _stateGate.Dispose();
        _downloadGate.Dispose();
    }
}
