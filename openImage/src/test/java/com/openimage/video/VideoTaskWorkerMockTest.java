package com.openimage.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.VideoTaskMapper;
import com.openimage.risk.RiskControlService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoTaskWorkerMockTest {
    @Test
    void mockAgnesTaskKeepsAssignedProviderAndAdvancesThroughAsyncStates() throws Exception {
        Fixture fixture = fixture(false);
        VideoTaskEntity task = task("vid_test", "standard", "agnes", "queued");
        task.setMetadata("{\"mock_mode\":true}");

        when(fixture.tasks.selectList(any())).thenReturn(
            List.of(), List.of(), List.of(),
            List.of(), List.of(task), List.of(),
            List.of(), List.of(task), List.of(),
            List.of(), List.of(task), List.of()
        );
        when(fixture.queue.claimNextAgnes()).thenAnswer(invocation -> {
            task.setStatus("submitting");
            return task;
        }).thenReturn(null);

        fixture.worker.process();
        assertThat(task.getStatus()).isEqualTo("submitted");
        assertThat(task.getProvider()).isEqualTo("agnes");
        assertThat(task.getUpstreamTaskId()).startsWith("mock_");
        fixture.worker.process();
        assertThat(task.getStatus()).isEqualTo("generating");
        fixture.worker.process();
        assertThat(task.getStatus()).isEqualTo("quality_check");
        fixture.worker.process();
        assertThat(task.getStatus()).isEqualTo("completed");
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(task.getActualSeconds()).isEqualByComparingTo("10");
        verify(fixture.gateway, never()).submit(any(), anyString(), any());
    }

    @Test
    void agnesFailureStaysOnAssignedRouteAndRefundsWithoutSecondSubmission() throws Exception {
        Fixture fixture = fixture(true);
        VideoTaskEntity task = task("vid_agnes_failure", "standard", "agnes", "generating");
        task.setModel("agnes-video-v2.0");
        task.setUpstreamTaskId("agnes-upstream");
        OffsetDateTime agnesSubmittedAt = OffsetDateTime.now().minusMinutes(1);
        task.setSubmittedAt(agnesSubmittedAt);
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(task), List.of());
        when(fixture.gateway.poll(task)).thenReturn(VideoProviderGateway.PollResult.failed("agnes failed", Map.of()));
        fixture.worker.process();

        assertThat(task.getStatus()).isEqualTo("failed");
        assertThat(task.getProvider()).isEqualTo("agnes");
        assertThat(task.getUpstreamTaskId()).isEqualTo("agnes-upstream");
        assertThat(task.getSubmittedAt()).isEqualTo(agnesSubmittedAt);
        verify(fixture.service).refundIfNeeded("vid_agnes_failure", "视频生成失败，积分已自动退回");
        verify(fixture.gateway, never()).submit(any(), anyString(), any());
    }

    @Test
    void explicitlySelectedAgnesFailureRefundsWithoutExpensiveGeminiFallback() throws Exception {
        Fixture fixture = fixture(true);
        VideoTaskEntity task = task("vid_agnes_direct_failure", "agnes-v2", "agnes", "generating");
        task.setCreditsCost(100);
        task.setModel("agnes-video-v2.0");
        task.setUpstreamTaskId("agnes-upstream");
        task.setSubmittedAt(OffsetDateTime.now().minusMinutes(1));
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(task), List.of());
        when(fixture.gateway.poll(task)).thenReturn(VideoProviderGateway.PollResult.failed("agnes failed", Map.of()));

        fixture.worker.process();

        assertThat(task.getStatus()).isEqualTo("failed");
        assertThat(task.getProvider()).isEqualTo("agnes");
        verify(fixture.service).refundIfNeeded("vid_agnes_direct_failure", "视频生成失败，积分已自动退回");
        verify(fixture.gateway, never()).submit(any(), anyString(), any());
    }

    @Test
    void agnesTimeoutKeepsPollingTheSameRouteWithoutRefunding() throws Exception {
        Fixture fixture = fixture(true);
        VideoTaskEntity task = task("vid_agnes_timeout", "standard", "agnes", "generating");
        task.setModel("agnes-video-v2.0");
        task.setUpstreamTaskId("agnes-upstream");
        task.setSubmittedAt(OffsetDateTime.now().minusMinutes(31));
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(task), List.of());
        when(fixture.gateway.poll(task)).thenReturn(
            VideoProviderGateway.PollResult.running("generating", 80, Map.of())
        );

        fixture.worker.process();

        assertThat(task.getStatus()).isEqualTo("generating");
        assertThat(task.getProvider()).isEqualTo("agnes");
        assertThat(task.getMetadata()).contains("timeout_verification_started_at", "automatic_timeout_refund_blocked");
        verify(fixture.gateway).poll(task);
        verify(fixture.gateway, never()).submit(any(), anyString(), any());
        verify(fixture.service, never()).refundIfNeeded(anyString(), anyString());
    }

    @Test
    void openCircuitKeepsClaimedAgnesTaskOnItsOriginalQueue() throws Exception {
        Fixture fixture = fixture(false);
        when(fixture.config.agnesCircuitOpen()).thenReturn(true);
        VideoTaskEntity task = task("vid_circuit", "standard", "agnes", "queued");
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(), List.of());
        when(fixture.queue.claimNextAgnes()).thenAnswer(invocation -> {
            task.setStatus("submitting");
            return task;
        }).thenReturn(null);

        fixture.worker.process();

        assertThat(task.getStatus()).isEqualTo("queued");
        assertThat(task.getProvider()).isEqualTo("agnes");
        assertThat(task.getProviderAttempts()).contains("circuit_open").doesNotContain("fallback_submitted");
        assertThat(task.getMetadata()).contains("submit_retry_after", "route_locked");
        verify(fixture.gateway, never()).submit(any(), anyString(), any());
    }

    @Test
    void qualitySeedanceFailureRefundsWithoutFallback() throws Exception {
        Fixture fixture = fixture(true);
        VideoTaskEntity task = task("vid_quality_failure", "quality", "seedance", "generating");
        task.setModel("doubao-seedance-2.0-fast");
        task.setUpstreamTaskId("seedance-upstream");
        task.setSubmittedAt(OffsetDateTime.now().minusMinutes(1));
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(task), List.of());
        when(fixture.gateway.poll(task)).thenReturn(VideoProviderGateway.PollResult.failed("seedance failed", Map.of()));

        fixture.worker.process();

        assertThat(task.getStatus()).isEqualTo("failed");
        assertThat(task.getProvider()).isEqualTo("seedance");
        verify(fixture.service).refundIfNeeded("vid_quality_failure", "视频生成失败，积分已自动退回");
        verify(fixture.gateway, never()).submit(any(), anyString(), any());
    }

    @Test
    void oldQueueTimeDoesNotCauseGenerationTimeout() {
        Fixture fixture = fixture(true);
        VideoTaskEntity queued = task("vid_old_queue", "standard", "agnes", "queued");
        queued.setCreatedAt(OffsetDateTime.now().minusHours(8));
        queued.setQueuedAt(queued.getCreatedAt());
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(), List.of());
        when(fixture.queue.claimNextAgnes()).thenReturn(null);

        fixture.worker.process();

        assertThat(queued.getStatus()).isEqualTo("queued");
        verify(fixture.service, never()).refundIfNeeded(anyString(), anyString());
    }

    @Test
    void ambiguousAgnesSubmitFailureIsQuarantinedWithoutRefundOrResubmit() throws Exception {
        Fixture fixture = fixture(true);
        VideoTaskEntity task = task("vid_agnes_retry", "standard", "agnes", "queued");
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(), List.of());
        when(fixture.queue.claimNextAgnes()).thenAnswer(invocation -> {
            task.setStatus("submitting");
            return task;
        }).thenReturn(null);
        when(fixture.gateway.submit(task, "agnes", List.of("https://example.com/product.jpg")))
            .thenThrow(new VideoProviderGateway.UpstreamHttpException(503, 0, "request-503", "{\"error\":\"busy\"}"));

        fixture.worker.process();

        assertThat(task.getStatus()).isEqualTo("submission_unknown");
        assertThat(task.getProgress()).isEqualTo(5);
        assertThat(task.getMetadata()).contains(
            "\"automatic_resubmit_blocked\":true",
            "\"route_locked\":true"
        );
        assertThat(task.getProviderAttempts()).contains("\"status\":\"submission_unknown\"");
        verify(fixture.service, never()).refundIfNeeded(anyString(), anyString());
    }

    @Test
    void explicitAgnesQueueFullIsSafelyRequeuedWithoutRefund() throws Exception {
        Fixture fixture = fixture(true);
        VideoTaskEntity task = task("vid_agnes_queue_full", "agnes-v2", "agnes", "queued");
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(), List.of());
        when(fixture.queue.claimNextAgnes()).thenAnswer(invocation -> {
            task.setStatus("submitting");
            return task;
        }).thenReturn(null);
        when(fixture.queue.requeueForAnotherAgnesKey(any(VideoTaskEntity.class), anyString())).thenReturn(false);
        when(fixture.gateway.submit(task, "agnes", List.of("https://example.com/product.jpg")))
            .thenThrow(new VideoProviderGateway.UpstreamHttpException(
                503, 0, "request-queue-full",
                "{\"code\":\"video_queue_full\",\"message\":\"video queue is full, please retry later\"}"
            ));

        fixture.worker.process();

        assertThat(task.getStatus()).isEqualTo("queued");
        assertThat(task.getProgress()).isZero();
        assertThat(task.getSafeError()).isEmpty();
        assertThat(task.getMetadata()).contains(
            "\"queue_full_retry_count\":1",
            "\"submit_retry_after\""
        );
        assertThat(task.getProviderAttempts()).contains("\"status\":\"queue_full_retry\"");
        verify(fixture.service, never()).refundIfNeeded(anyString(), anyString());
        verify(fixture.gateway).submit(task, "agnes", List.of("https://example.com/product.jpg"));
    }

    @Test
    void explicitlyRejectedSubmitFailsAndRefundsOnceWithoutChangingRoute() throws Exception {
        Fixture fixture = fixture(true);
        VideoTaskEntity task = task("vid_agnes_retry_exhausted", "standard", "agnes", "queued");
        task.setMetadata("{\"submit_retry_count\":3}");
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(), List.of());
        when(fixture.queue.claimNextAgnes()).thenAnswer(invocation -> {
            task.setStatus("submitting");
            return task;
        }).thenReturn(null);
        when(fixture.gateway.submit(task, "agnes", List.of("https://example.com/product.jpg")))
            .thenThrow(new VideoProviderGateway.UpstreamHttpException(429, 0, "request-final", "busy"));

        fixture.worker.process();

        assertThat(task.getStatus()).isEqualTo("failed");
        assertThat(task.getProvider()).isEqualTo("agnes");
        verify(fixture.service).refundIfNeeded(
            "vid_agnes_retry_exhausted",
            "视频提交被上游明确拒绝，积分已自动退回"
        );
        verify(fixture.gateway, never()).submit(task, "gemini", List.of("https://example.com/product.jpg"));
    }

    @Test
    void onlyDocumentedTransientHttpStatusesAreRetryable() {
        assertThat(new VideoProviderGateway.UpstreamHttpException(503, 0, "", "").transientFailure()).isTrue();
        assertThat(new VideoProviderGateway.UpstreamHttpException(429, 0, "", "").transientFailure()).isTrue();
        assertThat(new VideoProviderGateway.UpstreamHttpException(400, 0, "", "").transientFailure()).isFalse();
        assertThat(new VideoProviderGateway.UpstreamHttpException(401, 0, "", "").transientFailure()).isFalse();
    }

    @Test
    void generationTimeoutStartsAtSubmittedAtButDoesNotCausePrematureRefund() throws Exception {
        Fixture fixture = fixture(true);
        VideoTaskEntity task = task("vid_timeout", "standard", "gemini", "generating");
        task.setCreatedAt(OffsetDateTime.now().minusHours(8));
        task.setQueuedAt(task.getCreatedAt());
        task.setSubmittedAt(OffsetDateTime.now().minusMinutes(31));
        task.setUpstreamTaskId("gemini-upstream");
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(task), List.of());
        when(fixture.gateway.poll(task)).thenReturn(
            VideoProviderGateway.PollResult.running("generating", 90, Map.of())
        );

        fixture.worker.process();

        assertThat(task.getStatus()).isEqualTo("generating");
        assertThat(task.getCompletedAt()).isNull();
        assertThat(task.getMetadata()).contains("timeout_verification_started_at");
        verify(fixture.service, never()).refundIfNeeded(anyString(), anyString());
        verify(fixture.gateway).poll(task);
    }

    @Test
    void agnesResultIsDeliveredAtActualReturnedSize() throws Exception {
        Fixture fixture = fixture(true);
        VideoTaskEntity task = task("vid_agnes_704", "standard", "agnes", "generating");
        task.setModel("agnes-video-v2.0");
        task.setUpstreamTaskId("agnes-upstream");
        task.setSubmittedAt(OffsetDateTime.now().minusMinutes(1));
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(task), List.of());
        when(fixture.gateway.poll(task)).thenReturn(VideoProviderGateway.PollResult.completed(
            "https://example.com/agnes-result.mp4", 704, 1280, BigDecimal.TEN, Map.of("size", "704x1280")
        ));

        fixture.worker.process();

        assertThat(task.getStatus()).isEqualTo("completed");
        assertThat(task.getActualWidth()).isEqualTo(704);
        assertThat(task.getActualHeight()).isEqualTo(1280);
        assertThat(task.getCompletedAt()).isNotNull();
        verify(fixture.gateway, never()).submit(any(), anyString(), any());
    }

    @Test
    void completedUpstreamTaskRetriesStorageWithoutFallbackOrRefund() throws Exception {
        Fixture fixture = fixture(true);
        VideoTaskEntity task = task("vid_store_retry", "seedance-2", "seedance", "generating");
        task.setModel("doubao-seedance-2.0");
        task.setUpstreamTaskId("seedance-upstream");
        task.setSubmittedAt(OffsetDateTime.now().minusMinutes(1));
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(task), List.of());
        when(fixture.gateway.poll(task)).thenReturn(VideoProviderGateway.PollResult.completed(
            "https://example.com/result.mp4", null, null, null, Map.of("actual_cost", "0.528")
        ));
        when(fixture.resultStorage.persist(task.getId(), "https://example.com/result.mp4"))
            .thenThrow(new IllegalStateException("temporary storage failure"));

        fixture.worker.process();

        assertThat(task.getStatus()).isEqualTo("generating");
        assertThat(task.getProgress()).isEqualTo(99);
        assertThat(task.getMetadata()).contains("result_persist_error");
        verify(fixture.service, never()).refundIfNeeded(anyString(), anyString());
        verify(fixture.gateway, never()).submit(any(), anyString(), any());
    }

    @Test
    void temporaryPollFailureKeepsPaidUpstreamTaskRunningWithoutRefund() throws Exception {
        Fixture fixture = fixture(true);
        VideoTaskEntity task = task("vid_poll_retry", "seedance-2", "seedance", "generating");
        task.setModel("doubao-seedance-2.0");
        task.setUpstreamTaskId("seedance-upstream");
        task.setSubmittedAt(OffsetDateTime.now().minusMinutes(1));
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(task), List.of());
        when(fixture.gateway.poll(task)).thenThrow(
            new VideoProviderGateway.UpstreamHttpException(503, 3, "request-retry", "temporary")
        );

        fixture.worker.process();

        assertThat(task.getStatus()).isEqualTo("generating");
        assertThat(task.getMetadata()).contains("\"poll_error_count\":1", "\"poll_error_at\"");
        verify(fixture.service, never()).refundIfNeeded(anyString(), anyString());
        verify(fixture.gateway, never()).submit(any(), anyString(), any());
    }

    @Test
    void completionSettlementFailureStaysRunningAndNeverRefunds() throws Exception {
        Fixture fixture = fixture(true);
        VideoTaskEntity task = task("vid_settlement_retry", "seedance-2", "seedance", "generating");
        task.setModel("doubao-seedance-2.0");
        task.setUpstreamTaskId("seedance-upstream");
        task.setSubmittedAt(OffsetDateTime.now().minusMinutes(1));
        when(fixture.tasks.selectList(any())).thenReturn(List.of(), List.of(task), List.of());
        when(fixture.gateway.poll(task)).thenReturn(VideoProviderGateway.PollResult.completed(
            "https://example.com/result.mp4", null, null, BigDecimal.TEN, Map.of("actual_cost", "0.528")
        ));
        when(fixture.service.completeSuccessfully(task)).thenThrow(new IllegalStateException("temporary database failure"));

        fixture.worker.process();

        assertThat(task.getStatus()).isEqualTo("generating");
        assertThat(task.getMetadata()).contains("\"poll_error_count\":1");
        verify(fixture.service, never()).refundIfNeeded(anyString(), anyString());
        verify(fixture.gateway, never()).submit(any(), anyString(), any());
    }

    @Test
    void lateSuccessfulApimartCallbackCanRecoverPreviouslyRefundedTask() throws Exception {
        Fixture fixture = fixture(true);
        VideoTaskEntity task = task("vid_late_success", "seedance-2", "seedance", "failed");
        task.setModel("doubao-seedance-2.0");
        task.setUpstreamTaskId("seedance-upstream");
        task.setRefundedAt(OffsetDateTime.now().minusMinutes(1));
        when(fixture.tasks.findByUpstreamTaskId("seedance-upstream")).thenReturn(task);
        when(fixture.gateway.parseApimartPollResult(any(), anyInt())).thenReturn(
            VideoProviderGateway.PollResult.completed(
                "https://example.com/result.mp4", null, null, BigDecimal.TEN, Map.of("actual_cost", "0.528")
            )
        );

        fixture.worker.acceptApimartCallback(
            "seedance-upstream",
            new ObjectMapper().readTree("{\"id\":\"seedance-upstream\",\"status\":\"completed\"}")
        );

        assertThat(task.getStatus()).isEqualTo("completed");
        verify(fixture.service).completeSuccessfully(task);
        verify(fixture.service, never()).refundIfNeeded(anyString(), anyString());
    }

    private Fixture fixture(boolean paid) {
        ObjectMapper json = new ObjectMapper();
        VideoTaskMapper tasks = mock(VideoTaskMapper.class);
        when(tasks.claimInFlight(anyString(), anyString(), any())).thenReturn(1);
        VideoProperties properties = new VideoProperties();
        properties.setPaidProviderEnabled(paid);
        properties.setApimartApiKey(paid ? "test-key" : "");
        VideoConfigService config = mock(VideoConfigService.class);
        when(config.current()).thenReturn(new VideoConfigService.RuntimeSettings(
            true, paid, true, true, true,
            new VideoConfigService.TierSettings(true, 10, "720p", 900),
            new VideoConfigService.TierSettings(true, 10, "720p", 1200),
            15, 40, 1, 3, 5, 30
        ));
        when(config.paidEffective()).thenReturn(paid);
        when(config.mockMode()).thenReturn(!paid);
        when(config.providerEffective(anyString())).thenReturn(paid);
        when(config.agnesCircuitOpen()).thenReturn(false);
        VideoProviderGateway gateway = mock(VideoProviderGateway.class);
        when(gateway.mockSubmission(any(VideoTaskEntity.class), anyString())).thenAnswer(invocation -> {
            String provider = invocation.getArgument(1);
            String model = "agnes".equals(provider) ? "agnes-video-v2.0"
                : "seedance".equals(provider) ? "doubao-seedance-2.0-fast" : "gemini-omni-flash-preview";
            return new VideoProviderGateway.Submission("mock_" + provider, provider, model);
        });
        VideoTaskService service = mock(VideoTaskService.class);
        when(service.completeSuccessfully(any(VideoTaskEntity.class))).thenReturn(true);
        VideoTaskQueueService queue = mock(VideoTaskQueueService.class);
        RiskControlService risk = mock(RiskControlService.class);
        VideoResultStorageService resultStorage = mock(VideoResultStorageService.class, invocation -> {
            if ("persist".equals(invocation.getMethod().getName())) {
                String taskId = invocation.getArgument(0);
                return new VideoResultStorageService.StoredVideo(
                    "/api/public/files/videos/" + taskId + ".mp4", 1024, "video/mp4"
                );
            }
            return null;
        });
        return new Fixture(tasks, service, queue, config, gateway, resultStorage,
            new VideoTaskWorker(tasks, service, queue, config, gateway, resultStorage, properties, risk, json));
    }

    private VideoTaskEntity task(String id, String mode, String provider, String status) {
        OffsetDateTime now = OffsetDateTime.now();
        VideoTaskEntity task = new VideoTaskEntity();
        task.setId(id);
        task.setUserId("u_test");
        task.setPrompt("展示商品外观和使用方式");
        task.setSourceImageUrls("[\"https://example.com/product.jpg\"]");
        task.setAspectRatio("9:16");
        task.setDurationSeconds(10);
        task.setRequestedResolution("720p");
        task.setMode(mode);
        task.setStatus(status);
        task.setProgress(0);
        task.setProvider(provider);
        task.setModel(switch (provider) {
            case "agnes" -> "agnes-video-v2.0";
            case "seedance" -> "doubao-seedance-2.0-fast";
            case "hfsy" -> "sd-2.0-fast";
            default -> "gemini-omni-flash-preview";
        });
        task.setRoutingReason("test");
        task.setProviderAttempts("[]");
        task.setMetadata("{}");
        task.setCreditsCost(900);
        task.setActualCost(BigDecimal.ZERO);
        task.setQueuedAt(now);
        task.setCreatedAt(now);
        task.setUpdatedAt(now.minusSeconds(10));
        task.setDeleted(0);
        return task;
    }

    private record Fixture(
        VideoTaskMapper tasks,
        VideoTaskService service,
        VideoTaskQueueService queue,
        VideoConfigService config,
        VideoProviderGateway gateway,
        VideoResultStorageService resultStorage,
        VideoTaskWorker worker
    ) {}
}
