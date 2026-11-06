using Microsoft.Web.WebView2.Core;
using Microsoft.Win32;
using PicPilot.App.Dialogs;
using PicPilot.App.Services;
using PicPilot.App.ViewModels;
using PicPilot.Core;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Drawing = System.Drawing;
using WinForms = System.Windows.Forms;

namespace PicPilot.App;

public partial class MainWindow : Window
{
    private readonly MainViewModel _viewModel;
    private readonly ITokenStore _tokenStore;
    private readonly ClientSettingsService _settings;
    private readonly MediaLibraryService _mediaLibrary;
    private readonly bool _staging;
    private bool _canvasInitialized;
    private string? _sessionScriptId;
    private WinForms.NotifyIcon? _trayIcon;
    private Drawing.Icon? _trayOwnedIcon;
    private bool _allowExit;
    private string? _previewLocalPath;
    private string? _previewSuggestedName;
    private Uri? _previewRemoteUri;
    private bool _previewTemporary;
    private readonly DispatcherTimer _aiRefreshTimer;
    private bool _aiRefreshRunning;

    public MainWindow(
        MainViewModel viewModel,
        ITokenStore tokenStore,
        ClientSettingsService settings,
        MediaLibraryService mediaLibrary,
        bool staging)
    {
        InitializeComponent();
        _viewModel = viewModel;
        _tokenStore = tokenStore;
        _settings = settings;
        _mediaLibrary = mediaLibrary;
        _staging = staging;
        DataContext = viewModel;
        Title = staging ? "PicPilot 测试版" : "PicPilot";
        viewModel.ConfirmAsync = ConfirmAsync;
        viewModel.CanvasSessionChanged += ViewModel_CanvasSessionChanged;
        viewModel.ExternalUrlRequested += ViewModel_ExternalUrlRequested;
        viewModel.AssetActionRequested += ViewModel_AssetActionRequested;
        viewModel.ImageAssetsDiscovered += ViewModel_ImageAssetsDiscovered;
        viewModel.PropertyChanged += ViewModel_PropertyChanged;
        viewModel.AutoDownloadImages = settings.Current.AutoDownloadImages;
        viewModel.MinimizeToTray = settings.Current.EffectiveMinimizeToTray;
        viewModel.DownloadDirectory = settings.Current.DownloadDirectory;
        mediaLibrary.AssetCached += MediaLibrary_AssetCached;
        PreviewKeyDown += MainWindow_PreviewKeyDown;
        StateChanged += Window_StateChanged;
        InitializeTrayIcon();
        _aiRefreshTimer = new DispatcherTimer(TimeSpan.FromSeconds(8), DispatcherPriority.Background, AiRefreshTimer_Tick, Dispatcher);
        _aiRefreshTimer.Start();
    }

    private void PasswordInput_PasswordChanged(object sender, RoutedEventArgs e) => _viewModel.Password = PasswordInput.Password;
    private void ConfirmPasswordInput_PasswordChanged(object sender, RoutedEventArgs e) => _viewModel.ConfirmPassword = ConfirmPasswordInput.Password;

    private Task<bool> ConfirmAsync(string title, string description)
    {
        var dialog = new ConfirmationDialog(title, description) { Owner = this };
        return Task.FromResult(dialog.ShowDialog() == true);
    }

