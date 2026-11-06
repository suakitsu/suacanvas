using System.Text.Json;
using System.Text.Json.Serialization;

namespace PicPilot.Core;

public sealed record ApiEnvelope<T>(
    bool Success,
    string Code,
    string Message,
    T? Data,
    [property: JsonPropertyName("request_id")] string RequestId);

public sealed record UserProfile(
    string Id,
    [property: JsonPropertyName("email_masked")] string EmailMasked,
    [property: JsonPropertyName("email_display")] string EmailDisplay,
    string Nickname,
    string Role,
    int Credits,
    [property: JsonPropertyName("ui_language")] string UiLanguage,
    string Theme,
    [property: JsonPropertyName("avatar_url")] string AvatarUrl)
{
    public string DisplayEmail => string.IsNullOrWhiteSpace(EmailDisplay) ? EmailMasked : EmailDisplay;
}

public sealed record AuthResponse(
    UserProfile User,
    [property: JsonPropertyName("access_token")] string AccessToken);

public sealed record BillingSummary(int Credits, [property: JsonPropertyName("pending_orders")] int PendingOrders);

public sealed record ProjectSummary(
    string Id,
    string Brand,
    [property: JsonPropertyName("product_name")] string ProductName,
    string Country,
    string Language,
    string Status,
    [property: JsonPropertyName("success_images")] int SuccessImages,
    [property: JsonPropertyName("failed_images")] int FailedImages,
    [property: JsonPropertyName("running_images")] int RunningImages,
    [property: JsonPropertyName("total_images")] int TotalImages,
    [property: JsonPropertyName("credits_cost")] int CreditsCost,
    [property: JsonPropertyName("latest_failure_reason")] string LatestFailureReason,
    [property: JsonPropertyName("latest_failed_task_id")] string LatestFailedTaskId,
    [property: JsonPropertyName("cover_url")] string CoverUrl,
    [property: JsonPropertyName("preview_urls")] IReadOnlyList<string>? PreviewUrls,
    [property: JsonPropertyName("created_at")] string CreatedAt,
    [property: JsonPropertyName("updated_at")] string UpdatedAt)
{
    public string DisplayName => string.IsNullOrWhiteSpace(ProductName) ? "未命名项目" : ProductName;
    public string Summary => $"{StatusLabel.For(Status)} · {SuccessImages}/{Math.Max(TotalImages, SuccessImages + FailedImages + RunningImages)} 张";
    public string DisplayUpdatedAt => DisplayFormat.LocalDateTime(UpdatedAt);
    public bool HasCover => !string.IsNullOrWhiteSpace(CoverUrl);
}

public sealed record ProductImage(
    string Id,
    [property: JsonPropertyName("image_type")] string ImageType,
    [property: JsonPropertyName("original_filename")] string OriginalFilename,
    string Url,
    [property: JsonPropertyName("content_type")] string ContentType,
    [property: JsonPropertyName("image_format")] string ImageFormat,
    [property: JsonPropertyName("file_size")] long FileSize,
    int Width,
    int Height,
    [property: JsonPropertyName("created_at")] string CreatedAt);

public sealed record ProjectDetail(
    ProjectSummary Project,
    [property: JsonPropertyName("original_info")] JsonElement OriginalInfo,
    [property: JsonPropertyName("generated_copy")] JsonElement GeneratedCopy,
    [property: JsonPropertyName("confirmed_facts")] JsonElement ConfirmedFacts,
    [property: JsonPropertyName("product_images")] IReadOnlyList<ProductImage> ProductImages,
    [property: JsonPropertyName("image_tasks")] IReadOnlyList<ImageTask> ImageTasks);

public sealed record GenerateCopyResponse(
    [property: JsonPropertyName("project_id")] string ProjectId,
    string Status,
    [property: JsonPropertyName("generated_copy")] JsonElement GeneratedCopy);

