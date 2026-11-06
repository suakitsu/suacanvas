using PicPilot.App.Services;
using PicPilot.Core;
using System.Net;
using System.Net.Http.Headers;

namespace PicPilot.Tests;

public sealed class MediaLibraryServiceTests : IDisposable
{
    private static readonly byte[] Png = Convert.FromBase64String(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nSsAAAAASUVORK5CYII=");
    private readonly string _root = Path.Combine(Path.GetTempPath(), "PicPilotTests", Guid.NewGuid().ToString("N"));

    [Fact]
    public async Task Settings_are_persisted_in_explicit_data_root()
    {
        var download = Path.Combine(_root, "Pictures");
        var first = new ClientSettingsService(_root);
        await first.SaveAsync(true, download);

        var second = new ClientSettingsService(_root);
        await second.LoadAsync();

        Assert.True(second.Current.AutoDownloadImages);
        Assert.Equal(Path.GetFullPath(download), second.Current.DownloadDirectory);
        Assert.True(second.Current.EffectiveMinimizeToTray);
        Assert.True(File.Exists(Path.Combine(_root, "client-settings.json")));
    }

    [Fact]
    public async Task Legacy_settings_without_tray_field_default_to_enabled()
    {
        Directory.CreateDirectory(_root);
        await File.WriteAllTextAsync(Path.Combine(_root, "client-settings.json"),
            """{"autoDownloadImages":false,"downloadDirectory":"E:\\PicPilotImages"}""");
        var settings = new ClientSettingsService(_root);

        await settings.LoadAsync();

        Assert.True(settings.Current.EffectiveMinimizeToTray);
    }

    [Fact]
    public async Task Concurrent_requests_download_one_copy_and_preview_uses_local_file()
    {
        var settings = await SettingsAsync(autoDownload: true);
        var calls = 0;
        var handler = new StubHandler(async (request, cancellationToken) =>
        {
            Interlocked.Increment(ref calls);
            await Task.Delay(35, cancellationToken);
            Assert.Equal("Bearer", request.Headers.Authorization?.Scheme);
            return PngResponse();
        });
        var tokenStore = new MemoryTokenStore();
        await tokenStore.SaveAsync("fixture-token");
        using var library = new MediaLibraryService(settings, tokenStore, new Uri("https://example.com"), false, Path.Combine(_root, "Data"), handler);
        await library.InitializeAsync();
        var remote = new Uri("https://example.com/media/result.png");

        var paths = await Task.WhenAll(Enumerable.Range(0, 20).Select(_ => library.SaveToLibraryAsync(remote, "result")));

        Assert.Equal(1, calls);
        Assert.Single(paths.Distinct(StringComparer.OrdinalIgnoreCase));
        Assert.True(File.Exists(paths[0]));
        Assert.Equal(new Uri(paths[0]), library.ResolveDisplayUri(remote.AbsoluteUri));
    }

    [Fact]
    public async Task Observe_downloads_new_asset_once_and_persists_seen_state()
    {
        var settings = await SettingsAsync(autoDownload: true);
        var calls = 0;
        var handler = new StubHandler((_, _) =>
        {
            Interlocked.Increment(ref calls);
            return Task.FromResult(PngResponse());
        });
        using var library = new MediaLibraryService(settings, new MemoryTokenStore(), new Uri("https://example.com"), false, Path.Combine(_root, "Data"), handler);
        await library.InitializeAsync();
        var assets = new[] { new ImageAssetCandidate("/media/new.png", "new-result") };

        await library.ObserveAsync(assets);
        await library.ObserveAsync(assets);

        Assert.Equal(1, calls);
        Assert.NotNull(library.TryGetLocalPath(new Uri("https://example.com/media/new.png")));
    }

    [Fact]
    public async Task Missing_local_copy_falls_back_to_remote_url()
    {
        var settings = await SettingsAsync(autoDownload: false);
        using var library = new MediaLibraryService(settings, new MemoryTokenStore(), new Uri("https://example.com"), false, Path.Combine(_root, "Data"), new StubHandler((_, _) => Task.FromResult(PngResponse())));
        await library.InitializeAsync();
        var remote = new Uri("https://example.com/media/fallback.png");
        var local = Path.Combine(_root, "manual.png");
        Directory.CreateDirectory(_root);
        await File.WriteAllBytesAsync(local, Png);
        await library.RegisterLocalFileAsync(remote, local);
        File.Delete(local);

        var resolved = library.ResolveDisplayUri(remote.AbsoluteUri);

        Assert.Equal(remote, resolved);
    }

    [Fact]
    public async Task Cross_origin_download_never_receives_user_authorization()
    {
        var settings = await SettingsAsync(autoDownload: true);
        AuthenticationHeaderValue? authorization = null;
        var handler = new StubHandler((request, _) =>
        {
            authorization = request.Headers.Authorization;
            return Task.FromResult(PngResponse());
        });
        var tokenStore = new MemoryTokenStore();
        await tokenStore.SaveAsync("fixture-token");
        using var library = new MediaLibraryService(settings, tokenStore, new Uri("https://example.com"), false, Path.Combine(_root, "Data"), handler);
        await library.InitializeAsync();

        await library.SaveToLibraryAsync(new Uri("https://cdn.example.net/result.png"), "cross-origin");

        Assert.Null(authorization);
    }

    private async Task<ClientSettingsService> SettingsAsync(bool autoDownload)
    {
        var settings = new ClientSettingsService(Path.Combine(_root, "Settings"));
        await settings.SaveAsync(autoDownload, Path.Combine(_root, "Pictures"));
        return settings;
    }

    private static HttpResponseMessage PngResponse()
    {
        var response = new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(Png) };
        response.Content.Headers.ContentType = new MediaTypeHeaderValue("image/png");
        return response;
    }

    public void Dispose()
    {
        try { if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true); } catch { }
    }

    private sealed class StubHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> callback) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => callback(request, cancellationToken);
    }
}
