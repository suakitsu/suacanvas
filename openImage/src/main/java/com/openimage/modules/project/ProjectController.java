package com.openimage.modules.project;

import com.openimage.common.ApiResponse;
import com.openimage.modules.project.dto.ProjectDtos;
import com.openimage.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ProjectDtos.ProjectSummary>> list(@AuthenticationPrincipal CurrentUser user) {
        return ApiResponse.ok(service.listMine(user));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectDtos.ProjectDetail> detail(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) throws Exception {
        return ApiResponse.ok(service.detail(user, id));
    }

    @PostMapping
    public ApiResponse<ProjectDtos.ProjectSummary> create(
        @AuthenticationPrincipal CurrentUser user,
        @Valid @RequestBody ProjectDtos.CreateProjectRequest request
    ) throws Exception {
        return ApiResponse.ok(service.create(user, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<java.util.Map<String, Object>> delete(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
        return ApiResponse.ok(service.deleteProject(user, id));
    }

    @PostMapping("/{id}/generate-copy")
    public ApiResponse<ProjectDtos.GenerateCopyResponse> copy(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) throws Exception {
        return ApiResponse.ok(service.generateCopy(user, id));
    }

    @PostMapping("/optimize-prompt")
    public ApiResponse<java.util.Map<String, Object>> optimizePrompt(@AuthenticationPrincipal CurrentUser user, @RequestBody ProjectDtos.OptimizePromptRequest request) {
        return ApiResponse.ok(service.optimizePrompt(user, request));
    }

    @PostMapping("/canvas-text")
    public ApiResponse<java.util.Map<String, Object>> canvasText(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody ProjectDtos.CanvasTextRequest request
    ) {
        return ApiResponse.ok(service.canvasText(user, request));
    }

    @PostMapping("/domestic-selling-points/suggest")
    public ApiResponse<java.util.Map<String, Object>> suggestDomesticSellingPoints(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody ProjectDtos.DomesticSellingPointSuggestionRequest request
    ) {
        return ApiResponse.ok(service.suggestDomesticSellingPoints(user, request));
    }

    @PostMapping(value = "/aplus-images", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ProjectDtos.ProjectDetail> aplusImage(@AuthenticationPrincipal CurrentUser user, @RequestBody ProjectDtos.SingleImageRequest request) throws Exception {
        return ApiResponse.ok(service.createSingleImageProject(user, request, "aplus"));
    }

    @PostMapping(value = "/aplus-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProjectDtos.ProjectDetail> aplusImageForm(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam(required = false) String productName,
        @RequestParam(required = false) String brand,
        @RequestParam String prompt,
        @RequestParam(required = false) String size,
        @RequestParam(required = false) String language,
        @RequestParam(required = false) String module,
        @RequestParam(required = false) String referenceUrl,
        @RequestParam(required = false) String intent,
        @RequestParam(required = false) String textMode,
        @RequestParam(required = false) String referencePolicy,
        @RequestParam(required = false) String templateId,
        @RequestParam(required = false) MultipartFile reference
    ) throws Exception {
        return ApiResponse.ok(service.createSingleImageProject(user, new ProjectDtos.SingleImageRequest(productName, brand, prompt, size, language, module, referenceUrl, intent, textMode, referencePolicy, templateId), "aplus", reference));
    }

    @PostMapping("/{id}/linked-aplus")
    public ApiResponse<ProjectDtos.ProjectDetail> linkedAplusImage(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String id,
        @RequestBody ProjectDtos.LinkedAplusRequest request
    ) throws Exception {
        return ApiResponse.ok(service.createLinkedAplusProject(user, id, request));
    }

    @PostMapping(value = "/creative-images", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ProjectDtos.ProjectDetail> creativeImage(@AuthenticationPrincipal CurrentUser user, @RequestBody ProjectDtos.SingleImageRequest request) throws Exception {
        return ApiResponse.ok(service.createSingleImageProject(user, request, "creative"));
    }

    @PostMapping(value = "/creative-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProjectDtos.ProjectDetail> creativeImageForm(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam(required = false) String productName,
        @RequestParam(required = false) String brand,
        @RequestParam String prompt,
        @RequestParam(required = false) String size,
        @RequestParam(required = false) String language,
        @RequestParam(required = false) String module,
        @RequestParam(required = false) String referenceUrl,
        @RequestParam(required = false) String intent,
        @RequestParam(required = false) String textMode,
        @RequestParam(required = false) String referencePolicy,
        @RequestParam(required = false) String templateId,
        @RequestParam(required = false) MultipartFile reference,
        @RequestParam(required = false) List<MultipartFile> references
    ) throws Exception {
        return ApiResponse.ok(service.createSingleImageProject(user, new ProjectDtos.SingleImageRequest(productName, brand, prompt, size, language, module, referenceUrl, intent, textMode, referencePolicy, templateId), "creative", references, reference));
    }

    @GetMapping("/creative-chats")
    public ApiResponse<List<ProjectDtos.CreativeChatSummary>> creativeChats(@AuthenticationPrincipal CurrentUser user) {
        return ApiResponse.ok(service.listCreativeChats(user));
    }

    @GetMapping("/creative-chats/{chatId}")
    public ApiResponse<ProjectDtos.CreativeChatView> creativeChat(@AuthenticationPrincipal CurrentUser user, @PathVariable String chatId) {
        return ApiResponse.ok(service.creativeChat(user, chatId));
    }

    @PostMapping(value = "/creative-chats/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ProjectDtos.CreativeChatGenerateResponse> creativeChatGenerate(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody ProjectDtos.CreativeChatGenerateRequest request
    ) throws Exception {
        return ApiResponse.ok(service.generateCreativeChat(user, request));
    }

    @PostMapping("/creative-chats/estimate")
    public ApiResponse<ProjectDtos.CreativeChatEstimateResponse> creativeChatEstimate(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody ProjectDtos.CreativeChatGenerateRequest request
    ) {
        return ApiResponse.ok(service.estimateCreativeChat(user, request));
    }

    @PostMapping(value = "/creative-chats/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProjectDtos.CreativeChatGenerateResponse> creativeChatGenerateForm(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam(required = false) String chatId,
        @RequestParam String message,
        @RequestParam(required = false) String style,
        @RequestParam(required = false) String size,
        @RequestParam(required = false) String language,
        @RequestParam(required = false) String referenceUrl,
        @RequestParam(required = false) List<String> referenceUrls,
        @RequestParam(required = false) String intent,
        @RequestParam(required = false) String textMode,
        @RequestParam(required = false) String referencePolicy,
        @RequestParam(required = false) String templateId,
        @RequestParam(required = false) MultipartFile reference,
        @RequestParam(required = false) List<MultipartFile> references
    ) throws Exception {
        return ApiResponse.ok(service.generateCreativeChat(user, new ProjectDtos.CreativeChatGenerateRequest(chatId, message, style, size, language, referenceUrl, referenceUrls, intent, textMode, referencePolicy, templateId), references, reference));
    }

    @PostMapping("/{id}/confirm-facts")
    public ApiResponse<ProjectDtos.ProjectSummary> facts(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String id,
        @RequestBody ProjectDtos.ConfirmFactsRequest request
    ) throws Exception {
        return ApiResponse.ok(service.confirmFacts(user, id, request));
    }

    @PostMapping("/{id}/product-images")
    public ApiResponse<ProjectDtos.ProductImageView> uploadProductImage(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String id,
        @RequestParam(defaultValue = "main") String imageType,
        @RequestParam("file") MultipartFile file
    ) throws Exception {
        return ApiResponse.ok(service.uploadProductImage(user, id, imageType, file));
    }

    @PostMapping("/{id}/product-images/link")
    public ApiResponse<ProjectDtos.ProductImageView> linkProductImage(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String id,
        @Valid @RequestBody ProjectDtos.LinkProductImageRequest request
    ) throws Exception {
        return ApiResponse.ok(service.linkProductImage(user, id, request));
    }

    @GetMapping("/{id}/product-images")
    public ApiResponse<List<ProjectDtos.ProductImageView>> productImages(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) throws Exception {
        return ApiResponse.ok(service.listProductImages(user, id));
    }

    @PostMapping("/{id}/images")
    public ApiResponse<List<ProjectDtos.ImageTaskView>> images(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) throws Exception {
        return ApiResponse.ok(service.enqueueSevenImages(user, id));
    }

    @PostMapping("/{id}/listing-package")
    public ApiResponse<ProjectDtos.ListingPackageResponse> listingPackage(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String id,
        @RequestBody ProjectDtos.ListingPackageRequest request
    ) throws Exception {
        return ApiResponse.ok(service.enqueueListingPackage(user, id, request));
    }

    @GetMapping("/{id}/images")
    public ApiResponse<List<ProjectDtos.ImageTaskView>> listImages(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
        return ApiResponse.ok(service.listTasks(user, id));
    }

    @DeleteMapping("/{id}/images/{taskId}")
    public ApiResponse<java.util.Map<String, Object>> deleteFailedImage(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String id,
        @PathVariable String taskId
    ) {
        return ApiResponse.ok(service.deleteFailedTask(user, id, taskId));
    }

    @PostMapping(value = "/{id}/images/{taskId}/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ProjectDtos.ImageTaskView> retryImage(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String id,
        @PathVariable String taskId,
        @RequestBody(required = false) ProjectDtos.RetryImageRequest request
    ) throws Exception {
        return ApiResponse.ok(service.retryFailedTask(user, id, taskId, request));
    }

    @PostMapping(value = "/{id}/images/{taskId}/retry", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProjectDtos.ImageTaskView> retryImageForm(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String id,
        @PathVariable String taskId,
        @RequestParam(required = false) String referenceUrl,
        @RequestParam(required = false) String referenceText,
        @RequestParam(required = false) MultipartFile reference
    ) throws Exception {
        return ApiResponse.ok(service.retryFailedTask(user, id, taskId, new ProjectDtos.RetryImageRequest(referenceUrl, referenceText), reference));
    }
}
