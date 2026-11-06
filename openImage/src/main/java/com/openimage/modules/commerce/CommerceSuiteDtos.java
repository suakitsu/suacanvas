package com.openimage.modules.commerce;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.openimage.modules.project.dto.ProjectDtos;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class CommerceSuiteDtos {
    private CommerceSuiteDtos() {}

    public record PlatformPreset(
        String code,
        String name,
        String description,
        Boolean enabled,
        Boolean beta,
        @JsonProperty("default_resolution") @JsonAlias("defaultResolution") String defaultResolution,
        @JsonProperty("default_size") @JsonAlias("defaultSize") String defaultSize,
        @JsonProperty("default_slots") @JsonAlias("defaultSlots") List<String> defaultSlots,
        @JsonProperty("style_prompt") @JsonAlias("stylePrompt") String stylePrompt,
        String language,
        @JsonProperty("overlay_language") @JsonAlias("overlayLanguage") String overlayLanguage
    ) {}

    public record ImageSlot(
        String code,
        String name,
        String description,
        String category,
        @JsonProperty("default_size") @JsonAlias("defaultSize") String defaultSize,
        @JsonProperty("sort_order") @JsonAlias("sortOrder") Integer sortOrder
    ) {}

    public record TemplatePreset(
        String id,
        String platform,
        String slot,
        String name,
        String description,
        @JsonProperty("prompt_template") @JsonAlias("promptTemplate") String promptTemplate,
        @JsonProperty("negative_prompt") @JsonAlias("negativePrompt") String negativePrompt,
        @JsonProperty("overlay_template") @JsonAlias("overlayTemplate") Map<String, Object> overlayTemplate,
        @JsonProperty("default_size") @JsonAlias("defaultSize") String defaultSize,
        Boolean enabled,
        @JsonProperty("sort_order") @JsonAlias("sortOrder") Integer sortOrder,
        @JsonProperty("provider_params") @JsonAlias("providerParams") Map<String, Object> providerParams
    ) {}

    public record Catalog(
        List<PlatformPreset> platforms,
        List<ImageSlot> slots,
        List<TemplatePreset> templates,
        @JsonProperty("cost_rule") @JsonAlias("costRule") Map<String, Integer> costRule,
        @JsonProperty("updated_at") @JsonAlias("updatedAt") OffsetDateTime updatedAt
    ) {}

    public record ProductInfo(
        @JsonProperty("product_name") @JsonAlias("productName") String productName,
        String category,
        String brand,
        @JsonProperty("selling_points") @JsonAlias("sellingPoints") List<String> sellingPoints,
        String material,
        String specs,
        String audience,
        String scenario,
        @JsonProperty("price_text") @JsonAlias("priceText") String priceText,
        @JsonProperty("promo_text") @JsonAlias("promoText") String promoText,
        String country,
        String language
    ) {}

    public record GenerateRequest(
        String platform,
        @JsonProperty("product_info") @JsonAlias("productInfo") ProductInfo productInfo,
        @JsonProperty("selected_slots") @JsonAlias("selectedSlots") List<String> selectedSlots,
        String resolution,
        @JsonProperty("reference_image_ids") @JsonAlias("referenceImageIds") List<String> referenceImageIds,
        @JsonProperty("reference_url") @JsonAlias("referenceUrl") String referenceUrl,
        @JsonProperty("overlay_options") @JsonAlias("overlayOptions") Map<String, Object> overlayOptions
    ) {}

    public record EstimateRequest(
        String platform,
        @JsonProperty("selected_slots") @JsonAlias("selectedSlots") List<String> selectedSlots,
        String resolution
    ) {}

    public record EstimateResponse(
        String platform,
        String resolution,
        @JsonProperty("image_count") Integer imageCount,
        @JsonProperty("credits_cost") Integer creditsCost,
        @JsonProperty("slot_costs") List<Map<String, Object>> slotCosts
    ) {}

    public record GenerateResponse(
        @JsonProperty("project_id") String projectId,
        ProjectDtos.ProjectDetail project,
        @JsonProperty("created_tasks") List<ProjectDtos.ImageTaskView> createdTasks,
        @JsonProperty("credits_cost") Integer creditsCost,
        @JsonProperty("mock_mode") Boolean mockMode
    ) {}
}
