package com.suakitsu.picpilot.core.repository

import com.suakitsu.picpilot.core.model.*
import com.suakitsu.picpilot.core.network.ApiResult
import com.suakitsu.picpilot.core.network.PicPilotApi
import com.suakitsu.picpilot.core.network.apiCall
import com.suakitsu.picpilot.core.security.SecureSessionStore
import okhttp3.MultipartBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PicPilotRepository @Inject constructor(
    private val api: PicPilotApi,
    private val session: SecureSessionStore,
) {
    val token = session.token

    suspend fun login(email: String, password: String, remember: Boolean): ApiResult<AuthResponse> {
        val result = apiCall { api.login(LoginRequest(email.trim(), password, remember)) }
        if (result is ApiResult.Success) session.saveToken(result.value.accessToken, persistent = remember)
        return result
    }

    suspend fun loginWithCode(email: String, code: String, remember: Boolean): ApiResult<AuthResponse> {
        val result = apiCall { api.loginWithCode(EmailCodeLoginRequest(email.trim(), code.trim(), remember)) }
        if (result is ApiResult.Success) session.saveToken(result.value.accessToken, persistent = remember)
        return result
    }

    suspend fun register(email: String, password: String, confirm: String, code: String): ApiResult<AuthResponse> {
        val result = apiCall { api.register(RegisterRequest(email.trim(), password, confirm, code.trim())) }
        if (result is ApiResult.Success) session.saveToken(result.value.accessToken, persistent = true)
        return result
    }

    suspend fun sendCode(email: String, purpose: String) = apiCall {
        api.sendEmailCode(EmailCodeRequest(email.trim(), purpose))
    }

    suspend fun resetPassword(email: String, code: String, password: String, confirm: String) = apiCall {
        api.resetPassword(PasswordResetRequest(email.trim(), code.trim(), password, confirm))
    }

    suspend fun me() = apiCall { api.me() }
    suspend fun billingSummary() = apiCall { api.billingSummary() }
    suspend fun projects() = apiCall { api.projects() }
    suspend fun project(id: String) = apiCall { api.project(id) }
    suspend fun hideProject(id: String) = apiCall { api.hideProject(id) }
    suspend fun creativeChats() = apiCall { api.creativeChats() }
    suspend fun creativeChat(id: String) = apiCall { api.creativeChat(id) }
    suspend fun estimateCreativeChat(request: CreativeChatGenerateRequestDto) = apiCall {
        api.estimateCreativeChat(request)
    }
    suspend fun generateCreativeChat(request: CreativeChatGenerateRequestDto) = apiCall {
        api.generateCreativeChatJson(request)
    }
    suspend fun generateCreativeChat(parts: List<MultipartBody.Part>) = apiCall {
        api.generateCreativeChatMultipart(parts)
    }
    suspend fun commerceCatalog() = apiCall { api.commerceCatalog() }
    suspend fun estimateCommerce(request: CommerceEstimateRequestDto) = apiCall {
        api.estimateCommerce(request)
    }
    suspend fun generateCommerce(parts: List<MultipartBody.Part>) = apiCall {
        api.generateCommerceMultipart(parts)
    }
    suspend fun commerceProject(id: String) = apiCall { api.commerceProject(id) }
    suspend fun commerceTask(id: String) = apiCall { api.commerceTask(id) }
    suspend fun imageTranslationLanguages() = apiCall { api.imageTranslationLanguages() }
    suspend fun generateImageTranslation(parts: List<MultipartBody.Part>) = apiCall {
        api.generateImageTranslation(parts)
    }
    suspend fun imageTranslationProject(id: String) = apiCall { api.imageTranslationProject(id) }
    suspend fun imageTranslationTask(id: String) = apiCall { api.imageTranslationTask(id) }
    suspend fun videoConfig() = apiCall { api.videoConfig() }
    suspend fun videoTasks() = apiCall { api.videoTasks() }
    suspend fun videoTask(id: String) = apiCall { api.videoTask(id) }
    suspend fun hideVideoTask(id: String) = apiCall { api.hideVideoTask(id) }
    suspend fun messages() = apiCall { api.messages() }
    suspend fun rechargePlans() = apiCall { api.rechargePlans() }
    suspend fun ledger(page: Int = 1) = apiCall { api.creditLedger(page) }

    fun logout() = session.clear()
}
