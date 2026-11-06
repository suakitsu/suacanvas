package com.suakitsu.picpilot.feature.aicreate

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

const val AI_CREATE_MAX_REFERENCES = 10
const val AI_CREATE_MIN_PROMPT_LENGTH = 4

enum class AiCreateMessageRole {
    User,
    Assistant,
}

enum class AiCreateReferenceSource {
    Device,
    Conversation,
}

enum class AiCreateTaskStatus {
    Queued,
    Submitting,
    Generating,
    Completed,
    Failed,
    Cancelled,
}

@Immutable
data class AiCreateQualityOption(
    val id: String,
    val label: String,
    val pixels: String,
    val credits: Int,
)

@Immutable
data class AiCreateStyleOption(
    val id: String,
    val label: String,
)

@Immutable
data class AiCreateCatalog(
    val qualities: List<AiCreateQualityOption> = defaultAiCreateQualities,
    val styles: List<AiCreateStyleOption> = defaultAiCreateStyles,
    val authoritative: Boolean = false,
) {
    fun quality(id: String): AiCreateQualityOption =
        qualities.firstOrNull { it.id == id } ?: qualities.first()

    fun style(id: String): AiCreateStyleOption =
        styles.firstOrNull { it.id == id } ?: styles.first()
}

val defaultAiCreateQualities = listOf(
    AiCreateQualityOption(id = "1k", label = "1K", pixels = "1024 x 1024", credits = 0),
    AiCreateQualityOption(id = "2k", label = "2K", pixels = "2048 x 2048", credits = 0),
    AiCreateQualityOption(id = "4k", label = "4K", pixels = "2880 x 2880", credits = 0),
)

val defaultAiCreateStyles = listOf(
    AiCreateStyleOption(id = "auto", label = "自动"),
    AiCreateStyleOption(id = "commerce", label = "商品摄影"),
    AiCreateStyleOption(id = "portrait", label = "写实人像"),
    AiCreateStyleOption(id = "poster", label = "商业海报"),
    AiCreateStyleOption(id = "illustration", label = "插画"),
)

@Immutable
data class AiCreateReference(
    val id: String,
    val uri: String,
    val displayName: String = "图片",
    val mimeType: String = "image/*",
    val source: AiCreateReferenceSource = AiCreateReferenceSource.Device,
    val sourceMessageId: String? = null,
)

@Immutable
data class AiCreateResultImage(
    val id: String,
    val url: String,
    val displayName: String = "生成结果",
    val mimeType: String = "image/png",
)

@Immutable
data class AiCreateTaskPresentation(
    val localId: String,
    val taskId: String? = null,
    val status: AiCreateTaskStatus = AiCreateTaskStatus.Queued,
    val progress: Int = 0,
    val credits: Int = 0,
    val safeError: String? = null,
    val results: List<AiCreateResultImage> = emptyList(),
    val originalPrompt: String = "",
    val originalReferences: List<AiCreateReference> = emptyList(),
)

@Immutable
data class AiCreateMessage(
    val id: String,
    val role: AiCreateMessageRole,
    val text: String,
    val references: List<AiCreateReference> = emptyList(),
    val task: AiCreateTaskPresentation? = null,
    val createdAtLabel: String = "",
)

@Immutable
data class AiCreateConversation(
    val id: String,
    val title: String,
    val preview: String = "",
    val updatedAtLabel: String = "",
    val unread: Boolean = false,
)

@Immutable
data class AiCreateGenerateRequest(
    val conversationId: String,
    val prompt: String,
    val referenceImages: List<AiCreateReference>,
    val quality: AiCreateQualityOption,
    val style: AiCreateStyleOption,
    val expectedCredits: Int,
)

@Immutable
data class AiCreateSubmitReceipt(
    val taskId: String,
    val status: AiCreateTaskStatus = AiCreateTaskStatus.Queued,
    val chargedCredits: Int,
    val balanceAfter: Int? = null,
)

@Immutable
data class AiCreateTaskUpdate(
    val taskId: String,
    val status: AiCreateTaskStatus,
    val progress: Int = 0,
    val safeError: String? = null,
    val results: List<AiCreateResultImage> = emptyList(),
)

class AiCreateQuoteChangedException(
    val authoritativeCredits: Int,
) : IllegalStateException("服务端价格已更新为 $authoritativeCredits 积分，请重新确认")

@Immutable
data class AiCreateUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val conversations: List<AiCreateConversation> = emptyList(),
    val selectedConversationId: String? = null,
    val messages: List<AiCreateMessage> = emptyList(),
    val prompt: String = "",
    val draftReferences: List<AiCreateReference> = emptyList(),
    val selectedQualityId: String = "1k",
    val selectedStyleId: String = "auto",
    val catalog: AiCreateCatalog = AiCreateCatalog(),
    val availableCredits: Int? = null,
    val isCatalogLoading: Boolean = true,
    val isEstimating: Boolean = false,
    val pendingRequest: AiCreateGenerateRequest? = null,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
)

/**
 * The native feature depends on this narrow interface instead of knowing Retrofit endpoints.
 * The default implementation never performs network or model calls.
 */
interface AiCreateGateway {
    suspend fun loadCatalog(): Result<AiCreateCatalog> =
        Result.failure(IllegalStateException("AI 创作目录尚未加载"))

    suspend fun loadBalance(): Result<Int> =
        Result.failure(IllegalStateException("积分余额尚未加载"))

    suspend fun loadConversations(): Result<List<AiCreateConversation>> =
        Result.success(emptyList())

    suspend fun loadMessages(conversationId: String): Result<List<AiCreateMessage>> =
        Result.success(emptyList())

    suspend fun estimate(request: AiCreateGenerateRequest): Result<Int> =
        Result.failure(IllegalStateException("AI 创作报价尚未加载"))

    suspend fun submit(request: AiCreateGenerateRequest): Result<AiCreateSubmitReceipt>

    fun observeTask(taskId: String): Flow<AiCreateTaskUpdate> = emptyFlow()
}

object NoOpAiCreateGateway : AiCreateGateway {
    override suspend fun submit(request: AiCreateGenerateRequest): Result<AiCreateSubmitReceipt> =
        Result.failure(IllegalStateException("AI 创作服务尚未接线"))
}
