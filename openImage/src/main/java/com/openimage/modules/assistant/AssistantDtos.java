package com.openimage.modules.assistant;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public class AssistantDtos {
    public record AssistantStatusResponse(
        boolean enabled,
        String model,
        List<String> models
    ) {}

    public record AssistantMessage(
        String role,
        String content
    ) {}

    public record AssistantChatRequest(
        List<AssistantMessage> messages,
        @JsonProperty("page_context") Map<String, Object> pageContext,
        String locale,
        String model
    ) {}

    public record AssistantChatResponse(
        String reply,
        String intent,
        String action,
        @JsonProperty("action_label") String actionLabel,
        @JsonProperty("action_href") String actionHref,
        @JsonProperty("suggested_prompt") String suggestedPrompt,
        double confidence,
        String model
    ) {}
}