    private void ChooseCommerceReferences_Click(object sender, RoutedEventArgs e) => _viewModel.AddCommerceReferences(ChooseImages(10));
    private void ClearCommerceReferences_Click(object sender, RoutedEventArgs e) => _viewModel.ClearCommerceReferences();
    private void ChooseListingReferences_Click(object sender, RoutedEventArgs e) => _viewModel.AddListingReferences(ChooseImages(10));
    private void ClearListingReferences_Click(object sender, RoutedEventArgs e) => _viewModel.ClearListingReferences();
    private void ChooseAplusReference_Click(object sender, RoutedEventArgs e) => _viewModel.SetAplusReference(ChooseImages(1).FirstOrDefault());
    private void ClearAplusReference_Click(object sender, RoutedEventArgs e) => _viewModel.SetAplusReference(null);
    private void ChooseDomesticReference_Click(object sender, RoutedEventArgs e) => _viewModel.SetDomesticReference(ChooseImages(1).FirstOrDefault());
    private void ClearDomesticReference_Click(object sender, RoutedEventArgs e) => _viewModel.SetDomesticReference(null);
    private void ChooseAiReferences_Click(object sender, RoutedEventArgs e) => _viewModel.AddAiReferences(ChooseImages(10));
    private void ClearAiReferences_Click(object sender, RoutedEventArgs e) => _viewModel.ClearAiReferences();
    private void ChooseFreeReferences_Click(object sender, RoutedEventArgs e) => _viewModel.AddFreeReferences(ChooseImages(10));
    private void ClearFreeReferences_Click(object sender, RoutedEventArgs e) => _viewModel.ClearFreeReferences();
    private void ChooseVideoReferences_Click(object sender, RoutedEventArgs e) => _viewModel.AddVideoReferences(ChooseImages(_viewModel.SelectedVideoModel?.MaxReferenceImages ?? 3));
    private void ClearVideoReferences_Click(object sender, RoutedEventArgs e) => _viewModel.ClearVideoReferences();
    private void ChooseTranslationImages_Click(object sender, RoutedEventArgs e) => _viewModel.AddTranslationImages(ChooseImages(ImageFilePolicy.MaxReferenceFiles));
    private void ClearTranslationImages_Click(object sender, RoutedEventArgs e) => _viewModel.ClearTranslationImages();

