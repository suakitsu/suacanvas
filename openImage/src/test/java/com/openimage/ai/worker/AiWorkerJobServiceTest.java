package com.openimage.ai.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.AiRuntimeConfigService;
import com.openimage.domain.entity.AiWorkerArtifactEntity;
import com.openimage.domain.entity.AiWorkerInstanceEntity;
import com.openimage.domain.entity.AiWorkerJobEntity;
import com.openimage.domain.entity.ImageTaskEntity;
import com.openimage.domain.mapper.AiWorkerArtifactMapper;
import com.openimage.domain.mapper.AiWorkerInstanceMapper;
import com.openimage.domain.mapper.AiWorkerJobMapper;
import com.openimage.domain.mapper.ImageTaskMapper;
import com.openimage.storage.ImageUploadNormalizer;
import com.openimage.storage.LocalStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiWorkerJobServiceTest {
    private final AiWorkerJobMapper jobs = mock(AiWorkerJobMapper.class);
    private final AiWorkerInstanceMapper instances = mock(AiWorkerInstanceMapper.class);
    private final AiWorkerArtifactMapper artifacts = mock(AiWorkerArtifactMapper.class);
    private final ImageTaskMapper imageTasks = mock(ImageTaskMapper.class);
    private final AiRuntimeConfigService runtimeConfig = mock(AiRuntimeConfigService.class);
    private final AiWorkerTokenService tokenService = mock(AiWorkerTokenService.class);
    private final ImageUploadNormalizer normalizer = mock(ImageUploadNormalizer.class);
    private final LocalStorageService storage = mock(LocalStorageService.class);
    private final AiWorkerProperties properties = new AiWorkerProperties(
        "https://api.example.com",
        "test-token-pepper-32-characters-minimum",
        "test-signing-secret-32-characters-minimum",
        60,
        300,
        25L * 1024L * 1024L,
        15
    );
    private final AiWorkerJobService service = new AiWorkerJobService(
        jobs,
        instances,
        artifacts,
        imageTasks,
        runtimeConfig,
        tokenService,
        properties,
        new ObjectMapper(),
        new TransactionTemplate(new NoOpTransactionManager()),
        normalizer,
        storage
    );
    private final AiWorkerPrincipal principal = new AiWorkerPrincipal("token-1", "test", "ppw_test");

    @Test
    void claimUsesProviderAndWorkerLocksBeforeLeasingOneJob() {
        AiWorkerInstanceEntity instance = instance("worker-1", "token-1", 1);
        AiWorkerJobEntity row = leased("awj-1", "worker-1", "lease-hash");
        when(instances.selectById("worker-1")).thenReturn(instance);
        when(runtimeConfig.pullWorkerProviders()).thenReturn(List.of(provider(1)));
        when(jobs.countActiveByWorker("worker-1")).thenReturn(0);
        when(jobs.countActiveByProvider("local-cliproxy-image")).thenReturn(0);
        when(jobs.claimNext(
            org.mockito.ArgumentMatchers.eq("local-cliproxy-image"),
            org.mockito.ArgumentMatchers.eq("image"),
            org.mockito.ArgumentMatchers.eq("worker-1"),
            anyString(),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class)
        )).thenReturn(row);

        AiWorkerJobService.ClaimedJob claimed = service.claim(
            principal,
            "worker-1",
            List.of("image"),
            1,
            20
        );

        assertThat(claimed).isNotNull();
        assertThat(claimed.jobId()).isEqualTo("awj-1");
        assertThat(claimed.leaseId()).startsWith("awl_");
        assertThat(claimed.graceUntil()).isAfter(claimed.leaseUntil());
        verify(jobs).lockWorker("worker-1");
        verify(jobs).lockProvider("local-cliproxy-image");
    }

    @Test
    void duplicateCompleteIsIdempotentAndDoesNotWriteAgain() {
        String leaseToken = "lease-token";
        AiWorkerJobEntity job = leased("awj-text", "worker-1", leaseHash(leaseToken));
        job.setCapability("text");
        when(instances.selectById("worker-1")).thenReturn(instance("worker-1", "token-1", 1));
        when(jobs.lockById("awj-text")).thenReturn(job);

        Map<String, Object> first = service.complete(
            principal,
            "awj-text",
            "worker-1",
            leaseToken,
            "complete-request-1",
            null,
            Map.of("text", "hello"),
            Map.of("finish_reason", "stop")
        );
        Map<String, Object> duplicate = service.complete(
            principal,
            "awj-text",
            "worker-1",
            leaseToken,
            "complete-request-1",
            null,
            Map.of("text", "hello"),
            Map.of("finish_reason", "stop")
        );

        assertThat(first.get("idempotent")).isEqualTo(false);
        assertThat(duplicate.get("idempotent")).isEqualTo(true);
        verify(jobs).updateById(job);
    }

    @Test
    void expiredLeaseRejectsLateCompletionWithoutWriting() {
        String leaseToken = "old-lease";
        AiWorkerJobEntity job = leased("awj-expired", "worker-1", leaseHash(leaseToken));
        job.setCapability("text");
        job.setLeaseUntil(OffsetDateTime.now().minusSeconds(1));
        job.setLeaseGraceUntil(OffsetDateTime.now().minusSeconds(1));
        when(instances.selectById("worker-1")).thenReturn(instance("worker-1", "token-1", 1));
        when(jobs.lockById("awj-expired")).thenReturn(job);

        assertThatThrownBy(() -> service.complete(
            principal,
            "awj-expired",
            "worker-1",
            leaseToken,
            "complete-expired-1",
            null,
            Map.of("text", "late"),
            Map.of()
        ))
            .isInstanceOf(AiWorkerProtocolException.class)
            .extracting(exception -> ((AiWorkerProtocolException) exception).code())
            .isEqualTo("LEASE_EXPIRED");
        verify(jobs, never()).updateById(any(AiWorkerJobEntity.class));
    }

    @Test
    void imageCompletionRejectsBase64Metadata() {
        String leaseToken = "lease-token";
        AiWorkerJobEntity job = leased("awj-binary", "worker-1", leaseHash(leaseToken));
        job.setCapability("text");
        when(instances.selectById("worker-1")).thenReturn(instance("worker-1", "token-1", 1));
        when(jobs.lockById("awj-binary")).thenReturn(job);

        assertThatThrownBy(() -> service.complete(
            principal,
            "awj-binary",
            "worker-1",
            leaseToken,
            "complete-binary-1",
            null,
            Map.of("text", "ok"),
            Map.of("preview", "data:image/png;base64,AAAA")
        ))
            .isInstanceOf(AiWorkerProtocolException.class)
            .extracting(exception -> ((AiWorkerProtocolException) exception).code())
            .isEqualTo("BASE64_RESULT_NOT_ALLOWED");
        verify(jobs, never()).updateById(any(AiWorkerJobEntity.class));
    }

    @Test
    void completionRejectsCredentialFields() {
        String leaseId = "lease-result-secret";
        AiWorkerJobEntity job = leased("awj-result-secret", "worker-1", leaseHash(leaseId));
        job.setCapability("text");
        when(instances.selectById("worker-1")).thenReturn(instance("worker-1", "token-1", 1));
        when(jobs.lockById("awj-result-secret")).thenReturn(job);

        assertThatThrownBy(() -> service.complete(
            principal,
            "awj-result-secret",
            "worker-1",
            leaseId,
            "complete-result-secret-1",
            null,
            Map.of("text", "ok", "api_key", "must-not-be-stored"),
            Map.of()
        ))
            .isInstanceOf(AiWorkerProtocolException.class)
            .extracting(exception -> ((AiWorkerProtocolException) exception).code())
            .isEqualTo("RESULT_SECRET_NOT_ALLOWED");
        verify(jobs, never()).updateById(any(AiWorkerJobEntity.class));
    }

    @Test
    void failureSanitizesWorkerAndUpstreamSecretsBeforePersistence() {
        String leaseId = "lease-failure-secret";
        AiWorkerJobEntity job = leased("awj-failure-secret", "worker-1", leaseHash(leaseId));
        job.setCapability("text");
        when(instances.selectById("worker-1")).thenReturn(instance("worker-1", "token-1", 1));
        when(jobs.lockById("awj-failure-secret")).thenReturn(job);

        service.fail(
            principal,
            "awj-failure-secret",
            "worker-1",
            leaseId,
            "failure-secret-0001",
            "UPSTREAM_AUTH_FAILED",
            "Bearer ppw_should_never_persist api_key:\"sk-should-never-persist\""
        );

        assertThat(job.getErrorMessage())
            .doesNotContain("ppw_should_never_persist", "sk-should-never-persist")
            .contains("***");
        verify(jobs).updateById(job);
    }

    @Test
    void renewRecoversLeaseDuringGracePeriod() {
        String leaseId = "lease-grace";
        AiWorkerJobEntity job = leased("awj-grace", "worker-1", leaseHash(leaseId));
        job.setCapability("text");
        job.setLeaseUntil(OffsetDateTime.now().minusSeconds(2));
        job.setLeaseGraceUntil(OffsetDateTime.now().plusSeconds(10));
        when(instances.selectById("worker-1")).thenReturn(instance("worker-1", "token-1", 1));
        when(jobs.lockById("awj-grace")).thenReturn(job);
        when(runtimeConfig.pullWorkerProvider("local-cliproxy-image", "text"))
            .thenReturn(new AiRuntimeConfigService.WorkerProviderConfig(
                "local-cliproxy-image",
                "local-cliproxy-image",
                "text",
                "gpt-5.5",
                1,
                AiRuntimeConfigService.OFFLINE_FALLBACK,
                120,
                1
            ));

        Map<String, Object> renewed = service.renew(
            principal,
            "awj-grace",
            "worker-1",
            leaseId
        );

        assertThat((OffsetDateTime) renewed.get("leaseUntil")).isAfter(OffsetDateTime.now());
        assertThat((OffsetDateTime) renewed.get("graceUntil"))
            .isAfter((OffsetDateTime) renewed.get("leaseUntil"));
        verify(jobs).updateById(job);
    }

    @Test
    void sameCompletionKeyWithDifferentPayloadIsRejected() {
        String leaseId = "lease-conflict";
        AiWorkerJobEntity job = leased("awj-conflict", "worker-1", leaseHash(leaseId));
        job.setCapability("text");
        when(instances.selectById("worker-1")).thenReturn(instance("worker-1", "token-1", 1));
        when(jobs.lockById("awj-conflict")).thenReturn(job);

        service.complete(
            principal,
            "awj-conflict",
            "worker-1",
            leaseId,
            "complete-conflict-1",
            null,
            Map.of("text", "first"),
            Map.of()
        );

        assertThatThrownBy(() -> service.complete(
            principal,
            "awj-conflict",
            "worker-1",
            leaseId,
            "complete-conflict-1",
            null,
            Map.of("text", "different"),
            Map.of()
        ))
            .isInstanceOf(AiWorkerProtocolException.class)
            .extracting(exception -> ((AiWorkerProtocolException) exception).code())
            .isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void cancelledBusinessTaskRejectsLateCompletion() {
        String leaseId = "lease-cancelled";
        AiWorkerJobEntity job = leased("awj-cancelled", "worker-1", leaseHash(leaseId));
        when(instances.selectById("worker-1")).thenReturn(instance("worker-1", "token-1", 1));
        when(jobs.selectById("awj-cancelled")).thenReturn(job);
        when(imageTasks.selectById("business-1")).thenReturn(null);

        assertThatThrownBy(() -> service.complete(
            principal,
            "awj-cancelled",
            "worker-1",
            leaseId,
            "complete-cancelled-1",
            "artifact-does-not-matter",
            null,
            Map.of()
        ))
            .isInstanceOf(AiWorkerProtocolException.class)
            .extracting(exception -> ((AiWorkerProtocolException) exception).code())
            .isEqualTo("JOB_CANCELLED");
        verify(jobs).cancelActive(
            "awj-cancelled",
            "BUSINESS_TASK_CANCELLED",
            "Business image task is no longer active"
        );
    }

    @Test
    void artifactUploadIsIdempotentAndChecksSourceSha256() throws Exception {
        String leaseId = "lease-artifact";
        String jobId = "awj-artifact";
        byte[] sourceBytes = new byte[] {1, 2, 3, 4};
        byte[] normalizedBytes = new byte[] {9, 8, 7};
        String sourceSha = sha256(sourceBytes);
        AiWorkerJobEntity job = leased(jobId, "worker-1", leaseHash(leaseId));
        ImageTaskEntity imageTask = new ImageTaskEntity();
        imageTask.setId("business-1");
        imageTask.setProjectId("project-1");
        imageTask.setStatus("running");
        imageTask.setDeleted(0);
        AtomicReference<AiWorkerArtifactEntity> inserted = new AtomicReference<>();

        when(instances.selectById("worker-1")).thenReturn(instance("worker-1", "token-1", 1));
        when(jobs.selectById(jobId)).thenReturn(job);
        when(jobs.lockById(jobId)).thenReturn(job);
        when(imageTasks.selectById("business-1")).thenReturn(imageTask);
        when(artifacts.findByIdempotencyKey(jobId, "artifact-upload-1"))
            .thenAnswer(invocation -> inserted.get());
        when(normalizer.normalize(
            any(MockMultipartFile.class),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong()
        )).thenReturn(new ImageUploadNormalizer.NormalizedImage(
            normalizedBytes,
            "result.png",
            "image/png",
            "png",
            ".png",
            2,
            2,
            false
        ));
        when(storage.saveGeneratedProjectImage(
            org.mockito.ArgumentMatchers.eq("project-1"),
            anyString(),
            org.mockito.ArgumentMatchers.eq(normalizedBytes),
            org.mockito.ArgumentMatchers.eq("image/png")
        )).thenReturn(new LocalStorageService.StoredFile(
            "projects/project-1/result.png",
            "/api/public/files/projects/project-1/result.png",
            "result.png",
            "image/png",
            normalizedBytes.length,
            2,
            2,
            "png",
            false
        ));
        doAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        }).when(artifacts).insert(any(AiWorkerArtifactEntity.class));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "result.png",
            "image/png",
            sourceBytes
        );
        Map<String, Object> first = service.uploadArtifact(
            principal,
            jobId,
            "worker-1",
            leaseId,
            "artifact-upload-1",
            sourceSha,
            file
        );
        Map<String, Object> duplicate = service.uploadArtifact(
            principal,
            jobId,
            "worker-1",
            leaseId,
            "artifact-upload-1",
            sourceSha,
            file
        );

        assertThat(first.get("idempotent")).isEqualTo(false);
        assertThat(duplicate.get("idempotent")).isEqualTo(true);
        assertThat(first.get("sourceSha256")).isEqualTo(sourceSha);
        verify(artifacts).insert(any(AiWorkerArtifactEntity.class));
    }

    @Test
    void artifactChecksumMismatchIsRejectedBeforeNormalization() throws Exception {
        String leaseId = "lease-bad-sha";
        AiWorkerJobEntity job = leased("awj-bad-sha", "worker-1", leaseHash(leaseId));
        ImageTaskEntity imageTask = new ImageTaskEntity();
        imageTask.setId("business-1");
        imageTask.setProjectId("project-1");
        imageTask.setStatus("running");
        imageTask.setDeleted(0);
        when(instances.selectById("worker-1")).thenReturn(instance("worker-1", "token-1", 1));
        when(jobs.selectById("awj-bad-sha")).thenReturn(job);
        when(imageTasks.selectById("business-1")).thenReturn(imageTask);

        assertThatThrownBy(() -> service.uploadArtifact(
            principal,
            "awj-bad-sha",
            "worker-1",
            leaseId,
            "artifact-bad-sha-1",
            "0".repeat(64),
            new MockMultipartFile("file", "result.png", "image/png", new byte[] {1, 2, 3})
        ))
            .isInstanceOf(AiWorkerProtocolException.class)
            .extracting(exception -> ((AiWorkerProtocolException) exception).code())
            .isEqualTo("ARTIFACT_SHA256_MISMATCH");
        verify(normalizer, never()).normalize(
            any(MockMultipartFile.class),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void rejectsModelOutsideV1CatalogBeforeQueueing() {
        AiRuntimeConfigService.EffectiveTextConfig config = new AiRuntimeConfigService.EffectiveTextConfig(
            true,
            "local-cliproxy-text",
            "",
            "",
            "gpt-4o",
            45,
            "local-cliproxy-text",
            AiRuntimeConfigService.EXECUTION_PULL_WORKER,
            1,
            AiRuntimeConfigService.OFFLINE_FALLBACK,
            120,
            1
        );

        assertThatThrownBy(() -> service.executeText(
            config,
            "business-text-1",
            "openai_chat_completions",
            Map.of(
                "model", "gpt-4o",
                "messages", List.of(Map.of("role", "user", "content", "fixture")),
                "stream", false
            )
        ))
            .isInstanceOf(AiWorkerProviderException.class)
            .extracting(exception -> ((AiWorkerProviderException) exception).code())
            .isEqualTo("MODEL_NOT_ALLOWED");
        verify(jobs, never()).insert(any(AiWorkerJobEntity.class));
    }

    @Test
    void textJobUsesProcessingTimeoutAfterWorkerClaimsIt() {
        AiWorkerJobEntity leased = leased("awj-slow-text", "worker-1", leaseHash("lease-slow-text"));
        leased.setProviderId("local-cliproxy-text");
        leased.setCapability("text");
        leased.setProtocol("openai_chat_completions");
        leased.setModel("gpt-5.5");

        AiWorkerJobEntity succeeded = leased("awj-slow-text", "worker-1", leaseHash("lease-slow-text"));
        succeeded.setProviderId("local-cliproxy-text");
        succeeded.setCapability("text");
        succeeded.setProtocol("openai_chat_completions");
        succeeded.setModel("gpt-5.5");
        succeeded.setStatus("succeeded");
        succeeded.setResultJson("{\"result\":{\"text\":\"completed after claim wait\"}}");

        AiRuntimeConfigService.EffectiveTextConfig config = new AiRuntimeConfigService.EffectiveTextConfig(
            true,
            "local-cliproxy-text",
            "",
            "",
            "gpt-5.5",
            2,
            "local-cliproxy-text",
            AiRuntimeConfigService.EXECUTION_PULL_WORKER,
            1,
            AiRuntimeConfigService.OFFLINE_QUEUE,
            120,
            1
        );
        when(jobs.findDedupe(
            org.mockito.ArgumentMatchers.eq("business-slow-text"),
            org.mockito.ArgumentMatchers.eq("local-cliproxy-text"),
            org.mockito.ArgumentMatchers.eq("text"),
            anyString()
        )).thenReturn(leased);
        when(jobs.selectById("awj-slow-text"))
            .thenReturn(leased, leased, leased, leased, succeeded);

        String result = service.executeText(
            config,
            "business-slow-text",
            "openai_chat_completions",
            Map.of(
                "model", "gpt-5.5",
                "messages", List.of(Map.of("role", "user", "content", "fixture")),
                "stream", false
            )
        );

        assertThat(result).isEqualTo("completed after claim wait");
    }

    @Test
    void rejectsNestedCredentialsBeforeQueueing() {
        AiRuntimeConfigService.EffectiveTextConfig config = localTextConfig();
        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", "fixture");
        message.put("api_key", "must-not-leave-the-worker");

        assertThatThrownBy(() -> service.executeText(
            config,
            "business-text-2",
            "openai_chat_completions",
            Map.of(
                "model", "gpt-5.5",
                "messages", List.of(message),
                "stream", false
            )
        ))
            .isInstanceOf(AiWorkerProviderException.class)
            .extracting(exception -> ((AiWorkerProviderException) exception).code())
            .isEqualTo("PAYLOAD_SECRET_NOT_ALLOWED");
        verify(jobs, never()).insert(any(AiWorkerJobEntity.class));
    }

    @Test
    void rejectsUnknownTopLevelPayloadFieldBeforeQueueing() {
        AiRuntimeConfigService.EffectiveTextConfig config = localTextConfig();

        assertThatThrownBy(() -> service.executeText(
            config,
            "business-text-3",
            "openai_chat_completions",
            Map.of(
                "model", "gpt-5.5",
                "messages", List.of(Map.of("role", "user", "content", "fixture")),
                "stream", false,
                "local_config", Map.of("anything", true)
            )
        ))
            .isInstanceOf(AiWorkerProviderException.class)
            .extracting(exception -> ((AiWorkerProviderException) exception).code())
            .isEqualTo("PAYLOAD_FIELD_NOT_ALLOWED");
        verify(jobs, never()).insert(any(AiWorkerJobEntity.class));
    }

    @Test
    void rejectsPrivateNetworkImageInputBeforeQueueing() {
        AiRuntimeConfigService.EffectiveImageConfig config =
            new AiRuntimeConfigService.EffectiveImageConfig(
                true,
                "local-cliproxy-image",
                "",
                "",
                "gpt-image-2",
                "1024x1024",
                "b64_json",
                java.math.BigDecimal.ZERO,
                "https://suakitsu.com",
                180,
                "local-cliproxy-image",
                AiRuntimeConfigService.EXECUTION_PULL_WORKER,
                1,
                AiRuntimeConfigService.OFFLINE_FALLBACK,
                180,
                1
            );

        assertThatThrownBy(() -> service.executeImage(
            config,
            "business-image-private",
            "openai_images_edits",
            Map.of(
                "model", "gpt-image-2",
                "prompt", "fixture",
                "n", 1
            ),
            List.of(new AiWorkerJobService.InputSource(
                "https://172.20.0.10/private.png",
                "private.png",
                "image/png",
                100
            ))
        ))
            .isInstanceOf(AiWorkerProviderException.class)
            .extracting(exception -> ((AiWorkerProviderException) exception).code())
            .isEqualTo("INPUT_URL_NOT_PUBLIC");
        verify(jobs, never()).insert(any(AiWorkerJobEntity.class));
    }

    @Test
    void disabledProviderReleasesQueuedBusinessTaskButKeepsLeasedWork() {
        AiWorkerJobEntity queued = new AiWorkerJobEntity();
        queued.setJobId("queued-1");
        queued.setProviderId("local-cliproxy-image");
        queued.setCapability("image");
        queued.setStatus("queued");
        when(jobs.findBlockingByBusinessTask("task-1")).thenReturn(List.of(queued));
        when(jobs.countBlockingByBusinessTask("task-1")).thenReturn(0);
        when(runtimeConfig.pullWorkerProvider("local-cliproxy-image", "image")).thenReturn(null);

        assertThat(service.hasBlockingJob("task-1")).isFalse();
        verify(jobs).cancelQueued(
            "queued-1",
            "PROVIDER_DISABLED",
            "Pull Worker provider was disabled before claim"
        );
    }

    private AiRuntimeConfigService.WorkerProviderConfig provider(int concurrency) {
        return new AiRuntimeConfigService.WorkerProviderConfig(
            "local-cliproxy-image",
            "local-cliproxy-image",
            "image",
            "gpt-image-2",
            1,
            AiRuntimeConfigService.OFFLINE_FALLBACK,
            120,
            concurrency
        );
    }

    private AiRuntimeConfigService.EffectiveTextConfig localTextConfig() {
        return new AiRuntimeConfigService.EffectiveTextConfig(
            true,
            "local-cliproxy-text",
            "",
            "",
            "gpt-5.5",
            45,
            "local-cliproxy-text",
            AiRuntimeConfigService.EXECUTION_PULL_WORKER,
            1,
            AiRuntimeConfigService.OFFLINE_FALLBACK,
            120,
            1
        );
    }

    private AiWorkerInstanceEntity instance(String workerId, String tokenId, int concurrency) {
        AiWorkerInstanceEntity row = new AiWorkerInstanceEntity();
        row.setWorkerId(workerId);
        row.setTokenId(tokenId);
        row.setMaxConcurrency(concurrency);
        row.setDeleted(0);
        row.setLastHeartbeatAt(OffsetDateTime.now());
        return row;
    }

    private AiWorkerJobEntity leased(String jobId, String workerId, String leaseHash) {
        AiWorkerJobEntity row = new AiWorkerJobEntity();
        row.setJobId(jobId);
        row.setBusinessTaskId("business-1");
        row.setProviderId("local-cliproxy-image");
        row.setCapability("image");
        row.setProtocol("openai_images_generations");
        row.setModel("gpt-image-2");
        row.setRequestJson("{}");
        row.setInputFilesJson("[]");
        row.setStatus("leased");
        row.setWorkerId(workerId);
        row.setLeaseToken(leaseHash);
        row.setLeaseUntil(OffsetDateTime.now().plusMinutes(2));
        row.setLeaseGraceUntil(row.getLeaseUntil().plusSeconds(15));
        row.setAttempt(1);
        return row;
    }

    private String leaseHash(String leaseToken) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    ("test-signing-secret-32-characters-minimum\n" + leaseToken)
                        .getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
