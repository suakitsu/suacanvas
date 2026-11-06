package com.openimage.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.VideoTaskMapper;
import com.openimage.risk.RiskControlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class VideoTaskWorker {
    private static final int[] AGNES_SUBMIT_RETRY_DELAYS_SECONDS = {15, 45, 120};
    private static final int AGNES_QUEUE_FULL_MAX_RETRIES = 12;
    private static final List<String> IN_FLIGHT = List.of(
        "submitted", "generating", "upscaling", "quality_check"
    );
    private static final List<String> REFUNDABLE_TERMINAL = List.of("failed", "timeout");

    private final VideoTaskMapper tasks;
    private final VideoTaskService service;
    private final VideoTaskQueueService queue;
    private final VideoConfigService config;
    private final VideoProviderGateway gateway;
    private final VideoResultStorageService resultStorage;
    private final VideoProperties properties;
    private final RiskControlService riskControl;
    private final ObjectMapper json;

    public VideoTaskWorker(
        VideoTaskMapper tasks,
        VideoTaskService service,
        VideoTaskQueueService queue,
        VideoConfigService config,
        VideoProviderGateway gateway,
        VideoResultStorageService resultStorage,
        VideoProperties properties,
        RiskControlService riskControl,
        ObjectMapper json
    ) {
        this.tasks = tasks;
        this.service = service;
        this.queue = queue;
        this.config = config;
        this.gateway = gateway;
        this.resultStorage = resultStorage;
        this.properties = properties;
        this.riskControl = riskControl;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${openimage.video.poll-interval-ms:3000}")
    public void process() {
        int limit = Math.max(1, Math.min(properties.getWorkerBatchSize(), 100));
        recoverInterruptedSubmissions();
        reconcileRefunds(limit);
        processInFlight(limit);
        submitIndependentQueued(limit);
        submitAgnesQueued(limit);
    }

    public void refreshByUpstreamId(String upstreamTaskId) {
        VideoTaskEntity task = tasks.findByUpstreamTaskId(upstreamTaskId);
        if (task == null || task.getDeleted() != 0 || !IN_FLIGHT.contains(task.getStatus())) return;
        try {
            processInFlightTask(task, true);
        } catch (Exception exception) {
            handleProviderFailure(task, exception);
        }
    }

    public void acceptApimartCallback(String upstreamTaskId, JsonNode payload) {
        VideoTaskEntity task = tasks.findByUpstreamTaskId(upstreamTaskId);
        if (task == null || task.getDeleted() != 0 || !isApimartProvider(task.getProvider())) return;
        boolean terminalRecovery = REFUNDABLE_TERMINAL.contains(task.getStatus());
        if (!IN_FLIGHT.contains(task.getStatus()) && !terminalRecovery) return;
        try {
            if (!claimInFlight(task, true)) return;
            VideoProviderGateway.PollResult result = gateway.parseApimartPollResult(
                payload, task.getProgress() == null ? 10 : task.getProgress()
            );
            if (terminalRecovery && !"completed".equals(result.status())) return;
            applyPollResult(task, result);
        } catch (Exception exception) {
            handleProviderFailure(task, exception);
        }
    }

    private void recoverInterruptedSubmissions() {
        int recoverySeconds = Math.max(120, config.current().pollSeconds() * 4);
        queue.recoverStaleSubmitting(
            OffsetDateTime.now().minusSeconds(recoverySeconds),
            config.mockMode()
        );
    }

    private void reconcileRefunds(int limit) {
        List<VideoTaskEntity> rows = tasks.selectList(new LambdaQueryWrapper<VideoTaskEntity>()
            .eq(VideoTaskEntity::getDeleted, 0)
            .in(VideoTaskEntity::getStatus, REFUNDABLE_TERMINAL)
            .isNull(VideoTaskEntity::getRefundedAt)
            .gt(VideoTaskEntity::getCreditsCost, 0)
            .orderByAsc(VideoTaskEntity::getCompletedAt)
            .last("limit " + limit));
        for (VideoTaskEntity task : rows) {
            service.refundIfNeeded(task.getId(), clean(task.getSafeError(), "视频生成失败，积分已自动退回"));
        }
    }

    private void processInFlight(int limit) {
        List<VideoTaskEntity> rows = tasks.selectList(new LambdaQueryWrapper<VideoTaskEntity>()
            .eq(VideoTaskEntity::getDeleted, 0)
            .in(VideoTaskEntity::getStatus, IN_FLIGHT)
            .orderByAsc(VideoTaskEntity::getUpdatedAt)
            .last("limit " + limit));
        for (VideoTaskEntity task : rows) {
            try {
                processInFlightTask(task, false);
            } catch (Exception exception) {
                handleProviderFailure(task, exception);
            }
        }
    }

    private void submitIndependentQueued(int limit) {
        List<VideoTaskEntity> rows = tasks.selectList(new LambdaQueryWrapper<VideoTaskEntity>()
            .eq(VideoTaskEntity::getDeleted, 0)
            .eq(VideoTaskEntity::getStatus, "queued")
            .ne(VideoTaskEntity::getProvider, "agnes")
            .orderByAsc(VideoTaskEntity::getCreatedAt)
            .last("limit " + limit));
        for (VideoTaskEntity task : rows) {
            VideoTaskEntity claimed = task;
            try {
                if (tasks.claimQueued(task.getId()) == 0) continue;
                claimed = tasks.selectById(task.getId());
                if (claimed != null) submitClaimed(claimed);
            } catch (Exception exception) {
                handleProviderFailure(claimed, exception);
            }
        }
    }

    private void submitAgnesQueued(int limit) {
        for (int index = 0; index < limit; index++) {
            VideoTaskEntity claimed = queue.claimNextAgnes();
            if (claimed == null) return;
            try {
                submitClaimed(claimed);
            } catch (Exception exception) {
                handleProviderFailure(claimed, exception);
            }
        }
    }

    private void processInFlightTask(VideoTaskEntity task, boolean force) throws Exception {
        boolean mockTask = isMockTask(task);
        if (!claimInFlight(task, force || mockTask)) return;
        if (mockTask) {
            advanceMock(task);
            return;
        }
        if (timedOut(task)) markTimeoutVerification(task);
        VideoProviderGateway.PollResult result = gateway.poll(task);
        applyPollResult(task, result);
    }

    private void applyPollResult(VideoTaskEntity task, VideoProviderGateway.PollResult result) throws Exception {
        mergeMetadata(task, result.metadata());
        if ("failed".equals(result.status())) {
            task.setProviderAttempts(appendAttempt(
                task, task.getProvider(), task.getModel(), "failed",
                safe(clean(result.error(), "视频上游生成失败")), task.getUpstreamTaskId()
            ));
            tasks.updateById(task);
            fail(task, "视频生成失败，积分已自动退回", clean(result.error(), "视频上游明确返回失败"));
            return;
        }
        if (!"completed".equals(result.status())) {
            task.setStatus(normalizeRunningStatus(result.status()));
            task.setProgress(Math.max(task.getProgress() == null ? 0 : task.getProgress(), result.progress()));
            task.setUpdatedAt(OffsetDateTime.now());
            tasks.updateById(task);
            return;
        }
        complete(task, result);
    }

    private void submitClaimed(VideoTaskEntity task) throws Exception {
        String provider = task.getProvider();
        if (provider == null || provider.isBlank()) {
            VideoConfigService.ProviderAssignment assignment = config.assignmentFor(task.getMode(), task.getId(), task.getPrompt());
            provider = assignment.provider();
            task.setProvider(provider);
            task.setModel(assignment.model());
            task.setRoutingReason(assignment.reason());
        }
        if ("disabled".equals(provider)) {
            fail(task, "商品视频功能已由管理员关闭，积分已退回", "Video feature disabled");
            return;
        }
        if (!config.mockMode() && !config.providerEffective(provider)) {
            fail(task, "所选视频模型已关闭，积分已自动退回", "Assigned video provider disabled before submission");
            return;
        }
        if ("agnes".equals(provider) && config.agnesCircuitOpen()) {
            task.setProviderAttempts(appendAttempt(
                task, task.getProvider(), task.getModel(), "circuit_open", "Agnes circuit breaker open", ""
            ));
            deferAssignedRoute(task, 60, "Agnes 暂时熔断，任务保持原路由排队");
            return;
        }

        boolean mockMode = config.mockMode();
        VideoProviderGateway.Submission submission = mockMode
            ? gateway.mockSubmission(task, provider)
            : gateway.submit(task, provider, sourceUrls(task));
        if (!provider.equals(submission.provider())
            || task.getModel() == null
            || !task.getModel().equals(submission.model())) {
            throw new IllegalStateException("视频上游返回的供应商或模型与任务固定路由不一致");
        }
        OffsetDateTime now = OffsetDateTime.now();
        task.setUpstreamTaskId(submission.upstreamTaskId());
        task.setStatus("submitted");
        task.setProgress(10);
        if (task.getSubmittedAt() == null) task.setSubmittedAt(now);
        task.setProviderAttempts(appendAttempt(task, submission.provider(), submission.model(), "submitted", "", submission.upstreamTaskId()));
        Map<String, Object> metadata = metadata(task);
        metadata.put("mock_mode", mockMode);
        metadata.remove("submit_retry_after");
        if (integer(metadata.get("submit_retry_count"), 0) > 0) {
            metadata.put("submit_retry_recovered", true);
        }
        task.setMetadata(json.writeValueAsString(metadata));
        task.setUpdatedAt(now);
        tasks.updateById(task);
        log.info(
            "video_submit task_id={} user_id={} mode={} provider={} model={} routing_reason={} agnes_key_id={} upstream_task_id={} queue_ms={} mock={}",
            task.getId(), task.getUserId(), task.getMode(), task.getProvider(), task.getModel(), task.getRoutingReason(),
            config.agnesKeyId(task), submission.upstreamTaskId(), elapsedMillis(task.getQueuedAt(), now), mockMode
        );
    }

    private void advanceMock(VideoTaskEntity task) throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        switch (task.getStatus()) {
            case "submitted" -> {
                task.setStatus("generating");
                task.setProgress(45);
            }
            case "generating" -> {
                task.setStatus("quality_check");
                task.setProgress(85);
            }
            case "quality_check" -> {
                task.setStatus("completed");
                task.setProgress(100);
                Map<String, Object> metadata = metadata(task);
                metadata.put("mock_mode", true);
                metadata.put("delivery_note", "测试服未调用付费视频上游");
                boolean is480 = "480p".equals(task.getRequestedResolution());
                int shortSide = is480 ? 480 : 720;
                int longSide = is480 ? 854 : 1280;
                metadata.put("size", task.getAspectRatio().equals("9:16")
                    ? shortSide + "x" + longSide
                    : longSide + "x" + shortSide);
                metadata.put("seconds", task.getDurationSeconds());
                task.setMetadata(json.writeValueAsString(metadata));
                task.setActualWidth(task.getAspectRatio().equals("9:16") ? shortSide : longSide);
                task.setActualHeight(task.getAspectRatio().equals("9:16") ? longSide : shortSide);
                task.setActualSeconds(BigDecimal.valueOf(task.getDurationSeconds()));
                task.setCompletedAt(now);
            }
            default -> { return; }
        }
        task.setUpdatedAt(now);
        tasks.updateById(task);
        if ("completed".equals(task.getStatus())) logTerminal(task, "completed", "");
    }

    private void complete(VideoTaskEntity task, VideoProviderGateway.PollResult result) throws Exception {
        VideoResultStorageService.StoredVideo stored;
        try {
            stored = resultStorage.persist(task.getId(), result.resultUrl());
        } catch (Exception exception) {
            Map<String, Object> metadata = metadata(task);
            metadata.put("result_persist_error", safe(exception.getMessage()));
            metadata.put("result_persist_retry_at", OffsetDateTime.now().toString());
            task.setMetadata(json.writeValueAsString(metadata));
            task.setStatus("generating");
            task.setProgress(99);
            task.setUpdatedAt(OffsetDateTime.now());
            tasks.updateById(task);
            log.warn("video_result_persist_retry task_id={} upstream_task_id={} error={}",
                task.getId(), task.getUpstreamTaskId(), safe(exception.getMessage()));
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> metadata = metadata(task);
        metadata.remove("result_persist_error");
        metadata.remove("result_persist_retry_at");
        metadata.put("result_persisted", true);
        metadata.put("result_file_size", stored.fileSize());
        metadata.put("result_content_type", stored.contentType());
        task.setMetadata(json.writeValueAsString(metadata));
        task.setResultUrl(stored.url());
        task.setActualWidth(result.width());
        task.setActualHeight(result.height());
        task.setActualSeconds(result.seconds() == null
            ? BigDecimal.valueOf(task.getDurationSeconds())
            : result.seconds());
        task.setActualCost(actualCost(task));
        task.setSafeError("");
        task.setRawError("");
        task.setCompletedAt(now);
        task.setUpdatedAt(now);
        if (!service.completeSuccessfully(task)) {
            throw new IllegalStateException("视频完成状态保存失败");
        }
        task.setStatus("completed");
        task.setProgress(100);
        logTerminal(task, "completed", "");
    }

    private void handleProviderFailure(VideoTaskEntity task, Exception exception) {
        String raw = safe(clean(exception.getMessage(), exception.getClass().getSimpleName()));
        if (handleAgnesQueueFull(task, exception, raw)) return;
        if (scheduleAgnesSubmitRetry(task, exception, raw)) return;
        riskControl.recordProviderError(task.getUserId(), "", task.getId(), "product_video", raw);
        if (task.getUpstreamTaskId() != null
            && !task.getUpstreamTaskId().isBlank()
            && IN_FLIGHT.contains(task.getStatus())) {
            try {
                Map<String, Object> metadata = metadata(task);
                metadata.put("poll_error_at", OffsetDateTime.now().toString());
                metadata.put("poll_error", raw);
                metadata.put("poll_error_count", integer(metadata.get("poll_error_count"), 0) + 1);
                task.setMetadata(json.writeValueAsString(metadata));
                task.setUpdatedAt(OffsetDateTime.now());
                tasks.updateById(task);
                log.warn(
                    "video_poll_retry task_id={} user_id={} provider={} upstream_task_id={} error={}",
                    task.getId(), task.getUserId(), task.getProvider(), task.getUpstreamTaskId(), raw
                );
                return;
            } catch (Exception retryException) {
                log.error("video_poll_retry_record_failed task_id={}", task.getId(), retryException);
                return;
            }
        }
        try {
            boolean definiteRejection = definiteSubmissionRejection(exception);
            if (definiteRejection
                && "agnes".equals(task.getProvider())
                && (task.getUpstreamTaskId() == null || task.getUpstreamTaskId().isBlank())
                && queue.requeueForAnotherAgnesKey(task, raw)) {
                task.setProviderAttempts(appendAttempt(
                    task, "agnes", task.getModel(), "key_submit_failed", raw, ""
                ));
                tasks.updateById(task);
                return;
            }
            if (definiteRejection) {
                fail(task, "视频提交被上游明确拒绝，积分已自动退回", raw);
                return;
            }
            quarantineUnknownSubmission(task, raw);
        } catch (Exception handlingException) {
            log.error("video_submission_failure_handling_failed task_id={}", task.getId(), handlingException);
        }
    }

    private boolean handleAgnesQueueFull(VideoTaskEntity task, Exception exception, String raw) {
        if (!"agnes".equals(task.getProvider())
            || (task.getUpstreamTaskId() != null && !task.getUpstreamTaskId().isBlank())
            || !(exception instanceof VideoProviderGateway.UpstreamHttpException upstream)
            || upstream.statusCode() != 503
            || !isAgnesQueueFull(upstream.responseBody())) {
            return false;
        }
        try {
            if (queue.requeueForAnotherAgnesKey(task, raw)) {
                task.setProviderAttempts(appendAttempt(
                    task, task.getProvider(), task.getModel(), "key_queue_full", raw, ""
                ));
                tasks.updateById(task);
                return true;
            }

            Map<String, Object> metadata = metadata(task);
            int previousRetries = integer(metadata.get("queue_full_retry_count"), 0);
            if (previousRetries >= AGNES_QUEUE_FULL_MAX_RETRIES) {
                fail(task, "Agnes 当前排队拥堵，任务未创建，积分已自动退回", raw);
                return true;
            }

            int retryNumber = previousRetries + 1;
            int exponentialDelay = 30 * (1 << Math.min(previousRetries, 3));
            int delaySeconds = Math.max(Math.min(300, exponentialDelay), upstream.retryAfterSeconds());
            OffsetDateTime retryAfter = OffsetDateTime.now().plusSeconds(delaySeconds);

            metadata.put("queue_full_retry_count", retryNumber);
            metadata.put("submit_retry_after", retryAfter.toString());
            metadata.put("queue_full_last_error", raw);
            metadata.put("queue_full_request_id", safe(upstream.requestId()));
            metadata.remove("agnes_failed_key_ids");
            metadata.remove("agnes_last_failed_key_id");
            metadata.remove("agnes_last_key_failure");
            metadata.remove("agnes_key_id");
            metadata.remove("agnes_key_label");
            metadata.remove("agnes_key_fingerprint");
            task.setMetadata(json.writeValueAsString(metadata));
            task.setProviderAttempts(appendAttempt(
                task, task.getProvider(), task.getModel(), "queue_full_retry",
                "HTTP 503, retry " + retryNumber, ""
            ));
            task.setStatus("queued");
            task.setProgress(0);
            task.setSafeError("");
            task.setRawError("");
            task.setUpdatedAt(OffsetDateTime.now());
            tasks.updateById(task);
            log.warn(
                "video_agnes_queue_full task_id={} user_id={} retry={} delay_seconds={} request_id={}",
                task.getId(), task.getUserId(), retryNumber, delaySeconds, safe(upstream.requestId())
            );
            return true;
        } catch (Exception retryException) {
            log.error("video_agnes_queue_full_retry_failed task_id={}", task.getId(), retryException);
            return false;
        }
    }

    private boolean isAgnesQueueFull(String responseBody) {
        String value = responseBody == null ? "" : responseBody.toLowerCase();
        return value.contains("video_queue_full") || value.contains("video queue is full");
    }

    private boolean scheduleAgnesSubmitRetry(VideoTaskEntity task, Exception exception, String raw) {
        if (!"agnes".equals(task.getProvider())
            || (task.getUpstreamTaskId() != null && !task.getUpstreamTaskId().isBlank())
            || !(exception instanceof VideoProviderGateway.UpstreamHttpException upstream)
            || upstream.statusCode() != 429) {
            return false;
        }
        try {
            Map<String, Object> metadata = metadata(task);
            int previousRetries = integer(metadata.get("submit_retry_count"), 0);
            if (previousRetries >= AGNES_SUBMIT_RETRY_DELAYS_SECONDS.length) return false;

            int retryNumber = previousRetries + 1;
            int configuredDelay = AGNES_SUBMIT_RETRY_DELAYS_SECONDS[previousRetries];
            int delaySeconds = Math.max(configuredDelay, upstream.retryAfterSeconds());
            OffsetDateTime retryAfter = OffsetDateTime.now().plusSeconds(delaySeconds);

            metadata.put("submit_retry_count", retryNumber);
            metadata.put("submit_retry_after", retryAfter.toString());
            metadata.put("submit_retry_http_status", upstream.statusCode());
            metadata.put("submit_retry_request_id", safe(upstream.requestId()));
            metadata.put("submit_retry_last_error", raw);
            task.setMetadata(json.writeValueAsString(metadata));
            task.setProviderAttempts(appendAttempt(
                task, task.getProvider(), task.getModel(), "submit_retry",
                "HTTP " + upstream.statusCode() + ", retry " + retryNumber, ""
            ));
            task.setStatus("queued");
            task.setProgress(0);
            task.setSafeError("");
            task.setRawError("");
            task.setUpdatedAt(OffsetDateTime.now());
            tasks.updateById(task);
            log.warn(
                "video_submit_retry task_id={} user_id={} provider=agnes retry={} delay_seconds={} http_status={} request_id={}",
                task.getId(), task.getUserId(), retryNumber, delaySeconds, upstream.statusCode(), safe(upstream.requestId())
            );
            return true;
        } catch (Exception retryException) {
            log.error("video_submit_retry_schedule_failed task_id={}", task.getId(), retryException);
            return false;
        }
    }

    private boolean definiteSubmissionRejection(Exception exception) {
        if (!(exception instanceof VideoProviderGateway.UpstreamHttpException upstream)) return false;
        int status = upstream.statusCode();
        return status >= 400 && status < 500 && status != 408;
    }

    private void quarantineUnknownSubmission(VideoTaskEntity task, String raw) throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> metadata = metadata(task);
        metadata.put("submission_unknown_at", now.toString());
        metadata.put("submission_unknown_reason", safe(raw));
        metadata.put("automatic_resubmit_blocked", true);
        metadata.put("route_locked", true);
        task.setMetadata(json.writeValueAsString(metadata));
        task.setProviderAttempts(appendAttempt(
            task, task.getProvider(), task.getModel(), "submission_unknown", safe(raw), ""
        ));
        task.setStatus("submission_unknown");
        task.setProgress(5);
        task.setSafeError("上游提交结果正在核验，请勿重复提交相同任务");
        task.setRawError(safe(raw));
        task.setUpdatedAt(now);
        tasks.updateById(task);
        log.error(
            "video_submission_unknown task_id={} user_id={} provider={} model={} route_locked=true error={}",
            task.getId(), task.getUserId(), task.getProvider(), task.getModel(), safe(raw)
        );
    }

    private void deferAssignedRoute(VideoTaskEntity task, int delaySeconds, String reason) throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> metadata = metadata(task);
        metadata.put("submit_retry_after", now.plusSeconds(Math.max(5, delaySeconds)).toString());
        metadata.put("route_deferred_reason", safe(reason));
        metadata.put("route_locked", true);
        task.setMetadata(json.writeValueAsString(metadata));
        task.setStatus("queued");
        task.setProgress(0);
        task.setSafeError("");
        task.setRawError("");
        task.setUpdatedAt(now);
        tasks.updateById(task);
    }

    private void markTimeoutVerification(VideoTaskEntity task) throws Exception {
        Map<String, Object> metadata = metadata(task);
        if (!metadata.containsKey("timeout_verification_started_at")) {
            metadata.put("timeout_verification_started_at", OffsetDateTime.now().toString());
            metadata.put("automatic_timeout_refund_blocked", true);
            task.setProviderAttempts(appendAttempt(
                task, task.getProvider(), task.getModel(), "timeout_verification",
                "Generation exceeded configured duration; polling continues on the fixed route",
                task.getUpstreamTaskId()
            ));
            log.warn(
                "video_timeout_verification task_id={} user_id={} provider={} upstream_task_id={} refund=false",
                task.getId(), task.getUserId(), task.getProvider(), task.getUpstreamTaskId()
            );
        }
        task.setMetadata(json.writeValueAsString(metadata));
        task.setProgress(Math.max(task.getProgress() == null ? 0 : task.getProgress(), 95));
    }

    private void fail(VideoTaskEntity task, String safeMessage, String raw) {
        terminal(task, "failed", safeMessage, raw);
    }

    private void terminal(VideoTaskEntity task, String status, String safeMessage, String raw) {
        OffsetDateTime now = OffsetDateTime.now();
        task.setStatus(status);
        task.setProgress(100);
        task.setSafeError(safeMessage);
        task.setRawError(safe(raw));
        task.setCompletedAt(now);
        task.setUpdatedAt(now);
        tasks.updateById(task);
        service.refundIfNeeded(task.getId(), safeMessage);
        VideoTaskEntity refreshed = tasks.selectById(task.getId());
        if (refreshed != null) task.setRefundedAt(refreshed.getRefundedAt());
        logTerminal(task, status, raw);
    }

    private boolean timedOut(VideoTaskEntity task) {
        OffsetDateTime submitted = currentAttemptSubmittedAt(task);
        return submitted != null
            && IN_FLIGHT.contains(task.getStatus())
            && submitted.isBefore(OffsetDateTime.now().minusMinutes(config.current().timeoutMinutes()));
    }

    private OffsetDateTime currentAttemptSubmittedAt(VideoTaskEntity task) {
        Object fallbackSubmittedAt = metadata(task).get("fallback_submitted_at");
        if (fallbackSubmittedAt != null && "gemini".equals(task.getProvider())) {
            try { return OffsetDateTime.parse(String.valueOf(fallbackSubmittedAt)); }
            catch (Exception ignored) { }
        }
        return task.getSubmittedAt();
    }

    private boolean claimInFlight(VideoTaskEntity task, boolean force) {
        if (!force && !readyForPoll(task)) return false;
        OffsetDateTime expectedUpdatedAt = task.getUpdatedAt();
        if (expectedUpdatedAt == null) return false;
        if (tasks.claimInFlight(task.getId(), task.getStatus(), expectedUpdatedAt) == 0) return false;
        task.setUpdatedAt(OffsetDateTime.now());
        return true;
    }

    private boolean readyForPoll(VideoTaskEntity task) {
        OffsetDateTime updated = task.getUpdatedAt();
        return updated == null || updated.isBefore(OffsetDateTime.now().minusSeconds(config.current().pollSeconds()));
    }

    private boolean isMockTask(VideoTaskEntity task) {
        return task.getUpstreamTaskId() != null && task.getUpstreamTaskId().startsWith("mock_")
            || Boolean.TRUE.equals(metadata(task).get("mock_mode"));
    }

    private String normalizeRunningStatus(String status) {
        return "submitted".equals(status) ? "submitted" : "generating";
    }

    private boolean isApimartProvider(String provider) {
        return "gemini".equals(provider) || "seedance".equals(provider);
    }

    private void mergeMetadata(VideoTaskEntity task, Map<String, Object> updates) throws Exception {
        if (updates == null || updates.isEmpty()) return;
        Map<String, Object> metadata = metadata(task);
        metadata.putAll(updates);
        task.setMetadata(json.writeValueAsString(metadata));
    }

    private BigDecimal actualCost(VideoTaskEntity task) {
        Object value = metadata(task).get("actual_cost");
        if (value == null) return task.getActualCost() == null ? BigDecimal.ZERO : task.getActualCost();
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return task.getActualCost() == null ? BigDecimal.ZERO : task.getActualCost(); }
    }

    private Map<String, Object> metadata(VideoTaskEntity task) {
        try {
            return new LinkedHashMap<>(json.readValue(task.getMetadata() == null ? "{}" : task.getMetadata(), new TypeReference<>() {}));
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private List<String> sourceUrls(VideoTaskEntity task) {
        try {
            return json.readValue(task.getSourceImageUrls() == null ? "[]" : task.getSourceImageUrls(), new TypeReference<>() {});
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String appendAttempt(
        VideoTaskEntity task,
        String provider,
        String model,
        String status,
        String message,
        String upstreamTaskId
    ) throws Exception {
        List<Map<String, Object>> attempts;
        try {
            attempts = new ArrayList<>(json.readValue(task.getProviderAttempts() == null ? "[]" : task.getProviderAttempts(), new TypeReference<>() {}));
        } catch (Exception ignored) {
            attempts = new ArrayList<>();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", provider);
        row.put("model", model);
        row.put("status", status);
        row.put("message", message == null ? "" : message);
        row.put("upstream_task_id", upstreamTaskId == null ? "" : upstreamTaskId);
        String agnesKeyId = clean(config.agnesKeyId(task), "");
        if (agnesKeyId.isBlank() && "key_submit_failed".equals(status)) {
            agnesKeyId = String.valueOf(metadata(task).getOrDefault("agnes_last_failed_key_id", ""));
        }
        if (!agnesKeyId.isBlank()) row.put("agnes_key_id", agnesKeyId);
        row.put("time", OffsetDateTime.now().toString());
        attempts.add(row);
        return json.writeValueAsString(attempts);
    }

    private void logTerminal(VideoTaskEntity task, String status, String error) {
        log.info(
            "video_terminal task_id={} user_id={} mode={} provider={} model={} routing_reason={} agnes_key_id={} status={} queue_ms={} generation_ms={} total_ms={} upstream_task_id={} credits={} refunded={} actual_cost={} error={}",
            task.getId(), task.getUserId(), task.getMode(), task.getProvider(), task.getModel(), task.getRoutingReason(),
            config.agnesKeyId(task), status,
            elapsedMillis(task.getQueuedAt(), task.getSubmittedAt()), elapsedMillis(task.getSubmittedAt(), task.getCompletedAt()),
            elapsedMillis(task.getCreatedAt(), task.getCompletedAt()), task.getUpstreamTaskId(), task.getCreditsCost(),
            task.getRefundedAt() != null, task.getActualCost(), safe(error)
        );
    }

    private long elapsedMillis(OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null) return -1;
        return Math.max(0, Duration.between(start, end).toMillis());
    }

    private String safe(String value) {
        String sanitized = value == null ? "" : value.trim()
            .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._-]+", "Bearer ***")
            .replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-***");
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }

    private String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
