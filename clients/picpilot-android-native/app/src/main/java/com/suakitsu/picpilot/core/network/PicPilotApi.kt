package com.suakitsu.picpilot.core.network

import com.suakitsu.picpilot.core.model.*
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface PicPilotApi {
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): ApiEnvelope<AuthResponse>

    @POST("api/auth/login/email-code")
    suspend fun loginWithCode(@Body request: EmailCodeLoginRequest): ApiEnvelope<AuthResponse>

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): ApiEnvelope<AuthResponse>

    @POST("api/auth/email-code")
    suspend fun sendEmailCode(@Body request: EmailCodeRequest): ApiEnvelope<EmailCodeResult>

    @POST("api/auth/forgot-password")
    suspend fun forgotPassword(@Body request: ForgotPasswordRequest): ApiEnvelope<Map<String, String>>

    @POST("api/auth/password-reset")
    suspend fun resetPassword(@Body request: PasswordResetRequest): ApiEnvelope<Map<String, String>>

    @GET("api/me")
    suspend fun me(): ApiEnvelope<UserProfile>

    @PATCH("api/me")
    suspend fun updateMe(@Body request: Map<String, String>): ApiEnvelope<UserProfile>

    @GET("api/projects")
    suspend fun projects(): ApiEnvelope<List<ProjectSummary>>

    @GET("api/projects/{id}")
    suspend fun project(@Path("id") id: String): ApiEnvelope<ProjectDetailDto>

    @DELETE("api/projects/{id}")
    suspend fun hideProject(@Path("id") id: String): ApiEnvelope<JsonObject>

    @GET("api/projects/{id}/images")
    suspend fun projectImages(@Path("id") id: String): ApiEnvelope<List<ImageTask>>

    @GET("api/projects/creative-chats")
    suspend fun creativeChats(): ApiEnvelope<List<CreativeChatSummary>>

    @GET("api/projects/creative-chats/{chatId}")
    suspend fun creativeChat(@Path("chatId") chatId: String): ApiEnvelope<CreativeChatViewDto>

    @POST("api/projects/creative-chats/estimate")
    suspend fun estimateCreativeChat(
        @Body request: CreativeChatGenerateRequestDto,
    ): ApiEnvelope<CreativeChatEstimateDto>

    @POST("api/projects/creative-chats/generate")
    suspend fun generateCreativeChatJson(
        @Body request: CreativeChatGenerateRequestDto,
    ): ApiEnvelope<CreativeChatGenerateResponseDto>

    @Multipart
    @POST("api/projects/creative-chats/generate")
    suspend fun generateCreativeChatMultipart(
        @Part parts: List<MultipartBody.Part>,
    ): ApiEnvelope<CreativeChatGenerateResponseDto>

    @GET("api/commerce-suite/catalog")
    suspend fun commerceCatalog(): ApiEnvelope<CommerceCatalogDto>

    @POST("api/commerce-suite/estimate")
    suspend fun estimateCommerce(
        @Body request: CommerceEstimateRequestDto,
    ): ApiEnvelope<CommerceEstimateDto>

    @Multipart
    @POST("api/commerce-suite/generate")
    suspend fun generateCommerceMultipart(
        @Part parts: List<MultipartBody.Part>,
    ): ApiEnvelope<CommerceGenerateResponseDto>

    @GET("api/commerce-suite/projects/{projectId}")
    suspend fun commerceProject(
        @Path("projectId") projectId: String,
    ): ApiEnvelope<ProjectDetailDto>

    @GET("api/commerce-suite/tasks/{taskId}")
    suspend fun commerceTask(
        @Path("taskId") taskId: String,
    ): ApiEnvelope<ImageTask>

    @GET("api/image-translation/languages")
    suspend fun imageTranslationLanguages(): ApiEnvelope<List<ImageTranslationLanguageDto>>

    @Multipart
    @POST("api/image-translation/generate")
    suspend fun generateImageTranslation(
        @Part parts: List<MultipartBody.Part>,
    ): ApiEnvelope<ImageTranslationGenerateResponseDto>

    @GET("api/image-translation/projects/{projectId}")
    suspend fun imageTranslationProject(
        @Path("projectId") projectId: String,
    ): ApiEnvelope<ProjectDetailDto>

    @GET("api/image-translation/tasks/{taskId}")
    suspend fun imageTranslationTask(
        @Path("taskId") taskId: String,
    ): ApiEnvelope<ImageTask>

    @GET("api/videos/config")
    suspend fun videoConfig(): ApiEnvelope<VideoPublicConfig>

    @GET("api/videos/tasks")
    suspend fun videoTasks(): ApiEnvelope<List<VideoTask>>

    @GET("api/videos/tasks/{id}")
    suspend fun videoTask(@Path("id") id: String): ApiEnvelope<VideoTask>

    @DELETE("api/videos/tasks/{id}")
    suspend fun hideVideoTask(@Path("id") id: String): ApiEnvelope<JsonObject>

    @GET("api/billing/summary")
    suspend fun billingSummary(): ApiEnvelope<BillingSummary>

    @GET("api/billing/plans")
    suspend fun rechargePlans(): ApiEnvelope<List<RechargePlan>>

    @GET("api/billing/ledger")
    suspend fun creditLedger(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
    ): ApiEnvelope<PageResult<CreditLedger>>

    @GET("api/messages")
    suspend fun messages(): ApiEnvelope<List<StationMessage>>

    @PATCH("api/messages/{id}/read")
    suspend fun markMessageRead(@Path("id") id: String): ApiEnvelope<Map<String, String>>
}
