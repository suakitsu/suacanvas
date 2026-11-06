using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using PicPilot.App.Services;
using PicPilot.Core;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Text.Json;
using System.Windows.Data;

namespace PicPilot.App.ViewModels;

public sealed partial class MainViewModel : ObservableObject
{
    public const int WorkspacePage = 0;
    public const int CommercePage = 1;
    public const int VideoPage = 2;
    public const int AiPage = 3;
    public const int CanvasPage = 4;
    public const int HistoryPage = 5;
    public const int BillingPage = 6;
    public const int MessagesPage = 7;
    public const int SettingsPage = 8;

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower };
    private static readonly JsonSerializerOptions PrettyJson = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    private readonly PicPilotApiClient _api;
    private readonly SessionService _session;
    private CancellationTokenSource? _pageLoadCts;
    private readonly HashSet<string> _listingUploadedPaths = new(StringComparer.OrdinalIgnoreCase);

    public MainViewModel(PicPilotApiClient api, SessionService session)
    {
        _api = api;
        _session = session;
        BaseUrl = api.BaseUri.GetLeftPart(UriPartial.Authority);
        Resolutions.Add("1K");
        Resolutions.Add("2K");
        Resolutions.Add("4K");
        AiSizes.ReplaceWith([
            "1024x1024", "2048x2048", "2880x2880",
            "1280x720", "2048x1152", "3840x2160",
            "720x1280", "1152x2048", "2160x3840",
            "1024x768", "2048x1536", "3264x2448",
            "768x1024", "1536x2048", "2448x3264"
        ]);
        CommerceWorkflows.Add(new("listing", "亚马逊 Listing", "整理商品事实、生成文案，再创建 Listing 七图及可选 A+ 图"));
        CommerceWorkflows.Add(new("aplus", "亚马逊 A+", "独立创建品牌故事、核心卖点、场景与结构模块图"));
        CommerceWorkflows.Add(new("platform", "平台套图", "淘宝、京东、拼多多、Amazon 等平台按模板生成"));
        CommerceWorkflows.Add(new("translate", "图片翻译", "多图输入、目标语言选择并逐张输出"));
        CommerceWorkflows.Add(new("domestic", "国内电商", "按平台、用途和画面要求创建单张高质量电商图"));
        SelectedCommerceWorkflow = CommerceWorkflows[0];
        TranslationResolutions.Add("1K");
        TranslationResolutions.Add("2K");
        TranslationResolutions.Add("4K");
        ProjectsView = CollectionViewSource.GetDefaultView(Projects);
        VideoTasksView = CollectionViewSource.GetDefaultView(VideoTasks);
        CreativeChatsView = CollectionViewSource.GetDefaultView(CreativeChats);
        ProjectsView.Filter = FilterProject;
        VideoTasksView.Filter = FilterVideo;
        CreativeChatsView.Filter = FilterChat;
    }

    public string BaseUrl { get; }
    public Func<string, string, Task<bool>>? ConfirmAsync { get; set; }
    public event EventHandler? CanvasSessionChanged;
    public event EventHandler<string>? ExternalUrlRequested;
    public event EventHandler<AssetActionRequest>? AssetActionRequested;
    public event EventHandler<IReadOnlyList<ImageAssetCandidate>>? ImageAssetsDiscovered;

    [ObservableProperty] private bool isAuthenticated;
    [ObservableProperty] private bool isBusy;
    [ObservableProperty] private bool isPageLoading;
    [ObservableProperty] private string busyText = "正在加载";
    [ObservableProperty] private string errorMessage = "";
    [ObservableProperty] private string successMessage = "";
    [ObservableProperty] private UserProfile? currentUser;
    [ObservableProperty] private int selectedPageIndex;
    [ObservableProperty] private string pageTitle = "工作台";
    [ObservableProperty] private int authMode;
    [ObservableProperty] private string email = "";
    [ObservableProperty] private string password = "";
    [ObservableProperty] private string confirmPassword = "";
    [ObservableProperty] private string verificationCode = "";
    [ObservableProperty] private bool rememberMe = true;
    [ObservableProperty] private int codeCooldown;

    [ObservableProperty] private int dashboardCredits;
    [ObservableProperty] private int dashboardProjectCount;
    [ObservableProperty] private int dashboardRunningCount;
    [ObservableProperty] private int dashboardCompletedCount;

    public ObservableCollection<ProjectSummary> Projects { get; } = [];
    public ObservableCollection<VideoTask> VideoTasks { get; } = [];
    public ObservableCollection<CreativeChatSummary> CreativeChats { get; } = [];
    public ObservableCollection<StationMessage> Messages { get; } = [];
    public ObservableCollection<RechargePlan> RechargePlans { get; } = [];
    public ObservableCollection<PaymentChannel> PaymentChannels { get; } = [];
    public ObservableCollection<CreditLedger> CreditLedger { get; } = [];
    public ObservableCollection<RechargeOrder> RechargeOrders { get; } = [];
    public ICollectionView ProjectsView { get; }
    public ICollectionView VideoTasksView { get; }
    public ICollectionView CreativeChatsView { get; }

    public ObservableCollection<CommercePlatform> CommercePlatforms { get; } = [];
    public ObservableCollection<CommerceWorkflowChoice> CommerceWorkflows { get; } = [];
    public ObservableCollection<SlotChoice> CommerceSlots { get; } = [];
    public ObservableCollection<string> Resolutions { get; } = [];
    public ObservableCollection<string> CommerceReferencePaths { get; } = [];
    [ObservableProperty] private CommercePlatform? selectedCommercePlatform;
    [ObservableProperty] private CommerceWorkflowChoice? selectedCommerceWorkflow;
    [ObservableProperty] private string commerceProductName = "";
    [ObservableProperty] private string commerceBrand = "";
    [ObservableProperty] private string commerceCategory = "";
    [ObservableProperty] private string commerceSellingPoints = "";
    [ObservableProperty] private string commerceMaterial = "";
    [ObservableProperty] private string commerceSpecs = "";
    [ObservableProperty] private string commerceAudience = "";
    [ObservableProperty] private string commerceScenario = "";
    [ObservableProperty] private string commercePriceText = "";
    [ObservableProperty] private string commercePromoText = "";
    [ObservableProperty] private string commerceResolution = "1K";
    [ObservableProperty] private int commerceEstimatedCost;
    [ObservableProperty] private string commerceEstimateText = "请选择平台和画面后估算";

    public ObservableCollection<string> ListingReferencePaths { get; } = [];
    public ObservableCollection<string> ListingAplusModules { get; } = ["核心卖点模块", "品牌故事横幅", "场景生活方式图", "参数/结构说明图"];
    public ObservableCollection<string> ListingAplusSizes { get; } = ["970x600", "1464x600", "970x300"];
    [ObservableProperty] private string listingProductName = "";
    [ObservableProperty] private string listingBrand = "";
    [ObservableProperty] private string listingCountry = "US";
    [ObservableProperty] private string listingLanguage = "en-US";
    [ObservableProperty] private string listingDescription = "";
    [ObservableProperty] private string listingCategory = "";
    [ObservableProperty] private string listingMaterial = "";
    [ObservableProperty] private string listingDimensions = "";
    [ObservableProperty] private string listingVisualPrompt = "";
    [ObservableProperty] private string listingKeywords = "";
    [ObservableProperty] private string listingSellingKeywords = "";
    [ObservableProperty] private string listingDescriptionKeywords = "";
    [ObservableProperty] private bool listingIncludeAplus;
    [ObservableProperty] private string listingAplusModule = "核心卖点模块";
    [ObservableProperty] private string listingAplusSize = "970x600";
    [ObservableProperty] private string listingAplusPrompt = "";
    [ObservableProperty] private ProjectSummary? listingProject;
    [ObservableProperty] private string listingGeneratedCopy = "尚未生成文案";
    [ObservableProperty] private string listingFactsText = "{}";
    [ObservableProperty] private JsonElement listingGeneratedFacts;
    [ObservableProperty] private int listingStep = 1;

    public ObservableCollection<string> AplusModules { get; } = ["品牌故事横幅", "核心卖点模块", "场景生活方式图", "参数/结构说明图"];
    public ObservableCollection<string> AplusSizes { get; } = ["970x600", "1464x600", "970x300"];
    [ObservableProperty] private string aplusProductName = "";
    [ObservableProperty] private string aplusBrand = "";
    [ObservableProperty] private string aplusPrompt = "";
    [ObservableProperty] private string aplusModule = "核心卖点模块";
    [ObservableProperty] private string aplusSize = "970x600";
    [ObservableProperty] private string aplusLanguage = "zh-CN";
    [ObservableProperty] private string aplusReferencePath = "";

    public ObservableCollection<string> DomesticPlatforms { get; } = ["淘宝/天猫", "京东", "拼多多", "抖音", "小红书", "1688"];
    public ObservableCollection<string> DomesticUsages { get; } = ["商品主图", "核心卖点图", "促销氛围图", "规格参数图", "对比图", "包装清单图", "目标人群图"];
    [ObservableProperty] private string domesticProductName = "";
    [ObservableProperty] private string domesticBrand = "";
    [ObservableProperty] private string domesticPlatform = "淘宝/天猫";
    [ObservableProperty] private string domesticUsage = "商品主图";
    [ObservableProperty] private string domesticPrompt = "";
    [ObservableProperty] private string domesticSize = "1024x1024";
    [ObservableProperty] private string domesticLanguage = "zh-CN";
    [ObservableProperty] private string domesticReferencePath = "";
    [ObservableProperty] private int domesticEstimatedCost = 6;
    [ObservableProperty] private string domesticEstimateText = "1 张 · 预计 6 积分";

    public ObservableCollection<TranslationLanguage> TranslationLanguages { get; } = [];
    public ObservableCollection<string> TranslationResolutions { get; } = [];
    public ObservableCollection<string> TranslationImagePaths { get; } = [];
    [ObservableProperty] private TranslationLanguage? selectedTranslationLanguage;
    [ObservableProperty] private string translationResolution = "1K";
    [ObservableProperty] private string translationEstimateText = "选择图片后显示费用";
    [ObservableProperty] private int translationEstimatedCost;

    public ObservableCollection<string> AiSizes { get; } = [];
    public ObservableCollection<string> FreeStyles { get; } = ["AI 自动判断", "电商商业摄影", "韩系小红书", "日系杂志", "透明背景产品图", "复古胶片"];
    public ObservableCollection<string> FreeReferencePaths { get; } = [];
    public ObservableCollection<string> AiReferencePaths { get; } = [];
    public ObservableCollection<string> AssistantModels { get; } = [];
    public ObservableCollection<AssistantConversationItem> AssistantConversation { get; } = [];
    [ObservableProperty] private int selectedAiModeIndex;
    [ObservableProperty] private string freePrompt = "";
    [ObservableProperty] private string freeStyle = "AI 自动判断";
    [ObservableProperty] private string freeSize = "1024x1024";
    [ObservableProperty] private string freeLanguage = "zh-CN";
    [ObservableProperty] private ProjectDetail? freeCreativeDetail;
    [ObservableProperty] private string aiPrompt = "";
    [ObservableProperty] private string aiStyle = "电商商业摄影";
    [ObservableProperty] private string aiSize = "1024x1024";
    [ObservableProperty] private string aiLanguage = "zh-CN";
    [ObservableProperty] private int aiEstimatedCost;
    [ObservableProperty] private string aiEstimateText = "输入明确需求后估算积分";
    [ObservableProperty] private string activeAiChatId = "";
    [ObservableProperty] private string activeAiChatTitle = "新对话";
    [ObservableProperty] private bool assistantEnabled;
    [ObservableProperty] private string assistantModel = "gpt-5.5";
    [ObservableProperty] private string assistantInput = "";
    [ObservableProperty] private string assistantStatusText = "正在连接运营助手";
    [ObservableProperty] private string assistantSuggestedPrompt = "";
    [ObservableProperty] private string assistantActionLabel = "";
    [ObservableProperty] private string assistantActionHref = "";

    public ObservableCollection<VideoModelChoice> VideoModels { get; } = [];
    public ObservableCollection<string> VideoResolutions { get; } = [];
    public ObservableCollection<int> VideoDurations { get; } = [];
    public ObservableCollection<string> VideoRatios { get; } = [];
    public ObservableCollection<string> VideoReferencePaths { get; } = [];
    [ObservableProperty] private VideoModelChoice? selectedVideoModel;
    [ObservableProperty] private string videoTitle = "商品展示视频";
    [ObservableProperty] private string videoPrompt = "";
    [ObservableProperty] private string videoResolution = "720p";
    [ObservableProperty] private int videoDuration = 10;
    [ObservableProperty] private string videoRatio = "9:16";
    [ObservableProperty] private bool videoGenerateAudio;
    [ObservableProperty] private int videoEstimatedCost;
    [ObservableProperty] private string videoScriptPreview = "可先生成脚本预览，再确认提交。";

    [ObservableProperty] private string redeemCode = "";
    [ObservableProperty] private string profileNickname = "";
    [ObservableProperty] private string profileLanguage = "zh-CN";
    [ObservableProperty] private string profileTheme = "system";
    [ObservableProperty] private string historySearch = "";
    [ObservableProperty] private string historyStatus = "all";
    [ObservableProperty] private ProjectDetail? selectedProjectDetail;
    [ObservableProperty] private CreativeChatDetail? selectedCreativeChatDetail;
    [ObservableProperty] private bool isDetailOpen;
    [ObservableProperty] private int billingPageNumber = 1;
    [ObservableProperty] private int billingTotalPages = 1;
    [ObservableProperty] private bool autoDownloadImages;
    [ObservableProperty] private bool minimizeToTray = true;
    [ObservableProperty] private string downloadDirectory = ClientSettingsService.DefaultDownloadDirectory();
    [ObservableProperty] private string localMediaStatus = "预览会优先读取本地文件，本地不存在时自动从网站加载";

    public bool CanBillingPrevious => BillingPageNumber > 1;
    public bool CanBillingNext => BillingPageNumber < BillingTotalPages;
    public string ListingOutputLabel => ListingIncludeAplus ? "7 张 Listing 图 + 1 张 A+ 图" : "7 张 Listing 图";
    public string ListingCostText => ListingIncludeAplus ? "48 积分" : "42 积分";
    public bool AssistantHasSuggestion => !string.IsNullOrWhiteSpace(AssistantSuggestedPrompt);
    public bool AssistantHasAction => !string.IsNullOrWhiteSpace(AssistantActionLabel) && !string.IsNullOrWhiteSpace(AssistantActionHref);
    public int FreeEstimatedCost => ImageCreditsForSize(FreeSize);
    public string FreeCostText => $"1 张 · {ImageQualityForSize(FreeSize)} · {FreeEstimatedCost} 积分";

    public string AuthTitle => AuthMode switch { 1 => "验证码登录", 2 => "创建 PicPilot 账号", 3 => "重置密码", _ => "登录 PicPilot" };
    public string AuthActionLabel => AuthMode switch { 1 => "登录", 2 => "注册并登录", 3 => "确认重置", _ => "登录" };
    public bool AuthNeedsCode => AuthMode is 1 or 2 or 3;
    public bool AuthNeedsConfirmation => AuthMode is 2 or 3;
    public bool AuthNeedsPassword => AuthMode is 0 or 2 or 3;

    partial void OnAuthModeChanged(int value)
    {
        ErrorMessage = "";
        SuccessMessage = "";
        VerificationCode = "";
        OnPropertyChanged(nameof(AuthTitle));
        OnPropertyChanged(nameof(AuthActionLabel));
        OnPropertyChanged(nameof(AuthNeedsCode));
        OnPropertyChanged(nameof(AuthNeedsConfirmation));
        OnPropertyChanged(nameof(AuthNeedsPassword));
    }

    partial void OnBillingPageNumberChanged(int value)
    {
        OnPropertyChanged(nameof(CanBillingPrevious));
        OnPropertyChanged(nameof(CanBillingNext));
    }

    partial void OnBillingTotalPagesChanged(int value) => OnPropertyChanged(nameof(CanBillingNext));

    partial void OnListingIncludeAplusChanged(bool value)
    {
        OnPropertyChanged(nameof(ListingOutputLabel));
        OnPropertyChanged(nameof(ListingCostText));
    }

    partial void OnAssistantSuggestedPromptChanged(string value) => OnPropertyChanged(nameof(AssistantHasSuggestion));
    partial void OnAssistantActionLabelChanged(string value) => OnPropertyChanged(nameof(AssistantHasAction));
    partial void OnAssistantActionHrefChanged(string value) => OnPropertyChanged(nameof(AssistantHasAction));

    partial void OnSelectedCommercePlatformChanged(CommercePlatform? value)
    {
        if (value is null) return;
        CommerceResolution = string.IsNullOrWhiteSpace(value.DefaultResolution) ? "1K" : value.DefaultResolution;
        foreach (var slot in CommerceSlots) slot.IsSelected = value.DefaultSlots.Contains(slot.Code);
        CommerceEstimatedCost = 0;
        CommerceEstimateText = "配置已更新，请重新估算";
    }

    partial void OnSelectedCommerceWorkflowChanged(CommerceWorkflowChoice? value)
    {
        CommerceEstimatedCost = 0;
        ErrorMessage = "";
        OnPropertyChanged(nameof(IsTranslationWorkflow));
        OnPropertyChanged(nameof(IsPlatformWorkflow));
        OnPropertyChanged(nameof(IsListingWorkflow));
        OnPropertyChanged(nameof(IsAplusWorkflow));
        OnPropertyChanged(nameof(IsDomesticWorkflow));
    }

    public bool IsTranslationWorkflow => SelectedCommerceWorkflow?.Code == "translate";
    public bool IsPlatformWorkflow => SelectedCommerceWorkflow?.Code == "platform";
    public bool IsListingWorkflow => SelectedCommerceWorkflow?.Code == "listing";
    public bool IsAplusWorkflow => SelectedCommerceWorkflow?.Code == "aplus";
    public bool IsDomesticWorkflow => SelectedCommerceWorkflow?.Code == "domestic";

    partial void OnTranslationResolutionChanged(string value) => UpdateTranslationEstimate();
    partial void OnSelectedTranslationLanguageChanged(TranslationLanguage? value) => UpdateTranslationEstimate();
    partial void OnAiPromptChanged(string value) { AiEstimatedCost = 0; AiEstimateText = "需求已修改，请重新估算积分"; }
    partial void OnAiSizeChanged(string value) { AiEstimatedCost = 0; AiEstimateText = "尺寸已修改，请重新估算积分"; }
    partial void OnFreeSizeChanged(string value)
    {
        OnPropertyChanged(nameof(FreeEstimatedCost));
        OnPropertyChanged(nameof(FreeCostText));
    }
    partial void OnDomesticPromptChanged(string value) { DomesticEstimatedCost = 0; DomesticEstimateText = "需求已修改，请重新估算积分"; }
    partial void OnDomesticSizeChanged(string value) { DomesticEstimatedCost = 0; DomesticEstimateText = "尺寸已修改，请重新估算积分"; }

    partial void OnHistorySearchChanged(string value) => RefreshHistoryViews();
    partial void OnHistoryStatusChanged(string value) => RefreshHistoryViews();

    partial void OnSelectedVideoModelChanged(VideoModelChoice? value)
    {
        VideoResolutions.ReplaceWith(value?.Resolutions ?? []);
        VideoDurations.ReplaceWith(value?.Durations ?? []);
        VideoRatios.ReplaceWith(value?.AspectRatios ?? []);
        VideoResolution = value?.DefaultResolution ?? VideoResolutions.FirstOrDefault() ?? "720p";
        VideoDuration = value?.DefaultDuration ?? VideoDurations.FirstOrDefault();
        VideoRatio = VideoRatios.FirstOrDefault() ?? "9:16";
        VideoGenerateAudio = value?.AudioToggle == true && VideoGenerateAudio;
        UpdateVideoCost();
    }

    partial void OnVideoResolutionChanged(string value) => UpdateVideoCost();
    partial void OnVideoDurationChanged(int value) => UpdateVideoCost();

    public async Task InitializeAsync()
    {
        if (IsBusy) return;
        IsBusy = true;
        BusyText = "正在准备 PicPilot";
        try
        {
            var me = await _api.GetMeAsync();
            _session.SetProfile(me);
            SetAuthenticated(me);
            await LoadWorkspaceAsync();
        }
        catch (ApiException ex) when (ex.StatusCode == 401 || ex.Code is "UNAUTHORIZED" or "TOKEN_INVALID")
        {
            await _session.SignOutAsync();
            IsAuthenticated = false;
        }
        catch
        {
            IsAuthenticated = false;
        }
        finally
        {
            IsBusy = false;
            BusyText = "正在加载";
        }
    }

    [RelayCommand]
    private void SwitchAuthMode(string? mode)
    {
        if (int.TryParse(mode, out var parsed)) AuthMode = Math.Clamp(parsed, 0, 3);
    }

    [RelayCommand]
    private async Task SubmitAuthAsync()
    {
        if (IsBusy) return;
        ErrorMessage = "";
        SuccessMessage = "";
        if (string.IsNullOrWhiteSpace(Email)) { ErrorMessage = "请输入邮箱"; return; }
        if (AuthNeedsPassword && string.IsNullOrWhiteSpace(Password)) { ErrorMessage = "请输入密码"; return; }
        if (AuthNeedsCode && string.IsNullOrWhiteSpace(VerificationCode)) { ErrorMessage = "请输入验证码"; return; }
        if (AuthNeedsConfirmation && Password != ConfirmPassword) { ErrorMessage = "两次输入的密码不一致"; return; }

        await RunBusyAsync("正在验证账号", async () =>
        {
            if (AuthMode == 3)
            {
                await _api.ResetPasswordAsync(Email.Trim(), VerificationCode.Trim(), Password, ConfirmPassword);
                Password = ConfirmPassword = VerificationCode = "";
                AuthMode = 0;
                SuccessMessage = "密码已重置，请使用新密码登录";
                return;
            }

            var auth = AuthMode switch
            {
                1 => await _api.LoginWithCodeAsync(Email.Trim(), VerificationCode.Trim(), RememberMe),
                2 => await _api.RegisterAsync(Email.Trim(), Password, ConfirmPassword, VerificationCode.Trim()),
                _ => await _api.LoginAsync(Email.Trim(), Password, RememberMe)
            };
            await _session.SetAsync(auth);
            SetAuthenticated(auth.User);
            Password = ConfirmPassword = VerificationCode = "";
            await LoadWorkspaceAsync();
            CanvasSessionChanged?.Invoke(this, EventArgs.Empty);
        });
    }

    [RelayCommand]
    private async Task SendCodeAsync()
    {
        if (CodeCooldown > 0 || IsBusy) return;
        if (string.IsNullOrWhiteSpace(Email)) { ErrorMessage = "请先输入邮箱"; return; }
        var purpose = AuthMode == 2 ? "register" : AuthMode == 3 ? "reset_password" : "login";
        await RunBusyAsync("正在发送验证码", async () =>
        {
            await _api.SendEmailCodeAsync(Email.Trim(), purpose);
            SuccessMessage = "验证码已发送，请检查邮箱";
            CodeCooldown = 60;
            _ = CountdownAsync();
        });
    }

    [RelayCommand]
    private async Task SignOutAsync()
    {
        Interlocked.Exchange(ref _pageLoadCts, null)?.Cancel();
        IsPageLoading = false;
        await _session.SignOutAsync();
        CurrentUser = null;
        IsAuthenticated = false;
        SelectedPageIndex = 0;
        Projects.Clear(); VideoTasks.Clear(); CreativeChats.Clear(); Messages.Clear(); RechargeOrders.Clear();
        CloseDetail();
        CanvasSessionChanged?.Invoke(this, EventArgs.Empty);
    }

    [RelayCommand]
    private void Navigate(string? page)
    {
        if (!int.TryParse(page, out var index)) return;
        SelectedPageIndex = Math.Clamp(index, 0, SettingsPage);
        PageTitle = SelectedPageIndex switch
        {
            CommercePage => "电商制图",
            VideoPage => "商品视频",
            AiPage => "AI 创作",
            CanvasPage => "AI 画布",
            HistoryPage => "历史记录",
            BillingPage => "积分与充值",
            MessagesPage => "站内信",
            SettingsPage => "个人资料与设置",
            _ => "工作台"
        };
        _ = RefreshCurrentAsync();
    }

    [RelayCommand(AllowConcurrentExecutions = true)]
    private async Task RefreshCurrentAsync()
    {
        if (!IsAuthenticated) return;
        var current = new CancellationTokenSource();
        var previous = Interlocked.Exchange(ref _pageLoadCts, current);
        previous?.Cancel();
        previous?.Dispose();
        IsPageLoading = true;
        ErrorMessage = "";
        try
        {
            var page = SelectedPageIndex;
            switch (page)
            {
                case CommercePage: await LoadCommerceAsync(current.Token); break;
                case VideoPage: await LoadVideoAsync(current.Token); break;
                case AiPage: await LoadAiAsync(current.Token); break;
                case CanvasPage: break;
                case HistoryPage: await LoadHistoryAsync(current.Token); break;
                case BillingPage: await LoadBillingAsync(current.Token); break;
                case MessagesPage: await LoadMessagesAsync(current.Token); break;
                case SettingsPage: await RefreshProfileAsync(current.Token); break;
                default: await LoadWorkspaceAsync(current.Token); break;
            }
        }
        catch (OperationCanceledException) when (current.IsCancellationRequested)
        {
            // A newer navigation request owns the page now.
        }
        catch (ApiException ex)
        {
            ErrorMessage = ex.Message;
            if (ex.StatusCode == 401 || ex.Code is "UNAUTHORIZED" or "TOKEN_INVALID") await SignOutAsync();
        }
        catch (Exception exception)
        {
            ClientDiagnostics.RecordFailure(exception);
            ErrorMessage = "页面加载没有完成，请检查网络后重试。";
        }
        finally
        {
            if (ReferenceEquals(Interlocked.CompareExchange(ref _pageLoadCts, null, current), current))
                IsPageLoading = false;
            current.Dispose();
        }
    }

    [RelayCommand]
    private async Task EstimateCommerceAsync()
    {
        var platform = SelectedCommercePlatform;
        var slots = CommerceSlots.Where(x => x.IsSelected).Select(x => x.Code).ToArray();
        if (platform is null) { ErrorMessage = "请选择平台"; return; }
        if (slots.Length == 0) { ErrorMessage = "至少选择一个画面"; return; }
        await RunBusyAsync("正在估算积分", async () =>
        {
            var result = await _api.EstimateCommerceAsync(platform.Code, slots, CommerceResolution);
            CommerceEstimatedCost = Int(result, "credits_cost");
            CommerceEstimateText = $"{slots.Length} 张 · 预计 {CommerceEstimatedCost} 积分";
        });
    }

    [RelayCommand]
    private async Task GenerateCommerceAsync()
    {
        var platform = SelectedCommercePlatform;
        var slots = CommerceSlots.Where(x => x.IsSelected).Select(x => x.Code).ToArray();
        if (platform is null || slots.Length == 0 || string.IsNullOrWhiteSpace(CommerceProductName)) { ErrorMessage = "请填写商品名称，并选择平台和画面"; return; }
        if (CommerceEstimatedCost <= 0) await EstimateCommerceAsync();
        if (CommerceEstimatedCost <= 0) return;
        if (!await ConfirmChargeAsync("确认生成平台套图", $"将生成 {slots.Length} 张图片，预计扣除 {CommerceEstimatedCost} 积分。当前余额 {CurrentUser?.Credits ?? 0} 积分。")) return;

        await RunBusyAsync("正在创建图片任务", async () =>
        {
            var product = ProductInfo();
            var key = $"commerce:{Guid.NewGuid():N}";
            if (CommerceReferencePaths.Count == 0)
            {
                await _api.CreateCommerceAsync(new
                {
                    platform = platform.Code,
                    product_info = product,
                    selected_slots = slots,
                    resolution = CommerceResolution,
                    reference_url = "",
                    overlay_options = new { }
                }, key);
            }
            else
            {
                var fields = new Dictionary<string, string>
                {
                    ["platform"] = platform.Code,
                    ["product_info"] = JsonSerializer.Serialize(product, Json),
                    ["selected_slots"] = JsonSerializer.Serialize(slots, Json),
                    ["resolution"] = CommerceResolution,
                    ["overlay_options"] = "{}"
                };
                await _api.CreateCommerceMultipartAsync(fields, CommerceReferencePaths.ToArray(), key);
            }
            SuccessMessage = "套图任务已创建，可在历史记录中查看进度";
            await RefreshProfileAsync();
            SelectedPageIndex = HistoryPage;
            PageTitle = "历史记录";
            await LoadHistoryAsync();
        });
    }

    [RelayCommand]
    private async Task PrepareListingAsync()
    {
        if (ListingProductName.Trim().Length < 2 || ListingBrand.Trim().Length < 1)
        {
            ErrorMessage = "请先填写商品名称和品牌。";
            return;
        }

        await RunBusyAsync("正在创建 Listing 并生成文案", async () =>
        {
            var project = await _api.CreateListingProjectAsync(new
            {
                product_name = ListingProductName.Trim(),
                brand = ListingBrand.Trim(),
                country = ListingCountry.Trim(),
                language = ListingLanguage.Trim(),
                description = ListingDescription.Trim(),
                category = ListingCategory.Trim(),
                material = ListingMaterial.Trim(),
                dimensions = ListingDimensions.Trim(),
                visual_prompt = ListingVisualPrompt.Trim(),
                keywords = Csv(ListingKeywords),
                title_keywords = Csv(ListingKeywords),
                selling_point_keywords = Csv(ListingSellingKeywords),
                description_keywords = Csv(ListingDescriptionKeywords),
                include_aplus = ListingIncludeAplus,
                aplus_module = ListingAplusModule,
                aplus_size = ListingAplusSize,
                aplus_prompt = ListingAplusPrompt.Trim()
            }, $"listing:create:{Guid.NewGuid():N}");

            ListingProject = project;
            _listingUploadedPaths.Clear();
            var copy = await _api.GenerateListingCopyAsync(project.Id);
            ApplyListingCopy(copy);
            ListingStep = 2;
            SuccessMessage = "Listing 文案已生成，请核对文案并添加商品参考图";
        });
    }

    [RelayCommand]
    private async Task RegenerateListingCopyAsync()
    {
        if (ListingProject is null) { ErrorMessage = "请先创建 Listing 项目。"; return; }
        await RunBusyAsync("正在重新生成 Listing 文案", async () =>
        {
            ApplyListingCopy(await _api.GenerateListingCopyAsync(ListingProject.Id));
            SuccessMessage = "Listing 文案已更新";
        });
    }

    [RelayCommand]
    private async Task GenerateListingPackageAsync()
    {
        if (ListingProject is null || ListingGeneratedFacts.ValueKind is JsonValueKind.Undefined or JsonValueKind.Null)
        {
            ErrorMessage = "请先生成并核对 Listing 文案。";
            return;
        }
        JsonElement confirmedFacts;
        try
        {
            using var document = JsonDocument.Parse(ListingFactsText);
            if (document.RootElement.ValueKind != JsonValueKind.Object) throw new JsonException();
            confirmedFacts = document.RootElement.Clone();
        }
        catch (JsonException)
        {
            ErrorMessage = "确认事实必须是有效的 JSON 对象，请修正后再提交。";
            return;
        }

        var validation = ImageFilePolicy.Validate(ListingReferencePaths, 10);
        if (validation is not null) { ErrorMessage = "Listing 至少需要一张真实商品主图。"; return; }

        var cost = 42 + (ListingIncludeAplus ? 6 : 0);
        var output = ListingIncludeAplus ? "7 张 Listing 图和 1 张 A+ 图" : "7 张 Listing 图";
        if (!await ConfirmChargeAsync("确认生成 Amazon 图片", $"将生成 {output}，预计扣除 {cost} 积分。当前余额 {CurrentUser?.Credits ?? 0} 积分。")) return;

        await RunBusyAsync("正在上传素材并创建 Listing 图片任务", async () =>
        {
            var referenceTypes = new[] { "main", "angle", "detail", "material", "scene", "package", "other" };
            for (var i = 0; i < ListingReferencePaths.Count; i++)
            {
                var path = ListingReferencePaths[i];
                if (!_listingUploadedPaths.Add(path)) continue;
                try { await _api.UploadProductImageAsync(ListingProject.Id, referenceTypes[Math.Min(i, referenceTypes.Length - 1)], path); }
                catch { _listingUploadedPaths.Remove(path); throw; }
            }

            await _api.ConfirmProjectFactsAsync(ListingProject.Id, confirmedFacts);
            await _api.EnqueueListingPackageAsync(ListingProject.Id, new
            {
                include_aplus = ListingIncludeAplus,
                aplus_module = ListingAplusModule,
                aplus_size = ListingAplusSize,
                aplus_prompt = string.IsNullOrWhiteSpace(ListingAplusPrompt)
                    ? $"{ListingProductName} Amazon A+ module. Keep the product structure accurate and present the key benefits clearly."
                    : ListingAplusPrompt.Trim(),
                language = ListingLanguage.Trim()
            }, $"listing:package:{ListingProject.Id}:{Guid.NewGuid():N}");

            ListingStep = 3;
            SuccessMessage = $"{output}已进入队列，可在历史记录查看进度";
            await RefreshProfileAsync();
            await GoToHistoryAsync();
        });
    }

    [RelayCommand]
    private async Task GenerateAplusAsync()
    {
        if (AplusPrompt.Trim().Length < 4) { ErrorMessage = "A+ 画面要求至少 4 个字符。"; return; }
        if (!string.IsNullOrWhiteSpace(AplusReferencePath))
        {
            var validation = ImageFilePolicy.Validate([AplusReferencePath], 1);
            if (validation is not null) { ErrorMessage = validation; return; }
        }
        if (!await ConfirmChargeAsync("确认生成 A+ 图", $"将生成 1 张 {AplusSize} A+ 模块图，预计扣除 6 积分。当前余额 {CurrentUser?.Credits ?? 0} 积分。")) return;

        await RunBusyAsync("正在创建 A+ 图片任务", async () =>
        {
            var key = $"aplus:{Guid.NewGuid():N}";
            if (string.IsNullOrWhiteSpace(AplusReferencePath))
            {
                await _api.CreateAplusImageAsync(new
                {
                    product_name = AplusProductName.Trim(), brand = AplusBrand.Trim(), prompt = AplusPrompt.Trim(),
                    size = AplusSize, language = AplusLanguage, module = AplusModule,
                    reference_url = "", intent = "generate", text_mode = "auto", reference_policy = "preserve_subject", template_id = ""
                }, key);
            }
            else
            {
                await _api.CreateAplusImageMultipartAsync(SingleImageFields(AplusProductName, AplusBrand, AplusPrompt, AplusSize, AplusLanguage, AplusModule), AplusReferencePath, key);
            }
            SuccessMessage = "A+ 图片任务已创建，可在历史记录查看进度";
            await RefreshProfileAsync();
            await GoToHistoryAsync();
        });
    }

    [RelayCommand]
    private async Task EstimateDomesticAsync()
    {
        if (DomesticPrompt.Trim().Length < 4) { ErrorMessage = "画面要求至少 4 个字符。"; return; }
        await RunBusyAsync("正在估算积分", async () =>
        {
            var estimate = await _api.EstimateCreativeAsync(DomesticPrompt.Trim(), DomesticSize, DomesticLanguage);
            DomesticEstimatedCost = estimate.CreditsCost;
            DomesticEstimateText = $"1 张 · {estimate.QualityLabel} · {estimate.CreditsCost} 积分";
        });
    }

    [RelayCommand]
    private async Task GenerateDomesticAsync()
    {
        if (DomesticProductName.Trim().Length < 1 || DomesticPrompt.Trim().Length < 4)
        {
            ErrorMessage = "请填写商品名称和至少 4 个字符的画面要求。";
            return;
        }
        if (!string.IsNullOrWhiteSpace(DomesticReferencePath))
        {
            var validation = ImageFilePolicy.Validate([DomesticReferencePath], 1);
            if (validation is not null) { ErrorMessage = validation; return; }
        }
        DomesticEstimatedCost = 0;
        await EstimateDomesticAsync();
        if (DomesticEstimatedCost <= 0) return;
        if (!await ConfirmChargeAsync("确认生成国内电商图", $"平台：{DomesticPlatform}，用途：{DomesticUsage}。本次预计扣除 {DomesticEstimatedCost} 积分，当前余额 {CurrentUser?.Credits ?? 0} 积分。")) return;

        await RunBusyAsync("正在创建国内电商图片任务", async () =>
        {
            var prompt = $"平台：{DomesticPlatform}；用途：{DomesticUsage}。{DomesticPrompt.Trim()}";
            var key = $"domestic:{Guid.NewGuid():N}";
            if (string.IsNullOrWhiteSpace(DomesticReferencePath))
            {
                await _api.CreateDomesticImageAsync(new
                {
                    product_name = DomesticProductName.Trim(), brand = DomesticBrand.Trim(), prompt,
                    size = DomesticSize, language = DomesticLanguage, module = $"{DomesticPlatform} / {DomesticUsage}",
                    reference_url = "", intent = "generate", text_mode = "auto", reference_policy = "preserve_subject", template_id = ""
                }, key);
            }
            else
            {
                await _api.CreateDomesticImageMultipartAsync(SingleImageFields(DomesticProductName, DomesticBrand, prompt, DomesticSize, DomesticLanguage, $"{DomesticPlatform} / {DomesticUsage}"), DomesticReferencePath, key);
            }
            SuccessMessage = "国内电商图片任务已创建，可在历史记录查看进度";
            await RefreshProfileAsync();
            await GoToHistoryAsync();
        });
    }

    [RelayCommand]
    private async Task GenerateTranslationAsync()
    {
        var validation = ImageFilePolicy.Validate(TranslationImagePaths);
        if (validation is not null) { ErrorMessage = validation; return; }
        if (SelectedTranslationLanguage is null) { ErrorMessage = "请选择目标语言"; return; }
        UpdateTranslationEstimate();
        if (!await ConfirmChargeAsync(
                "确认翻译图片",
                $"将翻译 {TranslationImagePaths.Count} 张图片为 {SelectedTranslationLanguage.Name}，输出 {TranslationResolution}，预计扣除 {TranslationEstimatedCost} 积分。当前余额 {CurrentUser?.Credits ?? 0} 积分。")) return;

        await RunBusyAsync("正在创建图片翻译任务", async () =>
        {
            await _api.UploadTranslationAsync(
                SelectedTranslationLanguage.Code,
                TranslationResolution,
                TranslationImagePaths.ToArray(),
                $"translation:{Guid.NewGuid():N}");
            TranslationImagePaths.Clear();
            UpdateTranslationEstimate();
            SuccessMessage = "图片翻译任务已创建，原图与译图会逐张对应";
            await RefreshProfileAsync();
            SelectedPageIndex = HistoryPage;
            PageTitle = "历史记录";
            await LoadHistoryAsync();
        });
    }

    [RelayCommand]
    private void SelectAiMode(string? value)
    {
        SelectedAiModeIndex = int.TryParse(value, out var index) ? Math.Clamp(index, 0, 2) : 0;
    }

    [RelayCommand]
    private void SetFreeStyle(string? style)
    {
        if (!string.IsNullOrWhiteSpace(style)) FreeStyle = style.Trim();
    }

    [RelayCommand]
    private async Task GenerateFreeCreativeAsync()
    {
        var prompt = FreePrompt.Trim();
        if (prompt.Length < 4) { ErrorMessage = "提示词至少 4 个字符"; return; }
        var validation = ImageFilePolicy.Validate(FreeReferencePaths, 10);
        if (validation is not null) { ErrorMessage = validation; return; }
        var cost = FreeEstimatedCost;
        if (!await ConfirmChargeAsync("确认自由创作", $"输出 {FreeSize}，本次预计扣除 {cost} 积分，当前余额 {CurrentUser?.Credits ?? 0} 积分。")) return;

        await RunBusyAsync("正在创建自由创作任务", async () =>
        {
            var key = $"free-creative:{Guid.NewGuid():N}";
            if (FreeReferencePaths.Count == 0)
            {
                FreeCreativeDetail = await _api.CreateCreativeImageAsync(new
                {
                    product_name = "自由创作", brand = FreeStyle.Trim(), prompt, size = FreeSize,
                    language = FreeLanguage, module = FreeStyle.Trim(), reference_url = "",
                    intent = "generate", text_mode = "auto", reference_policy = "preserve_subject", template_id = ""
                }, key);
            }
            else
            {
                FreeCreativeDetail = await _api.CreateCreativeImageMultipartAsync(
                    SingleImageFields("自由创作", FreeStyle, prompt, FreeSize, FreeLanguage, FreeStyle),
                    FreeReferencePaths.ToArray(), key);
            }
            SuccessMessage = "自由创作任务已创建，结果会在当前页面自动更新";
            PublishKnownImageAssets();
            await RefreshProfileAsync();
        });
    }

    [RelayCommand]
    private async Task RefreshFreeCreativeAsync()
    {
        if (FreeCreativeDetail?.Project is not { } project) return;
        await RunBusyAsync("正在刷新创作结果", async () =>
        {
            FreeCreativeDetail = await _api.GetProjectAsync(project.Id);
            PublishKnownImageAssets();
        });
    }

    public async Task RefreshVisibleAiResultsAsync(CancellationToken cancellationToken = default)
    {
        if (!IsAuthenticated || IsBusy || SelectedPageIndex != AiPage) return;
        try
        {
            if (SelectedAiModeIndex == 0 && FreeCreativeDetail?.Project is { } project
                && FreeCreativeDetail.ImageTasks.Any(task => IsActiveGenerationStatus(task.Status)))
            {
                FreeCreativeDetail = await _api.GetProjectAsync(project.Id, cancellationToken);
                PublishKnownImageAssets();
            }
            else if (SelectedAiModeIndex == 1 && !string.IsNullOrWhiteSpace(ActiveAiChatId)
                && (SelectedCreativeChatDetail is null || SelectedCreativeChatDetail.Versions.Any(version => IsActiveGenerationStatus(version.Status))))
            {
                SelectedCreativeChatDetail = await _api.GetCreativeChatAsync(ActiveAiChatId, cancellationToken);
                PublishKnownImageAssets();
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            // Passive refresh failures remain silent; the explicit refresh action still reports errors.
        }
    }

    private static bool IsActiveGenerationStatus(string? status) =>
        status?.Trim().ToLowerInvariant() is
            "queued" or "pending" or "processing" or "running" or "submitting" or
            "submitted" or "generating" or "upscaling" or "quality_check";

    [RelayCommand]
    private async Task RetryFreeCreativeAsync(ImageTask? task)
    {
        var project = FreeCreativeDetail?.Project;
        if (project is null || task is null || !task.CanRetry) { ErrorMessage = "只有失败或超时的任务可以重新生成。"; return; }
        if (!await ConfirmChargeAsync("确认重新生成", $"重新生成“{task.ImageName}”将按服务端记录扣除 {task.CreditsCost} 积分。")) return;
        await RunBusyAsync("正在重新提交图片", async () =>
        {
            await _api.RetryProjectImageAsync(project.Id, task.Id);
            FreeCreativeDetail = await _api.GetProjectAsync(project.Id);
            await RefreshProfileAsync();
            SuccessMessage = "图片已重新排队";
        });
    }

    [RelayCommand]
    private async Task EstimateAiAsync()
    {
        if (AiPrompt.Trim().Length < 4) { ErrorMessage = "提示词至少 4 个字符"; return; }
        await RunBusyAsync("正在估算积分", async () =>
        {
            var estimate = await _api.EstimateCreativeAsync(AiPrompt.Trim(), AiSize, AiLanguage);
            AiEstimatedCost = estimate.CreditsCost;
            AiEstimateText = $"{estimate.QualityLabel} · {estimate.Size} · {estimate.CreditsCost} 积分";
        });
    }

    [RelayCommand]
    private async Task GenerateAiAsync()
    {
        if (AiPrompt.Trim().Length < 4) { ErrorMessage = "提示词至少 4 个字符"; return; }
        if (AiEstimatedCost <= 0) await EstimateAiAsync();
        if (AiEstimatedCost <= 0) return;
        if (!await ConfirmChargeAsync("确认生成图片", $"本次预计扣除 {AiEstimatedCost} 积分，当前余额 {CurrentUser?.Credits ?? 0} 积分。")) return;

        await RunBusyAsync("正在创建生图任务", async () =>
        {
            var key = $"creative:{Guid.NewGuid():N}";
            if (AiReferencePaths.Count == 0)
            {
                var result = await _api.GenerateCreativeAsync(new
                {
                    chat_id = ActiveAiChatId,
                    message = AiPrompt.Trim(),
                    style = AiStyle.Trim(),
                    size = AiSize,
                    language = AiLanguage,
                    reference_urls = Array.Empty<string>(),
                    intent = "generate",
                    text_mode = "auto",
                    reference_policy = "preserve_subject"
                }, key);
                ApplyGeneratedChat(result);
            }
            else
            {
                var fields = new Dictionary<string, string>
                {
                    ["message"] = AiPrompt.Trim(), ["style"] = AiStyle.Trim(), ["size"] = AiSize,
                    ["language"] = AiLanguage, ["intent"] = "generate", ["textMode"] = "auto",
                    ["referencePolicy"] = "preserve_subject"
                };
                if (!string.IsNullOrWhiteSpace(ActiveAiChatId)) fields["chatId"] = ActiveAiChatId;
                var result = await _api.GenerateCreativeMultipartAsync(fields, AiReferencePaths.ToArray(), key);
                ApplyGeneratedChat(result);
            }
            SuccessMessage = "图片任务已创建，可继续创作或前往历史记录";
            await RefreshProfileAsync();
            await LoadAiAsync();
            if (!string.IsNullOrWhiteSpace(ActiveAiChatId))
            {
                SelectedCreativeChatDetail = await _api.GetCreativeChatAsync(ActiveAiChatId);
                PublishKnownImageAssets();
            }
        });
    }

    [RelayCommand]
    private async Task SendAssistantAsync()
    {
        var input = AssistantInput.Trim();
        if (!AssistantEnabled) { ErrorMessage = "文案 Agent 当前不可用，请稍后刷新。"; return; }
        if (input.Length < 2) { ErrorMessage = "请告诉文案 Agent 你想完成什么。"; return; }
        if (input.Length > 4000) { ErrorMessage = "单次问题不能超过 4000 个字符。"; return; }

        await RunBusyAsync("文案 Agent 正在整理", async () =>
        {
            AssistantConversation.Add(new AssistantConversationItem("user", input));
            var messages = AssistantConversation
                .TakeLast(12)
                .Select(x => new AssistantChatMessage(x.Role, x.Content))
                .ToArray();
            var result = await _api.ChatWithAssistantAsync(messages, ProfileLanguage, AssistantModel, new
            {
                client = "windows",
                pathname = "/ai-create",
                tab = "copy",
                task = "copywriting_agent",
                logged_in = true
            });
            AssistantConversation.Add(new AssistantConversationItem("assistant", string.IsNullOrWhiteSpace(result.Reply) ? "我暂时没有整理出有效答复，请换一种说法再试。" : result.Reply.Trim()));
            while (AssistantConversation.Count > 20) AssistantConversation.RemoveAt(0);
            AssistantSuggestedPrompt = result.SuggestedPrompt?.Trim() ?? "";
            AssistantActionLabel = result.ActionLabel?.Trim() ?? "";
            AssistantActionHref = result.ActionHref?.Trim() ?? "";
            AssistantInput = "";
        });
    }

    [RelayCommand]
    private void ClearAssistantConversation()
    {
        AssistantConversation.Clear();
        AssistantSuggestedPrompt = "";
        AssistantActionLabel = "";
        AssistantActionHref = "";
        SuccessMessage = "文案 Agent 对话已清空";
    }

    [RelayCommand]
    private void ApplyAssistantSuggestion()
    {
        if (!AssistantHasSuggestion) return;
        FreePrompt = AssistantSuggestedPrompt;
        SelectedAiModeIndex = 0;
        SuccessMessage = "建议提示词已放入自由创作输入框";
    }

    [RelayCommand]
    private void OpenAssistantAction()
    {
        if (!AssistantHasAction) return;
        ExternalUrlRequested?.Invoke(this, AssistantActionHref);
    }

    [RelayCommand]
    private async Task PreviewVideoScriptAsync()
    {
        if (SelectedVideoModel is null || VideoPrompt.Trim().Length < 4) { ErrorMessage = "请选择模型，并输入至少 4 个字符的提示词"; return; }
        await RunBusyAsync("正在优化商品视频脚本", async () =>
        {
            var result = await _api.PreviewVideoScriptAsync(new
            {
                prompt = VideoPrompt.Trim(), model_code = SelectedVideoModel.Code, duration_seconds = VideoDuration,
                resolution = VideoResolution, aspect_ratio = VideoRatio, generate_audio = VideoGenerateAudio,
                reference_count = VideoReferencePaths.Count
            });
            VideoScriptPreview = String(result, "optimized_prompt", VideoPrompt.Trim());
        });
    }

    [RelayCommand]
    private async Task GenerateVideoAsync()
    {
        if (SelectedVideoModel is null || VideoPrompt.Trim().Length < 4) { ErrorMessage = "请选择模型，并输入至少 4 个字符的提示词"; return; }
        UpdateVideoCost();
        if (VideoEstimatedCost <= 0) { ErrorMessage = "当前参数没有可用售价，请更换模型参数"; return; }
        if (!await ConfirmChargeAsync("确认生成商品视频", $"将生成 1 条 {VideoDuration} 秒 {VideoResolution} 视频，预计扣除 {VideoEstimatedCost} 积分。当前余额 {CurrentUser?.Credits ?? 0} 积分。")) return;

        await RunBusyAsync("正在创建视频任务", async () =>
        {
            var idempotencyKey = Guid.NewGuid().ToString("N");
            var fields = new Dictionary<string, string>
            {
                ["title"] = string.IsNullOrWhiteSpace(VideoTitle) ? "商品展示视频" : VideoTitle.Trim(),
                ["prompt"] = string.IsNullOrWhiteSpace(VideoScriptPreview) ? VideoPrompt.Trim() : VideoScriptPreview,
                ["original_prompt"] = VideoPrompt.Trim(), ["aspect_ratio"] = VideoRatio,
                ["model_code"] = SelectedVideoModel.Code, ["duration_seconds"] = VideoDuration.ToString(),
                ["resolution"] = VideoResolution, ["generate_audio"] = VideoGenerateAudio.ToString().ToLowerInvariant()
            };
            await _api.CreateVideoAsync(fields, VideoReferencePaths.Take(SelectedVideoModel.MaxReferenceImages).ToArray(), idempotencyKey);
            SuccessMessage = "视频任务已创建，可离开本页，任务会在后台继续";
            await RefreshProfileAsync();
            await LoadVideoAsync();
        });
    }

    [RelayCommand]
    private async Task RedeemAsync()
    {
        if (string.IsNullOrWhiteSpace(RedeemCode)) { ErrorMessage = "请输入兑换码"; return; }
        if (!await ConfirmChargeAsync("确认兑换", "兑换码只会使用一次，是否继续？")) return;
        await RunBusyAsync("正在兑换", async () =>
        {
            await _api.RedeemAsync(RedeemCode.Trim(), $"redeem:{RedeemCode.Trim()}");
            RedeemCode = "";
            SuccessMessage = "兑换成功";
            await RefreshProfileAsync();
            await LoadBillingAsync();
        });
    }

    [RelayCommand]
    private async Task PurchasePlanAsync(RechargePlan? plan)
    {
        var channel = PaymentChannels.FirstOrDefault();
        if (plan is null || channel is null) { ErrorMessage = "当前没有可用支付渠道"; return; }
        if (!await ConfirmChargeAsync("确认创建充值订单", $"套餐：{plan.Name}，{plan.Credits} 积分，支付 {plan.PriceText}。支付渠道：{channel.Name}。")) return;
        await RunBusyAsync("正在创建充值订单", async () =>
        {
            var result = await _api.CreateRechargeOrderAsync(plan.Id, channel.Code, $"recharge:{Guid.NewGuid():N}");
            var url = String(result, "payment_url");
            if (string.IsNullOrWhiteSpace(url) && result.TryGetProperty("payment_links", out var links) && links.ValueKind == JsonValueKind.Array)
                url = links.EnumerateArray().Select(x => String(x, "url")).FirstOrDefault(x => !string.IsNullOrWhiteSpace(x)) ?? "";
            if (string.IsNullOrWhiteSpace(url)) { SuccessMessage = String(result, "payment_hint", "订单已创建，请按页面提示完成支付"); return; }
            ExternalUrlRequested?.Invoke(this, url);
            SuccessMessage = "支付页面已打开，完成后返回 PicPilot 刷新余额";
        });
    }

    [RelayCommand]
    private async Task SyncRechargeOrderAsync(RechargeOrder? order)
    {
        if (order is null || !order.Status.Equals("pending", StringComparison.OrdinalIgnoreCase)) return;
        await RunBusyAsync("正在同步充值订单", async () =>
        {
            await _api.SyncRechargeOrderAsync(order.Id, $"billing-sync:{order.Id}");
            await LoadBillingAsync();
            SuccessMessage = "订单状态已同步";
        });
    }

    [RelayCommand]
    private async Task CancelRechargeOrderAsync(RechargeOrder? order)
    {
        if (order is null || !order.Status.Equals("pending", StringComparison.OrdinalIgnoreCase)) return;
        if (!await ConfirmChargeAsync("关闭充值订单", $"确认关闭订单 {order.Id}？已经支付成功的订单不会因此失效。")) return;
        await RunBusyAsync("正在关闭充值订单", async () =>
        {
            await _api.CancelRechargeOrderAsync(order.Id, $"billing-cancel:{order.Id}");
            await LoadBillingAsync();
            SuccessMessage = "订单已更新";
        });
    }

    [RelayCommand]
    private async Task BillingPageAsync(string? direction)
    {
        var next = direction == "next" ? BillingPageNumber + 1 : BillingPageNumber - 1;
        if (next < 1 || next > BillingTotalPages) return;
        BillingPageNumber = next;
        await RunBusyAsync("正在加载订单", () => LoadBillingOrdersAsync());
    }

    [RelayCommand]
    private async Task SaveProfileAsync()
    {
        await RunBusyAsync("正在保存资料", async () =>
        {
            await _api.UpdateProfileAsync(ProfileNickname.Trim(), ProfileLanguage, ProfileTheme);
            await RefreshProfileAsync();
            SuccessMessage = "个人资料已保存";
        });
    }

    [RelayCommand]
    private async Task MarkMessageReadAsync(StationMessage? message)
    {
        if (message is null || message.Read) return;
        await RunBusyAsync("正在更新消息", async () =>
        {
            await _api.MarkMessageReadAsync(message.Id);
            await LoadMessagesAsync();
        });
    }

    [RelayCommand]
    private async Task OpenProjectAsync(ProjectSummary? project)
    {
        if (project is null) { ErrorMessage = "没有找到要打开的项目。"; return; }
        await RunBusyAsync("正在加载项目详情", async () =>
        {
            SelectedProjectDetail = await _api.GetProjectAsync(project.Id);
            SelectedCreativeChatDetail = null;
            IsDetailOpen = true;
            PublishKnownImageAssets();
        });
    }

    [RelayCommand]
    private async Task OpenCreativeChatAsync(CreativeChatSummary? chat)
    {
        if (chat is null) { ErrorMessage = "没有找到要打开的对话。"; return; }
        await RunBusyAsync("正在加载对话详情", async () =>
        {
            SelectedCreativeChatDetail = await _api.GetCreativeChatAsync(chat.Id);
            SelectedProjectDetail = null;
            IsDetailOpen = true;
            PublishKnownImageAssets();
        });
    }

    [RelayCommand]
    private async Task ContinueCreativeChatAsync(CreativeChatSummary? chat)
    {
        if (chat is null) { ErrorMessage = "没有找到要继续的对话。"; return; }
        await RunBusyAsync("正在打开创作对话", async () =>
        {
            ActiveAiChatId = chat.Id;
            ActiveAiChatTitle = chat.Title;
            AiStyle = string.IsNullOrWhiteSpace(chat.Style) ? AiStyle : chat.Style;
            AiSize = AiSizes.Contains(chat.Size) ? chat.Size : AiSize;
            AiLanguage = string.IsNullOrWhiteSpace(chat.Language) ? AiLanguage : chat.Language;
            AiPrompt = "";
            AiEstimatedCost = 0;
            AiEstimateText = "已续接该对话，输入下一步修改要求后估算积分";
            SelectedCreativeChatDetail = await _api.GetCreativeChatAsync(chat.Id);
            SelectedProjectDetail = null;
            IsDetailOpen = false;
            SelectedPageIndex = AiPage;
            SelectedAiModeIndex = 1;
            PageTitle = "AI 创作";
            SuccessMessage = $"正在继续“{chat.Title}”";
        });
    }

    [RelayCommand]
    private void NewAiChat()
    {
        ActiveAiChatId = "";
        ActiveAiChatTitle = "新对话";
        AiPrompt = "";
        AiReferencePaths.Clear();
        AiEstimatedCost = 0;
        AiEstimateText = "输入明确需求后估算积分";
        SuccessMessage = "已开始新对话";
    }

    [RelayCommand]
    private async Task DeleteProjectAsync(ProjectSummary? project)
    {
        if (project is null) { ErrorMessage = "没有找到要移除的项目。"; return; }
        if (!await ConfirmChargeAsync("从历史记录移除", $"确认移除“{project.DisplayName}”？这只会隐藏你的记录，管理员审计数据不会被删除。")) return;
        await RunBusyAsync("正在移除项目", async () =>
        {
            await _api.DeleteProjectAsync(project.Id);
            Projects.Remove(project);
            if (SelectedProjectDetail?.Project.Id == project.Id) CloseDetail();
            RefreshHistoryViews();
            SuccessMessage = "项目已从你的历史记录移除";
        });
    }

    [RelayCommand]
    private void CloseDetail()
    {
        IsDetailOpen = false;
        SelectedProjectDetail = null;
        SelectedCreativeChatDetail = null;
    }

    [RelayCommand]
    private void PreviewImage(ImageTask? task)
    {
        if (task is null) { ErrorMessage = "没有找到图片任务。"; return; }
        if (!task.HasResult) { ErrorMessage = task.ResultActionHint; return; }
        AssetActionRequested?.Invoke(this, new AssetActionRequest("preview", _api.AssetUri(task.ResultUrl), task.ImageName));
    }

    [RelayCommand]
    private void DownloadImage(ImageTask? task)
    {
        if (task is null) { ErrorMessage = "没有找到图片任务。"; return; }
        if (!task.HasResult) { ErrorMessage = task.ResultActionHint; return; }
        AssetActionRequested?.Invoke(this, new AssetActionRequest("download", _api.AssetUri(task.ResultUrl), task.ImageName));
    }

    [RelayCommand]
    private void OpenVideo(VideoTask? task)
    {
        if (task is null) { ErrorMessage = "没有找到视频任务。"; return; }
        if (!task.HasResult) { ErrorMessage = task.ResultActionHint; return; }
        AssetActionRequested?.Invoke(this, new AssetActionRequest("open", _api.AssetUri(task.ResultUrl), task.Title));
    }

    [RelayCommand]
    private async Task RetryImageAsync(ImageTask? task)
    {
        var project = SelectedProjectDetail?.Project;
        if (project is null || task is null) { ErrorMessage = "请先打开对应项目详情。"; return; }
        if (!task.CanRetry) { ErrorMessage = "只有失败或超时的图片任务可以重新生成。"; return; }
        if (!await ConfirmChargeAsync("确认重新生成", $"重新生成“{task.ImageName}”将按服务端记录扣除 {task.CreditsCost} 积分。当前余额 {CurrentUser?.Credits ?? 0} 积分。")) return;
        await RunBusyAsync("正在重新提交图片", async () =>
        {
            await _api.RetryProjectImageAsync(project.Id, task.Id);
            SelectedProjectDetail = await _api.GetProjectAsync(project.Id);
            await RefreshProfileAsync();
            SuccessMessage = "图片已重新排队";
        });
    }

    [RelayCommand]
    private async Task HideImageAsync(ImageTask? task)
    {
        var project = SelectedProjectDetail?.Project;
        if (project is null || task is null) { ErrorMessage = "请先打开对应项目详情。"; return; }
        if (!task.CanHide) { ErrorMessage = "任务生成期间不能从列表移除。"; return; }
        if (!await ConfirmChargeAsync("从列表移除图片", $"确认移除“{task.ImageName}”？该操作不会删除管理员审计记录。")) return;
        await RunBusyAsync("正在更新图片记录", async () =>
        {
            await _api.HideProjectImageAsync(project.Id, task.Id);
            SelectedProjectDetail = await _api.GetProjectAsync(project.Id);
            SuccessMessage = "图片已从你的列表移除";
        });
    }

    [RelayCommand]
    private async Task HideVideoAsync(VideoTask? task)
    {
        if (task is null) { ErrorMessage = "没有找到视频任务。"; return; }
        if (!task.CanHide) { ErrorMessage = "视频生成期间不能从列表移除。"; return; }
        if (!await ConfirmChargeAsync("从历史记录移除", $"确认移除视频“{task.Title}”？该操作不会删除管理员审计记录。")) return;
        await RunBusyAsync("正在更新视频记录", async () =>
        {
            await _api.HideVideoAsync(task.Id);
            VideoTasks.Remove(task);
            RefreshHistoryViews();
            SuccessMessage = "视频已从你的历史记录移除";
        });
    }

    [RelayCommand]
    private void PreviewProjectCover(ProjectSummary? project)
    {
        if (project is null || !project.HasCover) { ErrorMessage = "这个项目还没有可预览的完成图片。"; return; }
        AssetActionRequested?.Invoke(this, new AssetActionRequest("preview", _api.AssetUri(project.CoverUrl), project.DisplayName));
    }

    [RelayCommand]
    private void PreviewCreativeChatCover(CreativeChatSummary? chat)
    {
        if (chat is null || !chat.HasResult) { ErrorMessage = "这个对话还没有可预览的图片。"; return; }
        AssetActionRequested?.Invoke(this, new AssetActionRequest("preview", _api.AssetUri(chat.LatestResultUrl), chat.Title));
    }

    [RelayCommand]
    private void PreviewChatMessage(CreativeChatMessage? message)
    {
        if (message is null || !message.HasResult) { ErrorMessage = "这条消息还没有可预览的图片。"; return; }
        AssetActionRequested?.Invoke(this, new AssetActionRequest("preview", _api.AssetUri(message.ResultUrl), $"AI创作-v{message.VersionNo}"));
    }

    [RelayCommand]
    private void DownloadChatMessage(CreativeChatMessage? message)
    {
        if (message is null || !message.HasResult) { ErrorMessage = "这条消息还没有可下载的图片。"; return; }
        AssetActionRequested?.Invoke(this, new AssetActionRequest("download", _api.AssetUri(message.ResultUrl), $"AI创作-v{message.VersionNo}"));
    }

    [RelayCommand]
    private void PreviewChatVersion(CreativeChatVersion? version)
    {
        if (version is null || !version.HasResult) { ErrorMessage = "这个版本还没有可预览的图片。"; return; }
        AssetActionRequested?.Invoke(this, new AssetActionRequest("preview", _api.AssetUri(version.ResultUrl), $"AI创作-v{version.VersionNo}"));
    }

    [RelayCommand]
    private void DownloadChatVersion(CreativeChatVersion? version)
    {
        if (version is null || !version.HasResult) { ErrorMessage = "这个版本还没有可下载的图片。"; return; }
        AssetActionRequested?.Invoke(this, new AssetActionRequest("download", _api.AssetUri(version.ResultUrl), $"AI创作-v{version.VersionNo}"));
    }

    public void AddCommerceReferences(IEnumerable<string> paths) => AddImages(CommerceReferencePaths, paths, 10);
    public void AddListingReferences(IEnumerable<string> paths) => AddImages(ListingReferencePaths, paths, 10);
    public void AddFreeReferences(IEnumerable<string> paths) => AddImages(FreeReferencePaths, paths, 10);
    public void AddAiReferences(IEnumerable<string> paths) => AddImages(AiReferencePaths, paths, 10);
    public void AddVideoReferences(IEnumerable<string> paths) => AddImages(VideoReferencePaths, paths, SelectedVideoModel?.MaxReferenceImages ?? 3);
    public void AddTranslationImages(IEnumerable<string> paths) { AddImages(TranslationImagePaths, paths, ImageFilePolicy.MaxReferenceFiles); UpdateTranslationEstimate(); }
    public void ClearCommerceReferences() => CommerceReferencePaths.Clear();
    public void ClearListingReferences() { ListingReferencePaths.Clear(); _listingUploadedPaths.Clear(); }
    public void SetAplusReference(string? path) => AplusReferencePath = path ?? "";
    public void SetDomesticReference(string? path) => DomesticReferencePath = path ?? "";
    public void ClearFreeReferences() => FreeReferencePaths.Clear();
    public void ClearAiReferences() => AiReferencePaths.Clear();
    public void ClearVideoReferences() => VideoReferencePaths.Clear();
    public void ClearTranslationImages() { TranslationImagePaths.Clear(); UpdateTranslationEstimate(); }

    private void SetAuthenticated(UserProfile profile)
    {
        CurrentUser = profile;
        IsAuthenticated = true;
        DashboardCredits = profile.Credits;
        ProfileNickname = profile.Nickname;
        ProfileLanguage = string.IsNullOrWhiteSpace(profile.UiLanguage) ? "zh-CN" : profile.UiLanguage;
        ProfileTheme = string.IsNullOrWhiteSpace(profile.Theme) ? "system" : profile.Theme;
    }

    private async Task LoadWorkspaceAsync(CancellationToken cancellationToken = default)
    {
        var projects = await _api.GetProjectsAsync(cancellationToken);
        Projects.ReplaceWith(projects.OrderByDescending(x => x.UpdatedAt));
        DashboardProjectCount = Projects.Count;
        DashboardRunningCount = Projects.Sum(x => x.RunningImages);
        DashboardCompletedCount = Projects.Sum(x => x.SuccessImages);
        PublishKnownImageAssets();
        await RefreshProfileAsync(cancellationToken);
    }

    private async Task LoadHistoryAsync(CancellationToken cancellationToken = default)
    {
        var projectsTask = _api.GetProjectsAsync(cancellationToken);
        var videosTask = _api.GetVideoTasksAsync(cancellationToken);
        var chatsTask = _api.GetCreativeChatsAsync(cancellationToken);
        await Task.WhenAll(projectsTask, videosTask, chatsTask);
        Projects.ReplaceWith(projectsTask.Result.OrderByDescending(x => x.UpdatedAt));
        VideoTasks.ReplaceWith(videosTask.Result.OrderByDescending(x => x.UpdatedAt));
        CreativeChats.ReplaceWith(chatsTask.Result.OrderByDescending(x => x.UpdatedAt));
        RefreshHistoryViews();
        PublishKnownImageAssets();
    }

    private async Task LoadCommerceAsync(CancellationToken cancellationToken = default)
    {
        var catalogTask = _api.GetCommerceCatalogAsync(cancellationToken);
        var languagesTask = _api.GetTranslationLanguagesAsync(cancellationToken);
        await Task.WhenAll(catalogTask, languagesTask);
        var catalog = catalogTask.Result;
        var currentPlatform = SelectedCommercePlatform?.Code;
        CommercePlatforms.ReplaceWith(catalog.Platforms.Where(x => x.Enabled));
        CommerceSlots.ReplaceWith(catalog.Slots.OrderBy(x => x.SortOrder).Select(x => new SlotChoice(x.Code, x.Name, x.Description)));
        SelectedCommercePlatform = CommercePlatforms.FirstOrDefault(x => x.Code == currentPlatform) ?? CommercePlatforms.FirstOrDefault();
        var languageCode = SelectedTranslationLanguage?.Code;
        TranslationLanguages.ReplaceWith(languagesTask.Result);
        SelectedTranslationLanguage = TranslationLanguages.FirstOrDefault(x => x.Code == languageCode)
            ?? TranslationLanguages.FirstOrDefault(x => x.Code == "ms-MY")
            ?? TranslationLanguages.FirstOrDefault();
        UpdateTranslationEstimate();
    }

    private async Task LoadAiAsync(CancellationToken cancellationToken = default)
    {
        var chatsTask = _api.GetCreativeChatsAsync(cancellationToken);
        var assistantTask = LoadAssistantStatusAsync(cancellationToken);
        await Task.WhenAll(chatsTask, assistantTask);
        var chats = chatsTask.Result;
        CreativeChats.ReplaceWith(chats.OrderByDescending(x => x.UpdatedAt));
        PublishKnownImageAssets();
    }

    private async Task LoadAssistantStatusAsync(CancellationToken cancellationToken)
    {
        try
        {
            var status = await _api.GetAssistantStatusAsync(cancellationToken);
            AssistantEnabled = status.Enabled;
            AssistantModels.ReplaceWith((status.Models ?? []).Where(x => !string.IsNullOrWhiteSpace(x)).Distinct(StringComparer.OrdinalIgnoreCase));
            if (AssistantModels.Count == 0 && !string.IsNullOrWhiteSpace(status.Model)) AssistantModels.Add(status.Model);
            AssistantModel = AssistantModels.FirstOrDefault(x => x.Equals(status.Model, StringComparison.OrdinalIgnoreCase))
                ?? AssistantModels.FirstOrDefault()
                ?? "gpt-5.5";
            AssistantStatusText = AssistantEnabled ? $"已连接 · {AssistantModel}" : "管理员暂未启用文案 Agent";
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            AssistantEnabled = false;
            AssistantStatusText = "文案 Agent 暂时不可用，生图功能不受影响";
        }
    }

    private async Task LoadVideoAsync(CancellationToken cancellationToken = default)
    {
        var configTask = _api.GetVideoConfigAsync(cancellationToken);
        var tasksTask = _api.GetVideoTasksAsync(cancellationToken);
        await Task.WhenAll(configTask, tasksTask);
        ParseVideoConfig(configTask.Result);
        VideoTasks.ReplaceWith(tasksTask.Result.OrderByDescending(x => x.UpdatedAt));
    }

    private async Task LoadBillingAsync(CancellationToken cancellationToken = default)
    {
        var plansTask = _api.GetRechargePlansAsync(cancellationToken);
        var channelsTask = _api.GetPaymentChannelsAsync(cancellationToken);
        var ledgerTask = _api.GetCreditLedgerAsync(ct: cancellationToken);
        await Task.WhenAll(plansTask, channelsTask, ledgerTask);
        RechargePlans.ReplaceWith(plansTask.Result.Where(x => x.Enabled).OrderBy(x => x.SortOrder));
        PaymentChannels.ReplaceWith(channelsTask.Result.Where(x => x.Enabled && x.Visible).OrderBy(x => x.SortOrder));
        CreditLedger.ReplaceWith(ledgerTask.Result.Rows);
        await LoadBillingOrdersAsync(cancellationToken);
        await RefreshProfileAsync(cancellationToken);
    }

    private async Task LoadBillingOrdersAsync(CancellationToken cancellationToken = default)
    {
        var page = await _api.GetRechargeOrdersAsync(BillingPageNumber, 8, ct: cancellationToken);
        RechargeOrders.ReplaceWith(page.Rows);
        BillingPageNumber = Math.Max(1, page.Page);
        BillingTotalPages = Math.Max(1, page.TotalPages);
    }

    private async Task LoadMessagesAsync(CancellationToken cancellationToken = default) =>
        Messages.ReplaceWith((await _api.GetMessagesAsync(cancellationToken)).OrderByDescending(x => x.PublishedAt));

    private async Task RefreshProfileAsync(CancellationToken cancellationToken = default)
    {
        var me = await _api.GetMeAsync(cancellationToken);
        _session.SetProfile(me);
        SetAuthenticated(me);
    }

    private void ParseVideoConfig(JsonElement config)
    {
        var current = SelectedVideoModel?.Code;
        var models = new List<VideoModelChoice>();
        if (config.TryGetProperty("model_configs", out var rows) && rows.ValueKind == JsonValueKind.Array)
        {
            foreach (var row in rows.EnumerateArray())
            {
                if (!Bool(row, "enabled", true)) continue;
                var variants = new List<VideoVariant>();
                if (row.TryGetProperty("variants", out var variantsJson) && variantsJson.ValueKind == JsonValueKind.Array)
                    foreach (var item in variantsJson.EnumerateArray()) variants.Add(new VideoVariant(String(item, "resolution"), Int(item, "duration_seconds"), Int(item, "price"), Bool(item, "enabled", true)));
                models.Add(new VideoModelChoice(
                    String(row, "code"), String(row, "name"), String(row, "description"),
                    String(row, "default_resolution", "720p"), Int(row, "default_duration_seconds", 10),
                    Bool(row, "audio_toggle"), Int(row, "max_reference_images", 3),
                    Strings(row, "resolutions"), Ints(row, "durations"), Strings(row, "aspect_ratios"), variants));
            }
        }
        VideoModels.ReplaceWith(models);
        SelectedVideoModel = VideoModels.FirstOrDefault(x => x.Code == current) ?? VideoModels.FirstOrDefault();
    }

    private void UpdateVideoCost()
    {
        VideoEstimatedCost = SelectedVideoModel?.Variants.FirstOrDefault(x => x.Enabled && x.Resolution.Equals(VideoResolution, StringComparison.OrdinalIgnoreCase) && x.Duration == VideoDuration)?.Price ?? 0;
    }

    private void UpdateTranslationEstimate()
    {
        var unit = TranslationResolution.ToUpperInvariant() switch { "2K" => 24, "4K" => 30, _ => 6 };
        TranslationEstimatedCost = TranslationImagePaths.Count * unit;
        TranslationEstimateText = TranslationImagePaths.Count == 0
            ? "选择图片后显示费用"
            : $"{TranslationImagePaths.Count} 张 · {unit} 积分/张 · 共 {TranslationEstimatedCost} 积分";
    }

    private bool FilterProject(object item)
    {
        if (item is not ProjectSummary project) return false;
        if (!MatchesStatus(project.Status)) return false;
        return MatchesSearch(project.Id, project.ProductName, project.Brand, project.Country, project.Status);
    }

    private bool FilterVideo(object item)
    {
        if (item is not VideoTask task) return false;
        if (!MatchesStatus(task.Status)) return false;
        return MatchesSearch(task.Id, task.Title, task.Model, task.Status);
    }

    private bool FilterChat(object item)
    {
        if (item is not CreativeChatSummary chat) return false;
        if (!MatchesStatus(chat.LatestStatus)) return false;
        return MatchesSearch(chat.Id, chat.Title, chat.Style, chat.LatestStatus);
    }

    private bool MatchesStatus(string status) => HistoryStatus == "all" || status.Equals(HistoryStatus, StringComparison.OrdinalIgnoreCase)
        || HistoryStatus == "running" && status is "queued" or "submitting" or "submitted" or "processing" or "running" or "generating"
        || HistoryStatus == "completed" && status is "completed" or "success" or "succeeded"
        || HistoryStatus == "failed" && status is "failed" or "failure" or "timeout";

    private bool MatchesSearch(params string?[] fields)
    {
        var search = HistorySearch.Trim();
        return search.Length == 0 || fields.Any(value => (value ?? "").Contains(search, StringComparison.OrdinalIgnoreCase));
    }

    private void RefreshHistoryViews()
    {
        ProjectsView.Refresh();
        VideoTasksView.Refresh();
        CreativeChatsView.Refresh();
    }

    public IReadOnlyList<ImageAssetCandidate> GetKnownImageAssets()
    {
        var assets = new List<ImageAssetCandidate>();
        foreach (var project in Projects)
        {
            AddAsset(assets, project.CoverUrl, project.DisplayName);
            foreach (var url in project.PreviewUrls ?? []) AddAsset(assets, url, project.DisplayName);
        }
        foreach (var chat in CreativeChats) AddAsset(assets, chat.LatestResultUrl, chat.Title);
        if (FreeCreativeDetail is { } freeDetail)
        {
            foreach (var image in freeDetail.ProductImages) AddAsset(assets, image.Url, image.OriginalFilename);
            foreach (var task in freeDetail.ImageTasks) AddAsset(assets, task.ResultUrl, task.ImageName);
        }
        if (SelectedProjectDetail is { } projectDetail)
        {
            foreach (var image in projectDetail.ProductImages) AddAsset(assets, image.Url, image.OriginalFilename);
            foreach (var task in projectDetail.ImageTasks) AddAsset(assets, task.ResultUrl, task.ImageName);
        }
        if (SelectedCreativeChatDetail is { } chatDetail)
        {
            foreach (var message in chatDetail.Messages) AddAsset(assets, message.ResultUrl, $"{chatDetail.Chat.Title}-v{message.VersionNo}");
            foreach (var version in chatDetail.Versions) AddAsset(assets, version.ResultUrl, $"{chatDetail.Chat.Title}-v{version.VersionNo}");
        }
        return assets
            .GroupBy(x => x.Url, StringComparer.OrdinalIgnoreCase)
            .Select(x => x.First())
            .ToArray();
    }

    private void PublishKnownImageAssets()
    {
        var assets = GetKnownImageAssets();
        if (assets.Count > 0) ImageAssetsDiscovered?.Invoke(this, assets);
    }

    private void ApplyListingCopy(GenerateCopyResponse response)
    {
        ListingGeneratedCopy = JsonSerializer.Serialize(response.GeneratedCopy, PrettyJson);
        if (response.GeneratedCopy.ValueKind == JsonValueKind.Object
            && response.GeneratedCopy.TryGetProperty("confirmed_facts_candidate", out var facts)
            && facts.ValueKind == JsonValueKind.Object)
        {
            ListingGeneratedFacts = facts.Clone();
            ListingFactsText = JsonSerializer.Serialize(facts, PrettyJson);
        }
        else
        {
            using var empty = JsonDocument.Parse("{}");
            ListingGeneratedFacts = empty.RootElement.Clone();
            ListingFactsText = "{}";
        }
    }

    private void ApplyGeneratedChat(JsonElement result)
    {
        if (!result.TryGetProperty("chat", out var view) || view.ValueKind != JsonValueKind.Object) return;
        var summary = view.TryGetProperty("chat", out var nested) && nested.ValueKind == JsonValueKind.Object ? nested : view;
        var id = String(summary, "id");
        if (!string.IsNullOrWhiteSpace(id)) ActiveAiChatId = id;
        var title = String(summary, "title");
        if (!string.IsNullOrWhiteSpace(title)) ActiveAiChatTitle = title;
    }

    private async Task GoToHistoryAsync()
    {
        SelectedPageIndex = HistoryPage;
        PageTitle = "历史记录";
        await LoadHistoryAsync();
    }

    private static string[] Csv(string value) => value
        .Split([',', '，', '\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
        .Distinct(StringComparer.OrdinalIgnoreCase)
        .ToArray();

    private static int ImageCreditsForSize(string value)
    {
        var dimensions = value.Replace('×', 'x').Split('x');
        var maxEdge = dimensions.Select(item => int.TryParse(item, out var edge) ? edge : 0).DefaultIfEmpty().Max();
        return maxEdge >= 2800 ? 30 : maxEdge >= 1800 ? 24 : 6;
    }

    private static string ImageQualityForSize(string value) => ImageCreditsForSize(value) switch { 30 => "4K", 24 => "2K", _ => "1K" };

    private static Dictionary<string, string> SingleImageFields(string productName, string brand, string prompt, string size, string language, string module) => new()
    {
        ["productName"] = productName.Trim(),
        ["brand"] = brand.Trim(),
        ["prompt"] = prompt.Trim(),
        ["size"] = size,
        ["language"] = language,
        ["module"] = module,
        ["intent"] = "generate",
        ["textMode"] = "auto",
        ["referencePolicy"] = "preserve_subject"
    };

    private static void AddAsset(List<ImageAssetCandidate> assets, string? url, string suggestedName)
    {
        if (!string.IsNullOrWhiteSpace(url)) assets.Add(new ImageAssetCandidate(url.Trim(), suggestedName));
    }

    private object ProductInfo() => new
    {
        product_name = CommerceProductName.Trim(), category = CommerceCategory.Trim(), brand = CommerceBrand.Trim(),
        selling_points = CommerceSellingPoints.Split(['\r', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries),
        material = CommerceMaterial.Trim(), specs = CommerceSpecs.Trim(), audience = CommerceAudience.Trim(), scenario = CommerceScenario.Trim(),
        price_text = CommercePriceText.Trim(), promo_text = CommercePromoText.Trim(), country = SelectedCommercePlatform?.Code ?? "", language = "zh-CN"
    };

    private async Task<bool> ConfirmChargeAsync(string title, string description) => ConfirmAsync is null || await ConfirmAsync(title, description);

    private async Task RunBusyAsync(string text, Func<Task> action)
    {
        if (IsBusy)
        {
            ErrorMessage = $"“{BusyText}”尚未完成，请稍候。";
            return;
        }
        IsBusy = true; BusyText = text; ErrorMessage = ""; SuccessMessage = "";
        try { await action(); }
        catch (ApiException ex)
        {
            ErrorMessage = ex.SubmissionStateUnknown ? ex.Message + " 已自动停止重复提交。" : ex.Message;
            if (ex.StatusCode == 401 || ex.Code is "UNAUTHORIZED" or "TOKEN_INVALID") await SignOutAsync();
        }
        catch (Exception exception)
        {
            ClientDiagnostics.RecordFailure(exception);
            ErrorMessage = "操作没有完成，请检查网络后重试。";
        }
        finally { IsBusy = false; BusyText = "正在加载"; }
    }

    private async Task CountdownAsync()
    {
        while (CodeCooldown > 0)
        {
            await Task.Delay(1000);
            CodeCooldown--;
        }
    }

    private void AddImages(ObservableCollection<string> target, IEnumerable<string> paths, int limit)
    {
        var allowed = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif" };
        var selected = paths.Where(x => !string.IsNullOrWhiteSpace(x)).ToArray();
        if (selected.Length == 0) return;

        var added = 0;
        var skipped = new List<string>();
        foreach (var path in selected)
        {
            if (target.Count >= limit) { skipped.Add($"最多 {limit} 张"); break; }
            if (!File.Exists(path)) { skipped.Add("文件不存在"); continue; }
            if (!allowed.Contains(Path.GetExtension(path))) { skipped.Add("格式不支持"); continue; }
            if (target.Contains(path, StringComparer.OrdinalIgnoreCase)) { skipped.Add("重复图片"); continue; }
            var info = new FileInfo(path);
            if (info.Length <= 0) { skipped.Add("空文件或损坏"); continue; }
            if (info.Length > ImageFilePolicy.MaxSingleBytes) { skipped.Add($"单张超过 {ImageFilePolicy.MaxSingleBytes / 1024 / 1024}MB"); continue; }
            target.Add(path);
            added++;
        }
        if (skipped.Count > 0)
            ErrorMessage = $"已添加 {added} 张；另有图片未添加：{string.Join("、", skipped.Distinct())}。";
        else
            SuccessMessage = $"已添加 {added} 张图片";
    }

    private static int Int(JsonElement value, string property, int fallback = 0) => value.TryGetProperty(property, out var item) && item.TryGetInt32(out var result) ? result : fallback;
    private static bool Bool(JsonElement value, string property, bool fallback = false) => value.TryGetProperty(property, out var item) && item.ValueKind is JsonValueKind.True or JsonValueKind.False ? item.GetBoolean() : fallback;
    private static string String(JsonElement value, string property, string fallback = "") => value.TryGetProperty(property, out var item) && item.ValueKind == JsonValueKind.String ? item.GetString() ?? fallback : fallback;
    private static IReadOnlyList<string> Strings(JsonElement value, string property) => value.TryGetProperty(property, out var item) && item.ValueKind == JsonValueKind.Array ? item.EnumerateArray().Select(x => x.GetString() ?? "").Where(x => x.Length > 0).ToArray() : [];
    private static IReadOnlyList<int> Ints(JsonElement value, string property) => value.TryGetProperty(property, out var item) && item.ValueKind == JsonValueKind.Array ? item.EnumerateArray().Where(x => x.TryGetInt32(out _)).Select(x => x.GetInt32()).ToArray() : [];
}

public sealed partial class SlotChoice : ObservableObject
{
    public SlotChoice(string code, string name, string description) { Code = code; Name = name; Description = description; }
    public string Code { get; }
    public string Name { get; }
    public string Description { get; }
    [ObservableProperty] private bool isSelected;
}

public sealed record VideoVariant(string Resolution, int Duration, int Price, bool Enabled);
public sealed record VideoModelChoice(
    string Code, string Name, string Description, string DefaultResolution, int DefaultDuration, bool AudioToggle, int MaxReferenceImages,
    IReadOnlyList<string> Resolutions, IReadOnlyList<int> Durations, IReadOnlyList<string> AspectRatios, IReadOnlyList<VideoVariant> Variants)
{
    public override string ToString() => Name;
}

public sealed record CommerceWorkflowChoice(string Code, string Name, string Description)
{
    public override string ToString() => Name;
}

public sealed record AssistantConversationItem(string Role, string Content)
{
    public string DisplayRole => Role.Equals("user", StringComparison.OrdinalIgnoreCase) ? "我" : "PicPilot 文案 Agent";
}
public sealed record AssetActionRequest(string Action, Uri Uri, string SuggestedName);

internal static class CollectionExtensions
{
    public static void ReplaceWith<T>(this ObservableCollection<T> collection, IEnumerable<T> values)
    {
        collection.Clear();
        foreach (var value in values) collection.Add(value);
    }
}
