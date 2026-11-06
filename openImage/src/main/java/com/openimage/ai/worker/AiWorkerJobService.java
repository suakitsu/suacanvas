package com.openimage.ai.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class AiWorkerJobService {
    public static final Set<String> PROTOCOLS = Set.of(
        "openai_responses",
        "openai_chat_completions",
        "openai_images_generations",
        "openai_images_edits"
    );
    private static final Set<String> CAPABILITIES = Set.of("image", "text");
    private static final Set<String> TERMINAL_STATUSES = Set.of("succeeded", "failed", "cancelled", "expired");
    private static final Set<String> IMAGE_MODELS = Set.of("gpt-image-2");
    private static final Set<String> TEXT_MODELS = Set.of(
        "gpt-5.6-sol",
        "gpt-5.5",
        "gpt-5.6-terra",
        "gpt-5.6-luna"
    );
    private static final Set<String> IMAGE_PROTOCOLS = Set.of(
        "openai_images_generations",
        "openai_images_edits"
    );
    private static final Set<String> TEXT_PROTOCOLS = Set.of(
        "openai_responses",
        "openai_chat_completions"
    );
    private static final Map<String, Set<String>> REQUEST_FIELDS = Map.of(
        "openai_responses", Set.of(
            "model", "input", "instructions", "temperature", "top_p",
            "max_output_tokens", "reasoning", "text", "tools", "tool_choice",
            "metadata", "store", "stream"
        ),
        "openai_chat_completions", Set.of(
            "model", "messages", "temperature", "top_p", "max_tokens",
            "max_completion_tokens", "frequency_penalty", "presence_penalty",
            "response_format", "stop", "seed", "stream", "reasoning_effort",
            "thinking", "tools", "tool_choice"
        ),
        "openai_images_generations", Set.of(
            "model", "prompt", "n", "size", "quality", "output_format",
            "response_format", "background", "moderation", "output_compression",
            "user"
        ),
        "openai_images_edits", Set.of(
            "model", "prompt", "n", "size", "quality", "output_format",
            "response_format", "background", "moderation", "output_compression",
            "input_fidelity", "user"
        )
    );
    private static final Set<String> FORBIDDEN_REQUEST_KEYS = Set.of(
        "authorization",
        "api_key",
        "apikey",
        "access_token",
        "refresh_token",
        "oauth",
        "client_secret",
        "cookie",
        "base_url",
        "proxy_url"
    );
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};

    private final AiWorkerJobMapper jobs;
    private final AiWorkerInstanceMapper instances;
    private final AiWorkerArtifactMapper artifacts;
    private final ImageTaskMapper imageTasks;
    private final AiRuntimeConfigService runtimeConfig;
    private final AiWorkerTokenService tokenService;
    private final AiWorkerProperties properties;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final ImageUploadNormalizer imageNormalizer;
    private final LocalStorageService storage;

    public AiWorkerJobService(
        AiWorkerJobMapper jobs,
        AiWorkerInstanceMapper instances,
        AiWorkerArtifactMapper artifacts,
        ImageTaskMapper imageTasks,
        AiRuntimeConfigService runtimeConfig,
        AiWorkerTokenService tokenService,
        AiWorkerProperties properties,
        ObjectMapper json,
        TransactionTemplate transactions,
        ImageUploadNormalizer imageNormalizer,
        LocalStorageService storage
    ) {
        this.jobs = jobs;
        this.instances = instances;
        this.artifacts = artifacts;
        this.imageTasks = imageTasks;
        this.runtimeConfig = runtimeConfig;
        this.tokenService = tokenService;
        this.properties = properties;
        this.json = json;
        this.transactions = transactions;
        this.imageNormalizer = imageNormalizer;
        this.storage = storage;
    }

    public ImageResult executeImage(
        AiRuntimeConfigService.EffectiveImageConfig config,
        String businessTaskId,
        String protocol,
        Map<String, Object> request,
        List<InputSource> inputFiles
    ) {
        AiWorkerJobEntity job = createOrReuse(
            businessTaskId,
            config.id(),
            "image",
            protocol,
            config.model(),
            request,
            inputMaps(inputFiles)
        );
        AiWorkerJobEntity resolved = waitForResult(job, config.localWaitSeconds());
        if ("succeeded".equals(resolved.getStatus())) {
            return imageResult(resolved);
        }
        if ("queued".equals(resolved.getStatus())) {
            if (AiRuntimeConfigService.OFFLINE_FALLBACK.equals(config.offlineAction())) {
                jobs.cancelQueued(resolved.getJobId(), "LOCAL_WORKER_OFFLINE", "Local Worker did not claim the job before timeout");
                throw new AiWorkerProviderException("LOCAL_WORKER_OFFLINE", "本机 Worker 离线或领取超时");
            }
            throw new AiWorkerDeferredException(resolved.getJobId(), "本机 Worker 任务正在排队");
        }
        if ("leased".equals(resolved.getStatus())) {
            throw new AiWorkerDeferredException(resolved.getJobId(), "本机 Worker 已领取任务，正在处理");
        }
        throw terminalProviderFailure(resolved);
    }

    public String executeText(
        AiRuntimeConfigService.EffectiveTextConfig config,
        String businessTaskId,
        String protocol,
        Map<String, Object> request
    ) {
        return textResult(executeTextJob(config, businessTaskId, protocol, request));
    }

    public Object executeTextRaw(
        AiRuntimeConfigService.EffectiveTextConfig config,
        String businessTaskId,
        String protocol,
        Map<String, Object> request
    ) {
        AiWorkerJobEntity resolved = executeTextJob(config, businessTaskId, protocol, request);
        Map<String, Object> stored = readMap(resolved.getResultJson());
        if (!stored.containsKey("result") || stored.get("result") == null) {
            throw new AiWorkerProviderException("TEXT_RESULT_INVALID", "本机 Worker 文案结果格式无效");
        }
        return stored.get("result");
    }

    private AiWorkerJobEntity executeTextJob(
        AiRuntimeConfigService.EffectiveTextConfig config,
        String businessTaskId,
        String protocol,
        Map<String, Object> request
    ) {
        AiWorkerJobEntity job = createOrReuse(
            businessTaskId,
            config.id(),
            "text",
            protocol,
            config.model(),
            request,
            List.of()
        );
        AiWorkerJobEntity resolved = waitForTextResult(
            job,
            config.localWaitSeconds(),
            config.timeoutSeconds()
        );
        if ("succeeded".equals(resolved.getStatus())) {
            return resolved;
        }
        if ("queued".equals(resolved.getStatus())) {
            if (AiRuntimeConfigService.OFFLINE_FALLBACK.equals(config.offlineAction())) {
                jobs.cancelQueued(resolved.getJobId(), "LOCAL_WORKER_OFFLINE", "Local Worker did not claim the job before timeout");
                throw new AiWorkerProviderException("LOCAL_WORKER_OFFLINE", "本机 Worker 离线或领取超时");
            }
            throw new AiWorkerDeferredException(resolved.getJobId(), "本机文案 Worker 任务正在排队");
        }
        if ("leased".equals(resolved.getStatus())) {
            throw new AiWorkerDeferredException(resolved.getJobId(), "本机文案 Worker 已领取任务，正在处理");
        }
        throw terminalProviderFailure(resolved);
    }

    public boolean hasBlockingJob(String businessTaskId) {
        if (businessTaskId == null || businessTaskId.isBlank()) {
            return false;
        }
        List<AiWorkerJobEntity> blocking = jobs.findBlockingByBusinessTask(businessTaskId);
        for (AiWorkerJobEntity job : blocking == null ? List.<AiWorkerJobEntity>of() : blocking) {
            if ("queued".equals(job.getStatus())
                && runtimeConfig.pullWorkerProvider(job.getProviderId(), job.getCapability()) == null) {
                jobs.cancelQueued(
                    job.getJobId(),
                    "PROVIDER_DISABLED",
                    "Pull Worker provider was disabled before claim"
                );
            }
        }
        return jobs.countBlockingByBusinessTask(businessTaskId) > 0;
    }

    public Map<String, Object> heartbeat(
        AiWorkerPrincipal principal,
        String workerId,
        List<String> capabilities,
        Integer maxConcurrency
    ) {
        String cleanWorkerId = cleanWorkerId(workerId);
        List<String> cleanCapabilities = capabilities(capabilities);
        int cleanConcurrency = clamp(maxConcurrency, 1, 32, 1);
        transactions.executeWithoutResult(status -> {
            AiWorkerInstanceEntity existing = instances.selectById(cleanWorkerId);
            if (existing != null && existing.getDeleted() != null && existing.getDeleted() == 0
                && !principal.tokenId().equals(existing.getTokenId())) {
                throw conflict("WORKER_ID_IN_USE", "workerId is already registered to another token");
            }
            OffsetDateTime now = OffsetDateTime.now();
            AiWorkerInstanceEntity row = existing == null ? new AiWorkerInstanceEntity() : existing;
            row.setWorkerId(cleanWorkerId);
            row.setTokenId(principal.tokenId());
            row.setCapabilities(writeJson(cleanCapabilities));
            row.setMaxConcurrency(cleanConcurrency);
            row.setActiveTasks(jobs.countActiveByWorker(cleanWorkerId));
            row.setLastHeartbeatAt(now);
            row.setUpdatedAt(now);
            row.setDeleted(0);
            if (existing == null) {
                row.setCreatedAt(now);
                instances.insert(row);
            } else {
                instances.updateById(row);
            }
        });
        return Map.of(
            "workerId", cleanWorkerId,
            "serverTime", OffsetDateTime.now(),
            "activeTasks", jobs.countActiveByWorker(cleanWorkerId),
            "heartbeatIntervalSeconds", Math.max(5, properties.heartbeatOfflineSecondsValue() / 3)
        );
    }

    public ClaimedJob claim(
        AiWorkerPrincipal principal,
        String workerId,
        List<String> capabilities,
        Integer maxTasks,
        Integer waitSeconds
    ) {
        String cleanWorkerId = cleanWorkerId(workerId);
        List<String> cleanCapabilities = capabilities(capabilities);
        int wait = clamp(waitSeconds, 20, 30, 25);
        int requestedTasks = clamp(maxTasks, 1, 1, 1);
        verifyWorkerOwnership(principal, cleanWorkerId);
        long deadline = System.nanoTime() + Duration.ofSeconds(wait).toNanos();
        do {
            ClaimedJob claimed = claimOnce(cleanWorkerId, cleanCapabilities, requestedTasks);
            if (claimed != null) {
                return claimed;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            sleep(500);
        } while (true);
    }

    private ClaimedJob claimOnce(String workerId, List<String> capabilities, int maxTasks) {
        return transactions.execute(status -> {
            jobs.cancelInactiveImageJobs();
            jobs.expireLeases();
            jobs.lockWorker(workerId);
            AiWorkerInstanceEntity instance = instances.selectById(workerId);
            int workerLimit = instance == null
                ? maxTasks
                : Math.max(1, Math.min(instance.getMaxConcurrency() == null ? 1 : instance.getMaxConcurrency(), 32));
            if (jobs.countActiveByWorker(workerId) >= workerLimit) {
                return null;
            }
            for (AiRuntimeConfigService.WorkerProviderConfig provider : runtimeConfig.pullWorkerProviders()) {
                if (!capabilities.contains(provider.capability())) {
                    continue;
                }
                jobs.lockProvider(provider.id());
                if (jobs.countActiveByProvider(provider.id()) >= provider.maxConcurrency()) {
                    continue;
                }
                String leaseToken = randomToken("awl_");
                OffsetDateTime now = OffsetDateTime.now();
                OffsetDateTime leaseUntil = now.plusSeconds(provider.leaseSeconds());
                OffsetDateTime graceUntil = leaseUntil.plusSeconds(properties.leaseGraceSecondsValue());
                AiWorkerJobEntity row = jobs.claimNext(
                    provider.id(),
                    provider.capability(),
                    workerId,
                    hashLeaseToken(leaseToken),
                    leaseUntil,
                    graceUntil
                );
                if (row != null) {
                    return claimedJob(row, leaseToken);
                }
            }
            return null;
        });
    }

    public Map<String, Object> renew(
        AiWorkerPrincipal principal,
        String jobId,
        String workerId,
        String leaseToken
    ) {
        String cleanWorkerId = cleanWorkerId(workerId);
        verifyWorkerOwnership(principal, cleanWorkerId);
        cancelIfBusinessTaskInactiveIfPresent(jobs.selectById(jobId));
        return transactions.execute(status -> {
            AiWorkerJobEntity job = requiredLockedJob(jobId);
            cancelIfBusinessTaskInactive(job);
            verifyLease(job, cleanWorkerId, leaseToken, true);
            AiRuntimeConfigService.WorkerProviderConfig provider =
                runtimeConfig.pullWorkerProvider(job.getProviderId(), job.getCapability());
            int leaseSeconds = provider == null ? 120 : provider.leaseSeconds();
            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime leaseUntil = now.plusSeconds(leaseSeconds);
            OffsetDateTime graceUntil = leaseUntil.plusSeconds(properties.leaseGraceSecondsValue());
            job.setLeaseUntil(leaseUntil);
            job.setLeaseGraceUntil(graceUntil);
            job.setUpdatedAt(now);
            jobs.updateById(job);
            return leaseView(job.getJobId(), leaseUntil, graceUntil);
        });
    }

    public Map<String, Object> complete(
        AiWorkerPrincipal principal,
        String jobId,
        String workerId,
        String leaseId,
        String idempotencyKey,
        String artifactId,
        Object result,
        Map<String, Object> metadata
    ) {
        String cleanWorkerId = cleanWorkerId(workerId);
        String cleanIdempotencyKey = cleanIdempotencyKey(idempotencyKey);
        verifyWorkerOwnership(principal, cleanWorkerId);
        cancelIfBusinessTaskInactiveIfPresent(jobs.selectById(jobId));
        rejectEmbeddedBinary(result);
        rejectEmbeddedBinary(metadata);
        rejectResultSecrets(result);
        rejectResultSecrets(metadata);
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("action", "complete");
        fingerprint.put("artifact_id", clean(artifactId, 64));
        fingerprint.put("result", result);
        fingerprint.put("metadata", metadata == null ? Map.of() : metadata);
        String requestHash = requestHash(fingerprint);
        return transactions.execute(status -> {
            AiWorkerJobEntity job = requiredLockedJob(jobId);
            Map<String, Object> replay = terminalReplay(
                job,
                "succeeded",
                cleanWorkerId,
                leaseId,
                cleanIdempotencyKey,
                requestHash
            );
            if (replay != null) {
                return replay;
            }
            cancelIfBusinessTaskInactive(job);
            verifyLease(job, cleanWorkerId, leaseId, true);
            Map<String, Object> normalized = new LinkedHashMap<>();
            if ("image".equals(job.getCapability())) {
                AiWorkerArtifactEntity artifact = artifacts.findForJob(clean(artifactId, 64), job.getJobId());
                if (artifact == null || !cleanWorkerId.equals(artifact.getWorkerId())) {
                    throw badRequest("ARTIFACT_REQUIRED", "A valid artifactId is required for image completion");
                }
                normalized.put("artifact_id", artifact.getArtifactId());
                normalized.put("content_type", artifact.getContentType());
                normalized.put("width", artifact.getWidth());
                normalized.put("height", artifact.getHeight());
                normalized.put("format", artifact.getFormat());
            } else {
                if (result == null) {
                    throw badRequest("RESULT_REQUIRED", "Text completion requires result");
                }
                normalized.put("result", result);
            }
            if (metadata != null && !metadata.isEmpty()) {
                normalized.put("metadata", metadata);
            }
            String resultJson = writeJson(normalized);
            if (resultJson.length() > 262_144) {
                throw badRequest("RESULT_TOO_LARGE", "Completion metadata is too large");
            }
            OffsetDateTime now = OffsetDateTime.now();
            job.setStatus("succeeded");
            job.setResultJson(resultJson);
            job.setErrorCode(null);
            job.setErrorMessage(null);
            job.setCompletionIdempotencyKey(cleanIdempotencyKey);
            job.setCompletionRequestHash(requestHash);
            job.setCompletedAt(now);
            job.setUpdatedAt(now);
            jobs.updateById(job);
            return Map.of("jobId", job.getJobId(), "status", "succeeded", "idempotent", false);
        });
    }

    public Map<String, Object> fail(
        AiWorkerPrincipal principal,
        String jobId,
        String workerId,
        String leaseId,
        String idempotencyKey,
        String errorCode,
        String errorMessage
    ) {
        String cleanWorkerId = cleanWorkerId(workerId);
        String cleanIdempotencyKey = cleanIdempotencyKey(idempotencyKey);
        String cleanErrorCode = cleanErrorCode(errorCode);
        String cleanErrorMessage = sanitizeError(errorMessage);
        String requestHash = requestHash(Map.of(
            "action", "fail",
            "error_code", cleanErrorCode,
            "error_message", cleanErrorMessage
        ));
        verifyWorkerOwnership(principal, cleanWorkerId);
        cancelIfBusinessTaskInactiveIfPresent(jobs.selectById(jobId));
        return transactions.execute(status -> {
            AiWorkerJobEntity job = requiredLockedJob(jobId);
            Map<String, Object> replay = terminalReplay(
                job,
                "failed",
                cleanWorkerId,
                leaseId,
                cleanIdempotencyKey,
                requestHash
            );
            if (replay != null) {
                return replay;
            }
            cancelIfBusinessTaskInactive(job);
            verifyLease(job, cleanWorkerId, leaseId, true);
            OffsetDateTime now = OffsetDateTime.now();
            job.setStatus("failed");
            job.setErrorCode(cleanErrorCode);
            job.setErrorMessage(cleanErrorMessage);
            job.setCompletionIdempotencyKey(cleanIdempotencyKey);
            job.setCompletionRequestHash(requestHash);
            job.setCompletedAt(now);
            job.setUpdatedAt(now);
            jobs.updateById(job);
            return Map.of(
                "jobId", job.getJobId(),
                "status", "failed",
                "idempotent", false,
                "retryable", AiWorkerErrorPolicy.retryable(null, cleanErrorCode)
            );
        });
    }

    public Map<String, Object> uploadArtifact(
        AiWorkerPrincipal principal,
        String jobId,
        String workerId,
        String leaseId,
        String idempotencyKey,
        String expectedSha256,
        MultipartFile file
    ) {
        String cleanWorkerId = cleanWorkerId(workerId);
        String cleanIdempotencyKey = cleanIdempotencyKey(idempotencyKey);
        String cleanExpectedSha256 = cleanSha256(expectedSha256);
        verifyWorkerOwnership(principal, cleanWorkerId);
        AiWorkerJobEntity before = jobs.selectById(jobId);
        throwIfCancelled(before);
        AiWorkerArtifactEntity existing = artifacts.findByIdempotencyKey(
            clean(jobId, 64),
            cleanIdempotencyKey
        );
        if (existing != null) {
            verifyLeaseIdentity(before, cleanWorkerId, leaseId);
            return artifactReplay(existing, cleanWorkerId, cleanExpectedSha256);
        }
        cancelIfBusinessTaskInactive(before);
        verifyLease(before, cleanWorkerId, leaseId, true);
        if (!"image".equals(before.getCapability())) {
            throw badRequest("ARTIFACT_NOT_ALLOWED", "Artifacts are only accepted for image jobs");
        }
        if (file == null || file.isEmpty()) {
            throw badRequest("ARTIFACT_EMPTY", "Artifact file is required");
        }
        try {
            byte[] sourceBytes = file.getBytes();
            String actualSourceSha256 = sha256(sourceBytes);
            if (!constantEquals(cleanExpectedSha256, actualSourceSha256)) {
                throw badRequest("ARTIFACT_SHA256_MISMATCH", "Artifact SHA-256 does not match the uploaded bytes");
            }
            ImageUploadNormalizer.NormalizedImage image = imageNormalizer.normalize(
                file,
                properties.maxArtifactBytesValue(),
                ImageUploadNormalizer.DEFAULT_MAX_PIXELS
            );
            if (!Set.of("image/jpeg", "image/png", "image/webp").contains(image.contentType())) {
                throw badRequest("ARTIFACT_MIME_UNSUPPORTED", "Artifact must normalize to JPEG, PNG, or WebP");
            }
            ImageTaskEntity task = imageTasks.selectById(before.getBusinessTaskId());
            if (!activeImageTask(task)) {
                jobs.cancelActive(before.getJobId(), "BUSINESS_TASK_CANCELLED", "Business image task is no longer active");
                throw jobCancelled();
            }
            String artifactId = "awa_" + id();
            String filename = "worker_" + artifactId + image.extension();
            LocalStorageService.StoredFile stored = storage.saveGeneratedProjectImage(
                task.getProjectId(),
                filename,
                image.bytes(),
                image.contentType()
            );
            try {
                return transactions.execute(status -> {
                    AiWorkerArtifactEntity raced = artifacts.findByIdempotencyKey(
                        clean(jobId, 64),
                        cleanIdempotencyKey
                    );
                    if (raced != null) {
                        return artifactReplay(raced, cleanWorkerId, cleanExpectedSha256);
                    }
                    AiWorkerJobEntity locked = requiredLockedJob(jobId);
                    verifyLease(locked, cleanWorkerId, leaseId, true);
                    AiWorkerArtifactEntity artifact = new AiWorkerArtifactEntity();
                    artifact.setArtifactId(artifactId);
                    artifact.setJobId(jobId);
                    artifact.setWorkerId(cleanWorkerId);
                    artifact.setIdempotencyKey(cleanIdempotencyKey);
                    artifact.setStoragePath(stored.path());
                    artifact.setUrl(stored.url());
                    artifact.setFilename(stored.filename());
                    artifact.setContentType(image.contentType());
                    artifact.setFileSize(image.fileSize());
                    artifact.setSha256(sha256(image.bytes()));
                    artifact.setSourceSha256(actualSourceSha256);
                    artifact.setWidth(image.width());
                    artifact.setHeight(image.height());
                    artifact.setFormat(image.format());
                    artifact.setCreatedAt(OffsetDateTime.now());
                    artifact.setDeleted(0);
                    artifacts.insert(artifact);
                    return artifactView(artifact, false);
                });
            } catch (DuplicateKeyException exception) {
                AiWorkerArtifactEntity raced = artifacts.findByIdempotencyKey(
                    clean(jobId, 64),
                    cleanIdempotencyKey
                );
                if (raced != null) {
                    return artifactReplay(raced, cleanWorkerId, cleanExpectedSha256);
                }
                throw exception;
            }
        } catch (AiWorkerProtocolException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest("ARTIFACT_INVALID", "Artifact is not a valid supported image");
        }
    }

    public URI signedInputRedirect(
        AiWorkerPrincipal principal,
        String jobId,
        int inputIndex,
        String workerId,
        long expires,
        String signature
    ) {
        String cleanWorkerId = cleanWorkerId(workerId);
        verifyWorkerOwnership(principal, cleanWorkerId);
        AiWorkerJobEntity job = jobs.selectById(jobId);
        cancelIfBusinessTaskInactive(job);
        verifyLease(job, cleanWorkerId, "", false);
        if (expires < System.currentTimeMillis() / 1000L) {
            throw new AiWorkerProtocolException(HttpStatus.GONE, "INPUT_URL_EXPIRED", "Input URL has expired");
        }
        List<Map<String, Object>> inputs = readList(job.getInputFilesJson());
        if (inputIndex < 0 || inputIndex >= inputs.size()) {
            throw new AiWorkerProtocolException(HttpStatus.NOT_FOUND, "INPUT_NOT_FOUND", "Input file does not exist");
        }
        String sourceUrl = cleanUrl(inputs.get(inputIndex).get("source_url"));
        String expected = inputSignature(jobId, inputIndex, cleanWorkerId, expires, sourceUrl);
        if (!constantEquals(expected, signature)) {
            throw new AiWorkerProtocolException(HttpStatus.FORBIDDEN, "INPUT_SIGNATURE_INVALID", "Input signature is invalid");
        }
        URI target = URI.create(sourceUrl);
        if (!"https".equalsIgnoreCase(target.getScheme())) {
            throw new AiWorkerProtocolException(HttpStatus.BAD_REQUEST, "INPUT_HTTPS_REQUIRED", "Input source must use HTTPS");
        }
        return target;
    }

    public Map<String, Object> adminState() {
        OffsetDateTime onlineCutoff = OffsetDateTime.now().minusSeconds(properties.heartbeatOfflineSecondsValue());
        List<AiWorkerInstanceEntity> workerRows = instances.selectList(
            new LambdaQueryWrapper<AiWorkerInstanceEntity>()
                .eq(AiWorkerInstanceEntity::getDeleted, 0)
                .orderByDesc(AiWorkerInstanceEntity::getLastHeartbeatAt)
        );
        List<Map<String, Object>> workerViews = workerRows.stream().map(row -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("worker_id", row.getWorkerId());
            view.put("capabilities", readStringList(row.getCapabilities()));
            view.put("online", row.getLastHeartbeatAt() != null && row.getLastHeartbeatAt().isAfter(onlineCutoff));
            view.put("last_heartbeat_at", row.getLastHeartbeatAt());
            view.put("active_tasks", jobs.countActiveByWorker(row.getWorkerId()));
            view.put("max_concurrency", row.getMaxConcurrency());
            List<String> currentJobs = jobs.selectList(new LambdaQueryWrapper<AiWorkerJobEntity>()
                    .eq(AiWorkerJobEntity::getWorkerId, row.getWorkerId())
                    .eq(AiWorkerJobEntity::getStatus, "leased")
                    .apply("COALESCE(lease_grace_until, lease_until) > now()")
                    .orderByAsc(AiWorkerJobEntity::getCreatedAt))
                .stream()
                .map(AiWorkerJobEntity::getJobId)
                .toList();
            view.put("current_jobs", currentJobs);
            return view;
        }).toList();

        Map<String, Object> providerStatus = new LinkedHashMap<>();
        for (AiRuntimeConfigService.WorkerProviderConfig provider : runtimeConfig.pullWorkerProviders()) {
            long capableOnline = workerRows.stream()
                .filter(row -> row.getLastHeartbeatAt() != null && row.getLastHeartbeatAt().isAfter(onlineCutoff))
                .filter(row -> readStringList(row.getCapabilities()).contains(provider.capability()))
                .count();
            providerStatus.put(provider.id(), Map.of(
                "online", capableOnline > 0,
                "online_workers", capableOnline,
                "active_tasks", jobs.countActiveByProvider(provider.id()),
                "max_concurrency", provider.maxConcurrency()
            ));
        }
        return Map.of(
            "tokens", tokenService.adminViews(),
            "instances", workerViews,
            "providers", providerStatus,
            "offline_after_seconds", properties.heartbeatOfflineSecondsValue(),
            "token_pepper_configured", properties.tokenPepperValue().length() >= 32,
            "signing_secret_configured", properties.signingSecretValue().length() >= 32
        );
    }

    @Scheduled(fixedDelay = 5000)
    public void recoverExpiredLeases() {
        transactions.executeWithoutResult(status -> {
            jobs.cancelInactiveImageJobs();
            jobs.expireLeases();
            for (AiRuntimeConfigService.WorkerProviderConfig provider : runtimeConfig.pullWorkerProviders()) {
                if (AiRuntimeConfigService.OFFLINE_QUEUE.equals(provider.offlineAction())) {
                    jobs.requeueExpired(provider.id());
                }
            }
        });
    }

    private AiWorkerJobEntity createOrReuse(
        String businessTaskId,
        String providerId,
        String capability,
        String protocol,
        String model,
        Map<String, Object> request,
        List<Map<String, Object>> inputFiles
    ) {
        String cleanCapability = capability(capability);
        String cleanProtocol = protocol(protocol);
        String cleanModel = model(cleanCapability, clean(model, 160));
        String cleanBusinessTaskId = clean(businessTaskId, 160);
        String cleanProviderId = clean(providerId, 96);
        if (cleanBusinessTaskId.isBlank() || cleanProviderId.isBlank()) {
            throw new AiWorkerProviderException("WORKER_JOB_INVALID", "Worker Job 缺少业务任务或 Provider");
        }
        Map<String, Object> safeRequest = requestPayload(cleanCapability, cleanProtocol, cleanModel, request);
        List<Map<String, Object>> safeInputFiles = inputFiles == null ? List.of() : List.copyOf(inputFiles);
        if ("openai_images_edits".equals(cleanProtocol) && safeInputFiles.isEmpty()) {
            throw new AiWorkerProviderException(
                "INPUT_FILES_REQUIRED",
                "Image edit jobs require at least one signed input image"
            );
        }
        if ("openai_images_generations".equals(cleanProtocol) && !safeInputFiles.isEmpty()) {
            throw new AiWorkerProviderException(
                "INPUT_FILES_NOT_ALLOWED",
                "Image generation jobs do not accept edit input files"
            );
        }
        if ("text".equals(cleanCapability) && !safeInputFiles.isEmpty()) {
            throw new AiWorkerProviderException(
                "INPUT_FILES_NOT_ALLOWED",
                "Text jobs do not accept input files"
            );
        }
        if (safeInputFiles.size() > 10) {
            throw new AiWorkerProviderException(
                "INPUT_FILES_TOO_MANY",
                "Worker jobs accept at most 10 input files"
            );
        }
        String requestJson = writeJson(safeRequest);
        String inputJson = writeJson(safeInputFiles);
        if (requestJson.length() > 262_144 || inputJson.length() > 262_144) {
            throw new AiWorkerProviderException("WORKER_REQUEST_TOO_LARGE", "Worker Job 请求超过协议上限");
        }
        String requestHash = sha256((cleanProtocol + "\n" + cleanModel + "\n" + requestJson + "\n" + inputJson).getBytes(StandardCharsets.UTF_8));
        AiWorkerJobEntity existing = jobs.findDedupe(cleanBusinessTaskId, cleanProviderId, cleanCapability, requestHash);
        if (existing != null) {
            return existing;
        }
        AiWorkerJobEntity row = new AiWorkerJobEntity();
        row.setJobId("awj_" + id());
        row.setBusinessTaskId(cleanBusinessTaskId);
        row.setProviderId(cleanProviderId);
        row.setCapability(cleanCapability);
        row.setProtocol(cleanProtocol);
        row.setModel(cleanModel);
        row.setRequestJson(requestJson);
        row.setInputFilesJson(inputJson);
        row.setRequestHash(requestHash);
        row.setStatus("queued");
        row.setAttempt(0);
        row.setResultJson("{}");
        row.setCreatedAt(OffsetDateTime.now());
        row.setUpdatedAt(row.getCreatedAt());
        try {
            jobs.insert(row);
            return row;
        } catch (DuplicateKeyException exception) {
            AiWorkerJobEntity raced = jobs.findDedupe(cleanBusinessTaskId, cleanProviderId, cleanCapability, requestHash);
            if (raced != null) {
                return raced;
            }
            throw exception;
        }
    }

    private AiWorkerJobEntity waitForResult(AiWorkerJobEntity job, int waitSeconds) {
        long deadline = System.nanoTime() + Duration.ofSeconds(Math.max(1, Math.min(waitSeconds, 300))).toNanos();
        AiWorkerJobEntity current = job;
        while (current != null && !TERMINAL_STATUSES.contains(current.getStatus())) {
            if ("leased".equals(current.getStatus())
                && current.getLeaseUntil() != null
                && !current.getLeaseUntil().isAfter(OffsetDateTime.now())) {
                recoverExpiredLeases();
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            sleep(250);
            current = jobs.selectById(job.getJobId());
        }
        return current == null ? job : current;
    }

    private AiWorkerJobEntity waitForTextResult(
        AiWorkerJobEntity job,
        int claimWaitSeconds,
        int processingWaitSeconds
    ) {
        long claimDeadline = System.nanoTime()
            + Duration.ofSeconds(Math.max(1, Math.min(claimWaitSeconds, 300))).toNanos();
        long processingDeadline = 0L;
        AiWorkerJobEntity current = job;
        while (current != null && !TERMINAL_STATUSES.contains(current.getStatus())) {
            if ("leased".equals(current.getStatus())) {
                if (processingDeadline == 0L) {
                    processingDeadline = System.nanoTime()
                        + Duration.ofSeconds(Math.max(1, Math.min(processingWaitSeconds, 300))).toNanos();
                }
                if (current.getLeaseUntil() != null
                    && !current.getLeaseUntil().isAfter(OffsetDateTime.now())) {
                    recoverExpiredLeases();
                }
                if (System.nanoTime() >= processingDeadline) {
                    break;
                }
            } else if (System.nanoTime() >= claimDeadline) {
                break;
            }
            sleep(250);
            current = jobs.selectById(job.getJobId());
        }
        return current == null ? job : current;
    }

    private ClaimedJob claimedJob(AiWorkerJobEntity row, String leaseToken) {
        Map<String, Object> request = readMap(row.getRequestJson());
        List<Map<String, Object>> inputFiles = readList(row.getInputFilesJson());
        long expires = OffsetDateTime.now().plusSeconds(properties.inputUrlTtlSecondsValue()).toEpochSecond();
        List<Map<String, Object>> signedFiles = new ArrayList<>();
        String workerBaseUrl = inputFiles.isEmpty() ? "" : workerPublicBaseUrl();
        for (int index = 0; index < inputFiles.size(); index++) {
            Map<String, Object> source = inputFiles.get(index);
            String sourceUrl = cleanUrl(source.get("source_url"));
            String signature = inputSignature(row.getJobId(), index, row.getWorkerId(), expires, sourceUrl);
            String url = workerBaseUrl
                + AiWorkerAuthenticationFilter.PREFIX
                + "/inputs/" + row.getJobId() + "/" + index
                + "?workerId=" + encode(row.getWorkerId())
                + "&expires=" + expires
                + "&signature=" + signature;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("index", index);
            view.put("url", url);
            view.put("filename", clean(source.get("filename"), 255));
            view.put("contentType", clean(source.get("content_type"), 120));
            view.put("fileSize", longValue(source.get("file_size"), 0));
            view.put("urlExpiresAt", OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(expires),
                java.time.ZoneOffset.UTC
            ));
            view.put("urlChecksum", signature);
            view.put("urlSignatureAlgorithm", "HMAC-SHA256");
            signedFiles.add(view);
        }
        OffsetDateTime graceUntil = leaseGraceUntil(row);
        return new ClaimedJob(
            row.getJobId(),
            leaseToken,
            row.getLeaseUntil(),
            renewAfter(row.getLeaseUntil()),
            graceUntil,
            row.getCapability(),
            row.getProtocol(),
            row.getModel(),
            request,
            signedFiles,
            row.getAttempt() == null ? 0 : row.getAttempt()
        );
    }

    private ImageResult imageResult(AiWorkerJobEntity job) {
        Map<String, Object> result = readMap(job.getResultJson());
        String artifactId = clean(result.get("artifact_id"), 64);
        AiWorkerArtifactEntity artifact = artifacts.findForJob(artifactId, job.getJobId());
        if (artifact == null) {
            throw new AiWorkerProviderException("ARTIFACT_MISSING", "本机 Worker 完成记录缺少图片文件");
        }
        try {
            byte[] bytes = storage.readStoredBytes(artifact.getStoragePath());
            String returnedSize = artifact.getWidth() == null || artifact.getHeight() == null
                ? ""
                : artifact.getWidth() + "x" + artifact.getHeight();
            return new ImageResult(bytes, artifact.getContentType(), returnedSize, job.getJobId());
        } catch (Exception exception) {
            throw new AiWorkerProviderException("ARTIFACT_READ_FAILED", "本机 Worker 图片文件读取失败");
        }
    }

    private String textResult(AiWorkerJobEntity job) {
        JsonNode root;
        try {
            root = json.readTree(job.getResultJson()).path("result");
        } catch (Exception exception) {
            throw new AiWorkerProviderException("TEXT_RESULT_INVALID", "本机 Worker 文案结果格式无效");
        }
        String direct = root.path("text").asText("").trim();
        if (direct.isBlank()) direct = root.path("output_text").asText("").trim();
        if (direct.isBlank()) direct = root.path("choices").path(0).path("message").path("content").asText("").trim();
        if (direct.isBlank()) {
            JsonNode output = root.path("output");
            if (output.isArray()) {
                for (JsonNode item : output) {
                    for (JsonNode content : item.path("content")) {
                        String text = content.path("text").asText("").trim();
                        if (!text.isBlank()) {
                            return text;
                        }
                    }
                }
            }
        }
        if (direct.isBlank()) {
            throw new AiWorkerProviderException("TEXT_RESULT_EMPTY", "本机 Worker 未返回文案内容");
        }
        return direct;
    }

    private AiWorkerProviderException terminalProviderFailure(AiWorkerJobEntity job) {
        String code = cleanErrorCode(job.getErrorCode());
        String message = sanitizeError(job.getErrorMessage());
        if (message.isBlank()) {
            message = "本机 Worker 任务状态为 " + clean(job.getStatus(), 24);
        }
        return new AiWorkerProviderException(code, message);
    }

    private AiWorkerJobEntity requiredLockedJob(String jobId) {
        AiWorkerJobEntity job = jobs.lockById(clean(jobId, 64));
        if (job == null) {
            throw new AiWorkerProtocolException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Worker job does not exist");
        }
        return job;
    }

    private Map<String, Object> leaseView(
        String jobId,
        OffsetDateTime leaseUntil,
        OffsetDateTime graceUntil
    ) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", jobId);
        view.put("leaseUntil", leaseUntil);
        view.put("renewAfter", renewAfter(leaseUntil));
        view.put("graceUntil", graceUntil);
        return view;
    }

    private OffsetDateTime renewAfter(OffsetDateTime leaseUntil) {
        OffsetDateTime now = OffsetDateTime.now();
        if (leaseUntil == null || !leaseUntil.isAfter(now)) {
            return now;
        }
        long seconds = Math.max(1L, Duration.between(now, leaseUntil).getSeconds());
        return now.plusSeconds(Math.max(1L, (seconds * 2L) / 3L));
    }

    private OffsetDateTime leaseGraceUntil(AiWorkerJobEntity job) {
        if (job == null) {
            return null;
        }
        return job.getLeaseGraceUntil() == null ? job.getLeaseUntil() : job.getLeaseGraceUntil();
    }

    private void verifyLease(AiWorkerJobEntity job, String workerId, String leaseId, boolean requireToken) {
        if (job == null) {
            throw new AiWorkerProtocolException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Worker job does not exist");
        }
        throwIfCancelled(job);
        if ("expired".equals(job.getStatus())) {
            throw conflict("LEASE_EXPIRED", "Worker lease has expired");
        }
        if (!"leased".equals(job.getStatus())) {
            throw conflict("JOB_NOT_LEASED", "Worker job is not leased");
        }
        OffsetDateTime graceUntil = leaseGraceUntil(job);
        if (graceUntil == null || !graceUntil.isAfter(OffsetDateTime.now())) {
            throw conflict("LEASE_EXPIRED", "Worker lease has expired");
        }
        if (!workerId.equals(job.getWorkerId())) {
            throw new AiWorkerProtocolException(HttpStatus.FORBIDDEN, "WORKER_MISMATCH", "Worker does not own this lease");
        }
        if (requireToken && !constantEquals(job.getLeaseToken(), hashLeaseToken(leaseId))) {
            throw new AiWorkerProtocolException(HttpStatus.FORBIDDEN, "LEASE_ID_INVALID", "leaseId is invalid");
        }
    }

    private void verifyLeaseIdentity(AiWorkerJobEntity job, String workerId, String leaseId) {
        if (job == null) {
            throw new AiWorkerProtocolException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Worker job does not exist");
        }
        if (!workerId.equals(job.getWorkerId())
            || !constantEquals(job.getLeaseToken(), hashLeaseToken(leaseId))) {
            throw new AiWorkerProtocolException(HttpStatus.FORBIDDEN, "LEASE_ID_INVALID", "Lease identity is invalid");
        }
    }

    private Map<String, Object> terminalReplay(
        AiWorkerJobEntity job,
        String expectedStatus,
        String workerId,
        String leaseId,
        String idempotencyKey,
        String requestHash
    ) {
        throwIfCancelled(job);
        if ("expired".equals(job.getStatus())) {
            throw conflict("LEASE_EXPIRED", "Worker lease has expired");
        }
        if (!TERMINAL_STATUSES.contains(job.getStatus())) {
            return null;
        }
        if (!expectedStatus.equals(job.getStatus())) {
            throw conflict("JOB_TERMINAL_CONFLICT", "Worker job already has a different terminal result");
        }
        verifyLeaseIdentity(job, workerId, leaseId);
        String storedKey = job.getCompletionIdempotencyKey();
        String storedHash = job.getCompletionRequestHash();
        boolean legacyCompletion = (storedKey == null || storedKey.isBlank())
            && (storedHash == null || storedHash.isBlank());
        if (!legacyCompletion
            && (!constantEquals(storedKey, idempotencyKey) || !constantEquals(storedHash, requestHash))) {
            throw conflict(
                "IDEMPOTENCY_CONFLICT",
                "The idempotency key was already used with a different terminal payload"
            );
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", job.getJobId());
        response.put("status", job.getStatus());
        response.put("idempotent", true);
        if ("failed".equals(job.getStatus())) {
            response.put("retryable", AiWorkerErrorPolicy.retryable(null, job.getErrorCode()));
        }
        return response;
    }

    private Map<String, Object> artifactReplay(
        AiWorkerArtifactEntity artifact,
        String workerId,
        String expectedSourceSha256
    ) {
        if (!workerId.equals(artifact.getWorkerId())) {
            throw new AiWorkerProtocolException(
                HttpStatus.FORBIDDEN,
                "ARTIFACT_WORKER_MISMATCH",
                "Artifact belongs to another Worker"
            );
        }
        if (!constantEquals(expectedSourceSha256, artifact.getSourceSha256())) {
            throw conflict(
                "IDEMPOTENCY_CONFLICT",
                "The artifact idempotency key was already used with different bytes"
            );
        }
        return artifactView(artifact, true);
    }

    private void throwIfCancelled(AiWorkerJobEntity job) {
        if (job != null && "cancelled".equals(job.getStatus())) {
            throw jobCancelled();
        }
    }

    private AiWorkerProtocolException jobCancelled() {
        return new AiWorkerProtocolException(
            HttpStatus.GONE,
            "JOB_CANCELLED",
            "Worker job was cancelled"
        );
    }

    private boolean activeImageTask(ImageTaskEntity task) {
        if (task == null || !Integer.valueOf(0).equals(task.getDeleted())) {
            return false;
        }
        return "queued".equalsIgnoreCase(task.getStatus()) || "running".equalsIgnoreCase(task.getStatus());
    }

    private void cancelIfBusinessTaskInactive(AiWorkerJobEntity job) {
        if (job == null) {
            throw new AiWorkerProtocolException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Worker job does not exist");
        }
        throwIfCancelled(job);
        if (!"image".equals(job.getCapability())
            || !Set.of("queued", "leased").contains(job.getStatus())) {
            return;
        }
        if (!activeImageTask(imageTasks.selectById(job.getBusinessTaskId()))) {
            jobs.cancelActive(
                job.getJobId(),
                "BUSINESS_TASK_CANCELLED",
                "Business image task is no longer active"
            );
            throw jobCancelled();
        }
    }

    private void cancelIfBusinessTaskInactiveIfPresent(AiWorkerJobEntity job) {
        if (job != null) {
            cancelIfBusinessTaskInactive(job);
        }
    }

    private void verifyWorkerOwnership(AiWorkerPrincipal principal, String workerId) {
        AiWorkerInstanceEntity instance = instances.selectById(workerId);
        if (instance == null || instance.getDeleted() == null || instance.getDeleted() != 0) {
            throw badRequest("WORKER_HEARTBEAT_REQUIRED", "Worker must send heartbeat before this request");
        }
        if (!principal.tokenId().equals(instance.getTokenId())) {
            throw new AiWorkerProtocolException(HttpStatus.FORBIDDEN, "WORKER_TOKEN_MISMATCH", "Worker belongs to another token");
        }
    }

    private List<Map<String, Object>> inputMaps(List<InputSource> inputFiles) {
        if (inputFiles == null || inputFiles.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InputSource input : inputFiles) {
            if (input == null || input.url() == null || input.url().isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source_url", absoluteHttpsUrl(input.url()));
            row.put("filename", clean(input.filename(), 255));
            row.put("content_type", clean(input.contentType(), 120));
            row.put("file_size", Math.max(0L, input.fileSize()));
            rows.add(row);
        }
        return rows;
    }

    private String absoluteHttpsUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (url.startsWith("/")) {
            url = workerPublicBaseUrl() + url;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception exception) {
            throw new AiWorkerProviderException("INPUT_URL_INVALID", "Worker 输入图片地址无效");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null) {
            throw new AiWorkerProviderException("INPUT_HTTPS_REQUIRED", "Worker 输入图片必须使用 HTTPS 地址");
        }
        if (privateOrLocalHost(uri.getHost())) {
            throw new AiWorkerProviderException(
                "INPUT_URL_NOT_PUBLIC",
                "Worker 输入图片不能指向本机、内网或云元数据地址"
            );
        }
        return uri.toString();
    }

    private boolean privateOrLocalHost(String host) {
        String value = host == null ? "" : host.trim().toLowerCase();
        if (value.isBlank()
            || "localhost".equals(value)
            || value.endsWith(".localhost")
            || value.endsWith(".local")
            || value.endsWith(".internal")
            || value.endsWith(".lan")
            || value.endsWith(".home")
            || "metadata".equals(value)
            || "instance-data".equals(value)
            || "::1".equals(value)) {
            return true;
        }
        if (value.contains(":")
            && (value.startsWith("fc")
                || value.startsWith("fd")
                || value.startsWith("fe8")
                || value.startsWith("fe9")
                || value.startsWith("fea")
                || value.startsWith("feb"))) {
            return true;
        }
        if (!value.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return false;
        }
        String[] parts = value.split("\\.");
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        return first == 0
            || first == 10
            || first == 127
            || (first == 100 && second >= 64 && second <= 127)
            || (first == 169 && second == 254)
            || (first == 172 && second >= 16 && second <= 31)
            || (first == 192 && second == 168)
            || (first == 198 && (second == 18 || second == 19))
            || first >= 224;
    }

    private String workerPublicBaseUrl() {
        String base = properties.publicBaseUrlValue();
        try {
            URI uri = URI.create(base);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
                return base;
            }
        } catch (Exception ignored) {
            // Converted to a provider-safe configuration error below.
        }
        throw new AiWorkerProviderException(
            "WORKER_PUBLIC_HTTPS_REQUIRED",
            "AI Worker 公网地址必须配置为 HTTPS"
        );
    }

    private Map<String, Object> artifactView(AiWorkerArtifactEntity artifact, boolean idempotent) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("artifactId", artifact.getArtifactId());
        view.put("idempotent", idempotent);
        view.put("contentType", artifact.getContentType());
        view.put("fileSize", artifact.getFileSize());
        view.put("sha256", artifact.getSha256());
        view.put("sourceSha256", artifact.getSourceSha256());
        view.put("width", artifact.getWidth());
        view.put("height", artifact.getHeight());
        view.put("format", artifact.getFormat());
        return view;
    }

    private String inputSignature(String jobId, int inputIndex, String workerId, long expires, String sourceUrl) {
        return hmac(jobId + "\n" + inputIndex + "\n" + workerId + "\n" + expires + "\n" + sourceUrl);
    }

    private String hmac(String value) {
        String secret = requiredSigningSecret();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("AI Worker signature failed", exception);
        }
    }

    private String hashLeaseToken(String value) {
        return sha256((requiredSigningSecret() + "\n" + (value == null ? "" : value)).getBytes(StandardCharsets.UTF_8));
    }

    private String requiredSigningSecret() {
        String secret = properties.signingSecretValue();
        if (secret.length() < 32) {
            throw new IllegalStateException("AI Worker signing secret must contain at least 32 characters");
        }
        return secret;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean constantEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private String randomToken(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String cleanWorkerId(String value) {
        String workerId = clean(value, 120);
        if (workerId.isBlank() || !workerId.matches("[A-Za-z0-9._:-]{3,120}")) {
            throw badRequest("WORKER_ID_INVALID", "workerId must be 3-120 safe characters");
        }
        return workerId;
    }

    private List<String> capabilities(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String capability = value == null ? "" : value.trim().toLowerCase();
                if (CAPABILITIES.contains(capability)) {
                    normalized.add(capability);
                }
            }
        }
        if (normalized.isEmpty()) {
            throw badRequest("CAPABILITIES_REQUIRED", "At least one supported capability is required");
        }
        return List.copyOf(normalized);
    }

    private String capability(String value) {
        String capability = value == null ? "" : value.trim().toLowerCase();
        if (!CAPABILITIES.contains(capability)) {
            throw new AiWorkerProviderException("CAPABILITY_INVALID", "Worker capability is invalid");
        }
        return capability;
    }

    private String protocol(String value) {
        String protocol = value == null ? "" : value.trim();
        if (!PROTOCOLS.contains(protocol)) {
            throw new AiWorkerProviderException("PROTOCOL_INVALID", "Worker protocol is invalid");
        }
        return protocol;
    }

    private String model(String capability, String value) {
        String cleanModel = clean(value, 160);
        Set<String> allowed = "image".equals(capability) ? IMAGE_MODELS : TEXT_MODELS;
        if (!allowed.contains(cleanModel)) {
            throw new AiWorkerProviderException(
                "MODEL_NOT_ALLOWED",
                "Worker model is not allowed for capability " + capability
            );
        }
        return cleanModel;
    }

    private Map<String, Object> requestPayload(
        String capability,
        String protocol,
        String model,
        Map<String, Object> request
    ) {
        if ("image".equals(capability) && !IMAGE_PROTOCOLS.contains(protocol)) {
            throw new AiWorkerProviderException(
                "PROTOCOL_CAPABILITY_MISMATCH",
                "Image jobs require an image protocol"
            );
        }
        if ("text".equals(capability) && !TEXT_PROTOCOLS.contains(protocol)) {
            throw new AiWorkerProviderException(
                "PROTOCOL_CAPABILITY_MISMATCH",
                "Text jobs require a text protocol"
            );
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (request != null) {
            payload.putAll(request);
        }
        Set<String> allowedFields = REQUEST_FIELDS.getOrDefault(protocol, Set.of());
        for (String key : payload.keySet()) {
            if (!allowedFields.contains(key)) {
                throw new AiWorkerProviderException(
                    "PAYLOAD_FIELD_NOT_ALLOWED",
                    "Worker request field is not allowed: " + clean(key, 80)
                );
            }
        }
        rejectForbiddenRequestFields(payload, 0);
        rejectEmbeddedRequestBinary(payload);

        String payloadModel = clean(payload.get("model"), 160);
        if (!payloadModel.isBlank() && !model.equals(payloadModel)) {
            throw new AiWorkerProviderException(
                "PAYLOAD_MODEL_MISMATCH",
                "Worker request model must match the configured Provider model"
            );
        }
        payload.put("model", model);

        Object stream = payload.get("stream");
        if (stream != null && !Boolean.FALSE.equals(stream)) {
            throw new AiWorkerProviderException(
                "STREAM_NOT_ALLOWED",
                "Local Worker Protocol v1 does not allow streaming responses"
            );
        }

        if (TEXT_PROTOCOLS.contains(protocol)) {
            payload.put("stream", false);
            if ("openai_responses".equals(protocol) && emptyPayloadValue(payload.get("input"))) {
                throw new AiWorkerProviderException("PAYLOAD_INVALID", "Responses jobs require input");
            }
            if ("openai_chat_completions".equals(protocol)
                && (!(payload.get("messages") instanceof List<?> messages) || messages.isEmpty())) {
                throw new AiWorkerProviderException("PAYLOAD_INVALID", "Chat completion jobs require messages");
            }
        } else {
            String prompt = payload.get("prompt") instanceof String value ? value.trim() : "";
            if (prompt.isBlank() || prompt.length() > 32_000) {
                throw new AiWorkerProviderException(
                    "PAYLOAD_INVALID",
                    "Image jobs require a prompt of at most 32000 characters"
                );
            }
            Object n = payload.get("n");
            if (n != null && (!(n instanceof Number number) || number.intValue() != 1)) {
                throw new AiWorkerProviderException(
                    "PAYLOAD_INVALID",
                    "Image jobs must request exactly one output"
                );
            }
            payload.put("n", 1);
        }
        return payload;
    }

    private boolean emptyPayloadValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence sequence) {
            return sequence.toString().isBlank();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        return false;
    }

    private void rejectForbiddenRequestFields(Object value, int depth) {
        if (value == null || depth > 16) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
                String normalized = key.trim().toLowerCase()
                    .replace('-', '_')
                    .replace(' ', '_');
                String compact = normalized.replace("_", "");
                if (FORBIDDEN_REQUEST_KEYS.contains(normalized)
                    || compact.equals("authorization")
                    || compact.equals("apikey")
                    || compact.equals("accesstoken")
                    || compact.equals("refreshtoken")
                    || compact.equals("idtoken")
                    || compact.equals("clientsecret")
                    || compact.equals("cliproxyapikey")
                    || compact.equals("openaioauth")
                    || compact.equals("baseurl")
                    || compact.equals("proxyurl")) {
                    throw new AiWorkerProviderException(
                        "PAYLOAD_SECRET_NOT_ALLOWED",
                        "Worker jobs cannot contain credentials or local connection configuration"
                    );
                }
                rejectForbiddenRequestFields(entry.getValue(), depth + 1);
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                rejectForbiddenRequestFields(item, depth + 1);
            }
        }
    }

    private void rejectEmbeddedRequestBinary(Object value) {
        if (containsEmbeddedBinary(value, 0)) {
            throw new AiWorkerProviderException(
                "BASE64_REQUEST_NOT_ALLOWED",
                "Worker input images must use signed HTTPS URLs"
            );
        }
    }

    private void rejectResultSecrets(Object value) {
        try {
            rejectForbiddenRequestFields(value, 0);
        } catch (AiWorkerProviderException exception) {
            throw badRequest(
                "RESULT_SECRET_NOT_ALLOWED",
                "Worker results cannot contain credentials or local connection configuration"
            );
        }
    }

    private String cleanIdempotencyKey(String value) {
        String key = clean(value, 128);
        if (!key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw badRequest(
                "IDEMPOTENCY_KEY_INVALID",
                "idempotencyKey must be 8-128 safe characters"
            );
        }
        return key;
    }

    private String cleanSha256(String value) {
        String hash = clean(value, 64).toLowerCase();
        if (!hash.matches("[a-f0-9]{64}")) {
            throw badRequest(
                "ARTIFACT_SHA256_INVALID",
                "sha256 must be a 64-character hexadecimal SHA-256 digest"
            );
        }
        return hash;
    }

    private String requestHash(Object value) {
        return sha256(writeJson(canonicalize(value)).getBytes(StandardCharsets.UTF_8));
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> items = new ArrayList<>();
            for (Object item : iterable) {
                items.add(canonicalize(item));
            }
            return items;
        }
        return value;
    }

    private String cleanErrorCode(String value) {
        String code = clean(value, 80).toUpperCase().replaceAll("[^A-Z0-9_:-]", "_");
        return code.isBlank() ? "WORKER_FAILED" : code;
    }

    private void rejectEmbeddedBinary(Object value) {
        if (containsEmbeddedBinary(value, 0)) {
            throw badRequest(
                "BASE64_RESULT_NOT_ALLOWED",
                "Upload binary output through the artifacts endpoint"
            );
        }
    }

    private boolean containsEmbeddedBinary(Object value, int depth) {
        if (value == null || depth > 12) {
            return false;
        }
        if (value instanceof CharSequence sequence) {
            String text = sequence.toString().trim();
            if (text.toLowerCase().startsWith("data:") && text.toLowerCase().contains(";base64,")) {
                return true;
            }
            return text.length() > 8_192
                && text.indexOf(' ') < 0
                && text.matches("[A-Za-z0-9+/=_-]+");
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (containsEmbeddedBinary(entry.getValue(), depth + 1)) {
                    return true;
                }
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (containsEmbeddedBinary(item, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String sanitizeError(String value) {
        String cleaned = clean(value, 500)
            .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._-]+", "Bearer ***")
            .replaceAll("(?i)\\bsk-[A-Za-z0-9_-]{8,}", "sk-***")
            .replaceAll("(?i)\\bppw_[A-Za-z0-9_-]{8,}", "ppw_***")
            .replaceAll(
                "(?i)([\"']?(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|client[_ -]?secret)[\"']?\\s*[:=]\\s*[\"']?)[^\\s,\"'}]+",
                "$1***"
            );
        return cleaned;
    }

    private String cleanUrl(Object value) {
        String url = clean(value, 2000);
        if (url.isBlank()) {
            throw badRequest("INPUT_NOT_FOUND", "Input source is unavailable");
        }
        return url;
    }

    private String clean(Object value, int maxLength) {
        String text = value == null ? "" : String.valueOf(value).replaceAll("\\s+", " ").trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Worker payload is not valid JSON");
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return value == null || value.isBlank() ? new LinkedHashMap<>() : json.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            return new LinkedHashMap<>();
        }
    }

    private List<Map<String, Object>> readList(String value) {
        try {
            return value == null || value.isBlank() ? new ArrayList<>() : json.readValue(value, LIST_MAP_TYPE);
        } catch (Exception exception) {
            return new ArrayList<>();
        }
    }

    private List<String> readStringList(String value) {
        try {
            return value == null || value.isBlank()
                ? List.of()
                : json.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception exception) {
            return List.of();
        }
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception exception) {
            return fallback;
        }
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiWorkerProviderException("WORKER_WAIT_INTERRUPTED", "等待本机 Worker 时被中断");
        }
    }

    private AiWorkerProtocolException badRequest(String code, String message) {
        return new AiWorkerProtocolException(HttpStatus.BAD_REQUEST, code, message);
    }

    private AiWorkerProtocolException conflict(String code, String message) {
        return new AiWorkerProtocolException(HttpStatus.CONFLICT, code, message);
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public record InputSource(String url, String filename, String contentType, long fileSize) {
    }

    public record ImageResult(byte[] bytes, String contentType, String returnedSize, String jobId) {
    }

    public record ClaimedJob(
        @JsonProperty("jobId") String jobId,
        @JsonProperty("leaseId") String leaseId,
        @JsonProperty("leaseUntil") OffsetDateTime leaseUntil,
        @JsonProperty("renewAfter") OffsetDateTime renewAfter,
        @JsonProperty("graceUntil") OffsetDateTime graceUntil,
        String capability,
        String protocol,
        String model,
        Map<String, Object> request,
        @JsonProperty("inputFiles") List<Map<String, Object>> inputFiles,
        int attempt
    ) {
    }
}