    private string[] ChooseImages(int limit)
    {
        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Title = $"选择图片（最多 {limit} 张）",
            Multiselect = true,
            CheckFileExists = true,
            Filter = "支持的图片|*.jpg;*.jpeg;*.png;*.webp;*.heic;*.heif|JPEG|*.jpg;*.jpeg|PNG|*.png|WebP|*.webp|HEIC/HEIF|*.heic;*.heif"
        };
        return dialog.ShowDialog(this) == true ? dialog.FileNames.Take(limit).ToArray() : [];
    }

    private async void ViewModel_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(MainViewModel.SelectedPageIndex) && _viewModel.SelectedPageIndex == MainViewModel.CanvasPage)
            await EnsureCanvasAsync();
        else if (e.PropertyName == nameof(MainViewModel.AuthMode))
            _ = Dispatcher.BeginInvoke(() => AnimateSurface(AuthFormContent, 10));
        else if (e.PropertyName == nameof(MainViewModel.ErrorMessage) && !string.IsNullOrWhiteSpace(_viewModel.ErrorMessage))
            _ = Dispatcher.BeginInvoke(() => AnimateSurface(ErrorToast, -10));
        else if (e.PropertyName == nameof(MainViewModel.SuccessMessage) && !string.IsNullOrWhiteSpace(_viewModel.SuccessMessage))
            _ = Dispatcher.BeginInvoke(() => AnimateSurface(SuccessToast, -10));
        else if (e.PropertyName == nameof(MainViewModel.IsBusy) && _viewModel.IsBusy)
            _ = Dispatcher.BeginInvoke(() => AnimateSurface(BusyToast, 10));
    }

    private async void ViewModel_CanvasSessionChanged(object? sender, EventArgs e)
    {
        if (!_canvasInitialized) return;
        await ConfigureCanvasSessionAsync();
        if (_viewModel.IsAuthenticated && _viewModel.SelectedPageIndex == MainViewModel.CanvasPage) NavigateCanvas();
        else CanvasWebView.CoreWebView2?.Navigate("about:blank");
    }

    private void ViewModel_ExternalUrlRequested(object? sender, string url)
    {
        if (!NavigationUriPolicy.TryResolve(_viewModel.BaseUrl, url, out var uri)) return;
        Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
    }

    private async void ViewModel_AssetActionRequested(object? sender, AssetActionRequest request)
    {
        try
        {
            if (request.Action == "open" && !LooksLikeImage(request.Uri))
            {
                Process.Start(new ProcessStartInfo(request.Uri.AbsoluteUri) { UseShellExecute = true });
                return;
            }

            var existingPath = _mediaLibrary.TryGetLocalPath(request.Uri);
            var localPath = existingPath ?? await _mediaLibrary.GetForPreviewAsync(request.Uri, request.SuggestedName);
            var temporary = existingPath is null && !_settings.Current.AutoDownloadImages;
            if (request.Action == "download")
            {
                var extension = Path.GetExtension(localPath);
                var suggested = ImageFilePolicy.SanitizeDownloadName(request.SuggestedName);
                if (string.IsNullOrWhiteSpace(Path.GetExtension(suggested))) suggested += extension;
                var dialog = new Microsoft.Win32.SaveFileDialog
                {
                    FileName = suggested,
                    InitialDirectory = _settings.Current.DownloadDirectory,
                    OverwritePrompt = true
                };
                if (dialog.ShowDialog(this) == true)
                {
                    if (!Path.GetFullPath(localPath).Equals(Path.GetFullPath(dialog.FileName), StringComparison.OrdinalIgnoreCase))
                        File.Copy(localPath, dialog.FileName, overwrite: true);
                    await _mediaLibrary.RegisterLocalFileAsync(request.Uri, dialog.FileName);
                    _viewModel.SuccessMessage = "文件已下载";
                }
                if (temporary) File.Delete(localPath);
                return;
            }

            ShowImagePreview(localPath, request.SuggestedName, request.Uri, temporary);
        }
        catch
        {
            _viewModel.ErrorMessage = "文件读取失败，请刷新任务后重试。";
        }
    }

    private async void ViewModel_ImageAssetsDiscovered(object? sender, IReadOnlyList<ImageAssetCandidate> assets)
    {
        try
        {
            await _mediaLibrary.ObserveAsync(assets);
            if (_settings.Current.AutoDownloadImages && assets.Count > 0)
                _viewModel.LocalMediaStatus = "新生成图片会自动保存到本地图片库";
        }
        catch
        {
            _viewModel.LocalMediaStatus = "自动保存暂时失败，预览仍会从网站加载";
        }
    }

    private void MediaLibrary_AssetCached(object? sender, EventArgs e) => Dispatcher.BeginInvoke(RefreshImageBindings);

    private void RefreshImageBindings()
    {
        foreach (var image in FindVisualChildren<System.Windows.Controls.Image>(this))
            image.GetBindingExpression(System.Windows.Controls.Image.SourceProperty)?.UpdateTarget();
    }

    private async void AutoDownloadToggle_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            await _settings.SaveAsync(_viewModel.AutoDownloadImages, _viewModel.DownloadDirectory, _viewModel.MinimizeToTray);
            _viewModel.LocalMediaStatus = _viewModel.AutoDownloadImages
                ? "已开启：从现在起检测到的新图片会自动保存"
                : "已关闭：预览时仍会优先读取已经下载的本地文件";
        }
        catch
        {
            _viewModel.ErrorMessage = "本地下载设置保存失败，请检查文件夹权限。";
        }
    }

    private async void MinimizeToTrayToggle_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            await _settings.SaveAsync(_viewModel.AutoDownloadImages, _viewModel.DownloadDirectory, _viewModel.MinimizeToTray);
            _viewModel.SuccessMessage = _viewModel.MinimizeToTray
                ? "关闭或最小化窗口时，PicPilot 会保留在系统托盘"
                : "已关闭托盘保留，关闭主窗口将退出客户端";
        }
        catch
        {
            _viewModel.ErrorMessage = "窗口设置保存失败，请稍后重试。";
        }
    }

    private void InitializeTrayIcon()
    {
        try
        {
            var iconPath = Path.Combine(AppContext.BaseDirectory, "Assets", "picpilot.ico");
            _trayOwnedIcon = File.Exists(iconPath) ? new Drawing.Icon(iconPath) : Drawing.SystemIcons.Application;
            var menu = new WinForms.ContextMenuStrip();
            menu.Items.Add("打开 PicPilot", null, (_, _) => Dispatcher.Invoke(RestoreFromTray));
            menu.Items.Add(new WinForms.ToolStripSeparator());
            menu.Items.Add("工作台", null, (_, _) => Dispatcher.Invoke(() => NavigateFromTray(MainViewModel.WorkspacePage)));
            menu.Items.Add("AI 创作", null, (_, _) => Dispatcher.Invoke(() => NavigateFromTray(MainViewModel.AiPage)));
            menu.Items.Add("历史记录", null, (_, _) => Dispatcher.Invoke(() => NavigateFromTray(MainViewModel.HistoryPage)));
            menu.Items.Add(new WinForms.ToolStripSeparator());
            menu.Items.Add("退出 PicPilot", null, (_, _) => Dispatcher.Invoke(ExitFromTray));
            _trayIcon = new WinForms.NotifyIcon
            {
                Icon = _trayOwnedIcon,
                Text = _staging ? "PicPilot 测试版" : "PicPilot",
                ContextMenuStrip = menu,
                Visible = true
            };
            _trayIcon.DoubleClick += (_, _) => Dispatcher.Invoke(RestoreFromTray);
        }
        catch
        {
            _trayIcon = null;
        }
    }

    private void Window_StateChanged(object? sender, EventArgs e)
    {
        if (WindowState != WindowState.Minimized || !_viewModel.MinimizeToTray || _trayIcon is null) return;
        Hide();
    }

    private async void AiRefreshTimer_Tick(object? sender, EventArgs e)
    {
        if (_aiRefreshRunning || !IsVisible) return;
        _aiRefreshRunning = true;
        try { await _viewModel.RefreshVisibleAiResultsAsync(); }
        finally { _aiRefreshRunning = false; }
    }

    private void RestoreFromTray()
    {
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
        Topmost = true;
        Topmost = false;
        Focus();
    }

    private void NavigateFromTray(int page)
    {
        RestoreFromTray();
        _viewModel.NavigateCommand.Execute(page.ToString());
    }

    private void ExitFromTray()
    {
        _allowExit = true;
        Close();
    }

    private async void ChooseDownloadFolder_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFolderDialog { Title = "选择 PicPilot 图片保存位置", InitialDirectory = _viewModel.DownloadDirectory };
        if (dialog.ShowDialog(this) != true) return;
        _viewModel.DownloadDirectory = dialog.FolderName;
        try
        {
            await _settings.SaveAsync(_viewModel.AutoDownloadImages, _viewModel.DownloadDirectory, _viewModel.MinimizeToTray);
            _viewModel.SuccessMessage = "默认下载位置已更新";
        }
        catch
        {
            _viewModel.ErrorMessage = "默认下载位置保存失败，请选择有写入权限的文件夹。";
        }
    }

    private void OpenDownloadFolder_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Directory.CreateDirectory(_viewModel.DownloadDirectory);
            Process.Start(new ProcessStartInfo(_viewModel.DownloadDirectory) { UseShellExecute = true });
        }
        catch
        {
            _viewModel.ErrorMessage = "无法打开下载文件夹，请重新选择保存位置。";
        }
    }

    private async void SyncCompletedImages_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            _viewModel.LocalMediaStatus = "正在保存当前已完成图片...";
            await _mediaLibrary.CacheAllAsync(_viewModel.GetKnownImageAssets());
            _viewModel.LocalMediaStatus = "当前已完成图片已同步到本地图片库";
            _viewModel.SuccessMessage = "本地图片同步完成";
        }
        catch
        {
            _viewModel.LocalMediaStatus = "部分图片未能保存，可稍后重试";
            _viewModel.ErrorMessage = "同步未全部完成，请检查网络和文件夹权限。";
        }
    }

    private void ShowImagePreview(string localPath, string displayName, Uri remoteUri, bool temporary)
    {
        CloseImagePreview();
        _aiRefreshTimer.Stop();
        using var stream = new FileStream(localPath, FileMode.Open, FileAccess.Read, FileShare.Read);
        var image = new BitmapImage();
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = stream;
        image.EndInit();
        if (image.CanFreeze) image.Freeze();

        _previewLocalPath = localPath;
        _previewSuggestedName = displayName;
        _previewRemoteUri = remoteUri;
        _previewTemporary = temporary;
        ImagePreview.Source = image;
        ImagePreviewName.Text = string.IsNullOrWhiteSpace(displayName) ? "PicPilot 图片" : displayName;
        ImagePreviewDimensions.Text = $"{image.PixelWidth} × {image.PixelHeight}";
        ImagePreviewOverlay.Visibility = Visibility.Visible;
        Dispatcher.BeginInvoke(FitImagePreview);
    }

    private void CloseImagePreview()
    {
        ImagePreviewOverlay.Visibility = Visibility.Collapsed;
        ImagePreview.Source = null;
        ImagePreviewZoom.Value = 1;
        var temporaryPath = _previewTemporary ? _previewLocalPath : null;
        _previewLocalPath = null;
        _previewSuggestedName = null;
        _previewRemoteUri = null;
        _previewTemporary = false;
        if (!string.IsNullOrWhiteSpace(temporaryPath))
        {
            try { File.Delete(temporaryPath); } catch { }
        }
    }

    private void FitImagePreview()
    {
        if (ImagePreview.Source is not BitmapSource bitmap) return;
        var width = Math.Max(100, ImagePreviewScroll.ActualWidth - 36);
        var height = Math.Max(100, ImagePreviewScroll.ActualHeight - 36);
        ImagePreviewZoom.Value = Math.Clamp(Math.Min(width / bitmap.PixelWidth, height / bitmap.PixelHeight), 0.1, 6);
    }

    private void ImagePreviewClose_Click(object sender, RoutedEventArgs e) => CloseImagePreview();
    private void ImagePreviewFit_Click(object sender, RoutedEventArgs e) => FitImagePreview();
    private void ImagePreviewActual_Click(object sender, RoutedEventArgs e) => ImagePreviewZoom.Value = 1;

    private void ImagePreviewOverlay_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (ReferenceEquals(e.Source, ImagePreviewOverlay)) CloseImagePreview();
    }

    private void ImagePreviewScroll_PreviewMouseWheel(object sender, MouseWheelEventArgs e)
    {
        if (!Keyboard.Modifiers.HasFlag(ModifierKeys.Control)) return;
        ImagePreviewZoom.Value = Math.Clamp(ImagePreviewZoom.Value * (e.Delta > 0 ? 1.12 : 0.89), 0.1, 6);
        e.Handled = true;
    }

    private async void ImagePreviewDownload_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(_previewLocalPath) || !File.Exists(_previewLocalPath)) return;
        try
        {
            var extension = Path.GetExtension(_previewLocalPath);
            var suggested = ImageFilePolicy.SanitizeDownloadName(_previewSuggestedName ?? "PicPilot-output");
            if (string.IsNullOrWhiteSpace(Path.GetExtension(suggested))) suggested += extension;
            var dialog = new Microsoft.Win32.SaveFileDialog { FileName = suggested, InitialDirectory = _settings.Current.DownloadDirectory, OverwritePrompt = true };
            if (dialog.ShowDialog(this) != true) return;
            if (!Path.GetFullPath(_previewLocalPath).Equals(Path.GetFullPath(dialog.FileName), StringComparison.OrdinalIgnoreCase))
                File.Copy(_previewLocalPath, dialog.FileName, overwrite: true);
            if (_previewRemoteUri is not null) await _mediaLibrary.RegisterLocalFileAsync(_previewRemoteUri, dialog.FileName);
            _viewModel.SuccessMessage = "图片已保存";
        }
        catch
        {
            _viewModel.ErrorMessage = "图片保存失败，请检查文件夹权限。";
        }
    }

    private static bool LooksLikeImage(Uri uri) => new[] { ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif" }.Contains(Path.GetExtension(uri.AbsolutePath), StringComparer.OrdinalIgnoreCase);

    private async Task EnsureCanvasAsync()
    {
        if (_canvasInitialized)
        {
            if (CanvasWebView.Source is null || CanvasWebView.Source.AbsoluteUri == "about:blank") NavigateCanvas();
            return;
        }

        CanvasLoading.Visibility = Visibility.Visible;
        try
        {
            var dataFolder = Path.Combine(AppDataPaths.ResolveDataRoot(), "WebView2");
            Directory.CreateDirectory(dataFolder);
            var options = new CoreWebView2EnvironmentOptions { AdditionalBrowserArguments = "--disable-features=msEdgeSidebarV2" };
            var environment = await CoreWebView2Environment.CreateAsync(null, dataFolder, options);
            await CanvasWebView.EnsureCoreWebView2Async(environment);
            CanvasWebView.CoreWebView2.Settings.UserAgent = "PicPilotWindows/2.0.2";
            CanvasWebView.CoreWebView2.Settings.AreDevToolsEnabled = _staging;
            CanvasWebView.CoreWebView2.Settings.AreDefaultContextMenusEnabled = true;
            CanvasWebView.CoreWebView2.Settings.IsPasswordAutosaveEnabled = false;
            CanvasWebView.CoreWebView2.Settings.IsGeneralAutofillEnabled = false;
            CanvasWebView.CoreWebView2.NavigationStarting += Canvas_NavigationStarting;
            CanvasWebView.CoreWebView2.NavigationCompleted += Canvas_NavigationCompleted;
            CanvasWebView.CoreWebView2.NewWindowRequested += Canvas_NewWindowRequested;
            CanvasWebView.CoreWebView2.DownloadStarting += Canvas_DownloadStarting;
            _canvasInitialized = true;
            await ConfigureCanvasSessionAsync();
            NavigateCanvas();
        }
        catch
        {
            CanvasLoading.Visibility = Visibility.Visible;
            if (CanvasLoading.Child is StackPanel panel && panel.Children.OfType<TextBlock>().FirstOrDefault() is { } label)
                label.Text = "AI 画布暂时无法打开，请检查网络后重试";
        }
    }

    private async Task ConfigureCanvasSessionAsync()
    {
        if (!_canvasInitialized || CanvasWebView.CoreWebView2 is null) return;
        if (!string.IsNullOrWhiteSpace(_sessionScriptId))
        {
            CanvasWebView.CoreWebView2.RemoveScriptToExecuteOnDocumentCreated(_sessionScriptId);
            _sessionScriptId = null;
        }

        var token = _viewModel.IsAuthenticated ? await _tokenStore.ReadAsync() : "";
        var origin = new Uri(_viewModel.BaseUrl).GetLeftPart(UriPartial.Authority);
        var userJson = JsonSerializer.Serialize(_viewModel.CurrentUser, new JsonSerializerOptions(JsonSerializerDefaults.Web) { PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower });
        var script = "(() => {"
            + "if (location.origin !== " + JsonSerializer.Serialize(origin) + ") return;"
            + "const token = " + JsonSerializer.Serialize(token) + ";"
            + "if (token) {"
            + "localStorage.setItem('openimage_token', token);"
            + "localStorage.setItem('openimage_user', " + JsonSerializer.Serialize(userJson) + ");"
            + "} else {"
            + "localStorage.removeItem('openimage_token');"
            + "localStorage.removeItem('openimage_user');"
            + "}"
            + "document.documentElement.dataset.picpilotClient = 'windows';"
            + "if (location.pathname.startsWith('/workflows')) {"
            + "window.setTimeout(() => {"
            + "const id = 'picpilot-windows-canvas-ready';"
            + "if (!document.getElementById(id)) {"
            + "const style = document.createElement('style');"
            + "style.id = id;"
            + "style.textContent = '.canvas-lab-frame.is-active{opacity:1!important}.canvas-lab-loading{display:none!important}';"
            + "document.head?.appendChild(style);"
            + "}"
            + "}, 12000);"
            + "}"
            + "})();";
        _sessionScriptId = await CanvasWebView.CoreWebView2.AddScriptToExecuteOnDocumentCreatedAsync(script);
    }

    private void NavigateCanvas()
    {
        if (!_canvasInitialized || !_viewModel.IsAuthenticated) return;
        CanvasLoading.Visibility = Visibility.Visible;
        var uri = new Uri(new Uri(_viewModel.BaseUrl.TrimEnd('/') + "/"), "workflows?client=windows&embedded=1");
        CanvasWebView.CoreWebView2.Navigate(uri.AbsoluteUri);
    }

    private void Canvas_NavigationStarting(object? sender, CoreWebView2NavigationStartingEventArgs e)
    {
        if (e.Uri == "about:blank") return;
        if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out var uri)) { e.Cancel = true; return; }
        var expected = new Uri(_viewModel.BaseUrl);
        if (!uri.Scheme.Equals(expected.Scheme, StringComparison.OrdinalIgnoreCase) || !uri.Host.Equals(expected.Host, StringComparison.OrdinalIgnoreCase) || uri.Port != expected.Port)
        {
            e.Cancel = true;
            if (uri.Scheme is "https" or "http") Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
        }
    }

    private void Canvas_NavigationCompleted(object? sender, CoreWebView2NavigationCompletedEventArgs e)
    {
        CanvasLoading.Visibility = e.IsSuccess ? Visibility.Collapsed : Visibility.Visible;
        if (!e.IsSuccess && CanvasLoading.Child is StackPanel panel && panel.Children.OfType<TextBlock>().FirstOrDefault() is { } label)
            label.Text = "AI 画布连接失败，点击右上角“刷新画布”重试";
    }

    private void Canvas_NewWindowRequested(object? sender, CoreWebView2NewWindowRequestedEventArgs e)
    {
        e.Handled = true;
        if (Uri.TryCreate(e.Uri, UriKind.Absolute, out var uri) && uri.Scheme is "https" or "http")
            Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
    }

    private void Canvas_DownloadStarting(object? sender, CoreWebView2DownloadStartingEventArgs e)
    {
        var suggested = Path.GetFileName(e.ResultFilePath);
        var dialog = new Microsoft.Win32.SaveFileDialog
        {
            FileName = string.IsNullOrWhiteSpace(suggested) ? "PicPilot-output" : suggested,
            InitialDirectory = _viewModel.DownloadDirectory,
            OverwritePrompt = true
        };
        if (dialog.ShowDialog(this) == true) e.ResultFilePath = dialog.FileName;
        else e.Cancel = true;
    }

    private void CanvasBack_Click(object sender, RoutedEventArgs e)
    {
        if (_canvasInitialized && CanvasWebView.CanGoBack) CanvasWebView.GoBack();
    }

    private async void CanvasRefresh_Click(object sender, RoutedEventArgs e)
    {
        await EnsureCanvasAsync();
        if (_canvasInitialized) CanvasWebView.Reload();
    }

    private void Window_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        var compact = ActualWidth < 1120;
        SidebarColumn.Width = new GridLength(compact ? 76 : 224);
        AuthHeroColumn.Width = new GridLength(compact ? 0 : 1.15, compact ? GridUnitType.Pixel : GridUnitType.Star);
        AuthFormColumn.Width = new GridLength(compact ? 1 : 0.85, GridUnitType.Star);
        foreach (var element in FindVisualChildren<FrameworkElement>(this).Where(x => Equals(x.Tag, "NavLabel")))
            element.Visibility = compact ? Visibility.Collapsed : Visibility.Visible;
    }

    private void PageTabs_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!ReferenceEquals(e.Source, PageTabs) || PageTabs.SelectedContent is not FrameworkElement content) return;
        content.Opacity = 0;
        var translate = new TranslateTransform(0, 8);
        content.RenderTransform = translate;
        content.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(180))
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
        });
        translate.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(8, 0, TimeSpan.FromMilliseconds(210))
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
        });
    }

    private static void AnimateSurface(FrameworkElement element, double offset)
    {
        element.Opacity = 0;
        var translate = element.RenderTransform as TranslateTransform ?? new TranslateTransform();
        element.RenderTransform = translate;
        translate.Y = offset;
        element.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(170))
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
        });
        translate.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(offset, 0, TimeSpan.FromMilliseconds(210))
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
        });
    }

    private async void MainWindow_PreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == Key.Escape && ImagePreviewOverlay.Visibility == Visibility.Visible)
        {
            e.Handled = true;
            CloseImagePreview();
        }
        else if (e.Key == Key.R && Keyboard.Modifiers.HasFlag(ModifierKeys.Control))
        {
            e.Handled = true;
            if (_viewModel.RefreshCurrentCommand.CanExecute(null)) await _viewModel.RefreshCurrentCommand.ExecuteAsync(null);
        }
        else if (e.Key == Key.Left && Keyboard.Modifiers.HasFlag(ModifierKeys.Alt) && _viewModel.SelectedPageIndex != MainViewModel.WorkspacePage)
        {
            e.Handled = true;
            _viewModel.NavigateCommand.Execute("0");
        }
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        if (!_allowExit && _viewModel.MinimizeToTray && _trayIcon is not null)
        {
            e.Cancel = true;
            Hide();
            return;
        }

        CloseImagePreview();
        _viewModel.CanvasSessionChanged -= ViewModel_CanvasSessionChanged;
        _viewModel.ExternalUrlRequested -= ViewModel_ExternalUrlRequested;
        _viewModel.AssetActionRequested -= ViewModel_AssetActionRequested;
        _viewModel.ImageAssetsDiscovered -= ViewModel_ImageAssetsDiscovered;
        _viewModel.PropertyChanged -= ViewModel_PropertyChanged;
        _mediaLibrary.AssetCached -= MediaLibrary_AssetCached;
        StateChanged -= Window_StateChanged;
        if (_trayIcon is not null)
        {
            _trayIcon.Visible = false;
            _trayIcon.ContextMenuStrip?.Dispose();
            _trayIcon.Dispose();
            _trayIcon = null;
        }
        if (_trayOwnedIcon is not null && !ReferenceEquals(_trayOwnedIcon, Drawing.SystemIcons.Application)) _trayOwnedIcon.Dispose();
        CanvasWebView.Dispose();
    }

    private static IEnumerable<T> FindVisualChildren<T>(DependencyObject root) where T : DependencyObject
    {
        for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
        {
            var child = VisualTreeHelper.GetChild(root, index);
            if (child is T match) yield return match;
            foreach (var nested in FindVisualChildren<T>(child)) yield return nested;
        }
    }
}
