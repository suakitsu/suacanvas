package com.openimage.modules.translation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageTranslationServiceTest {
    @Test
    void choosesNearestSupportedAspectAtRequestedResolution() {
        assertThat(ImageTranslationService.generationSize(1200, 1200, "1K")).isEqualTo("1024x1024");
        assertThat(ImageTranslationService.generationSize(1200, 1600, "2K")).isEqualTo("1536x2048");
        assertThat(ImageTranslationService.generationSize(1920, 1080, "4K")).isEqualTo("3840x2160");
    }

    @Test
    void strictPromptLimitsEditsToExistingText() {
        ImageTranslationDtos.LanguageOption language = new ImageTranslationDtos.LanguageOption(
            "ms-MY",
            "马来语（马来西亚）",
            "Bahasa Melayu",
            "Malay for Malaysia"
        );

        String prompt = ImageTranslationService.translationPrompt(language);

        assertThat(prompt).contains("Malay for Malaysia (ms-MY)");
        assertThat(prompt).contains("Change pixels only inside existing translatable text regions");
        assertThat(prompt).contains("Do not summarize, omit, combine, expand, or invent marketing claims");
        assertThat(prompt).contains("Never replace Taobao, Tmall, 1688, JD, Xiaohongshu, PicPilot");
        assertThat(prompt).contains("Use standard Malaysian Bahasa Melayu only");
        assertThat(prompt).contains("the only intended visual difference between input and output is the language");
    }

    @Test
    void givesEveryBatchTaskAUniqueImageType() {
        assertThat(ImageTranslationService.taskImageType(0)).isEqualTo("image_translation_01");
        assertThat(ImageTranslationService.taskImageType(9)).isEqualTo("image_translation_10");
    }
}
