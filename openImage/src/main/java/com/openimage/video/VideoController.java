package com.openimage.video;

import com.openimage.common.ApiResponse;
import com.openimage.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/videos")
public class VideoController {
    private final VideoTaskService service;
    private final VideoConfigService config;
    private final VideoScriptService scripts;

    public VideoController(
        VideoTaskService service,
        VideoConfigService config,
        VideoScriptService scripts
    ) {
        this.service = service;
        this.config = config;
        this.scripts = scripts;
    }

    @GetMapping("/config")
    public ApiResponse<VideoDtos.PublicConfig> config() {
        return ApiResponse.ok(config.publicConfig());
    }

    @GetMapping("/tasks")
    public ApiResponse<List<VideoDtos.VideoTaskView>> tasks(@AuthenticationPrincipal CurrentUser user) {
        return ApiResponse.ok(service.listMine(user));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<VideoDtos.VideoTaskView> task(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
        return ApiResponse.ok(service.get(user, id));
    }

    @PostMapping("/script-preview")
    public ApiResponse<VideoDtos.VideoScriptPreviewResponse> previewScript(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody VideoDtos.VideoScriptPreviewRequest request
    ) {
        return ApiResponse.ok(scripts.preview(user, request));
    }

    @PostMapping(value = "/tasks", consumes = "multipart/form-data")
    public ApiResponse<VideoDtos.VideoTaskView> create(
        @AuthenticationPrincipal CurrentUser user,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestParam(defaultValue = "") String title,
        @RequestParam String prompt,
        @RequestParam(name = "aspect_ratio", defaultValue = "9:16") String aspectRatio,
        @RequestParam(defaultValue = "standard") String mode,
        @RequestParam(name = "model_code", required = false) String modelCode,
        @RequestParam(name = "duration_seconds", required = false) Integer durationSeconds,
        @RequestParam(name = "resolution", required = false) String resolution,
        @RequestParam(name = "generate_audio", required = false) Boolean generateAudio,
        @RequestParam(name = "seed", required = false) Long seed,
        @RequestParam(name = "original_prompt", required = false) String originalPrompt,
        @RequestParam(name = "image_urls", required = false) List<String> imageUrls,
        @RequestParam(name = "images", required = false) List<MultipartFile> images
    ) throws Exception {
        return ApiResponse.ok(service.create(
            user, title, prompt, aspectRatio, mode, modelCode, durationSeconds,
            resolution, generateAudio, seed, imageUrls, images, originalPrompt, idempotencyKey
        ));
    }

    @DeleteMapping("/tasks/{id}")
    public ApiResponse<Map<String, Object>> delete(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
        return ApiResponse.ok(service.delete(user, id));
    }
}
