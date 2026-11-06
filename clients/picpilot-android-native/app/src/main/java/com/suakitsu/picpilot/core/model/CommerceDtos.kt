package com.suakitsu.picpilot.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class CommercePlatformDto(
    val code: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val beta: Boolean = false,
    @SerialName("default_resolution") val defaultResolution: String = "1K",
    @SerialName("default_size") val defaultSize: String = "1024x1024",
    @SerialName("default_slots") val defaultSlots: List<String> = emptyList(),
    @SerialName("style_prompt") val stylePrompt: String = "",
    val language: String = "zh-CN",
    @SerialName("overlay_language") val overlayLanguage: String = "zh-CN",
)

@Serializable
data class CommerceImageSlotDto(
    val code: String,
    val name: String,
    val description: String = "",
    val category: String = "",
    @SerialName("default_size") val defaultSize: String = "1024x1024",
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class CommerceTemplateDto(
    val id: String,
    val platform: String,
    val slot: String,
    val name: String,
    val description: String = "",
    @SerialName("prompt_template") val promptTemplate: String = "",
    @SerialName("negative_prompt") val negativePrompt: String = "",
    @SerialName("overlay_template") val overlayTemplate: JsonObject = JsonObject(emptyMap()),
    @SerialName("default_size") val defaultSize: String = "1024x1024",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("provider_params") val providerParams: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CommerceCatalogDto(
    val platforms: List<CommercePlatformDto> = emptyList(),
    val slots: List<CommerceImageSlotDto> = emptyList(),
    val templates: List<CommerceTemplateDto> = emptyList(),
    @SerialName("cost_rule") val costRule: Map<String, Int> = emptyMap(),
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class CommerceEstimateRequestDto(
    val platform: String,
    @SerialName("selected_slots") val selectedSlots: List<String>,
    val resolution: String,
)

@Serializable
data class CommerceSlotCostDto(
    val slot: String = "",
    val name: String = "",
    val size: String = "",
    @SerialName("credits_cost") val creditsCost: Int = 0,
)

@Serializable
data class CommerceEstimateDto(
    val platform: String = "",
    val resolution: String = "1K",
    @SerialName("image_count") val imageCount: Int = 0,
    @SerialName("credits_cost") val creditsCost: Int = 0,
    @SerialName("slot_costs") val slotCosts: List<CommerceSlotCostDto> = emptyList(),
)

@Serializable
data class CommerceProductInfoDto(
    @SerialName("product_name") val productName: String,
    val category: String = "",
    val brand: String = "",
    @SerialName("selling_points") val sellingPoints: List<String> = emptyList(),
    val material: String = "",
    val specs: String = "",
    val audience: String = "",
    val scenario: String = "",
    @SerialName("price_text") val priceText: String = "",
    @SerialName("promo_text") val promoText: String = "",
    val country: String = "",
    val language: String = "zh-CN",
)

@Serializable
data class CommerceGenerateResponseDto(
    @SerialName("project_id") val projectId: String,
    val project: ProjectDetailDto,
    @SerialName("created_tasks") val createdTasks: List<ImageTask> = emptyList(),
    @SerialName("credits_cost") val creditsCost: Int = 0,
    @SerialName("mock_mode") val mockMode: Boolean = false,
)

@Serializable
data class ImageTranslationLanguageDto(
    val code: String,
    val name: String,
    @SerialName("native_name") val nativeName: String = "",
    @SerialName("prompt_name") val promptName: String = "",
)

@Serializable
data class ImageTranslationGenerateResponseDto(
    @SerialName("project_id") val projectId: String,
    val project: ProjectDetailDto,
    @SerialName("created_tasks") val createdTasks: List<ImageTask> = emptyList(),
    @SerialName("target_language") val targetLanguage: ImageTranslationLanguageDto,
    val resolution: String = "1K",
    @SerialName("credits_cost") val creditsCost: Int = 0,
)