public sealed record ImageTask(
    string Id,
    [property: JsonPropertyName("image_type")] string ImageType,
    [property: JsonPropertyName("image_name")] string ImageName,
    string Status,
    [property: JsonPropertyName("prompt_snapshot")] string PromptSnapshot,
    [property: JsonPropertyName("result_url")] string ResultUrl,
    [property: JsonPropertyName("credits_cost")] int CreditsCost,
    string Provider,
    string Model,
    [property: JsonPropertyName("safe_error")] string SafeError,
    [property: JsonPropertyName("created_at")] string CreatedAt)
{
    public string StatusText => StatusLabel.For(Status);
    public bool HasResult => !string.IsNullOrWhiteSpace(ResultUrl);
    public bool CanRetry => Status.Trim().ToLowerInvariant() is "failed" or "failure" or "timeout" or "expired";
    public bool CanHide => Status.Trim().ToLowerInvariant() is not ("queued" or "running" or "generating" or "submitting" or "submitted");
    public string ResultActionHint => HasResult ? "使用已生成的图片" : $"当前状态：{StatusText}，完成后可预览和下载";
}

public sealed record CreativeChatSummary(
    string Id,
    string Title,
    string Style,
    string Size,
    string Language,
    [property: JsonPropertyName("reference_url")] string ReferenceUrl,
    [property: JsonPropertyName("latest_status")] string LatestStatus,
    [property: JsonPropertyName("latest_result_url")] string LatestResultUrl,
    [property: JsonPropertyName("version_count")] int VersionCount,
    [property: JsonPropertyName("updated_at")] string UpdatedAt)
{
    public string Summary => $"{Size} · {StatusLabel.For(LatestStatus)} · {VersionCount} 个版本";
    public string DisplayUpdatedAt => DisplayFormat.LocalDateTime(UpdatedAt);
    public bool HasResult => !string.IsNullOrWhiteSpace(LatestResultUrl);
}

public sealed record CreativeChatMessage(
    string Id,
    [property: JsonPropertyName("chat_id")] string ChatId,
    string Role,
    string Content,
    [property: JsonPropertyName("project_id")] string ProjectId,
    [property: JsonPropertyName("task_id")] string TaskId,
    [property: JsonPropertyName("result_url")] string ResultUrl,
    string Status,
    [property: JsonPropertyName("version_no")] int VersionNo,
    [property: JsonPropertyName("created_at")] string CreatedAt)
{
    public string DisplayCreatedAt => DisplayFormat.LocalDateTime(CreatedAt);
    public bool HasResult => !string.IsNullOrWhiteSpace(ResultUrl);
}

public sealed record CreativeChatVersion(
    string Id,
    [property: JsonPropertyName("chat_id")] string ChatId,
    [property: JsonPropertyName("version_no")] int VersionNo,
    [property: JsonPropertyName("user_request")] string UserRequest,
    [property: JsonPropertyName("final_prompt")] string FinalPrompt,
    string Style,
    string Size,
    [property: JsonPropertyName("reference_url")] string ReferenceUrl,
    [property: JsonPropertyName("reference_urls")] IReadOnlyList<string>? ReferenceUrls,
    string Status,
    [property: JsonPropertyName("result_url")] string ResultUrl,
    [property: JsonPropertyName("safe_error")] string SafeError,
    [property: JsonPropertyName("created_at")] string CreatedAt,
    [property: JsonPropertyName("updated_at")] string UpdatedAt)
{
    public bool HasResult => !string.IsNullOrWhiteSpace(ResultUrl);
}

public sealed record CreativeChatDetail(
    CreativeChatSummary Chat,
    IReadOnlyList<CreativeChatMessage> Messages,
    IReadOnlyList<CreativeChatVersion> Versions);

public sealed record CreativeEstimate(
    string Size,
    [property: JsonPropertyName("quality_label")] string QualityLabel,
    [property: JsonPropertyName("credits_cost")] int CreditsCost);

public sealed record VideoTask(
    string Id,
    string Title,
    string Prompt,
    [property: JsonPropertyName("source_image_urls")] IReadOnlyList<string>? SourceImageUrls,
    [property: JsonPropertyName("aspect_ratio")] string AspectRatio,
    [property: JsonPropertyName("duration_seconds")] int DurationSeconds,
    [property: JsonPropertyName("requested_resolution")] string RequestedResolution,
    string Mode,
    string Status,
    int Progress,
    string Provider,
    string Model,
    [property: JsonPropertyName("result_url")] string ResultUrl,
    [property: JsonPropertyName("credits_cost")] int CreditsCost,
    [property: JsonPropertyName("safe_error")] string SafeError,
    [property: JsonPropertyName("refunded_at")] string RefundedAt,
    [property: JsonPropertyName("created_at")] string CreatedAt,
    [property: JsonPropertyName("updated_at")] string UpdatedAt)
{
    public string StatusText => StatusLabel.For(Status);
    public string Specification => $"{AspectRatio} · {DurationSeconds} 秒 · {RequestedResolution}";
    public string DisplayUpdatedAt => DisplayFormat.LocalDateTime(UpdatedAt);
    public bool HasResult => !string.IsNullOrWhiteSpace(ResultUrl);
    public bool CanHide => Status.Trim().ToLowerInvariant() is not ("queued" or "submitting" or "submitted" or "generating" or "processing" or "running");
    public string ResultActionHint => HasResult ? "打开已生成视频" : $"当前状态：{StatusText}，完成后可播放";
}

