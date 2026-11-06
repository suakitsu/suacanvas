package com.openimage.video;

import com.openimage.ai.AiTextCallAuditService;
import com.openimage.ai.AiTextService;
import com.openimage.risk.RiskControlService;
import com.openimage.security.CurrentUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoScriptServiceTest {
    @Test
    void previewOptimizesWithoutCreatingOrBillingAVideoTask() {
        VideoConfigService config = mock(VideoConfigService.class);
        AiTextService aiText = mock(AiTextService.class);
        AiTextCallAuditService audit = mock(AiTextCallAuditService.class);
        RiskControlService risk = mock(RiskControlService.class);
        when(config.selectModel("seedance-mini", "720p", 8, "9:16"))
            .thenReturn(selection(4));
        when(audit.open(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(() -> {});
        when(aiText.optimizeVideoScript(
            anyString(), anyString(), anyString(), anyInt(), anyString(), anyInt(), anyBoolean()
        )).thenReturn(new AiTextService.VideoScriptPlan(
            "single_shot", "8 秒连续展示雨伞防水细节", "ai", "已优化镜头"
        ));
        VideoScriptService service = new VideoScriptService(config, aiText, audit, risk);

        VideoDtos.VideoScriptPreviewResponse response = service.preview(
            new CurrentUser("user-1", "user@example.com", "user"),
            request(2)
        );

        assertThat(response.optimizedPrompt()).isEqualTo("8 秒连续展示雨伞防水细节");
        assertThat(response.mode()).isEqualTo("single_shot");
        assertThat(response.source()).isEqualTo("ai");
        verify(risk).checkBeforeProvider(
            "user-1", "", "", "video_script_optimize", "展示雨伞防水细节", "user_input"
        );
        verify(aiText).optimizeVideoScript(
            "展示雨伞防水细节", "hfsy", "sd-2.0-mini", 8, "9:16", 2, true
        );
    }

    @Test
    void previewEnforcesTheSelectedModelsReferenceLimit() {
        VideoConfigService config = mock(VideoConfigService.class);
        when(config.selectModel("seedance-mini", "720p", 8, "9:16"))
            .thenReturn(selection(1));
        VideoScriptService service = new VideoScriptService(
            config,
            mock(AiTextService.class),
            mock(AiTextCallAuditService.class),
            mock(RiskControlService.class)
        );

        assertThatThrownBy(() -> service.preview(
            new CurrentUser("user-1", "user@example.com", "user"),
            request(2)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("最多支持 1 张");
    }

    private VideoConfigService.ModelSelection selection(int maxReferences) {
        return new VideoConfigService.ModelSelection(
            "seedance-mini",
            "Seedance Mini",
            "hfsy",
            "sd-2.0-mini",
            "720p",
            8,
            "9:16",
            490,
            true,
            maxReferences,
            "fixed_hfsy_exact_match"
        );
    }

    private VideoDtos.VideoScriptPreviewRequest request(int references) {
        return new VideoDtos.VideoScriptPreviewRequest(
            "展示雨伞防水细节",
            "seedance-mini",
            8,
            "720p",
            "9:16",
            true,
            references
        );
    }
}
