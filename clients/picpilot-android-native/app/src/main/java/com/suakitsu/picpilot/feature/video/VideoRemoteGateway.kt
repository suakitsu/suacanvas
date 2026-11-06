package com.suakitsu.picpilot.feature.video

import android.content.ContentResolver
import android.content.Context
import com.suakitsu.picpilot.BuildConfig
import com.suakitsu.picpilot.core.model.ApiEnvelope
import com.suakitsu.picpilot.core.model.VideoTask
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSink

@Singleton
class VideoRemoteGateway @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    @ApplicationContext private val context: Context,
) : VideoSubmissionGateway {
    override suspend fun previewScript(
        request: VideoScriptPreviewRequest,
    ): VideoGatewayResult<VideoScriptPreview> {
        val body = json.encodeToString(request)
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(apiUrl("api/videos/script-preview"))
            .post(body)
            .build()
        return execute(httpRequest)
    }

    override suspend fun createTask(request: VideoCreateRequest): VideoGatewayResult<VideoTask> {
        if (request.idempotencyKey.length !in 16..128 ||
            !request.idempotencyKey.matches(IDEMPOTENCY_PATTERN)
        ) {
            return VideoGatewayResult.Failure("任务标识无效，请重新确认", "INVALID_IDEMPOTENCY_KEY")
        }
        if (request.references.isEmpty()) {
            return VideoGatewayResult.Failure("请至少选择一张商品参考图", "REFERENCE_REQUIRED")
        }

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .field("title", request.title.trim())
            .field("prompt", request.prompt.trim())
            .field("aspect_ratio", request.aspectRatio)
            .field("model_code", request.modelCode)
            .field("duration_seconds", request.durationSeconds.toString())
            .field("resolution", request.resolution)
            .field("generate_audio", request.generateAudio.toString())
            .apply {
                request.seed?.let { field("seed", it.toString()) }
                request.originalPrompt.trim().takeIf(String::isNotBlank)?.let {
                    field("original_prompt", it)
                }
                request.references.forEach { image ->
                    addFormDataPart(
                        "images",
                        safeFilename(image.displayName),
                        ContentUriRequestBody(
                            resolver = context.contentResolver,
                            image = image,
                        ),
                    )
                }
            }
            .build()

        val httpRequest = Request.Builder()
            .url(apiUrl("api/videos/tasks"))
            .header("Idempotency-Key", request.idempotencyKey)
            .post(multipart)
            .build()
        return execute(httpRequest)
    }

    override suspend fun getTask(taskId: String): VideoGatewayResult<VideoTask> {
        if (!taskId.matches(TASK_ID_PATTERN)) {
            return VideoGatewayResult.Failure("任务编号无效", "INVALID_TASK_ID")
        }
        val httpRequest = Request.Builder()
            .url(apiUrl("api/videos/tasks/$taskId"))
            .get()
            .build()
        return execute(httpRequest)
    }

    private suspend inline fun <reified T> execute(request: Request): VideoGatewayResult<T> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    decodeResponse(response)
                }
            } catch (_: SecurityException) {
                VideoGatewayResult.Failure(
                    "文件读取失败，请重新选择参考图",
                    "FILE_PERMISSION_LOST",
                )
            } catch (_: IOException) {
                VideoGatewayResult.Failure(
                    "网络连接异常，请检查网络后重试",
                    "NETWORK_ERROR",
                    retryable = true,
                )
            } catch (_: Throwable) {
                VideoGatewayResult.Failure("服务暂时不可用，请稍后重试")
            }
        }

    private inline fun <reified T> decodeResponse(response: Response): VideoGatewayResult<T> {
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            return VideoGatewayResult.Failure(
                message = safeServerMessage(raw, response.code),
                code = "HTTP_${response.code}",
                retryable = response.code == 408 || response.code == 429 || response.code >= 500,
            )
        }
        val envelope = runCatching { json.decodeFromString<ApiEnvelope<T>>(raw) }
            .getOrElse {
                return VideoGatewayResult.Failure("服务返回异常，请稍后重试", "INVALID_RESPONSE")
            }
        val data = envelope.data
        return if (envelope.success && data != null) {
            VideoGatewayResult.Success(data)
        } else {
            VideoGatewayResult.Failure(
                envelope.message.ifBlank { "服务暂时不可用，请稍后重试" },
                envelope.code,
                retryable = envelope.code in RETRYABLE_CODES,
            )
        }
    }

    private fun safeServerMessage(raw: String, status: Int): String {
        val serverMessage = runCatching {
            json.parseToJsonElement(raw).jsonObject["message"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf(::isSafeUserMessage)
        if (serverMessage != null) return serverMessage
        return when (status) {
            400 -> "提交参数有误，请检查后重试"
            401 -> "登录状态已失效，请重新登录"
            403 -> "当前操作没有权限"
            409 -> "任务状态已变化，请刷新后重试"
            413 -> "参考图过大，请压缩后重试"
            429 -> "操作过于频繁，请稍后再试"
            else -> "服务暂时不可用，请稍后重试"
        }
    }

    private fun apiUrl(path: String) = BuildConfig.API_BASE_URL.toHttpUrl()
        .newBuilder()
        .addPathSegments(path)
        .build()
}

private fun isSafeUserMessage(value: String): Boolean {
    if (value.isBlank() || value.length > 180 || value.any { it == '\r' || it == '\n' }) return false
    val lowered = value.lowercase()
    return listOf(
        "exception", "stacktrace", "java.", "org.springframework", "select ", "insert ",
        "c:\\", "/var/", "/home/", "authorization", "bearer ", "api_key", "apikey",
    ).none(lowered::contains)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VideoGatewayModule {
    @Binds
    @Singleton
    abstract fun bindVideoSubmissionGateway(implementation: VideoRemoteGateway): VideoSubmissionGateway
}

private class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val image: VideoReferenceImage,
) : RequestBody() {
    override fun contentType() = image.mimeType.toMediaType()
    override fun contentLength(): Long = image.sizeBytes

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(image.uri)
            ?: throw IOException("Unable to open selected image")
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_UPLOAD_BYTES) throw IOException("Selected image is too large")
                sink.write(buffer, 0, count)
            }
        }
    }
}

private fun MultipartBody.Builder.field(name: String, value: String) = apply {
    addFormDataPart(name, value)
}

private fun safeFilename(value: String): String {
    val cleaned = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
    return cleaned.ifBlank { "reference-image" }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val IDEMPOTENCY_PATTERN = Regex("^[A-Za-z0-9._:-]+$")
private val TASK_ID_PATTERN = Regex("^[A-Za-z0-9_-]{6,128}$")
private val RETRYABLE_CODES = setOf("NETWORK_ERROR", "UPSTREAM_TIMEOUT", "PROVIDER_UNAVAILABLE")
private const val MAX_UPLOAD_BYTES = 30L * 1024L * 1024L
