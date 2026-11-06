package com.openimage.modules.translation;

import com.openimage.common.ApiResponse;
import com.openimage.modules.project.dto.ProjectDtos;
import com.openimage.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/image-translation")
public class ImageTranslationController {
    private final ImageTranslationService service;

    public ImageTranslationController(ImageTranslationService service) {
        this.service = service;
    }

    @GetMapping("/languages")
    public ApiResponse<List<ImageTranslationDtos.LanguageOption>> languages() {
        return ApiResponse.ok(service.languages());
    }

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImageTranslationDtos.GenerateResponse> generate(
        @AuthenticationPrincipal CurrentUser user,
        @RequestParam("target_language") String targetLanguage,
        @RequestParam(defaultValue = "1K") String resolution,
        @RequestParam("images") List<MultipartFile> images
    ) throws Exception {
        return ApiResponse.ok(service.generate(user, targetLanguage, resolution, images));
    }

    @GetMapping("/projects/{projectId}")
    public ApiResponse<ProjectDtos.ProjectDetail> project(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String projectId
    ) throws Exception {
        return ApiResponse.ok(service.project(user, projectId));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<ProjectDtos.ImageTaskView> task(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String taskId
    ) throws Exception {
        return ApiResponse.ok(service.task(user, taskId));
    }

    @PostMapping("/tasks/{taskId}/retry")
    public ApiResponse<ProjectDtos.ImageTaskView> retry(
        @AuthenticationPrincipal CurrentUser user,
        @PathVariable String taskId
    ) throws Exception {
        return ApiResponse.ok(service.retry(user, taskId));
    }
}
