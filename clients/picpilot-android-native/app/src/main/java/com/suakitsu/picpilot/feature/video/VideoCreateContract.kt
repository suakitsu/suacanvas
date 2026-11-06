package com.suakitsu.picpilot.feature.video

import android.net.Uri
import androidx.compose.runtime.Immutable
import com.suakitsu.picpilot.core.model.VideoTask
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
data class VideoReferenceImage(
    val localId: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)

data class VideoImageReadResult(
    val images: List<VideoReferenceImage>,
    val errors: List<String>,
)

@Serializable
data class VideoScriptPreviewRequest(
    val prompt: String,
    @SerialName("model_code") val modelCode: String,
    @SerialName("duration_seconds") val durationSeconds: Int,
    val resolution: String,
    @SerialName("aspect_ratio") val aspectRatio: String,
    @SerialName("generate_audio") val generateAudio: Boolean,
    @SerialName("reference_count") val referenceCount: Int,
)

@Serializable
data class VideoScriptPreview(
    @SerialName("optimized_prompt") val optimizedPrompt: String = "",
    val mode: String = "single_shot",
    val source: String = "",
    val summary: String = "",
)

data class VideoCreateRequest(
    val title: String,
    val prompt: String,
    val originalPrompt: String,
    val modelCode: String,
    val resolution: String,
    val durationSeconds: Int,
    val aspectRatio: String,
    val generateAudio: Boolean,
    val seed: Long?,
    val references: List<VideoReferenceImage>,
    val idempotencyKey: String,
)

interface VideoSubmissionGateway {
    suspend fun previewScript(request: VideoScriptPreviewRequest): VideoGatewayResult<VideoScriptPreview>
    suspend fun createTask(request: VideoCreateRequest): VideoGatewayResult<VideoTask>
    suspend fun getTask(taskId: String): VideoGatewayResult<VideoTask>
}

sealed interface VideoGatewayResult<out T> {
    data class Success<T>(val value: T) : VideoGatewayResult<T>
    data class Failure(
        val message: String,
        val code: String = "REQUEST_FAILED",
        val retryable: Boolean = false,
    ) : VideoGatewayResult<Nothing>
}

data class VideoConfirmation(
    val modelName: String,
    val resolution: String,
    val durationSeconds: Int,
    val aspectRatio: String,
    val generateAudio: Boolean,
    val referenceCount: Int,
    val price: Int,
    val credits: Int,
    val priceNotice: String = "",
)