public sealed record RechargePlan(
    string Id,
    string Name,
    int Credits,
    [property: JsonPropertyName("price_cents")] int PriceCents,
    string Currency,
    bool Enabled,
    [property: JsonPropertyName("sort_order")] int SortOrder)
{
    public string PriceText => $"¥{PriceCents / 100d:0.00}";
}

public sealed record PaymentChannel(
    string Id,
    string Name,
    string Code,
    bool Visible,
    bool Enabled,
    [property: JsonPropertyName("sort_order")] int SortOrder);

public sealed record RechargeOrder(
    string Id,
    [property: JsonPropertyName("user_id")] string UserId,
    [property: JsonPropertyName("plan_id")] string PlanId,
    [property: JsonPropertyName("channel_code")] string ChannelCode,
    [property: JsonPropertyName("amount_cents")] int AmountCents,
    int Credits,
    string Currency,
    string Status,
    string Remark,
    [property: JsonPropertyName("created_at")] string CreatedAt)
{
    public string AmountText => $"¥{AmountCents / 100d:0.00}";
    public string StatusText => Status.Trim().ToLowerInvariant() switch
    {
        "paid" or "success" or "completed" => "已支付",
        "pending" or "created" => "待支付",
        "processing" => "处理中",
        "failed" => "支付失败",
        "cancelled" or "canceled" or "closed" => "已关闭",
        "refunded" => "已退款",
        _ => Status
    };
    public bool CanClose => Status.Trim().ToLowerInvariant() is "pending" or "created" or "processing";
    public string DisplayCreatedAt => DisplayFormat.LocalDateTime(CreatedAt);
}

public sealed record CreditLedger(
    string Id,
    [property: JsonPropertyName("change_amount")] int ChangeAmount,
    [property: JsonPropertyName("balance_after")] int BalanceAfter,
    [property: JsonPropertyName("biz_type")] string BizType,
    [property: JsonPropertyName("biz_id")] string BizId,
    string Remark,
    [property: JsonPropertyName("created_at")] string CreatedAt)
{
    public string ChangeText => ChangeAmount > 0 ? $"+{ChangeAmount}" : ChangeAmount.ToString();
    public string DisplayCreatedAt => DisplayFormat.LocalDateTime(CreatedAt);
}

public sealed record StationMessage(
    string Id,
    string Title,
    string Content,
    [property: JsonPropertyName("published_at")] string PublishedAt,
    [property: JsonPropertyName("expires_at")] string ExpiresAt,
    bool Read)
{
    public string DisplayPublishedAt => DisplayFormat.LocalDateTime(PublishedAt);
    public string ReadActionText => Read ? "已读" : "标记已读";
}

public sealed record AssistantStatus(bool Enabled, string Model, IReadOnlyList<string>? Models);

public sealed record AssistantChatMessage(string Role, string Content);

public sealed record AssistantChatResponse(
    string Reply,
    string Intent,
    string Action,
    [property: JsonPropertyName("action_label")] string ActionLabel,
    [property: JsonPropertyName("action_href")] string ActionHref,
    [property: JsonPropertyName("suggested_prompt")] string SuggestedPrompt,
    double Confidence,
    string Model);

public sealed record PageResult<T>(
    IReadOnlyList<T> Rows,
    long Total,
    int Page,
    [property: JsonPropertyName("page_size")] int PageSize,
    [property: JsonPropertyName("total_pages")] int TotalPages);

public sealed record CommercePlatform(
    string Code,
    string Name,
    string Description,
    bool Enabled,
    bool Beta,
    [property: JsonPropertyName("default_resolution")] string DefaultResolution,
    [property: JsonPropertyName("default_size")] string DefaultSize,
    [property: JsonPropertyName("default_slots")] IReadOnlyList<string> DefaultSlots,
    string Language,
    [property: JsonPropertyName("overlay_language")] string OverlayLanguage)
{
    public override string ToString() => Name;
}

