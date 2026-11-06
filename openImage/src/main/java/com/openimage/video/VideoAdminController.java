package com.openimage.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openimage.common.ApiResponse;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.domain.mapper.VideoTaskMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/video-config")
public class VideoAdminController {
    private final VideoConfigService config;
    private final VideoTaskMapper tasks;
    private final UserMapper users;

    public VideoAdminController(VideoConfigService config, VideoTaskMapper tasks, UserMapper users) {
        this.config = config;
        this.tasks = tasks;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> get() {
        Map<String, Object> view = new LinkedHashMap<>(config.adminView());
        List<VideoTaskEntity> recent = tasks.selectList(new LambdaQueryWrapper<VideoTaskEntity>()
            .eq(VideoTaskEntity::getDeleted, 0)
            .orderByDesc(VideoTaskEntity::getCreatedAt)
            .last("limit 12"));
        List<String> userIds = recent.stream().map(VideoTaskEntity::getUserId).distinct().toList();
        List<UserEntity> userRows = userIds.isEmpty() ? List.of() : users.selectBatchIds(userIds);
        Map<String, UserEntity> usersById = userRows.stream()
            .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (left, right) -> left));
        view.put("recent_tasks", recent.stream().map(task -> taskView(task, usersById.get(task.getUserId()))).toList());
        Map<String, Object> summary = tasks.selectVideoSummaryMetrics();
        List<Map<String, Object>> modelMetrics = tasks.selectVideoModelMetrics();
        view.put("metrics", Map.of(
            "summary", summary == null ? Map.of() : summary,
            "models", modelMetrics == null ? List.of() : modelMetrics
        ));
        return ApiResponse.ok(view);
    }

    @PatchMapping
    public ApiResponse<Map<String, Object>> update(@RequestBody VideoDtos.AdminConfigUpdate request) {
        return ApiResponse.ok(config.save(request));
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
        row.put("agnes_key_id", config.agnesKeyId(task));
        row.put("routing_reason", task.getRoutingReason());
        row.put("aspect_ratio", task.getAspectRatio());
        row.put("duration_seconds", task.getDurationSeconds());
        row.put("requested_resolution", task.getRequestedResolution());
        row.put("actual_width", task.getActualWidth());
        row.put("actual_height", task.getActualHeight());
        row.put("actual_seconds", task.getActualSeconds());
        row.put("credits_cost", task.getCreditsCost());
        row.put("actual_cost", task.getActualCost());
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

    private String account(UserEntity user, String fallback) {
        if (user == null) return fallback;
        if (user.getEmail() != null && !user.getEmail().isBlank()) return user.getEmail();
        if (user.getGithubLogin() != null && !user.getGithubLogin().isBlank()) return "GitHub@" + user.getGithubLogin();
        return fallback;
    }
}
