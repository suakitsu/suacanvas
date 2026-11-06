using PicPilot.App.Services;
using PicPilot.App.ViewModels;
using PicPilot.App.Converters;
using PicPilot.Core;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Windows;
using System.Windows.Threading;

namespace PicPilot.App;

public partial class App : System.Windows.Application
{
    private PicPilotApiClient? _api;
    private MediaLibraryService? _mediaLibrary;

    public App()
    {
        DispatcherUnhandledException += App_DispatcherUnhandledException;
    }

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        ShutdownMode = ShutdownMode.OnMainWindowClose;

        try
        {
            var staging = e.Args.Any(x => x.Equals("--staging", StringComparison.OrdinalIgnoreCase));
            var baseUri = new Uri(staging ? "http://127.0.0.1:13000" : "https://suakitsu.com");
            var tokenStore = new WindowsCredentialTokenStore();
            var session = new SessionService(tokenStore);
            var settings = new ClientSettingsService();
            await settings.LoadAsync();
            var httpHandler = new HttpClientHandler
            {
#pragma warning disable SYSLIB0014 // Required to honor the current Windows Internet Options proxy/PAC settings.
                Proxy = WebRequest.GetSystemWebProxy(),
#pragma warning restore SYSLIB0014
                UseProxy = true,
                AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate | DecompressionMethods.Brotli
            };
            _api = new PicPilotApiClient(baseUri, tokenStore, httpHandler);
            _mediaLibrary = new MediaLibraryService(settings, tokenStore, baseUri, staging);
            await _mediaLibrary.InitializeAsync();
            MediaDisplaySourceRegistry.Current = _mediaLibrary;
            var viewModel = new MainViewModel(_api, session);
            var window = new MainWindow(viewModel, tokenStore, settings, _mediaLibrary, staging);
            MainWindow = window;
            window.Show();
            await viewModel.InitializeAsync();
        }
        catch (Exception exception)
        {
            WriteSafeCrashMarker(exception);
            System.Windows.MessageBox.Show("PicPilot 启动失败，请重新打开客户端；若问题持续，请联系管理员。", "PicPilot", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
        }
    }

    private void App_DispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        WriteSafeCrashMarker(e.Exception);
        e.Handled = true;
        System.Windows.MessageBox.Show("客户端遇到异常，本次操作已停止。请刷新当前页面后重试。", "PicPilot", MessageBoxButton.OK, MessageBoxImage.Warning);
    }

    private static void WriteSafeCrashMarker(Exception exception)
    {
        try
        {
            var version = Assembly.GetExecutingAssembly().GetName().Version?.ToString() ?? "unknown";
            var line = $"{DateTimeOffset.UtcNow:O}\tversion={version}\ttype={exception.GetType().FullName}\thresult={exception.HResult}\n";
            AppDataPaths.AppendBoundedLog("client-crash.log", line, 256 * 1024);
        }
        catch
        {
            // Crash reporting must never cause a second failure.
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        MediaDisplaySourceRegistry.Current = null;
        _mediaLibrary?.Dispose();
        _api?.Dispose();
        base.OnExit(e);
    }
}
