package com.openimage.video;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.VideoTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class VideoTaskQueueService {
    private static final long AGNES_QUEUE_LOCK_KEY = 2026072201L;

    private final VideoTaskMapper tasks;
    private final VideoConfigService config;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public VideoTaskQueueService(
        VideoTaskMapper tasks,
        VideoConfigService config,
        JdbcTemplate jdbc,
        ObjectMapper json
    ) {
        this.tasks = tasks;
        this.config = config;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public VideoTaskEntity claimNextAgnes() {
        jdbc.execute("SELECT pg_advisory_xact_lock(" + AGNES_QUEUE_LOCK_KEY + ")");
        int concurrency = config.current().agnesConcurrency();
        int active = tasks.countActiveAgnes();
        if (active >= concurrency) return null;

        VideoTaskEntity next = tasks.lockNextQueuedAgnes();
        if (next == null) return null;
        Set<String> failedKeyIds = failedKeyIds(next);
        VideoConfigService.AgnesKeyRuntime key = selectKey(failedKeyIds);
        if (key == null) return null;
        if (tasks.claimQueuedAgnesWithKey(next.getId(), key.id(), key.label(), key.fingerprint()) == 0) return null;

        VideoTaskEntity claimed = tasks.findClaimedAgnesById(next.getId());
        if (claimed == null) {
            claimed = next;
            claimed.setStatus("submitting");
            claimed.setProgress(5);
            claimed.setMetadata(withAssignedKey(claimed, key));
            claimed.setUpdatedAt(OffsetDateTime.now());
        }
        log.info(
            "video_agnes_claim task_id={} key_id={} active_before={} concurrency={}",
            next.getId(), key.id(), active, concurrency
        );
        return claimed;
    }

    @Transactional
    public boolean requeueForAnotherAgnesKey(VideoTaskEntity task, String failure) {
        if (task == null || task.getUpstreamTaskId() != null && !task.getUpstreamTaskId().isBlank()) return false;
        jdbc.execute("SELECT pg_advisory_xact_lock(" + AGNES_QUEUE_LOCK_KEY + ")");
        String currentKeyId = config.agnesKeyId(task);
        if (currentKeyId.isBlank()) return false;

        Map<String, Object> metadata = metadata(task);
        Set<String> failed = failedKeyIds(task);
        failed.add(currentKeyId);
        boolean hasAlternative = config.dispatchableAgnesKeys().stream()
            .anyMatch(key -> !failed.contains(key.id()));
        if (!hasAlternative) return false;

        metadata.put("agnes_failed_key_ids", new ArrayList<>(failed));
        metadata.put("agnes_last_failed_key_id", currentKeyId);
        metadata.put("agnes_last_key_failure", safe(failure));
        metadata.remove("agnes_key_id");
        metadata.remove("agnes_key_label");
        metadata.remove("agnes_key_fingerprint");
        try {
            String value = json.writeValueAsString(metadata);
            if (tasks.requeueAgnesAfterKeyFailure(task.getId(), value) == 0) return false;
            task.setStatus("queued");
            task.setProgress(0);
            task.setMetadata(value);
            task.setUpdatedAt(OffsetDateTime.now());
            log.warn(
                "video_agnes_key_retry task_id={} failed_key_id={} failed_key_count={}",
                task.getId(), currentKeyId, failed.size()
            );
            return true;
        } catch (Exception exception) {
            log.error("video_agnes_key_retry_failed task_id={}", task.getId(), exception);
            return false;
        }
    }

    @Transactional
    public int recoverStaleSubmitting(OffsetDateTime staleBefore, boolean safeToRequeue) {
        int affected = safeToRequeue
            ? tasks.requeueStaleSubmitting(staleBefore)
            : tasks.quarantineStaleSubmitting(staleBefore);
        if (affected > 0) {
            log.warn(
                "video_submit_interruption_handled count={} stale_before={} action={}",
                affected, staleBefore, safeToRequeue ? "mock_requeue" : "quarantine_no_resubmit"
            );
        }
        return affected;
    }

    @Transactional
    public int recoverStaleSubmitting(OffsetDateTime staleBefore) {
        return recoverStaleSubmitting(staleBefore, false);
    }

    private VideoConfigService.AgnesKeyRuntime selectKey(Set<String> excluded) {
        List<VideoConfigService.AgnesKeyRuntime> keys = config.dispatchableAgnesKeys();
        if (keys.isEmpty()) return null;
        Map<String, KeyLoad> loads = keyLoads(keys);
        return keys.stream()
            .filter(key -> !excluded.contains(key.id()))
            .filter(key -> loads.getOrDefault(key.id(), KeyLoad.EMPTY).active() < key.concurrency())
            .min(Comparator
                .comparingDouble((VideoConfigService.AgnesKeyRuntime key) -> {
                    KeyLoad load = loads.getOrDefault(key.id(), KeyLoad.EMPTY);
                    return (double) load.active() / Math.max(1, key.concurrency());
                })
                .thenComparingLong(key -> loads.getOrDefault(key.id(), KeyLoad.EMPTY).today())
                .thenComparing(VideoConfigService.AgnesKeyRuntime::id))
            .orElse(null);
    }

    private Map<String, KeyLoad> keyLoads(List<VideoConfigService.AgnesKeyRuntime> keys) {
        Map<String, KeyLoad> result = new HashMap<>();
        List<Map<String, Object>> rows = tasks.selectAgnesKeyMetrics();
        if (rows == null) return result;
        for (Map<String, Object> row : rows) {
            String keyId = text(row.get("key_id"));
            if (keyId.isBlank() && !keys.isEmpty()) keyId = keys.get(0).id();
            if (!keyId.isBlank()) {
                result.put(keyId, new KeyLoad(number(row.get("active_count")), number(row.get("today_count"))));
            }
        }
        return result;
    }

    private Set<String> failedKeyIds(VideoTaskEntity task) {
        Object raw = metadata(task).get("agnes_failed_key_ids");
        Set<String> result = new HashSet<>();
        if (raw instanceof List<?> values) {
            values.stream().map(this::text).filter(value -> !value.isBlank()).forEach(result::add);
        }
        return result;
    }

    private String withAssignedKey(
        VideoTaskEntity task,
        VideoConfigService.AgnesKeyRuntime key
    ) {
        try {
            Map<String, Object> value = metadata(task);
            value.put("agnes_key_id", key.id());
            value.put("agnes_key_label", key.label());
            value.put("agnes_key_fingerprint", key.fingerprint());
            return json.writeValueAsString(value);
        } catch (Exception ignored) {
            return task.getMetadata();
        }
    }

    private Map<String, Object> metadata(VideoTaskEntity task) {
        try {
            return new LinkedHashMap<>(json.readValue(
                task.getMetadata() == null || task.getMetadata().isBlank() ? "{}" : task.getMetadata(),
                new TypeReference<>() {}
            ));
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? 0L : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safe(String value) {
        if (value == null) return "";
        String sanitized = value.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._-]+", "Bearer ***")
            .replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-***");
        return sanitized.length() <= 240 ? sanitized : sanitized.substring(0, 240);
    }

    private record KeyLoad(long active, long today) {
        private static final KeyLoad EMPTY = new KeyLoad(0, 0);
    }
}
