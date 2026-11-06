package com.openimage.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.VideoTaskMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class VideoTaskQueueServiceTest {
    @Test
    void concurrencyOneClaimsOnlyEarliestTaskAndLeavesFollowersQueued() {
        VideoTaskMapper tasks = mock(VideoTaskMapper.class);
        VideoConfigService config = mock(VideoConfigService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(config.current()).thenReturn(runtime(1));
        when(config.dispatchableAgnesKeys()).thenReturn(List.of(key("key-1")));
        when(tasks.selectAgnesKeyMetrics()).thenReturn(List.of());
        when(tasks.countActiveAgnes()).thenReturn(0, 1);
        VideoTaskEntity first = queued("vid_1", OffsetDateTime.now().minusSeconds(3));
        when(tasks.lockNextQueuedAgnes()).thenReturn(first);
        when(tasks.claimQueuedAgnesWithKey("vid_1", "key-1", "Key 1", "fp-key-1")).thenReturn(1);
        VideoTaskQueueService firstInstance = new VideoTaskQueueService(tasks, config, jdbc, new ObjectMapper());
        VideoTaskQueueService secondInstance = new VideoTaskQueueService(tasks, config, jdbc, new ObjectMapper());

        VideoTaskEntity claimed = firstInstance.claimNextAgnes();
        VideoTaskEntity blocked = secondInstance.claimNextAgnes();

        assertThat(claimed.getId()).isEqualTo("vid_1");
        assertThat(claimed.getStatus()).isEqualTo("submitting");
        assertThat(blocked).isNull();
        verify(tasks).lockNextQueuedAgnes();
    }

    @Test
    void completionFreesSlotAndNextTaskCanBeClaimedWithoutRestart() {
        VideoTaskMapper tasks = mock(VideoTaskMapper.class);
        VideoConfigService config = mock(VideoConfigService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(config.current()).thenReturn(runtime(1));
        when(config.dispatchableAgnesKeys()).thenReturn(List.of(key("key-1")));
        when(tasks.selectAgnesKeyMetrics()).thenReturn(List.of());
        when(tasks.countActiveAgnes()).thenReturn(0, 1, 0);
        VideoTaskEntity first = queued("vid_1", OffsetDateTime.now().minusSeconds(3));
        VideoTaskEntity second = queued("vid_2", OffsetDateTime.now().minusSeconds(2));
        when(tasks.lockNextQueuedAgnes()).thenReturn(first, second);
        when(tasks.claimQueuedAgnesWithKey("vid_1", "key-1", "Key 1", "fp-key-1")).thenReturn(1);
        when(tasks.claimQueuedAgnesWithKey("vid_2", "key-1", "Key 1", "fp-key-1")).thenReturn(1);
        VideoTaskQueueService queue = new VideoTaskQueueService(tasks, config, jdbc, new ObjectMapper());

        assertThat(queue.claimNextAgnes().getId()).isEqualTo("vid_1");
        assertThat(queue.claimNextAgnes()).isNull();
        assertThat(queue.claimNextAgnes().getId()).isEqualTo("vid_2");
    }

    @Test
    void workerReadsLatestConcurrencyForEveryClaim() {
        VideoTaskMapper tasks = mock(VideoTaskMapper.class);
        VideoConfigService config = mock(VideoConfigService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(config.current()).thenReturn(runtime(1), runtime(2));
        when(config.dispatchableAgnesKeys()).thenReturn(List.of(key("key-1")));
        when(tasks.selectAgnesKeyMetrics()).thenReturn(List.of());
        when(tasks.countActiveAgnes()).thenReturn(1);
        VideoTaskEntity next = queued("vid_2", OffsetDateTime.now());
        when(tasks.lockNextQueuedAgnes()).thenReturn(next);
        when(tasks.claimQueuedAgnesWithKey("vid_2", "key-1", "Key 1", "fp-key-1")).thenReturn(1);
        VideoTaskQueueService queue = new VideoTaskQueueService(tasks, config, jdbc, new ObjectMapper());

        assertThat(queue.claimNextAgnes()).isNull();
        assertThat(queue.claimNextAgnes().getId()).isEqualTo("vid_2");
    }

    @Test
    void keyPoolSkipsBusyKeyAndClaimsWithAvailableKey() {
        VideoTaskMapper tasks = mock(VideoTaskMapper.class);
        VideoConfigService config = mock(VideoConfigService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(config.current()).thenReturn(runtime(2));
        when(config.dispatchableAgnesKeys()).thenReturn(List.of(key("key-1"), key("key-2")));
        when(tasks.countActiveAgnes()).thenReturn(1);
        when(tasks.selectAgnesKeyMetrics()).thenReturn(List.of(
            Map.of("key_id", "key-1", "active_count", 1L, "today_count", 2L),
            Map.of("key_id", "key-2", "active_count", 0L, "today_count", 4L)
        ));
        VideoTaskEntity next = queued("vid_2", OffsetDateTime.now());
        when(tasks.lockNextQueuedAgnes()).thenReturn(next);
        when(tasks.claimQueuedAgnesWithKey("vid_2", "key-2", "Key 1", "fp-key-2")).thenReturn(1);
        VideoTaskQueueService queue = new VideoTaskQueueService(tasks, config, jdbc, new ObjectMapper());

        VideoTaskEntity claimed = queue.claimNextAgnes();

        assertThat(claimed).isNotNull();
        assertThat(claimed.getMetadata()).contains("\"agnes_key_id\":\"key-2\"");
        verify(tasks).claimQueuedAgnesWithKey("vid_2", "key-2", "Key 1", "fp-key-2");
    }

    @Test
    void submitFailureRequeuesForAnotherKeyWithoutChangingProvider() {
        VideoTaskMapper tasks = mock(VideoTaskMapper.class);
        VideoConfigService config = mock(VideoConfigService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        VideoTaskEntity task = queued("vid_retry", OffsetDateTime.now());
        task.setStatus("submitting");
        task.setMetadata("{\"agnes_key_id\":\"key-1\"}");
        when(config.agnesKeyId(task)).thenReturn("key-1");
        when(config.dispatchableAgnesKeys()).thenReturn(List.of(key("key-1"), key("key-2")));
        when(tasks.requeueAgnesAfterKeyFailure(org.mockito.ArgumentMatchers.eq("vid_retry"), anyString())).thenReturn(1);
        VideoTaskQueueService queue = new VideoTaskQueueService(tasks, config, jdbc, new ObjectMapper());

        assertThat(queue.requeueForAnotherAgnesKey(task, "HTTP 503")).isTrue();
        assertThat(task.getStatus()).isEqualTo("queued");
        assertThat(task.getProvider()).isEqualTo("agnes");
        assertThat(task.getMetadata()).contains("agnes_failed_key_ids", "key-1");
    }

    @Test
    void mapperContractUsesPostgresSkipLockedAndExcludesQueuedFromActiveSlots() throws Exception {
        Method next = VideoTaskMapper.class.getMethod("lockNextQueuedAgnes");
        String nextSql = String.join(" ", next.getAnnotation(Select.class).value()).replaceAll("\\s+", " ").toLowerCase();
        Method count = VideoTaskMapper.class.getMethod("countActiveAgnes");
        String countSql = String.join(" ", count.getAnnotation(Select.class).value()).replaceAll("\\s+", " ").toLowerCase();

        assertThat(nextSql).contains("order by created_at asc, id asc", "for update skip locked");
        assertThat(countSql).contains("submitting", "submitted", "generating", "upscaling", "quality_check");
        assertThat(countSql).doesNotContain("'queued'");
        assertThat(VideoTaskQueueService.class.getMethod("claimNextAgnes").getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void staleRealSubmissionIsQuarantinedWhileMockSubmissionCanBeRequeued() {
        VideoTaskMapper tasks = mock(VideoTaskMapper.class);
        VideoTaskQueueService queue = new VideoTaskQueueService(
            tasks, mock(VideoConfigService.class), mock(JdbcTemplate.class), new ObjectMapper()
        );
        OffsetDateTime staleBefore = OffsetDateTime.now().minusMinutes(2);
        when(tasks.quarantineStaleSubmitting(staleBefore)).thenReturn(1);
        when(tasks.requeueStaleSubmitting(staleBefore)).thenReturn(2);

        assertThat(queue.recoverStaleSubmitting(staleBefore, false)).isEqualTo(1);
        assertThat(queue.recoverStaleSubmitting(staleBefore, true)).isEqualTo(2);

        verify(tasks).quarantineStaleSubmitting(staleBefore);
        verify(tasks).requeueStaleSubmitting(staleBefore);
    }

    private VideoConfigService.RuntimeSettings runtime(int concurrency) {
        return new VideoConfigService.RuntimeSettings(
            true, false, true, true, true,
            new VideoConfigService.TierSettings(true, 10, "720p", 900),
            new VideoConfigService.TierSettings(true, 10, "720p", 1200),
            15, 40, concurrency, 3, 5, 30
        );
    }

    private VideoTaskEntity queued(String id, OffsetDateTime createdAt) {
        VideoTaskEntity task = new VideoTaskEntity();
        task.setId(id);
        task.setProvider("agnes");
        task.setStatus("queued");
        task.setCreatedAt(createdAt);
        return task;
    }

    private VideoConfigService.AgnesKeyRuntime key(String id) {
        return new VideoConfigService.AgnesKeyRuntime(
            id, "Key 1", true, 1, "secret", "fp-" + id, "admin", true, false
        );
    }
}
