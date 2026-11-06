package com.openimage.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.common.ApiResponse;
import com.openimage.domain.entity.AdminAuditLogEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.AdminAuditLogMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.domain.mapper.VideoTaskMapper;
import com.openimage.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/video-tasks")
public class VideoTaskAdminController {
    private final VideoTaskMapper tasks;
    private final UserMapper users;
    private final AdminAuditLogMapper auditLogs;
    private final ObjectMapper json;

    public VideoTaskAdminController(
        VideoTaskMapper tasks,
        UserMapper users,
        AdminAuditLogMapper auditLogs,
        ObjectMapper json
    ) {
        this.tasks = tasks;
        this.users = users;
        this.auditLogs = auditLogs;
        this.json = json;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "provider", required = false) String provider,
        @RequestParam(value = "page", defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", defaultValue = "20") Integer pageSize
    ) {
        LambdaQueryWrapper<VideoTaskEntity> query = new LambdaQueryWrapper<VideoTaskEntity>()
            .eq(VideoTaskEntity::getDeleted, 0);
        if (notBlank(status)) query.eq(VideoTaskEntity::getStatus, status.trim());
        if (notBlank(provider)) query.eq(VideoTaskEntity::getProvider, provider.trim());
        if (notBlank(keyword)) {
            String like = "%" + keyword.trim().toLowerCase() + "%";
            query.and(wrapper -> wrapper
                .apply("lower(id) like {0}", like)
                .or()
                .apply("lower(user_id) like {0}", like)
                .or()
                .apply("lower(coalesce(title, '')) like {0}", like));
        }

        long total = tasks.selectCount(query);
        int normalizedPage = page == null || page < 1 ? 1 : page;
        int normalizedSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        int offset = (normalizedPage - 1) * normalizedSize;
        List<VideoTaskEntity> rows = tasks.selectList(query
            .orderByDesc(VideoTaskEntity::getCreatedAt)
            .last("limit " + normalizedSize + " offset " + offset));
        Map<String, UserEntity> usersById = usersById(rows);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rows", rows.stream().map(task -> taskView(task, usersById.get(task.getUserId()))).toList());
        payload.put("total", total);
        payload.put("page", normalizedPage);
        payload.put("page_size", normalizedSize);
        payload.put("total_pages", total == 0 ? 0 : (long) Math.ceil(total / (double) normalizedSize));
        return ApiResponse.ok(payload);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> delete(
        @AuthenticationPrincipal CurrentUser admin,
        @PathVariable String id
    ) throws Exception {
        VideoTaskEntity task = tasks.lockById(id);
        if (task == null || task.getDeleted() != 0) {
            throw new IllegalArgumentException("视频任务不存在");
        }
        if (List.of("queued", "submitting", "submitted", "generating", "upscaling", "quality_check").contains(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "生成中的视频任务不能删除，请等待任务结束");
        }
        int deleted = tasks.softDeleteTerminalByAdmin(id, OffsetDateTime.now());
        if (deleted != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务状态已变化，请刷新后重试");
        }
        writeDeleteAudit(admin, task);
        return ApiResponse.ok(Map.of("task_id", id, "deleted", true));
    }

    private Map<String, UserEntity> usersById(List<VideoTaskEntity> rows) {
        List<String> userIds = rows.stream().map(VideoTaskEntity::getUserId).filter(this::notBlank).distinct().toList();
        if (userIds.isEmpty()) return Map.of();
        return users.selectBatchIds(userIds).stream()
            .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (left, right) -> left));
    }

    private Map<String, Object> taskView(VideoTaskEntity task, UserEntity user) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", task.getId());
        row.put("user_id", task.getUserId());
        row.put("user_account", account(user, task.getUserId()));
        row.put("title", task.getTitle());
        row.put("mode", task.getMode());
        row.put("status", task.getStatus());
        row.put("progress", task.getProgress());
        row.put("provider", task.getProvider());
        row.put("model", task.getModel());
        row.put("routing_reason", task.getRoutingReason());
        row.put("aspect_ratio", task.getAspectRatio());
        row.put("duration_seconds", task.getDurationSeconds());
        row.put("requested_resolution", task.getRequestedResolution());
        row.put("actual_width", task.getActualWidth());
        row.put("actual_height", task.getActualHeight());
        row.put("actual_seconds", task.getActualSeconds());
        row.put("credits_cost", task.getCreditsCost());
        row.put("actual_cost", task.getActualCost());
        row.put("cost_details", costDetails(task));
        row.put("refunded_at", task.getRefundedAt());
        row.put("safe_error", task.getSafeError());
        row.put("hidden_by_user", Integer.valueOf(1).equals(task.getHiddenByUser()));
        row.put("queued_at", task.getQueuedAt());
        row.put("submitted_at", task.getSubmittedAt());
        row.put("completed_at", task.getCompletedAt());
        row.put("created_at", task.getCreatedAt());
        row.put("updated_at", task.getUpdatedAt());
        return row;
    }

    private Map<String, Object> costDetails(VideoTaskEntity task) {
        Map<String, Object> metadata;
        try {
            metadata = json.readValue(
                task.getMetadata() == null || task.getMetadata().isBlank() ? "{}" : task.getMetadata(),
                new TypeReference<>() {}
            );
        } catch (Exception ignored) {
            metadata = Map.of();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currency", "CNY");
        details.put("actual_cost_cny", task.getActualCost() == null ? 0 : task.getActualCost());
        for (String key : List.of("upstream_cost", "upstream_cost_unit")) {
            if (metadata.containsKey(key)) {
                details.put(key, metadata.get(key));
            }
        }
        if (metadata.containsKey("cost_conversion_rate")) {
            details.put("conversion_rate", metadata.get("cost_conversion_rate"));
        }
        if (metadata.containsKey("cost_source")) {
            details.put("source", metadata.get("cost_source"));
        }
        return details;
    }

    private String account(UserEntity user, String fallback) {
        if (user == null) return fallback;
        if (notBlank(user.getEmail())) return user.getEmail();
        if (notBlank(user.getGithubLogin())) return "GitHub@" + user.getGithubLogin();
        return fallback;
    }

    private void writeDeleteAudit(CurrentUser admin, VideoTaskEntity task) throws Exception {
        AdminAuditLogEntity log = new AdminAuditLogEntity();
        log.setId("audit_" + UUID.randomUUID().toString().replace("-", ""));
        log.setAdminId(admin == null ? null : admin.id());
        log.setAction("video_task_delete");
        log.setTargetType("video_task");
        log.setTargetId(task.getId());
        log.setSummary("后台删除视频任务");
        log.setBeforeJson(json.writeValueAsString(Map.of(
            "deleted", 0,
            "status", task.getStatus(),
            "hidden_by_user", Integer.valueOf(1).equals(task.getHiddenByUser())
        )));
        log.setAfterJson(json.writeValueAsString(Map.of(
            "deleted", 1,
            "user_id", task.getUserId()
        )));
        log.setCreatedAt(OffsetDateTime.now());
        log.setDeleted(0);
        auditLogs.insert(log);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
