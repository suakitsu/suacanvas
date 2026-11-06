package com.suakitsu.picpilot.integration

import com.suakitsu.picpilot.core.media.resolveMediaUrl
import com.suakitsu.picpilot.core.model.CreativeChatViewDto
import com.suakitsu.picpilot.core.model.ProjectDetailDto
import com.suakitsu.picpilot.core.model.VideoTask
import com.suakitsu.picpilot.core.network.ApiResult
import com.suakitsu.picpilot.core.repository.PicPilotRepository
import com.suakitsu.picpilot.feature.detail.CreativeChatDetailModel
import com.suakitsu.picpilot.feature.detail.CreativeMessage
import com.suakitsu.picpilot.feature.detail.CreativeVersion
import com.suakitsu.picpilot.feature.detail.DetailField
import com.suakitsu.picpilot.feature.detail.DetailGateway
import com.suakitsu.picpilot.feature.detail.DetailLoadResult
import com.suakitsu.picpilot.feature.detail.ProjectAsset
import com.suakitsu.picpilot.feature.detail.ProjectDetailModel
import com.suakitsu.picpilot.feature.detail.ProjectImageTask
import com.suakitsu.picpilot.feature.detail.VideoTaskDetailModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Singleton
class ProductionDetailGateway @Inject constructor(
    private val repository: PicPilotRepository,
) : DetailGateway {
    override suspend fun loadProject(projectId: String): DetailLoadResult<ProjectDetailModel> =
        repository.project(projectId).mapDetail(ProjectDetailDto::toDetailModel)

    override suspend fun loadCreativeChat(chatId: String): DetailLoadResult<CreativeChatDetailModel> =
        repository.creativeChat(chatId).mapDetail(CreativeChatViewDto::toDetailModel)

    override suspend fun loadVideo(taskId: String): DetailLoadResult<VideoTaskDetailModel> =
        repository.videoTask(taskId).mapDetail(VideoTask::toDetailModel)
}

private inline fun <T, R> ApiResult<T>.mapDetail(transform: (T) -> R): DetailLoadResult<R> = when (this) {
    is ApiResult.Success -> DetailLoadResult.Success(transform(value))
    is ApiResult.Failure -> DetailLoadResult.Failure(code, message)
}

internal fun ProjectDetailDto.toDetailModel(): ProjectDetailModel {
    val summary = project
    return ProjectDetailModel(
        id = summary.id,
        productName = summary.productName,
        brand = summary.brand,
        country = summary.country,
        language = summary.language,
        status = summary.status,
        creditsCost = summary.creditsCost,
        successImages = summary.successImages,
        failedImages = summary.failedImages,
        runningImages = summary.runningImages,
        totalImages = summary.totalImages,
        latestFailureReason = summary.latestFailureReason,
        createdAt = summary.createdAt,
        updatedAt = summary.updatedAt,
        sourceType = summary.sourceType,
        originalInfo = originalInfo.toDetailFields(),
        generatedCopy = generatedCopy.toDetailFields(),
        confirmedFacts = confirmedFacts.toDetailFields(),
        assets = productImages.map { image ->
            ProjectAsset(
                id = image.id,
                imageType = image.imageType,
                originalFilename = image.originalFilename,
                url = resolveMediaUrl(image.url),
                mimeType = image.contentType.ifBlank { "image/jpeg" },
                format = image.imageFormat,
                fileSize = image.fileSize,
                width = image.width,
                height = image.height,
                createdAt = image.createdAt,
            )
        },
        imageTasks = imageTasks.map { task ->
            ProjectImageTask(
                id = task.id,
                imageType = task.imageType,
                imageName = task.imageName,
                status = task.status,
                promptSnapshot = task.promptSnapshot,
                resultUrl = resolveMediaUrl(task.resultUrl),
                creditsCost = task.creditsCost,
                safeError = task.safeError,
                createdAt = task.createdAt,
            )
        },
    )
}

internal fun CreativeChatViewDto.toDetailModel(): CreativeChatDetailModel = CreativeChatDetailModel(
    id = chat.id,
    title = chat.title,
    style = chat.style,
    size = chat.size,
    language = chat.language,
    latestStatus = chat.latestStatus,
    latestResultUrl = resolveMediaUrl(chat.latestResultUrl),
    versionCount = chat.versionCount,
    createdAt = chat.createdAt,
    updatedAt = chat.updatedAt,
    messages = messages.map { message ->
        CreativeMessage(
            id = message.id,
            role = message.role,
            content = message.content,
            projectId = message.projectId.orEmpty(),
            taskId = message.taskId.orEmpty(),
            resultUrl = resolveMediaUrl(message.resultUrl.orEmpty()),
            status = message.status.orEmpty(),
            versionNo = message.versionNo,
            createdAt = message.createdAt,
        )
    },
    versions = versions.map { version ->
        CreativeVersion(
            id = version.id,
            versionNo = version.versionNo,
            projectId = version.projectId.orEmpty(),
            taskId = version.taskId.orEmpty(),
            userRequest = version.userRequest,
            finalPrompt = version.finalPrompt,
            style = version.style,
            size = version.size,
            referenceUrls = version.referenceUrls.ifEmpty {
                version.referenceUrl?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
            }.map(::resolveMediaUrl),
            status = version.status,
            resultUrl = resolveMediaUrl(version.resultUrl.orEmpty()),
            safeError = version.safeError.orEmpty(),
            createdAt = version.createdAt,
            updatedAt = version.updatedAt,
        )
    },
)

internal fun VideoTask.toDetailModel(): VideoTaskDetailModel = VideoTaskDetailModel(
    id = id,
    title = title,
    prompt = prompt,
    sourceImageUrls = sourceImageUrls.map(::resolveMediaUrl),
    aspectRatio = aspectRatio,
    durationSeconds = durationSeconds,
    requestedResolution = requestedResolution,
    mode = mode,
    status = status,
    progress = progress,
    provider = provider,
    model = model,
    routingReason = routingReason,
    queueAhead = queueAhead,
    upstreamTaskId = upstreamTaskId,
    resultUrl = resolveMediaUrl(resultUrl),
    creditsCost = creditsCost,
    publicMetadata = JsonObject(metadata).toDetailFields(),
    safeError = safeError,
    refundedAt = refundedAt,
    queuedAt = queuedAt,
    submittedAt = submittedAt,
    completedAt = completedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun JsonElement.toDetailFields(): List<DetailField> = when (this) {
    is JsonObject -> entries.flatMap { (key, value) -> value.flattenField(key) }
    is JsonArray -> mapIndexed { index, value -> DetailField("${index + 1}", value.safeDisplay()) }
    is JsonPrimitive -> contentOrNull?.takeIf(String::isNotBlank)?.let { listOf(DetailField("内容", it)) }.orEmpty()
    JsonNull -> emptyList()
}

private fun JsonElement.flattenField(path: String): List<DetailField> = when (this) {
    is JsonObject -> entries.flatMap { (key, value) -> value.flattenField("$path · $key") }
    is JsonArray, is JsonPrimitive -> safeDisplay().takeIf(String::isNotBlank)
        ?.let { listOf(DetailField(path.humanizeKey(), it)) }
        .orEmpty()
    JsonNull -> emptyList()
}

private fun JsonElement.safeDisplay(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonArray -> joinToString("、") { it.safeDisplay() }.take(600)
    is JsonObject -> entries.joinToString("；") { (key, value) -> "${key.humanizeKey()}：${value.safeDisplay()}" }.take(600)
    JsonNull -> ""
}

private fun String.humanizeKey(): String = replace('_', ' ').trim().ifBlank { "内容" }
