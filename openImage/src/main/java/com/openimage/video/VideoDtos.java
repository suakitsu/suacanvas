package com.openimage.video;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class VideoDtos {
    private VideoDtos() {}

    public record VideoTaskView(
        String id,
        String title,
        String prompt,
        @JsonProperty("source_image_urls") List<String> sourceImageUrls,
        @JsonProperty("aspect_ratio") String aspectRatio,
        @JsonProperty("duration_seconds") Integer durationSeconds,
        @JsonProperty("requested_resolution") String requestedResolution,
        String mode,
        String status,
        Integer progress,
        String provider,
        String model,
        @JsonProperty("routing_reason") String routingReason,
        @JsonProperty("queue_ahead") Integer queueAhead,
        @JsonProperty("upstream_task_id") String upstreamTaskId,
        @JsonProperty("result_url") String resultUrl,
        @JsonProperty("credits_cost") Integer creditsCost,
        Map<String, Object> metadata,
        @JsonProperty("safe_error") String safeError,
        @JsonProperty("refunded_at") OffsetDateTime refundedAt,
        @JsonProperty("queued_at") OffsetDateTime queuedAt,
        @JsonProperty("submitted_at") OffsetDateTime submittedAt,
        @JsonProperty("completed_at") OffsetDateTime completedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
    ) {}

    public record PublicTier(
        boolean enabled,
        @JsonProperty("duration_seconds") int durationSeconds,
        String resolution,
        int price
    ) {}

    public record VideoVariant(
        String resolution,
        @JsonProperty("duration_seconds") int durationSeconds,
        int price,
        boolean enabled
    ) {}

    public record VideoModelConfig(
        String code,
        String name,
        String description,
        String provider,
        String model,
        boolean enabled,
        @JsonProperty("default_resolution") String defaultResolution,
        @JsonProperty("default_duration_seconds") int defaultDurationSeconds,
        @JsonProperty("duration_selectable") boolean durationSelectable,
        @JsonProperty("audio_toggle") boolean audioToggle,
        @JsonProperty("max_reference_images") int maxReferenceImages,
        @JsonProperty("aspect_ratios") List<String> aspectRatios,
        List<String> resolutions,
        List<Integer> durations,
        List<VideoVariant> variants
    ) {}

    public record PublicConfig(
        boolean enabled,
        @JsonProperty("mock_mode") boolean mockMode,
        @JsonProperty("aspect_ratios") List<String> aspectRatios,
        PublicTier standard,
        PublicTier quality,
        @JsonProperty("default_model") String defaultModel,
        @JsonProperty("model_configs") List<VideoModelConfig> modelConfigs
    ) {}

    public record VideoScriptPreviewRequest(
        String prompt,
        @JsonProperty("model_code") String modelCode,
        @JsonProperty("duration_seconds") Integer durationSeconds,
        String resolution,
        @JsonProperty("aspect_ratio") String aspectRatio,
        @JsonProperty("generate_audio") Boolean generateAudio,
        @JsonProperty("reference_count") Integer referenceCount
    ) {}

    public record VideoScriptPreviewResponse(
        @JsonProperty("optimized_prompt") String optimizedPrompt,
        String mode,
        String source,
        String summary
    ) {}

    public record VideoVariantUpdate(
        String resolution,
        @JsonProperty("duration_seconds") int durationSeconds,
        int price,
        boolean enabled
    ) {}

    public record VideoModelConfigUpdate(
        String code,
        boolean enabled,
        List<VideoVariantUpdate> variants
    ) {}

    public record AgnesKeyUpdate(
        String id,
        String label,
        boolean enabled,
        int concurrency,
        @JsonProperty("api_key") String apiKey,
        @JsonProperty("clear_api_key") Boolean clearApiKey
    ) {}

    public record AdminConfigUpdate(
        Boolean enabled,
        @JsonProperty("paid_provider_requested") Boolean paidProviderRequested,
        @JsonProperty("gemini_enabled") Boolean geminiEnabled,
        @JsonProperty("seedance_enabled") Boolean seedanceEnabled,
        @JsonProperty("agnes_route_enabled") Boolean agnesRouteEnabled,
        @JsonProperty("standard_enabled") Boolean standardEnabled,
        @JsonProperty("standard_duration_seconds") Integer standardDurationSeconds,
        @JsonProperty("standard_resolution") String standardResolution,
        @JsonProperty("standard_price") Integer standardPrice,
        @JsonProperty("quality_enabled") Boolean qualityEnabled,
        @JsonProperty("quality_duration_seconds") Integer qualityDurationSeconds,
        @JsonProperty("quality_resolution") String qualityResolution,
        @JsonProperty("quality_price") Integer qualityPrice,
        @JsonProperty("agnes_route_percent") Integer agnesRoutePercent,
        @JsonProperty("agnes_daily_limit") Integer agnesDailyLimit,
        @JsonProperty("agnes_concurrency") Integer agnesConcurrency,
        @JsonProperty("agnes_circuit_failures") Integer agnesCircuitFailures,
        @JsonProperty("poll_seconds") Integer pollSeconds,
        @JsonProperty("timeout_minutes") Integer timeoutMinutes,
        @JsonProperty("agnes_model") String agnesModel,
        @JsonProperty("agnes_api_key") String agnesApiKey,
        @JsonProperty("clear_agnes_api_key") Boolean clearAgnesApiKey,
        @JsonProperty("agnes_keys") List<AgnesKeyUpdate> agnesKeys,
        @JsonProperty("apimart_api_key") String apimartApiKey,
        @JsonProperty("clear_apimart_api_key") Boolean clearApimartApiKey,
        @JsonProperty("hfsy_enabled") Boolean hfsyEnabled,
        @JsonProperty("hfsy_api_key") String hfsyApiKey,
        @JsonProperty("clear_hfsy_api_key") Boolean clearHfsyApiKey,
        @JsonProperty("model_configs") List<VideoModelConfigUpdate> modelConfigs
    ) {
        public AdminConfigUpdate(
            Boolean enabled,
            Boolean paidProviderRequested,
            Boolean geminiEnabled,
            Boolean seedanceEnabled,
            Boolean agnesRouteEnabled,
            Boolean standardEnabled,
            Integer standardDurationSeconds,
            String standardResolution,
            Integer standardPrice,
            Boolean qualityEnabled,
            Integer qualityDurationSeconds,
            String qualityResolution,
            Integer qualityPrice,
            Integer agnesRoutePercent,
            Integer agnesDailyLimit,
            Integer agnesConcurrency,
            Integer agnesCircuitFailures,
            Integer pollSeconds,
            Integer timeoutMinutes,
            String agnesModel,
            String agnesApiKey,
            Boolean clearAgnesApiKey,
            List<AgnesKeyUpdate> agnesKeys,
            String apimartApiKey,
            Boolean clearApimartApiKey
        ) {
            this(
                enabled, paidProviderRequested, geminiEnabled, seedanceEnabled, agnesRouteEnabled,
                standardEnabled, standardDurationSeconds, standardResolution, standardPrice,
                qualityEnabled, qualityDurationSeconds, qualityResolution, qualityPrice,
                agnesRoutePercent, agnesDailyLimit, agnesConcurrency, agnesCircuitFailures,
                pollSeconds, timeoutMinutes, agnesModel, agnesApiKey, clearAgnesApiKey,
                agnesKeys, apimartApiKey, clearApimartApiKey,
                null, null, null, null
            );
        }
    }
}
