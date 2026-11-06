package com.openimage.modules.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.common.ApiResponse;
import com.openimage.modules.project.dto.ProjectDtos;
import com.openimage.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/commerce-suite")
public class CommerceSuiteController {
    private final CommerceSuiteCatalogService catalog;
    private final CommerceSuiteService service;
    private final ObjectMapper json;

    public CommerceSuiteController(CommerceSuiteCatalogService catalog, CommerceSuiteService service, ObjectMapper json) {
        this.catalog = catalog;
        this.service = service;
        this.json = json;
    }

    @GetMapping("/platforms")
    public ApiResponse<List<CommerceSuiteDtos.PlatformPreset>> platforms() {
        return ApiResponse.ok(catalog.platforms().stream().filter(item -> Boolean.TRUE.equals(item.enabled())).toList());
    }

    @GetMapping("/templates")
    public ApiResponse<List<CommerceSuiteDtos.TemplatePreset>> templates(@RequestParam(required = false) String platform) {
        return ApiResponse.ok(catalog.templates(platform).stream().filter(item -> Boolean.TRUE.equals(item.enabled())).toList());
    }

    @GetMapping("/catalog")
    public ApiResponse<CommerceSuiteDtos.Catalog> catalog() {
        return ApiResponse.ok(catalog.catalog());
    }

    @PostMapping("/estimate")
    public ApiResponse<CommerceSuiteDtos.EstimateResponse> estimate(@RequestBody CommerceSuiteDtos.EstimateRequest request) {
        return ApiResponse.ok(service.estimate(request));
    }

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<CommerceSuiteDtos.GenerateResponse> generateJson(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody CommerceSuiteDtos.GenerateRequest request
    ) throws Exception {
        return ApiResponse.ok(service.generate(user, request, List.of(), null));
    }

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CommerceSuiteDtos.GenerateResponse> generateForm(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam String platform,
        @RequestParam("product_info") String productInfoJson,
        @RequestParam(value = "selected_slots", required = false) String selectedSlotsJson,
        @RequestParam(required = false) String resolution,
        @RequestParam(value = "reference_url", required = false) String referenceUrl,
        @RequestParam(value = "overlay_options", required = false) String overlayOptionsJson,
        @RequestParam(value = "reference", required = false) MultipartFile reference,
        @RequestParam(value = "references", required = false) List<MultipartFile> references
    ) throws Exception {
        CommerceSuiteDtos.ProductInfo productInfo = json.readValue(productInfoJson, CommerceSuiteDtos.ProductInfo.class);
        List<String> selectedSlots = selectedSlotsJson == null || selectedSlotsJson.isBlank()
            ? List.of()
            : json.readValue(selectedSlotsJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        java.util.Map<String, Object> overlayOptions = overlayOptionsJson == null || overlayOptionsJson.isBlank()
            ? java.util.Map.of()
            : json.readValue(overlayOptionsJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        CommerceSuiteDtos.GenerateRequest request = new CommerceSuiteDtos.GenerateRequest(
            platform,
            productInfo,
            selectedSlots,
            resolution,
            List.of(),
            referenceUrl,
            overlayOptions
        );
        return ApiResponse.ok(service.generate(user, request, references == null ? List.of() : references, reference));
    }

    @PostMapping("/projects")
    public ApiResponse<CommerceSuiteDtos.GenerateResponse> createProject(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody CommerceSuiteDtos.GenerateRequest request
    ) throws Exception {
        return ApiResponse.ok(service.generate(user, request, List.of(), null));
    }

    @GetMapping("/projects/{projectId}")
    public ApiResponse<ProjectDtos.ProjectDetail> project(@AuthenticationPrincipal CurrentUser user, @PathVariable String projectId) throws Exception {
        return ApiResponse.ok(service.project(user, projectId));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<ProjectDtos.ImageTaskView> task(@AuthenticationPrincipal CurrentUser user, @PathVariable String taskId) throws Exception {
        return ApiResponse.ok(service.task(user, taskId));
    }

    @PostMapping("/tasks/{taskId}/retry")
    public ApiResponse<ProjectDtos.ImageTaskView> retry(@AuthenticationPrincipal CurrentUser user, @PathVariable String taskId) throws Exception {
        return ApiResponse.ok(service.retry(user, taskId));
    }
}
