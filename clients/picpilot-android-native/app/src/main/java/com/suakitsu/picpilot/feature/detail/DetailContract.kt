package com.suakitsu.picpilot.feature.detail

import androidx.compose.runtime.Immutable

enum class DetailKind {
    Project,
    CreativeChat,
    Video,
}

@Immutable
data class DetailField(
    val label: String,
    val value: String,
)

@Immutable
data class ProjectAsset(
    val id: String,
    val imageType: String,
    val originalFilename: String,
    val url: String,
    val mimeType: String = "image/jpeg",
    val format: String = "",
    val fileSize: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val createdAt: String = "",
)

@Immutable
data class ProjectImageTask(
    val id: String,
    val imageType: String,
    val imageName: String,
    val status: String,
    val promptSnapshot: String = "",
    val resultUrl: String = "",
    val creditsCost: Int = 0,
    val safeError: String = "",
    val createdAt: String = "",
)

@Immutable
data class ProjectDetailModel(
    val id: String,
    val productName: String,
    val brand: String = "",
    val country: String = "",
    val language: String = "",
    val status: String = "",
    val creditsCost: Int = 0,
    val successImages: Int = 0,
    val failedImages: Int = 0,
    val runningImages: Int = 0,
    val totalImages: Int = 0,
    val latestFailureReason: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val sourceType: String = "",
    val originalInfo: List<DetailField> = emptyList(),
    val generatedCopy: List<DetailField> = emptyList(),
    val confirmedFacts: List<DetailField> = emptyList(),
    val assets: List<ProjectAsset> = emptyList(),
    val imageTasks: List<ProjectImageTask> = emptyList(),
)

@Immutable
data class CreativeMessage(
    val id: String,
    val role: String,
    val content: String,
    val projectId: String = "",
    val taskId: String = "",
    val resultUrl: String = "",
    val status: String = "",
    val versionNo: Int? = null,
    val createdAt: String = "",
)

@Immutable
data class CreativeVersion(
    val id: String,
    val versionNo: Int,
    val projectId: String = "",
    val taskId: String = "",
    val userRequest: String = "",
    val finalPrompt: String = "",
    val style: String = "",
    val size: String = "",
    val referenceUrls: List<String> = emptyList(),
    val status: String = "",
    val resultUrl: String = "",
    val safeError: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Immutable
data class CreativeChatDetailModel(
    val id: String,
    val title: String,
    val style: String = "",
    val size: String = "",
    val language: String = "",
    val latestStatus: String = "",
    val latestResultUrl: String = "",
    val versionCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val messages: List<CreativeMessage> = emptyList(),
    val versions: List<CreativeVersion> = emptyList(),
)

@Immutable
data class VideoTaskDetailModel(
    val id: String,
    val title: String,
    val prompt: String = "",
    val sourceImageUrls: List<String> = emptyList(),
    val aspectRatio: String = "",
    val durationSeconds: Int = 0,
    val requestedResolution: String = "",
    val mode: String = "",
    val status: String = "",
    val progress: Int = 0,
    val provider: String = "",
    val model: String = "",
    val routingReason: String = "",
    val queueAhead: Int? = null,
    val upstreamTaskId: String = "",
    val resultUrl: String = "",
    val creditsCost: Int = 0,
    val publicMetadata: List<DetailField> = emptyList(),
    val safeError: String = "",
    val refundedAt: String = "",
    val queuedAt: String = "",
    val submittedAt: String = "",
    val completedAt: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
)

sealed interface DetailPayload {
    val id: String

    @Immutable
    data class Project(val value: ProjectDetailModel) : DetailPayload {
        override val id: String = value.id
    }

    @Immutable
    data class CreativeChat(val value: CreativeChatDetailModel) : DetailPayload {
        override val id: String = value.id
    }

    @Immutable
    data class Video(val value: VideoTaskDetailModel) : DetailPayload {
        override val id: String = value.id
    }
}

sealed interface DetailLoadResult<out T> {
    @Immutable
    data class Success<T>(val value: T) : DetailLoadResult<T>

    @Immutable
    data class Failure(
        val code: String,
        val message: String,
    ) : DetailLoadResult<Nothing>
}

/**
 * Narrow read-only contract. The host app maps its authenticated repository DTOs to these models.
 * Implementations must load exactly the supplied ID and must not fall back to a latest-list item.
 */
interface DetailGateway {
    suspend fun loadProject(projectId: String): DetailLoadResult<ProjectDetailModel>

    suspend fun loadCreativeChat(chatId: String): DetailLoadResult<CreativeChatDetailModel>

    suspend fun loadVideo(taskId: String): DetailLoadResult<VideoTaskDetailModel>
}

object NoOpDetailGateway : DetailGateway {
    private fun unavailable(): DetailLoadResult.Failure = DetailLoadResult.Failure(
        code = "DETAIL_GATEWAY_NOT_CONNECTED",
        message = "详情接口尚未接入，请稍后再试。",
    )

    override suspend fun loadProject(projectId: String) = unavailable()

    override suspend fun loadCreativeChat(chatId: String) = unavailable()

    override suspend fun loadVideo(taskId: String) = unavailable()
}

/** Deterministic fixture gateway for previews and host-side tests. It performs no I/O. */
class InMemoryDetailGateway(
    projects: List<ProjectDetailModel> = emptyList(),
    chats: List<CreativeChatDetailModel> = emptyList(),
    videos: List<VideoTaskDetailModel> = emptyList(),
) : DetailGateway {
    private val projectById = projects.associateBy(ProjectDetailModel::id)
    private val chatById = chats.associateBy(CreativeChatDetailModel::id)
    private val videoById = videos.associateBy(VideoTaskDetailModel::id)

    override suspend fun loadProject(projectId: String): DetailLoadResult<ProjectDetailModel> =
        projectById[projectId].asResult("PROJECT_NOT_FOUND", "项目不存在或无权访问。")

    override suspend fun loadCreativeChat(chatId: String): DetailLoadResult<CreativeChatDetailModel> =
        chatById[chatId].asResult("CHAT_NOT_FOUND", "对话不存在或无权访问。")

    override suspend fun loadVideo(taskId: String): DetailLoadResult<VideoTaskDetailModel> =
        videoById[taskId].asResult("VIDEO_TASK_NOT_FOUND", "视频任务不存在或无权访问。")
}

private fun <T> T?.asResult(code: String, message: String): DetailLoadResult<T> =
    if (this == null) DetailLoadResult.Failure(code, message) else DetailLoadResult.Success(this)
