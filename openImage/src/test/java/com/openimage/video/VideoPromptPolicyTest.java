package com.openimage.video;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoPromptPolicyTest {
    @Test
    void detectsExplicitStoryboardAndSingleShotRequests() {
        assertThat(VideoPromptPolicy.modeFor("镜头1展示商品，镜头2进入使用场景", "hfsy"))
            .isEqualTo("storyboard");
        assertThat(VideoPromptPolicy.modeFor("一镜到底展示商品细节", "hfsy"))
            .isEqualTo("single_shot");
        assertThat(VideoPromptPolicy.modeFor("镜头1展示商品，镜头2进入场景", "agnes"))
            .isEqualTo("single_shot");
    }

    @Test
    void fallbackUsesProviderSpecificReferencesAndExactDuration() {
        String hfsy = VideoPromptPolicy.fallbackScript(
            "展示雨伞防水细节", "hfsy", 8, 2, true
        );
        String apimart = VideoPromptPolicy.fallbackScript(
            "展示雨伞防水细节", "seedance", 8, 2, false
        );

        assertThat(hfsy).contains("8 秒", "@图1", "@图2", "环境声");
        assertThat(apimart).contains("8 秒", "图片1", "图片2", "无需生成音轨");
    }
}
