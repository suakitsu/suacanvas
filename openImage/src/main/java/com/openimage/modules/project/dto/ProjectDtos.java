package com.openimage.modules.project.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class ProjectDtos {
    private ProjectDtos() {}

    public record CreateProjectRequest(
        @JsonAlias("productName") @NotBlank String productName,
        String brand,
        String country,
        String language,
        String description,
        String category,
        String material,
        String dimensions,
        @JsonAlias("visualPrompt")
        String visualPrompt,
        List<String> keywords,
        @JsonAlias("title_keywords")
        List<String> titleKeywords,
        @JsonAlias("selling_point_keywords")
        List<String> sellingPointKeywords,
        @JsonAlias("description_keywords")
        List<String> descriptionKeywords,
        @JsonProperty("include_aplus") @JsonAlias("includeAplus") Boolean includeAplus,
        @JsonProperty("aplus_module") @JsonAlias("aplusModule") String aplusModule,
        @JsonProperty("aplus_size") @JsonAlias("aplusSize") String aplusSize,
        @JsonProperty("aplus_prompt") @JsonAlias("aplusPrompt") String aplusPrompt
    ) {}

    public record ListingPackageRequest(
        @JsonProperty("include_aplus") @JsonAlias("includeAplus") Boolean includeAplus,
        @JsonProperty("aplus_module") @JsonAlias("aplusModule") String aplusModule,
        @JsonProperty("aplus_size") @JsonAlias("aplusSize") String aplusSize,
        @JsonProperty("aplus_prompt") @JsonAlias("aplusPrompt") String aplusPrompt,
        String language
    ) {}

    public record ListingPackageResponse(
        @JsonProperty("image_tasks") List<ImageTaskView> imageTasks,
        @JsonProperty("aplus_project") ProjectDetail aplusProject,
        @JsonProperty("aplus_requested") boolean aplusRequested,
        @JsonProperty("aplus_status") String aplusStatus
    ) {}

    public record ProjectSummary(
        String id,
        String brand,
        @com.fasterxml.jackson.annotation.JsonProperty("product_name") String productName,
        String country,
        String language,
        String status,
        @com.fasterxml.jackson.annotation.JsonProperty("success_images") Integer successImages,
        @com.fasterxml.jackson.annotation.JsonProperty("failed_images") Integer failedImages,
        @com.fasterxml.jackson.annotation.JsonProperty("running_images") Integer runningImages,
        @com.fasterxml.jackson.annotation.JsonProperty("total_images") Integer totalImages,
        @com.fasterxml.jackson.annotation.JsonProperty("credits_cost") Integer creditsCost,
        @com.fasterxml.jackson.annotation.JsonProperty("latest_failure_reason") String latestFailureReason,
        @com.fasterxml.jackson.annotation.JsonProperty("latest_failed_task_id") String latestFailedTaskId,
        @com.fasterxml.jackson.annotation.JsonProperty("cover_url") String coverUrl,
        @com.fasterxml.jackson.annotation.JsonProperty("preview_urls") List<String> previewUrls,
        @com.fasterxml.jackson.annotation.JsonProperty("created_at") OffsetDateTime createdAt,
        @com.fasterxml.jackson.annotation.JsonProperty("updated_at") OffsetDateTime updatedAt,
        @com.fasterxml.jackson.annotation.JsonProperty("source_type") String sourceType,
        @com.fasterxml.jackson.annotation.JsonProperty("source_chat_id") String sourceChatId,
        @com.fasterxml.jackson.annotation.JsonProperty("version_count") Integer versionCount
    ) {}

    public record ProjectDetail(
        ProjectSummary project,
        @com.fasterxml.jackson.annotation.JsonProperty("original_info") Object originalInfo,
        @com.fasterxml.jackson.annotation.JsonProperty("generated_copy") Object generatedCopy,
        @com.fasterxml.jackson.annotation.JsonProperty("confirmed_facts") Object confirmedFacts,
        @com.fasterxml.jackson.annotation.JsonProperty("product_images") List<ProductImageView> productImages,
        @com.fasterxml.jackson.annotation.JsonProperty("image_tasks") List<ImageTaskView> imageTasks
    ) {}

    public record GenerateCopyResponse(@com.fasterxml.jackson.annotation.JsonProperty("project_id") String projectId, String status, @com.fasterxml.jackson.annotation.JsonProperty("generated_copy") Object generatedCopy) {}

    public record ConfirmFactsRequest(@JsonAlias("confirmed_facts") Map<String, Object> confirmedFacts) {}

    public record OptimizePromptRequest(
        String prompt,
        @com.fasterxml.jackson.annotation.JsonProperty("product_name") @JsonAlias("productName") String productName,
        String marketplace,
        String language
    ) {}

    public record CanvasTextRequest(
        String protocol,
        String model,
        @JsonProperty("request_body") @JsonAlias("requestBody") Map<String, Object> requestBody,
        String feature
    ) {}

    public record DomesticSellingPointSuggestionRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("product_name") @JsonAlias("productName") String productName,
        String platform,
        String usage,
        String specs,
        String audience,
        String language
    ) {}

    public record SingleImageRequest(
        @JsonAlias("productName") String productName,
        String brand,
        String prompt,
        String size,
        String language,
        String module,
        @com.fasterxml.jackson.annotation.JsonProperty("reference_url") @JsonAlias("referenceUrl") String referenceUrl,
        String intent,
        @com.fasterxml.jackson.annotation.JsonProperty("text_mode") @JsonAlias("textMode") String textMode,
        @com.fasterxml.jackson.annotation.JsonProperty("reference_policy") @JsonAlias("referencePolicy") String referencePolicy,
        @com.fasterxml.jackson.annotation.JsonProperty("template_id") @JsonAlias("templateId") String templateId
    ) {
        public SingleImageRequest(String productName, String brand, String prompt, String size, String language, String module, String referenceUrl) {
            this(productName, brand, prompt, size, language, module, referenceUrl, "", "", "", "");
        }
    }

    public record LinkedAplusRequest(
        String prompt,
        String size,
        String language,
        String module
    ) {}

    public record CreativeChatGenerateRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("chat_id") @JsonAlias("chatId") String chatId,
        String message,
        String style,
        String size,
        String language,
        @com.fasterxml.jackson.annotation.JsonProperty("reference_url") @JsonAlias("referenceUrl") String referenceUrl,
        @com.fasterxml.jackson.annotation.JsonProperty("reference_urls") @JsonAlias("referenceUrls") List<String> referenceUrls,
        String intent,
        @com.fasterxml.jackson.annotation.JsonProperty("text_mode") @JsonAlias("textMode") String textMode,
        @com.fasterxml.jackson.annotation.JsonProperty("reference_policy") @JsonAlias("referencePolicy") String referencePolicy,
        @com.fasterxml.jackson.annotation.JsonProperty("template_id") @JsonAlias("templateId") String templateId
    ) {
        public CreativeChatGenerateRequest(String chatId, String message, String style, String size, String language, String referenceUrl) {
            this(chatId, message, style, size, language, referenceUrl, List.of(), "", "", "", "");
        }
    }

    public record CreativeChatEstimateResponse(
        String size,
        @com.fasterxml.jackson.annotation.JsonProperty("quality_label") String qualityLabel,
        @com.fasterxml.jackson.annotation.JsonProperty("credits_cost") Integer creditsCost
    ) {}

    public record CreativeChatSummary(
        String id,
        String title,
        String style,
        String size,
        String language,
        @com.fasterxml.jackson.annotation.JsonProperty("reference_url") String referenceUrl,
        @com.fasterxml.jackson.annotation.JsonProperty("latest_status") String latestStatus,
        @com.fasterxml.jackson.annotation.JsonProperty("latest_result_url") String latestResultUrl,
        @com.fasterxml.jackson.annotation.JsonProperty("version_count") Integer versionCount,
        @com.fasterxml.jackson.annotation.JsonProperty("created_at") OffsetDateTime createdAt,
        @com.fasterxml.jackson.annotation.JsonProperty("updated_at") OffsetDateTime updatedAt
    ) {}

    public record CreativeChatMessageView(
        String id,
        @com.fasterxml.jackson.annotation.JsonProperty("chat_id") String chatId,
        String role,
        String content,
        @com.fasterxml.jackson.annotation.JsonProperty("project_id") String projectId,
        @com.fasterxml.jackson.annotation.JsonProperty("task_id") String taskId,
        @com.fasterxml.jackson.annotation.JsonProperty("result_url") String resultUrl,
        String status,
        @com.fasterxml.jackson.annotation.JsonProperty("version_no") Integer versionNo,
        @com.fasterxml.jackson.annotation.JsonProperty("created_at") OffsetDateTime createdAt
    ) {}

    public record CreativeChatVersionView(
        String id,
        @com.fasterxml.jackson.annotation.JsonProperty("chat_id") String chatId,
        @com.fasterxml.jackson.annotation.JsonProperty("version_no") Integer versionNo,
        @com.fasterxml.jackson.annotation.JsonProperty("user_message_id") String userMessageId,
        @com.fasterxml.jackson.annotation.JsonProperty("assistant_message_id") String assistantMessageId,
        @com.fasterxml.jackson.annotation.JsonProperty("project_id") String projectId,
        @com.fasterxml.jackson.annotation.JsonProperty("task_id") String taskId,
        @com.fasterxml.jackson.annotation.JsonProperty("user_request") String userRequest,
        @com.fasterxml.jackson.annotation.JsonProperty("final_prompt") String finalPrompt,
        String style,
        String size,
        @com.fasterxml.jackson.annotation.JsonProperty("reference_url") String referenceUrl,
        @com.fasterxml.jackson.annotation.JsonProperty("reference_urls") List<String> referenceUrls,
        String status,
        @com.fasterxml.jackson.annotation.JsonProperty("result_url") String resultUrl,
        @com.fasterxml.jackson.annotation.JsonProperty("safe_error") String safeError,
        @com.fasterxml.jackson.annotation.JsonProperty("created_at") OffsetDateTime createdAt,
        @com.fasterxml.jackson.annotation.JsonProperty("updated_at") OffsetDateTime updatedAt
    ) {}

    public record CreativeChatView(
        CreativeChatSummary chat,
        List<CreativeChatMessageView> messages,
        List<CreativeChatVersionView> versions
    ) {}

    public record CreativeChatGenerateResponse(
        CreativeChatView chat,
        ProjectDetail project,
        @com.fasterxml.jackson.annotation.JsonProperty("final_prompt") String finalPrompt,
        @com.fasterxml.jackson.annotation.JsonProperty("version_no") Integer versionNo
    ) {}

    public record ProductImageView(
        String id,
        @com.fasterxml.jackson.annotation.JsonProperty("image_type") String imageType,
        @com.fasterxml.jackson.annotation.JsonProperty("original_filename") String originalFilename,
        String url,
        @com.fasterxml.jackson.annotation.JsonProperty("content_type") String contentType,
        @com.fasterxml.jackson.annotation.JsonProperty("image_format") String imageFormat,
        @com.fasterxml.jackson.annotation.JsonProperty("file_size") Long fileSize,
        Integer width,
        Integer height,
        @com.fasterxml.jackson.annotation.JsonProperty("created_at") OffsetDateTime createdAt
    ) {}

    public record LinkProductImageRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("image_type") @JsonAlias("imageType") String imageType,
        @NotBlank String url
    ) {}

    public record RetryImageRequest(
        @com.fasterxml.jackson.annotation.JsonProperty("reference_url") @JsonAlias("referenceUrl") String referenceUrl,
        @com.fasterxml.jackson.annotation.JsonProperty("reference_text") @JsonAlias("referenceText") String referenceText
    ) {}

    public record ImageTaskView(
        String id,
        @com.fasterxml.jackson.annotation.JsonProperty("image_type") String imageType,
        @com.fasterxml.jackson.annotation.JsonProperty("image_name") String imageName,
        String status,
        @com.fasterxml.jackson.annotation.JsonProperty("prompt_snapshot") String promptSnapshot,
        @com.fasterxml.jackson.annotation.JsonProperty("result_url") String resultUrl,
        @com.fasterxml.jackson.annotation.JsonProperty("credits_cost") Integer creditsCost,
        @com.fasterxml.jackson.annotation.JsonIgnore String provider,
        @com.fasterxml.jackson.annotation.JsonIgnore String model,
        @com.fasterxml.jackson.annotation.JsonProperty("safe_error") String safeError,
        @com.fasterxml.jackson.annotation.JsonProperty("created_at") OffsetDateTime createdAt
    ) {}
}
