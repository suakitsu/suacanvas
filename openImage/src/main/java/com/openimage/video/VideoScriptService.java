package com.openimage.video;

import com.openimage.ai.AiTextCallAuditService;
import com.openimage.ai.AiTextService;
import com.openimage.risk.RiskControlService;
import com.openimage.security.CurrentUser;
import org.springframework.stereotype.Service;

@Service
public class VideoScriptService {
    private final VideoConfigService config;
    private final AiTextService aiText;
    private final AiTextCallAuditService textAudit;
    private final RiskControlService riskControl;

    public VideoScriptService(
        VideoConfigService config,
        AiTextService aiText,
        AiTextCallAuditService textAudit,
        RiskControlService riskControl
    ) {
        this.config = config;
        this.aiText = aiText;
        this.textAudit = textAudit;
        this.riskControl = riskControl;
    }

    public VideoDtos.VideoScriptPreviewResponse preview(
        CurrentUser user,
        VideoDtos.VideoScriptPreviewRequest request
    ) {
        if (user == null || user.id() == null || user.id().isBlank()) {
            throw new IllegalArgumentException("请先登录后再优化视频脚本");
        }
        if (request == null) throw new IllegalArgumentException("视频脚本参数不能为空");
        String prompt = clean(request.prompt());
        if (prompt.length() < 4) throw new IllegalArgumentException("请填写至少 4 个字符的商品视频描述");
        if (prompt.length() > 4000) throw new IllegalArgumentException("商品视频描述不能超过 4000 个字符");

        VideoConfigService.ModelSelection selection = config.selectModel(
            request.modelCode(),
            request.resolution(),
            request.durationSeconds(),
            request.aspectRatio()
        );
        int referenceCount = request.referenceCount() == null ? 0 : request.referenceCount();
        if (referenceCount < 1) throw new IllegalArgumentException("请至少选择一张商品参考图");
        if (referenceCount > selection.maxReferenceImages()) {
            throw new IllegalArgumentException("所选模型最多支持 " + selection.maxReferenceImages() + " 张商品参考图");
        }
        boolean generateAudio = selection.audioToggle() && !Boolean.FALSE.equals(request.generateAudio());

        riskControl.checkBeforeProvider(
            user.id(), "", "", "video_script_optimize", prompt, "user_input"
        );
        AiTextService.VideoScriptPlan plan;
        try (AiTextCallAuditService.Scope ignored = textAudit.open(
            user.id(), "video_script_optimize", "video", ""
        )) {
            plan = aiText.optimizeVideoScript(
                prompt,
                selection.provider(),
                selection.model(),
                selection.durationSeconds(),
                selection.aspectRatio(),
                referenceCount,
                generateAudio
            );
        }
        return new VideoDtos.VideoScriptPreviewResponse(
            plan.optimizedPrompt(),
            plan.mode(),
            plan.source(),
            plan.summary()
        );
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
