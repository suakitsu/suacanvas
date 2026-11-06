package com.suakitsu.picpilot.integration

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.suakitsu.picpilot.BuildConfig
import com.suakitsu.picpilot.core.model.ApiEnvelope
import com.suakitsu.picpilot.core.model.BillingSummary
import com.suakitsu.picpilot.core.model.StationMessage
import com.suakitsu.picpilot.core.model.UserProfile
import com.suakitsu.picpilot.core.network.ApiResult
import com.suakitsu.picpilot.core.network.PicPilotApi
import com.suakitsu.picpilot.core.network.apiCall
import com.suakitsu.picpilot.core.settings.AppPreferences
import com.suakitsu.picpilot.feature.messages.MessageWriteGateway
import com.suakitsu.picpilot.feature.profile.ProfileWriteGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

@Singleton
class ProductionProfileMessageGateway @Inject constructor(
    private val api: PicPilotApi,
    private val client: OkHttpClient,
    private val json: Json,
    private val preferences: AppPreferences,
    @ApplicationContext private val context: Context,
) : MessageWriteGateway, ProfileWriteGateway {
    private val avatarApi: AvatarApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AvatarApi::class.java)
    }

    override suspend fun messages(): ApiResult<List<StationMessage>> = apiCall { api.messages() }

    override suspend fun markRead(id: String): ApiResult<Unit> = when (
        val result = apiCall { api.markMessageRead(id) }
    ) {
        is ApiResult.Success -> ApiResult.Success(Unit)
        is ApiResult.Failure -> result
    }

    override suspend fun profile(): ApiResult<UserProfile> = apiCall { api.me() }

    override suspend fun billing(): ApiResult<BillingSummary> = apiCall { api.billingSummary() }

    override suspend fun updateProfile(
        nickname: String,
        uiLanguage: String,
        theme: String,
    ): ApiResult<UserProfile> {
        val result = apiCall {
            api.updateMe(
                mapOf(
                    "nickname" to nickname.trim(),
                    "ui_language" to uiLanguage,
                    "theme" to theme,
                ),
            )
        }
        if (result is ApiResult.Success) {
            preferences.update(result.value.theme, result.value.uiLanguage)
        }
        return result
    }

    override suspend fun uploadAvatar(uri: Uri): ApiResult<UserProfile> {
        val resolver = context.contentResolver
        val fileName = queryName(uri).ifBlank { "avatar" }
        val mime = resolver.getType(uri).orEmpty().lowercase().ifBlank { inferImageMime(fileName) }
        if (!mime.startsWith("image/")) {
            return ApiResult.Failure("请选择 JPG、PNG、WebP 或 HEIC 图片", "INVALID_AVATAR_TYPE")
        }
        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_AVATAR_BYTES) {
                        return ApiResult.Failure("头像不能超过 2MB", "AVATAR_TOO_LARGE")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: return ApiResult.Failure("无法读取所选图片", "AVATAR_READ_FAILED")
        } catch (_: IOException) {
            return ApiResult.Failure("无法读取所选图片", "AVATAR_READ_FAILED")
        } catch (_: SecurityException) {
            return ApiResult.Failure("没有读取该图片的权限，请重新选择", "AVATAR_PERMISSION_DENIED")
        }
        if (bytes.isEmpty()) return ApiResult.Failure("所选图片为空", "AVATAR_EMPTY")
        val body = bytes.toRequestBody(mime.toMediaType())
        val part = MultipartBody.Part.createFormData("file", fileName, body)
        return apiCall { avatarApi.uploadAvatar(part) }
    }

    private fun queryName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("")

    private fun inferImageMime(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        else -> ""
    }

    private interface AvatarApi {
        @Multipart
        @POST("api/me/avatar")
        suspend fun uploadAvatar(@Part file: MultipartBody.Part): ApiEnvelope<UserProfile>
    }

    private companion object {
        const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProfileMessageGatewayModule {
    @Binds
    @Singleton
    abstract fun messageGateway(implementation: ProductionProfileMessageGateway): MessageWriteGateway

    @Binds
    @Singleton
    abstract fun profileGateway(implementation: ProductionProfileMessageGateway): ProfileWriteGateway
}
