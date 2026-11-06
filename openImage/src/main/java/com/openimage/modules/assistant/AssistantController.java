package com.openimage.modules.assistant;

import com.openimage.common.ApiResponse;
import com.openimage.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {
    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public ApiResponse<AssistantDtos.AssistantStatusResponse> status() {
        return ApiResponse.ok(service.status());
    }

    @PostMapping("/chat")
    public ApiResponse<AssistantDtos.AssistantChatResponse> chat(
        @AuthenticationPrincipal CurrentUser user,
        @RequestBody AssistantDtos.AssistantChatRequest request
    ) throws Exception {
        return ApiResponse.ok(service.chat(user, request));
    }
}
