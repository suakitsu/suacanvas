using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace PicPilot.Core;

public sealed class PicPilotApiClient : IDisposable
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
    };

    private readonly HttpClient _http;
    private readonly ITokenStore _tokenStore;
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _submissionLocks = new(StringComparer.Ordinal);
    private readonly bool _ownsHttp;

    public PicPilotApiClient(Uri baseUri, ITokenStore tokenStore, HttpMessageHandler? handler = null)
    {
        if (!baseUri.IsAbsoluteUri) throw new ArgumentException("Base URL must be absolute", nameof(baseUri));
        if (baseUri.Scheme != Uri.UriSchemeHttps && !IsAllowedStaging(baseUri)) throw new ArgumentException("Production API must use HTTPS", nameof(baseUri));
        BaseUri = new Uri(baseUri.ToString().TrimEnd('/') + "/", UriKind.Absolute);
        _tokenStore = tokenStore;
        _ownsHttp = true;
        _http = handler is null ? new HttpClient() : new HttpClient(handler, disposeHandler: true);
        _http.BaseAddress = BaseUri;
        _http.Timeout = Timeout.InfiniteTimeSpan;
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("PicPilotWindows/2.0.2");
    }

    public Uri BaseUri { get; }
    public Uri AssetUri(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return new Uri(BaseUri, "/");
        return Uri.TryCreate(value, UriKind.Absolute, out var absolute) ? absolute : new Uri(BaseUri, value.TrimStart('/'));
    }

    public Task<AuthResponse> LoginAsync(string email, string password, bool rememberMe = true, string turnstileToken = "", CancellationToken ct = default) =>
        SendJsonAsync<AuthResponse>(HttpMethod.Post, "api/auth/login", new { email, password, remember_me = rememberMe, turnstile_token = turnstileToken }, false, TimeSpan.FromSeconds(30), ct);

    public Task<AuthResponse> LoginWithCodeAsync(string email, string verificationCode, bool rememberMe = true, string turnstileToken = "", CancellationToken ct = default) =>
        SendJsonAsync<AuthResponse>(HttpMethod.Post, "api/auth/login/email-code", new { email, verification_code = verificationCode, remember_me = rememberMe, turnstile_token = turnstileToken }, false, TimeSpan.FromSeconds(30), ct);

    public Task<JsonElement> SendEmailCodeAsync(string email, string purpose, string turnstileToken = "", CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "api/auth/email-code", new { email, purpose, turnstile_token = turnstileToken }, false, TimeSpan.FromSeconds(30), ct);

    public Task<AuthResponse> RegisterAsync(string email, string password, string confirmPassword, string code, string turnstileToken = "", CancellationToken ct = default) =>
        SendJsonAsync<AuthResponse>(HttpMethod.Post, "api/auth/register", new { email, password, confirm_password = confirmPassword, verification_code = code, turnstile_token = turnstileToken }, false, TimeSpan.FromSeconds(30), ct);

    public Task<JsonElement> ResetPasswordAsync(string email, string code, string password, string confirmPassword, string turnstileToken = "", CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "api/auth/password-reset", new { email, verification_code = code, password, confirm_password = confirmPassword, turnstile_token = turnstileToken }, false, TimeSpan.FromSeconds(30), ct);

    public Task<UserProfile> GetMeAsync(CancellationToken ct = default) => GetAsync<UserProfile>("api/me", ct);
    public Task<BillingSummary> GetBillingSummaryAsync(CancellationToken ct = default) => GetAsync<BillingSummary>("api/billing/summary", ct);
    public Task<IReadOnlyList<ProjectSummary>> GetProjectsAsync(CancellationToken ct = default) => GetAsync<IReadOnlyList<ProjectSummary>>("api/projects", ct);
    public Task<ProjectDetail> GetProjectAsync(string id, CancellationToken ct = default) => GetAsync<ProjectDetail>($"api/projects/{Escape(id)}", ct);
    public Task<IReadOnlyList<ImageTask>> GetProjectImagesAsync(string id, CancellationToken ct = default) => GetAsync<IReadOnlyList<ImageTask>>($"api/projects/{Escape(id)}/images", ct);
    public Task<IReadOnlyList<CreativeChatSummary>> GetCreativeChatsAsync(CancellationToken ct = default) => GetAsync<IReadOnlyList<CreativeChatSummary>>("api/projects/creative-chats", ct);
    public Task<CreativeChatDetail> GetCreativeChatAsync(string id, CancellationToken ct = default) => GetAsync<CreativeChatDetail>($"api/projects/creative-chats/{Escape(id)}", ct);
    public Task<IReadOnlyList<VideoTask>> GetVideoTasksAsync(CancellationToken ct = default) => GetAsync<IReadOnlyList<VideoTask>>("api/videos/tasks", ct);
    public Task<JsonElement> GetVideoConfigAsync(CancellationToken ct = default) => GetAsync<JsonElement>("api/videos/config", ct);
    public Task<CommerceCatalog> GetCommerceCatalogAsync(CancellationToken ct = default) => GetAsync<CommerceCatalog>("api/commerce-suite/catalog", ct);
    public Task<IReadOnlyList<TranslationLanguage>> GetTranslationLanguagesAsync(CancellationToken ct = default) => GetAsync<IReadOnlyList<TranslationLanguage>>("api/image-translation/languages", ct);
    public Task<IReadOnlyList<RechargePlan>> GetRechargePlansAsync(CancellationToken ct = default) => GetAsync<IReadOnlyList<RechargePlan>>("api/billing/plans", ct);
    public Task<IReadOnlyList<PaymentChannel>> GetPaymentChannelsAsync(CancellationToken ct = default) => GetAsync<IReadOnlyList<PaymentChannel>>("api/billing/channels", ct);
    public Task<IReadOnlyList<StationMessage>> GetMessagesAsync(CancellationToken ct = default) => GetAsync<IReadOnlyList<StationMessage>>("api/messages", ct);
    public Task<PageResult<CreditLedger>> GetCreditLedgerAsync(int page = 1, int pageSize = 20, CancellationToken ct = default) => GetAsync<PageResult<CreditLedger>>($"api/billing/ledger?page={page}&page_size={pageSize}", ct);
    public Task<PageResult<RechargeOrder>> GetRechargeOrdersAsync(int page = 1, int pageSize = 20, string status = "", CancellationToken ct = default)
    {
        var suffix = string.IsNullOrWhiteSpace(status) || status == "all" ? "" : $"&status={Escape(status)}";
        return GetAsync<PageResult<RechargeOrder>>($"api/billing/orders?page={Math.Max(1, page)}&page_size={Math.Clamp(pageSize, 1, 100)}{suffix}", ct);
    }

    public Task<JsonElement> GetGithubStatusAsync(CancellationToken ct = default) => GetAsync<JsonElement>("api/auth/github/status", ct);
    public Uri GithubLoginUri(string next = "/") => new(BaseUri, $"api/auth/github?next={Escape(next.StartsWith('/') && !next.StartsWith("//") ? next : "/")}");

    public Task<CreativeEstimate> EstimateCreativeAsync(string message, string size, string language, CancellationToken ct = default) =>
        SendJsonAsync<CreativeEstimate>(HttpMethod.Post, "api/projects/creative-chats/estimate", new { message, size, language }, true, TimeSpan.FromSeconds(30), ct);

    public Task<JsonElement> EstimateCommerceAsync(string platform, IReadOnlyList<string> slots, string resolution, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "api/commerce-suite/estimate", new { platform, selected_slots = slots, resolution }, true, TimeSpan.FromSeconds(30), ct);

    public Task<JsonElement> CreateCommerceAsync(object request, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendJsonAsync<JsonElement>(HttpMethod.Post, "api/commerce-suite/generate", request, true, TimeSpan.FromSeconds(90), ct));

    public Task<JsonElement> CreateCommerceMultipartAsync(IReadOnlyDictionary<string, string> fields, IReadOnlyList<string> imagePaths, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendFilesAsync("api/commerce-suite/generate", fields, imagePaths, "references", null, TimeSpan.FromSeconds(90), ct));

    public Task<JsonElement> GenerateCreativeAsync(object request, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendJsonAsync<JsonElement>(HttpMethod.Post, "api/projects/creative-chats/generate", request, true, TimeSpan.FromSeconds(90), ct));

    public Task<JsonElement> GenerateCreativeMultipartAsync(
        IReadOnlyDictionary<string, string> fields,
        IReadOnlyList<string> imagePaths,
        string localOperationKey,
        CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendFilesAsync("api/projects/creative-chats/generate", fields, imagePaths, "references", null, TimeSpan.FromSeconds(90), ct));

    public Task<JsonElement> CreateProjectAsync(object request, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendJsonAsync<JsonElement>(HttpMethod.Post, "api/projects", request, true, TimeSpan.FromSeconds(45), ct));

    public Task<ProjectSummary> CreateListingProjectAsync(object request, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendJsonAsync<ProjectSummary>(HttpMethod.Post, "api/projects", request, true, TimeSpan.FromSeconds(45), ct));

    public Task<GenerateCopyResponse> GenerateListingCopyAsync(string projectId, CancellationToken ct = default) =>
        SendJsonAsync<GenerateCopyResponse>(HttpMethod.Post, $"api/projects/{Escape(projectId)}/generate-copy", null, true, TimeSpan.FromSeconds(90), ct);

    public Task<ProjectSummary> ConfirmProjectFactsAsync(string projectId, object confirmedFacts, CancellationToken ct = default) =>
        SendJsonAsync<ProjectSummary>(HttpMethod.Post, $"api/projects/{Escape(projectId)}/confirm-facts", new { confirmed_facts = confirmedFacts }, true, TimeSpan.FromSeconds(45), ct);

    public Task<ProductImage> UploadProductImageAsync(string projectId, string imageType, string imagePath, CancellationToken ct = default) =>
        SendFilesTypedAsync<ProductImage>(
            $"api/projects/{Escape(projectId)}/product-images?imageType={Escape(imageType)}",
            new Dictionary<string, string>(), [imagePath], "file", null, TimeSpan.FromSeconds(90), ct);

    public Task<JsonElement> EnqueueListingPackageAsync(string projectId, object request, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendJsonAsync<JsonElement>(HttpMethod.Post, $"api/projects/{Escape(projectId)}/listing-package", request, true, TimeSpan.FromSeconds(90), ct));

    public Task<ProjectDetail> CreateAplusImageAsync(object request, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendJsonAsync<ProjectDetail>(HttpMethod.Post, "api/projects/aplus-images", request, true, TimeSpan.FromSeconds(90), ct));

    public Task<ProjectDetail> CreateAplusImageMultipartAsync(IReadOnlyDictionary<string, string> fields, string imagePath, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendFilesTypedAsync<ProjectDetail>("api/projects/aplus-images", fields, [imagePath], "reference", null, TimeSpan.FromSeconds(90), ct));

    public Task<ProjectDetail> CreateDomesticImageAsync(object request, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendJsonAsync<ProjectDetail>(HttpMethod.Post, "api/projects/creative-images", request, true, TimeSpan.FromSeconds(90), ct));

    public Task<ProjectDetail> CreateDomesticImageMultipartAsync(IReadOnlyDictionary<string, string> fields, string imagePath, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendFilesTypedAsync<ProjectDetail>("api/projects/creative-images", fields, [imagePath], "reference", null, TimeSpan.FromSeconds(90), ct));

    public Task<ProjectDetail> CreateCreativeImageAsync(object request, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendJsonAsync<ProjectDetail>(HttpMethod.Post, "api/projects/creative-images", request, true, TimeSpan.FromSeconds(90), ct));

    public Task<ProjectDetail> CreateCreativeImageMultipartAsync(
        IReadOnlyDictionary<string, string> fields,
        IReadOnlyList<string> imagePaths,
        string localOperationKey,
        CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendFilesTypedAsync<ProjectDetail>("api/projects/creative-images", fields, imagePaths, "references", null, TimeSpan.FromSeconds(90), ct));

    public Task<AssistantStatus> GetAssistantStatusAsync(CancellationToken ct = default) => GetAsync<AssistantStatus>("api/assistant/status", ct);

    public Task<AssistantChatResponse> ChatWithAssistantAsync(IReadOnlyList<AssistantChatMessage> messages, string locale, string model, CancellationToken ct = default) =>
        SendJsonAsync<AssistantChatResponse>(HttpMethod.Post, "api/assistant/chat", new { messages, page_context = new { client = "windows" }, locale, model }, true, TimeSpan.FromSeconds(90), ct);

    public Task<AssistantChatResponse> ChatWithAssistantAsync(
        IReadOnlyList<AssistantChatMessage> messages,
        string locale,
        string model,
        object pageContext,
        CancellationToken ct = default) =>
        SendJsonAsync<AssistantChatResponse>(HttpMethod.Post, "api/assistant/chat", new { messages, page_context = pageContext, locale, model }, true, TimeSpan.FromSeconds(90), ct);

    public Task<JsonElement> CreateVideoAsync(IReadOnlyDictionary<string, string> fields, IReadOnlyList<string> imagePaths, string idempotencyKey, CancellationToken ct = default) =>
        SingleFlightAsync("video:" + idempotencyKey, () => SendFilesAsync("api/videos/tasks", fields, imagePaths, "images", idempotencyKey, TimeSpan.FromSeconds(90), ct));

    public Task<JsonElement> PreviewVideoScriptAsync(object request, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Post, "api/videos/script-preview", request, true, TimeSpan.FromSeconds(90), ct);

    public Task<JsonElement> UploadTranslationAsync(string targetLanguage, string resolution, IReadOnlyList<string> imagePaths, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendFilesAsync(
            "api/image-translation/generate",
            new Dictionary<string, string> { ["target_language"] = targetLanguage, ["resolution"] = resolution },
            imagePaths,
            "images",
            null,
            TimeSpan.FromSeconds(90),
            ct));

    public Task<JsonElement> RetryTranslationAsync(string taskId, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendJsonAsync<JsonElement>(HttpMethod.Post, $"api/image-translation/tasks/{Escape(taskId)}/retry", null, true, TimeSpan.FromSeconds(45), ct));

    public Task<JsonElement> HideProjectImageAsync(string projectId, string taskId, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Delete, $"api/projects/{Escape(projectId)}/images/{Escape(taskId)}", null, true, TimeSpan.FromSeconds(30), ct);

    public Task<ImageTask> RetryProjectImageAsync(string projectId, string taskId, CancellationToken ct = default) =>
        SendJsonAsync<ImageTask>(HttpMethod.Post, $"api/projects/{Escape(projectId)}/images/{Escape(taskId)}/retry", new { reference_url = "", reference_text = "" }, true, TimeSpan.FromSeconds(45), ct);

    public Task<JsonElement> DeleteProjectAsync(string projectId, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Delete, $"api/projects/{Escape(projectId)}", null, true, TimeSpan.FromSeconds(30), ct);

    public Task<JsonElement> UpdateProfileAsync(string nickname, string uiLanguage, string theme, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Patch, "api/me", new { nickname, ui_language = uiLanguage, theme }, true, TimeSpan.FromSeconds(30), ct);

    public Task<JsonElement> MarkMessageReadAsync(string id, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Patch, $"api/messages/{Escape(id)}/read", null, true, TimeSpan.FromSeconds(30), ct);

    public Task<JsonElement> RedeemAsync(string code, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendJsonAsync<JsonElement>(HttpMethod.Post, "api/billing/redeem", new { code }, true, TimeSpan.FromSeconds(30), ct));

    public Task<JsonElement> CreateRechargeOrderAsync(string planId, string channelCode, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendJsonAsync<JsonElement>(HttpMethod.Post, "api/billing/orders", new { plan_id = planId, channel_code = channelCode, turnstile_token = "" }, true, TimeSpan.FromSeconds(30), ct));

    public Task<JsonElement> SyncRechargeOrderAsync(string orderId, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendJsonAsync<JsonElement>(HttpMethod.Post, $"api/billing/orders/{Escape(orderId)}/sync", null, true, TimeSpan.FromSeconds(45), ct));

    public Task<JsonElement> CancelRechargeOrderAsync(string orderId, string localOperationKey, CancellationToken ct = default) =>
        SingleFlightAsync(localOperationKey, () => SendJsonAsync<JsonElement>(HttpMethod.Post, $"api/billing/orders/{Escape(orderId)}/cancel", null, true, TimeSpan.FromSeconds(45), ct));

    public Task<JsonElement> HideVideoAsync(string id, CancellationToken ct = default) =>
        SendJsonAsync<JsonElement>(HttpMethod.Delete, $"api/videos/tasks/{Escape(id)}", null, true, TimeSpan.FromSeconds(30), ct);

    public async Task<T> GetAsync<T>(string path, CancellationToken ct = default) => await SendAsync<T>(new HttpRequestMessage(HttpMethod.Get, path), true, TimeSpan.FromSeconds(30), false, ct);

    internal async Task<T> SendJsonAsync<T>(HttpMethod method, string path, object? body, bool authenticated, TimeSpan timeout, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(method, path);
        if (body is not null) request.Content = new StringContent(JsonSerializer.Serialize(body, Json), Encoding.UTF8, "application/json");
        return await SendAsync<T>(request, authenticated, timeout, method == HttpMethod.Post, ct);
    }

    private async Task<T> SendMultipartAsync<T>(string path, MultipartFormDataContent form, string? idempotencyKey, TimeSpan timeout, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, path) { Content = form };
        if (!string.IsNullOrWhiteSpace(idempotencyKey)) request.Headers.TryAddWithoutValidation("Idempotency-Key", idempotencyKey);
        return await SendAsync<T>(request, true, timeout, true, ct);
    }

    private async Task<JsonElement> SendFilesAsync(
        string path,
        IReadOnlyDictionary<string, string> fields,
        IReadOnlyList<string> filePaths,
        string fileField,
        string? idempotencyKey,
        TimeSpan timeout,
        CancellationToken ct)
        => await SendFilesTypedAsync<JsonElement>(path, fields, filePaths, fileField, idempotencyKey, timeout, ct);

    private async Task<T> SendFilesTypedAsync<T>(
        string path,
        IReadOnlyDictionary<string, string> fields,
        IReadOnlyList<string> filePaths,
        string fileField,
        string? idempotencyKey,
        TimeSpan timeout,
        CancellationToken ct)
    {
        using var form = new MultipartFormDataContent();
        foreach (var field in fields) form.Add(new StringContent(field.Value ?? "", Encoding.UTF8), field.Key);
        foreach (var filePath in filePaths)
        {
            var info = new FileInfo(filePath);
            if (!info.Exists) throw new ApiException($"文件不存在：{info.Name}", "FILE_NOT_FOUND");
            if (info.Length > 30L * 1024 * 1024) throw new ApiException($"文件超过 30MB：{info.Name}", "UPLOAD_TOO_LARGE");
            var stream = new FileStream(info.FullName, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, FileOptions.Asynchronous | FileOptions.SequentialScan);
            var content = new StreamContent(stream);
            content.Headers.ContentType = new MediaTypeHeaderValue(MimeFor(info.Extension));
            form.Add(content, fileField, info.Name);
        }
        return await SendMultipartAsync<T>(path, form, idempotencyKey, timeout, ct);
    }

    private async Task<T> SendAsync<T>(HttpRequestMessage request, bool authenticated, TimeSpan timeout, bool isSubmission, CancellationToken ct)
    {
        if (authenticated)
        {
            var token = await _tokenStore.ReadAsync(ct);
            if (string.IsNullOrWhiteSpace(token)) throw new ApiException("请先登录", "UNAUTHORIZED", statusCode: 401);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        }

        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeoutCts.CancelAfter(timeout);
        HttpResponseMessage response;
        try
        {
            response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeoutCts.Token);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            var message = isSubmission
                ? "请求超时，后台可能仍在处理。请先到历史记录确认，不要重复提交。"
                : "请求超时，请检查网络后重试。";
            throw new ApiException(message, "REQUEST_TIMEOUT", submissionStateUnknown: isSubmission);
        }
        catch (HttpRequestException)
        {
            throw new ApiException("网络连接失败，请检查网络后重试。", "NETWORK_ERROR", submissionStateUnknown: isSubmission);
        }

        using (response)
        {
            var text = await response.Content.ReadAsStringAsync(ct);
            ApiEnvelope<T>? envelope = null;
            try { envelope = JsonSerializer.Deserialize<ApiEnvelope<T>>(text, Json); } catch (JsonException) { }
            if (!response.IsSuccessStatusCode || envelope is null || !envelope.Success)
            {
                var code = envelope?.Code ?? ((int)response.StatusCode).ToString();
                var message = LocalizeError(code, envelope?.Message, response.StatusCode);
                throw new ApiException(message, code, envelope?.RequestId ?? "", (int)response.StatusCode);
            }

            if (envelope.Data is null) throw new ApiException("服务返回了空数据", "EMPTY_RESPONSE", envelope.RequestId, (int)response.StatusCode);
            return envelope.Data;
        }
    }

    private async Task<T> SingleFlightAsync<T>(string key, Func<Task<T>> action)
    {
        var gate = _submissionLocks.GetOrAdd(key, _ => new SemaphoreSlim(1, 1));
        if (!await gate.WaitAsync(0)) throw new ApiException("该操作正在提交，请勿重复点击。", "DUPLICATE_SUBMISSION");
        try { return await action(); }
        finally
        {
            gate.Release();
            _submissionLocks.TryRemove(new KeyValuePair<string, SemaphoreSlim>(key, gate));
            gate.Dispose();
        }
    }

    private static bool IsAllowedStaging(Uri uri) => uri.Scheme == Uri.UriSchemeHttp && (uri.Host is "localhost" or "127.0.0.1");
    private static string Escape(string value) => Uri.EscapeDataString(value ?? "");
    private static string MimeFor(string extension) => extension.ToLowerInvariant() switch
    {
        ".jpg" or ".jpeg" => "image/jpeg",
        ".png" => "image/png",
        ".webp" => "image/webp",
        ".heic" => "image/heic",
        ".heif" => "image/heif",
        _ => "application/octet-stream"
    };
    private static string LocalizeError(string code, string? message, HttpStatusCode status) => code switch
    {
        "UPLOAD_TOO_LARGE" => "图片超过上传限制，请压缩后重试。",
        "IMAGE_FORMAT_UNSUPPORTED" => "该照片格式暂时无法解析，请选择 JPG、PNG、WebP 或 HEIC 图片。",
        "IMAGE_CORRUPT" => "图片文件已损坏或无法读取，请重新选择。",
        "IMAGE_PIXEL_LIMIT" => "图片像素过大，请缩小尺寸后重试。",
        "AI_WORKER_PROCESSING" => "本机 AI 仍在处理这次请求，请稍后再试。",
        "UNAUTHORIZED" or "TOKEN_INVALID" => "登录已失效，请重新登录。",
        _ when status == HttpStatusCode.Unauthorized => "登录已失效，请重新登录。",
        _ when status == (HttpStatusCode)429 => "操作过于频繁，请稍后再试。",
        _ when (int)status >= 500 => "服务暂时不可用，请稍后重试。",
        _ => string.IsNullOrWhiteSpace(message) ? "请求失败，请稍后重试。" : message
    };

    public void Dispose()
    {
        if (_ownsHttp) _http.Dispose();
        foreach (var gate in _submissionLocks.Values) gate.Dispose();
    }
}
