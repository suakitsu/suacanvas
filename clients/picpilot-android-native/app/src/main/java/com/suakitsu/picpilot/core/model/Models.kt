package com.suakitsu.picpilot.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    val success: Boolean = false,
    val code: String = "REQUEST_FAILED",
    val message: String = "",
    val data: T? = null,
    @SerialName("request_id") val requestId: String = "",
)

@Serializable
data class UserProfile(
    val id: String,
    @SerialName("email_masked") val emailMasked: String = "",
    @SerialName("email_display") val emailDisplay: String = "",
    val nickname: String = "",
    val role: String = "user",
    val credits: Int = 0,
    @SerialName("ui_language") val uiLanguage: String = "zh-CN",
    val theme: String = "system",
    @SerialName("avatar_url") val avatarUrl: String = "",
)

@Serializable
data class AuthResponse(
    val user: UserProfile,
    @SerialName("access_token") val accessToken: String,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    @SerialName("remember_me") val rememberMe: Boolean,
    @SerialName("turnstile_token") val turnstileToken: String = "",
)

@Serializable
data class EmailCodeLoginRequest(
    val email: String,
    @SerialName("verification_code") val verificationCode: String,
    @SerialName("remember_me") val rememberMe: Boolean,
    @SerialName("turnstile_token") val turnstileToken: String = "",
)

@Serializable
data class EmailCodeRequest(
    val email: String,
    val purpose: String,
    @SerialName("turnstile_token") val turnstileToken: String = "",
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerialName("confirm_password") val confirmPassword: String,
    @SerialName("verification_code") val verificationCode: String,
    @SerialName("invite_code") val inviteCode: String = "",
    @SerialName("turnstile_token") val turnstileToken: String = "",
)

@Serializable
data class PasswordResetRequest(
    val email: String,
    @SerialName("verification_code") val verificationCode: String,
    val password: String,
    @SerialName("confirm_password") val confirmPassword: String,
    @SerialName("turnstile_token") val turnstileToken: String = "",
)

@Serializable
data class ForgotPasswordRequest(
    val email: String,
    val note: String = "",
    @SerialName("turnstile_token") val turnstileToken: String = "",
)

@Serializable
data class EmailCodeResult(
    val sent: Boolean = false,
    @SerialName("email_masked") val emailMasked: String = "",
    val purpose: String = "",
    @SerialName("expires_in_seconds") val expiresInSeconds: Int = 0,
    @SerialName("cooldown_seconds") val cooldownSeconds: Int = 60,
)

@Serializable
data class BillingSummary(
    val credits: Int = 0,
    @SerialName("pending_orders") val pendingOrders: Int = 0,
)

@Serializable
data class ProjectSummary(
    val id: String,
    val brand: String = "",
    @SerialName("product_name") val productName: String = "",
    val country: String = "",
    val language: String = "",
    val status: String = "",
    @SerialName("success_images") val successImages: Int = 0,
    @SerialName("failed_images") val failedImages: Int = 0,
    @SerialName("running_images") val runningImages: Int = 0,
    @SerialName("total_images") val totalImages: Int = 0,
    @SerialName("credits_cost") val creditsCost: Int = 0,
    @SerialName("latest_failure_reason") val latestFailureReason: String = "",
    @SerialName("latest_failed_task_id") val latestFailedTaskId: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("preview_urls") val previewUrls: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("source_type") val sourceType: String = "",
    @SerialName("source_chat_id") val sourceChatId: String = "",
    @SerialName("version_count") val versionCount: Int = 0,
)

@Serializable
data class ImageTask(
    val id: String,
    @SerialName("image_type") val imageType: String = "",
    @SerialName("image_name") val imageName: String = "",
    val status: String = "",
    @SerialName("prompt_snapshot") val promptSnapshot: String = "",
    @SerialName("result_url") val resultUrl: String = "",
    @SerialName("credits_cost") val creditsCost: Int = 0,
    val provider: String = "",
    val model: String = "",
    @SerialName("safe_error") val safeError: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class CreativeChatSummary(
    val id: String,
    val title: String = "",
    val style: String = "",
    val size: String = "",
    val language: String = "",
    @SerialName("reference_url") val referenceUrl: String = "",
    @SerialName("latest_status") val latestStatus: String = "",
    @SerialName("latest_result_url") val latestResultUrl: String = "",
    @SerialName("version_count") val versionCount: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class RechargePlan(
    val id: String,
    val name: String,
    val credits: Int,
    @SerialName("price_cents") val priceCents: Int,
    val currency: String = "CNY",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class CreditLedger(
    val id: String,
    @SerialName("change_amount") val changeAmount: Int,
    @SerialName("balance_after") val balanceAfter: Int,
    @SerialName("biz_type") val bizType: String,
    val remark: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class StationMessage(
    val id: String,
    val title: String,
    val content: String,
    @SerialName("published_at") val publishedAt: String = "",
    val read: Boolean = false,
)

@Serializable
data class VideoVariant(
    val resolution: String,
    @SerialName("duration_seconds") val durationSeconds: Int,
    val price: Int,
    val enabled: Boolean,
)

@Serializable
data class VideoModelConfig(
    val code: String,
    val name: String,
    val description: String = "",
    val provider: String = "",
    val model: String = "",
    val enabled: Boolean = false,
    @SerialName("default_resolution") val defaultResolution: String = "720p",
    @SerialName("default_duration_seconds") val defaultDurationSeconds: Int = 10,
    @SerialName("duration_selectable") val durationSelectable: Boolean = false,
    @SerialName("audio_toggle") val audioToggle: Boolean = false,
    @SerialName("max_reference_images") val maxReferenceImages: Int = 1,
    @SerialName("aspect_ratios") val aspectRatios: List<String> = emptyList(),
    val resolutions: List<String> = emptyList(),
    val durations: List<Int> = emptyList(),
    val variants: List<VideoVariant> = emptyList(),
)

@Serializable
data class VideoPublicConfig(
    val enabled: Boolean = false,
    @SerialName("mock_mode") val mockMode: Boolean = false,
    @SerialName("default_model") val defaultModel: String = "",
    @SerialName("model_configs") val modelConfigs: List<VideoModelConfig> = emptyList(),
)

@Serializable
data class VideoTask(
    val id: String,
    val title: String = "",
    val prompt: String = "",
    @SerialName("source_image_urls") val sourceImageUrls: List<String> = emptyList(),
    @SerialName("aspect_ratio") val aspectRatio: String = "",
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    @SerialName("requested_resolution") val requestedResolution: String = "",
    val mode: String = "",
    val status: String = "",
    val progress: Int = 0,
    val provider: String = "",
    val model: String = "",
    @SerialName("routing_reason") val routingReason: String = "",
    @SerialName("queue_ahead") val queueAhead: Int? = null,
    @SerialName("upstream_task_id") val upstreamTaskId: String = "",
    @SerialName("result_url") val resultUrl: String = "",
    @SerialName("credits_cost") val creditsCost: Int = 0,
    val metadata: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    @SerialName("safe_error") val safeError: String = "",
    @SerialName("refunded_at") val refundedAt: String = "",
    @SerialName("queued_at") val queuedAt: String = "",
    @SerialName("submitted_at") val submittedAt: String = "",
    @SerialName("completed_at") val completedAt: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class PageResult<T>(
    val rows: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
    @SerialName("total_pages") val totalPages: Int = 1,
)
