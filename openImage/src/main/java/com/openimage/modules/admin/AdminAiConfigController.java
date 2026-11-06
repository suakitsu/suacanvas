package com.openimage.modules.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.AiRuntimeConfigService;
import com.openimage.ai.worker.AiWorkerJobService;
import com.openimage.ai.worker.AiWorkerTokenService;
import com.openimage.common.ApiResponse;
import com.openimage.domain.entity.AdminAuditLogEntity;
import com.openimage.domain.mapper.AdminAuditLogMapper;
import com.openimage.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/ai-config")
public class AdminAiConfigController {
    private final AiRuntimeConfigService aiConfig;
    private final AdminAuditLogMapper auditLogs;
    private final ObjectMapper json;
    private final AiWorkerJobService workerJobs;
    private final AiWorkerTokenService workerTokens;

    public AdminAiConfigController(
        AiRuntimeConfigService aiConfig,
        AdminAuditLogMapper auditLogs,
        ObjectMapper json,
        AiWorkerJobService workerJobs,
        AiWorkerTokenService workerTokens
    ) {
        this.aiConfig = aiConfig;
        this.auditLogs = auditLogs;
        this.json = json;
        this.workerJobs = workerJobs;
        this.workerTokens = workerTokens;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> getConfig() {
        return ApiResponse.ok(configWithWorkers(aiConfig.adminView()));
    }

    @PostMapping("/validate")
    public ApiResponse<Map<String, Object>> validateConfig(@RequestBody AiRuntimeConfigService.UpdateRequest request) {
        return ApiResponse.ok(aiConfig.validate(request));
    }

    @PostMapping("/probe/{kind}")
    public ApiResponse<Map<String, Object>> probeConfig(@PathVariable String kind, @RequestBody AiRuntimeConfigService.UpdateRequest request) {
        return ApiResponse.ok(aiConfig.probe(kind, request));
    }

    @PatchMapping
    public ApiResponse<Map<String, Object>> updateConfig(@AuthenticationPrincipal CurrentUser currentUser, @RequestBody AiRuntimeConfigService.UpdateRequest request) {
        Map<String, Object> before = aiConfig.adminView();
        Map<String, Object> after = aiConfig.save(request);
        audit(currentUser, "ai_runtime_config_update", "system_setting", "ai_runtime_config", "修改 AI 图片/文案运行配置", safeAudit(before), safeAudit(after));
        return ApiResponse.ok(configWithWorkers(after));
    }

    @PostMapping("/rollback")
    public ApiResponse<Map<String, Object>> rollbackConfig(@AuthenticationPrincipal CurrentUser currentUser) {
        Map<String, Object> before = aiConfig.adminView();
        Map<String, Object> after = aiConfig.rollback();
        audit(currentUser, "ai_runtime_config_rollback", "system_setting", "ai_runtime_config", "回滚 AI 图片/文案运行配置", safeAudit(before), safeAudit(after));
        return ApiResponse.ok(configWithWorkers(after));
    }

    @GetMapping("/workers")
    public ApiResponse<Map<String, Object>> workerState() {
        return ApiResponse.ok(workerJobs.adminState());
    }

    @PostMapping("/worker-tokens")
    public ApiResponse<Map<String, Object>> createWorkerToken(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestBody(required = false) WorkerTokenRequest request
    ) {
        AiWorkerTokenService.CreatedToken created = workerTokens.create(
            request == null ? "" : request.name()
        );
        audit(
            currentUser,
            "ai_worker_token_create",
            "ai_worker_token",
            String.valueOf(created.workerToken().get("id")),
            "创建本机 AI Worker Token",
            Map.of(),
            created.workerToken()
        );
        return ApiResponse.ok(oneTimeTokenView(created));
    }

    @PostMapping("/worker-tokens/{tokenId}/regenerate")
    public ApiResponse<Map<String, Object>> regenerateWorkerToken(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable String tokenId
    ) {
        AiWorkerTokenService.CreatedToken created = workerTokens.regenerate(tokenId);
        audit(
            currentUser,
            "ai_worker_token_regenerate",
            "ai_worker_token",
            tokenId,
            "重新生成本机 AI Worker Token",
            Map.of("id", tokenId),
            created.workerToken()
        );
        return ApiResponse.ok(oneTimeTokenView(created));
    }

    @PostMapping("/worker-tokens/{tokenId}/revoke")
    public ApiResponse<Map<String, Object>> revokeWorkerToken(
        @AuthenticationPrincipal CurrentUser currentUser,
        @PathVariable String tokenId
    ) {
        Map<String, Object> revoked = workerTokens.revoke(tokenId);
        audit(
            currentUser,
            "ai_worker_token_revoke",
            "ai_worker_token",
            tokenId,
            "撤销本机 AI Worker Token",
            Map.of("id", tokenId),
            revoked
        );
        return ApiResponse.ok(revoked);
    }

    private Map<String, Object> safeAudit(Map<String, Object> value) {
        Map<String, Object> row = new LinkedHashMap<>(value);
        row.remove("previous");
        row.remove("workers");
        return row;
    }

    private Map<String, Object> configWithWorkers(Map<String, Object> config) {
        Map<String, Object> view = new LinkedHashMap<>(config);
        view.put("workers", workerJobs.adminState());
        return view;
    }

    private Map<String, Object> oneTimeTokenView(AiWorkerTokenService.CreatedToken created) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("token", created.token());
        view.put("worker_token", created.workerToken());
        view.put("shown_once", true);
        return view;
    }

    private void audit(CurrentUser admin, String action, String targetType, String targetId, String summary, Object before, Object after) {
        AdminAuditLogEntity log = new AdminAuditLogEntity();
        log.setId("audit_" + UUID.randomUUID().toString().replace("-", ""));
        log.setAdminId(admin == null ? null : admin.id());
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setSummary(summary);
        log.setBeforeJson(toJson(before));
        log.setAfterJson(toJson(after));
        log.setCreatedAt(OffsetDateTime.now());
        log.setDeleted(0);
        auditLogs.insert(log);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    public record WorkerTokenRequest(String name) {
    }
}