public sealed record CommerceSlot(
    string Code,
    string Name,
    string Description,
    string Category,
    [property: JsonPropertyName("default_size")] string DefaultSize,
    [property: JsonPropertyName("sort_order")] int SortOrder);

public sealed record CommerceCatalog(
    IReadOnlyList<CommercePlatform> Platforms,
    IReadOnlyList<CommerceSlot> Slots,
    JsonElement Templates,
    [property: JsonPropertyName("cost_rule")] JsonElement CostRule,
    [property: JsonPropertyName("updated_at")] string UpdatedAt);

public sealed record TranslationLanguage(string Code, string Name, [property: JsonPropertyName("native_name")] string NativeName, [property: JsonPropertyName("prompt_name")] string PromptName)
{
    public override string ToString() => Name;
}

public static class ImageFilePolicy
{
    public const int MaxReferenceFiles = 10;
    public const long MaxSingleBytes = 25L * 1024L * 1024L;
    public const long MaxTotalBytes = 28L * 1024L * 1024L;
    private static readonly HashSet<string> Extensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif"
    };

    public static string? Validate(IEnumerable<string> paths, int maxFiles = MaxReferenceFiles, long maxSingleBytes = MaxSingleBytes, long maxTotalBytes = MaxTotalBytes)
    {
        var files = paths.Distinct(StringComparer.OrdinalIgnoreCase).Select(path => new FileInfo(path)).ToArray();
        if (files.Length == 0) return "请至少选择一张图片。";
        if (files.Length > maxFiles) return $"一次最多选择 {maxFiles} 张图片。";
        long total = 0;
        foreach (var file in files)
        {
            if (!file.Exists) return $"文件不存在：{file.Name}";
            if (!Extensions.Contains(file.Extension)) return $"不支持的图片格式：{file.Name}";
            if (file.Length <= 0) return $"图片文件为空或已损坏：{file.Name}";
            if (file.Length > maxSingleBytes) return $"单张图片不能超过 {maxSingleBytes / 1024 / 1024}MB：{file.Name}";
            total += file.Length;
            if (total > maxTotalBytes) return $"全部图片合计不能超过 {maxTotalBytes / 1024 / 1024}MB。";
        }
        return null;
    }

    public static string SanitizeDownloadName(string? value, string fallback = "PicPilot-output")
    {
        var name = Path.GetFileName(string.IsNullOrWhiteSpace(value) ? fallback : value.Trim());
        foreach (var invalid in Path.GetInvalidFileNameChars()) name = name.Replace(invalid, '_');
        return string.IsNullOrWhiteSpace(name) ? fallback : name;
    }
}

public sealed class ApiException : Exception
{
    public ApiException(string message, string code, string requestId = "", int statusCode = 0, bool submissionStateUnknown = false)
        : base(message)
    {
        Code = code;
        RequestId = requestId;
        StatusCode = statusCode;
        SubmissionStateUnknown = submissionStateUnknown;
    }

    public string Code { get; }
    public string RequestId { get; }
    public int StatusCode { get; }
    public bool SubmissionStateUnknown { get; }
}

public static class StatusLabel
{
    public static string For(string? value) => (value ?? "").Trim().ToLowerInvariant() switch
    {
        "pending" or "queued" => "排队中",
        "submitting" => "正在提交",
        "submitted" or "processing" or "running" or "generating" or "upscaling" or "quality_check" => "生成中",
        "completed" or "succeeded" or "success" => "已完成",
        "images_completed" => "出图已完成",
        "copy_confirmed" => "文案已确认",
        "copy_ready" or "copy_generated" => "文案已生成",
        "partially_completed" or "partial_success" => "部分完成",
        "failed" or "failure" => "生成失败",
        "cancelled" or "canceled" => "已取消",
        "timeout" or "expired" => "已超时",
        "active" => "正常",
        "draft" => "草稿",
        "refunded" => "已退款",
        "" => "未知",
        _ => value!
    };
}

public static class DisplayFormat
{
    public static string LocalDateTime(string? value)
    {
        if (!DateTimeOffset.TryParse(value, out var parsed)) return value ?? "";
        var local = parsed.ToLocalTime();
        return local.Year == DateTimeOffset.Now.Year
            ? local.ToString("MM-dd HH:mm")
            : local.ToString("yyyy-MM-dd HH:mm");
    }
}
