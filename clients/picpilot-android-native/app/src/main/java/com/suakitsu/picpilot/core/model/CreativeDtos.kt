package com.suakitsu.picpilot.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class CreativeChatGenerateRequestDto(
    @SerialName("chat_id") val chatId: String = "",
    val message: String,
    val style: String = "",
    val size: String = "1024x1024",
    val language: String = "zh-CN",
    @SerialName("reference_url") val referenceUrl: String = "",
    @SerialName("reference_urls") val referenceUrls: List<String> = emptyList(),
    val intent: String = "",
    @SerialName("text_mode") val textMode: String = "",
    @SerialName("reference_policy") val referencePolicy: String = "",
    @SerialName("template_id") val templateId: String = "",
)

@Serializable
data class CreativeChatEstimateDto(
    val size: String = "1024x1024",
    @SerialName("quality_label") val qualityLabel: String = "1K",
    @SerialName("credits_cost") val creditsCost: Int = 0,
)

@Serializable
data class ProductImageDto(
    val id: String,
    @SerialName("image_type") val imageType: String = "",
    @SerialName("original_filename") val originalFilename: String = "",
    val url: String = "",
    @SerialName("content_type") val contentType: String = "",
    @SerialName("image_format") val imageFormat: String = "",
    @SerialName("file_size") val fileSize: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class ProjectDetailDto(
    val project: ProjectSummary,
    @SerialName("original_info") val originalInfo: JsonElement = JsonObject(emptyMap()),
    @SerialName("generated_copy") val generatedCopy: JsonElement = JsonObject(emptyMap()),
    @SerialName("confirmed_facts") val confirmedFacts: JsonElement = JsonObject(emptyMap()),
    @SerialName("product_images") val productImages: List<ProductImageDto> = emptyList(),
    @SerialName("image_tasks") val imageTasks: List<ImageTask> = emptyList(),
)

@Serializable
data class CreativeChatMessageDto(
    val id: String,
    @SerialName("chat_id") val chatId: String = "",
    val role: String = "",
    val content: String = "",
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("result_url") val resultUrl: String? = null,
    val status: String? = null,
    @SerialName("version_no") val versionNo: Int? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class CreativeChatVersionDto(
    val id: String,
    @SerialName("chat_id") val chatId: String = "",
    @SerialName("version_no") val versionNo: Int = 0,
    @SerialName("user_message_id") val userMessageId: String? = null,
    @SerialName("assistant_message_id") val assistantMessageId: String? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("user_request") val userRequest: String = "",
    @SerialName("final_prompt") val finalPrompt: String = "",
    val style: String = "",
    val size: String = "",
    @SerialName("reference_url") val referenceUrl: String? = null,
    @SerialName("reference_urls") val referenceUrls: List<String> = emptyList(),
    val status: String = "",
    @SerialName("result_url") val resultUrl: String? = null,
    @SerialName("safe_error") val safeError: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class CreativeChatViewDto(
    val chat: CreativeChatSummary,
    val messages: List<CreativeChatMessageDto> = emptyList(),
    val versions: List<CreativeChatVersionDto> = emptyList(),
)

@Serializable
data class CreativeChatGenerateResponseDto(
    val chat: CreativeChatViewDto,
    val project: ProjectDetailDto? = null,
    @SerialName("final_prompt") val finalPrompt: String = "",
    @SerialName("version_no") val versionNo: Int = 0,
)
