package com.openimage.modules.translation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.openimage.modules.project.dto.ProjectDtos;

import java.util.List;

public final class ImageTranslationDtos {
    private ImageTranslationDtos() {}

    public record LanguageOption(
        String code,
        String name,
        @JsonProperty("native_name") String nativeName,
        @JsonProperty("prompt_name") String promptName
    ) {}

    public record GenerateResponse(
        @JsonProperty("project_id") String projectId,
        ProjectDtos.ProjectDetail project,
        @JsonProperty("created_tasks") List<ProjectDtos.ImageTaskView> createdTasks,
        @JsonProperty("target_language") LanguageOption targetLanguage,
        String resolution,
        @JsonProperty("credits_cost") Integer creditsCost
    ) {}
}
