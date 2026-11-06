package com.openimage.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.worker.AiWorkerDeferredException;
import com.openimage.ai.worker.AiWorkerJobService;
import com.openimage.domain.entity.ImageTaskEntity;
import com.openimage.domain.entity.ProductImageEntity;
import com.openimage.domain.entity.ProductProjectEntity;
import com.openimage.risk.RiskControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
@EnableConfigurationProperties(AiImageProperties.class)
public class AiImageClient {
    private static final Logger log = LoggerFactory.getLogger(AiImageClient.class);
    private static final long HFSY_REFERENCE_IMAGE_BYTES = 5L * 1024L * 1024L;
    private static final long IMAGE_PROVIDER_AUTH_CIRCUIT_MS = Duration.ofMinutes(30).toMillis();
    private static final long IMAGE_PROVIDER_TRANSIENT_CIRCUIT_MS = Duration.ofMinutes(10).toMillis();
    private static final Map<String, String> AISHUCH_SIZE_RATIOS = Map.ofEntries(
        Map.entry("1024x1024", "1:1"),
        Map.entry("2048x2048", "1:1"),
        Map.entry("2880x2880", "1:1"),
        Map.entry("4096x4096", "1:1"),
        Map.entry("1040x832", "5:4"),
        Map.entry("2080x1664", "5:4"),
        Map.entry("3200x2560", "5:4"),
        Map.entry("720x1280", "9:16"),
        Map.entry("1152x2048", "9:16"),
        Map.entry("2160x3840", "9:16"),
        Map.entry("1280x720", "16:9"),
        Map.entry("2048x1152", "16:9"),
        Map.entry("3840x2160", "16:9"),
        Map.entry("4096x2304", "16:9"),
        Map.entry("1024x768", "4:3"),
        Map.entry("2048x1536", "4:3"),
        Map.entry("3264x2448", "4:3"),
        Map.entry("4096x3072", "4:3"),
        Map.entry("1008x672", "3:2"),
        Map.entry("2016x1344", "3:2"),
        Map.entry("3504x2336", "3:2"),
        Map.entry("832x1040", "4:5"),
        Map.entry("1664x2080", "4:5"),
        Map.entry("2560x3200", "4:5"),
        Map.entry("768x1024", "3:4"),
        Map.entry("1536x2048", "3:4"),
        Map.entry("2448x3264", "3:4"),
        Map.entry("3072x4096", "3:4"),
        Map.entry("672x1008", "2:3"),
        Map.entry("1344x2016", "2:3"),
        Map.entry("2336x3504", "2:3"),
        Map.entry("1344x576", "21:9"),
        Map.entry("2016x864", "21:9"),
        Map.entry("3696x1584", "21:9")
    );
    private static final Map<String, String> AISHUCH_PIXEL_SIZES = Map.ofEntries(
        Map.entry("1024x1024", "1024x1024"),
        Map.entry("2048x2048", "2048x2048"),
        Map.entry("2880x2880", "2880x2880"),
        Map.entry("4096x4096", "2880x2880"),
        Map.entry("1040x832", "1280x1024"),
        Map.entry("2080x1664", "2560x2048"),
        Map.entry("3200x2560", "3216x2576"),
        Map.entry("720x1280", "864x1536"),
        Map.entry("1152x2048", "1152x2048"),
        Map.entry("2160x3840", "2160x3840"),
        Map.entry("1280x720", "1536x864"),
        Map.entry("2048x1152", "2048x1152"),
        Map.entry("3840x2160", "3840x2160"),
        Map.entry("4096x2304", "3840x2160"),
        Map.entry("1024x768", "1024x768"),
        Map.entry("2048x1536", "2048x1536"),
        Map.entry("3264x2448", "3312x2480"),
        Map.entry("4096x3072", "3312x2480"),
        Map.entry("1008x672", "1536x1024"),
        Map.entry("2016x1344", "2048x1360"),
        Map.entry("3504x2336", "3520x2336"),
        Map.entry("832x1040", "1024x1280"),
        Map.entry("1664x2080", "2048x2560"),
        Map.entry("2560x3200", "2576x3216"),
        Map.entry("768x1024", "768x1024"),
        Map.entry("1536x2048", "1536x2048"),
        Map.entry("2448x3264", "2480x3312"),
        Map.entry("3072x4096", "2480x3312"),
        Map.entry("672x1008", "1024x1536"),
        Map.entry("1344x2016", "1360x2048"),
        Map.entry("2336x3504", "2336x3520"),
        Map.entry("1344x576", "2016x864"),
        Map.entry("2016x864", "2688x1152"),
        Map.entry("3696x1584", "3840x1648")
    );

    private final AiImageProperties properties;
    private final AiRuntimeConfigService runtimeConfig;
    private final ObjectMapper json;
    private final WebClient webClient;
    private final RiskControlService riskControl;
    private final AiWorkerJobService workerJobs;
    private final Semaphore yunfeiOneKSlots = new Semaphore(intEnv("OPENIMAGE_YUNFEI_1K_CONCURRENCY", 10));
    private final Semaphore yunfeiHiresSlots = new Semaphore(intEnv("OPENIMAGE_YUNFEI_HIRES_CONCURRENCY", 10));
    private final Semaphore ruaChatSlots = new Semaphore(intEnv("OPENIMAGE_RUA_CHAT_CONCURRENCY", 2));
    private final Semaphore xinZhiZhuSlots = new Semaphore(intEnv("OPENIMAGE_XIN_ZHI_ZHU_CONCURRENCY", 10));
    private final Map<String, Long> imageProviderCircuitOpenUntil = new ConcurrentHashMap<>();

    public AiImageClient(AiImageProperties properties, AiRuntimeConfigService runtimeConfig, ObjectMapper json, WebClient.Builder builder) {
        this(properties, runtimeConfig, json, builder, null, null);
    }

    public AiImageClient(AiImageProperties properties, AiRuntimeConfigService runtimeConfig, ObjectMapper json, WebClient.Builder builder, RiskControlService riskControl) {
        this(properties, runtimeConfig, json, builder, riskControl, null);
    }

    @Autowired
    public AiImageClient(
        AiImageProperties properties,
        AiRuntimeConfigService runtimeConfig,
        ObjectMapper json,
        WebClient.Builder builder,
        RiskControlService riskControl,
        AiWorkerJobService workerJobs
    ) {
        this.properties = properties;
        this.runtimeConfig = runtimeConfig;
        this.json = json;
        this.webClient = builder
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
            .build();
        this.riskControl = riskControl;
        this.workerJobs = workerJobs;
    }

    public boolean enabled() {
        return runtimeConfig.effectiveImage().enabledAndConfigured();
    }

    public GeneratedImage generate(ProductProjectEntity project, ImageTaskEntity task, List<ProductImageEntity> referenceImages) throws Exception {
        String taskSize = AiImageSizes.normalizeGenerationSize(snapshotText(task, "generationSize"), properties.sizeValue());
        List<AiRuntimeConfigService.EffectiveImageConfig> attempts = runtimeConfig.imageProviderChainForTask(snapshotText(task, "aiProviderId"), task.getProvider(), task.getModel(), taskSize);
        if (attempts.isEmpty()) {
            throw new IllegalStateException("AI 作图服务已被管理员禁用或未配置密钥，请联系管理员开启后再使用");
        }
        if (riskControl != null) {
            riskControl.checkBeforeProvider(task.getUserId(), task.getProjectId(), task.getId(), "image_generation:" + blankToDefault(task.getImageType(), "unknown"), imageRiskText(project, task), "image_prompt");
        }
        Exception lastFailure = null;
        List<String> providerFailureMessages = new ArrayList<>();
        List<ProviderAttempt> attemptLogs = new ArrayList<>();
        for (int index = 0; index < attempts.size(); index++) {
            AiRuntimeConfigService.EffectiveImageConfig attempt = attempts.get(index);
            String model = model(task, attempt);
            String requestSize = providerRequestSize(task, attempt);
            int rawReferenceCount = shouldIgnoreReferences(task) ? 0 : nonEmptyReferenceCount(referenceImages);
            int requestReferenceCount = referenceLimit(attempt, model);
            String notes = rawReferenceCount > requestReferenceCount
                ? "reference_images_truncated:" + rawReferenceCount + "->" + requestReferenceCount
                : "";
            String promptAdapter = providerPromptAdapter(attempt);
            if (isImageProviderCircuitOpen(attempt)) {
                String circuitMessage = "上游近期请求失败，短期熔断中";
                attemptLogs.add(new ProviderAttempt(
                    attempt.provider(),
                    model,
                    requestSize,
                    "",
                    0,
                    "skipped",
                    "circuit_open",
                    circuitMessage,
                    false,
                    notes,
                    snapshotText(task, "intent"),
                    snapshotText(task, "text_mode"),
                    snapshotText(task, "reference_policy"),
                    rawReferenceCount,
                    promptAdapter,
                    ""
                ));
                providerFailureMessages.add(attempt.provider() + ": " + circuitMessage);
                log.warn("Image task {} skipped provider={} model={} because its circuit is open",
                    task.getId(), attempt.provider(), model);
                continue;
            }
            long started = System.nanoTime();
            Semaphore providerSlot = providerSlot(attempt);
            boolean slotAcquired = false;
            try {
                slotAcquired = acquireProviderSlot(providerSlot);
                GeneratedImage generated = generateWithConfig(project, task, referenceImages, attempt);
                String warning = sizeWarning(requestSize, generated.returnedSize());
                CostDetails costDetails = generated.costDetails();
                attemptLogs.add(new ProviderAttempt(
                    attempt.provider(),
                    model,
                    requestSize,
                    generated.returnedSize(),
                    elapsedMillis(started),
                    "success",
                    "",
                    "",
                    true,
                    notes,
                    snapshotText(task, "intent"),
                    snapshotText(task, "text_mode"),
                    snapshotText(task, "reference_policy"),
                    rawReferenceCount,
                    promptAdapter,
                    warning,
                    costDetails.actualCostCny(),
                    costDetails.upstreamCost(),
                    costDetails.upstreamCostUnit(),
                    costDetails.conversionRate(),
                    costDetails.source()
                ));
                closeImageProviderCircuit(attempt);
                return new GeneratedImage(generated.bytes(), generated.provider(), generated.model(), generated.actualCost(), generated.returnedSize(), generated.costDetails(), attemptLogs);
            } catch (AiWorkerDeferredException exception) {
                attemptLogs.add(new ProviderAttempt(
                    attempt.provider(),
                    model,
                    requestSize,
                    "",
                    elapsedMillis(started),
                    "queued",
                    "worker_deferred",
                    "",
                    false,
                    appendNote(notes, "worker_job:" + exception.jobId()),
                    snapshotText(task, "intent"),
                    snapshotText(task, "text_mode"),
                    snapshotText(task, "reference_policy"),
                    rawReferenceCount,
                    promptAdapter,
                    ""
                ));
                throw new AiImageDeferredException(exception.jobId(), exception.getMessage(), attemptLogs);
            } catch (Exception exception) {
                lastFailure = exception;
                openImageProviderCircuitIfNeeded(attempt, exception);
                providerFailureMessages.add(blankToDefault(exception.getMessage(), exception.getClass().getSimpleName()));
                attemptLogs.add(new ProviderAttempt(
                    attempt.provider(),
                    model,
                    requestSize,
                    "",
                    elapsedMillis(started),
                    "failed",
                    errorType(exception),
                    summarize(exception.getMessage()),
                    false,
                    notes,
                    snapshotText(task, "intent"),
                    snapshotText(task, "text_mode"),
                    snapshotText(task, "reference_policy"),
                    rawReferenceCount,
                    promptAdapter,
                    ""
                ));
                if (index + 1 < attempts.size()) {
                    log.warn("Image task {} failed on provider={} model={}, trying fallback provider={}: {}",
                        task.getId(), attempt.provider(), model, attempts.get(index + 1).provider(), summarize(exception.getMessage()));
                } else {
                    log.warn("Image task {} failed on provider={} model={}, no more fallbacks: {}",
                        task.getId(), attempt.provider(), model, summarize(exception.getMessage()));
                }
            } finally {
                if (slotAcquired) {
                    providerSlot.release();
                }
            }
        }
        if (riskControl != null && !providerFailureMessages.isEmpty()) {
            riskControl.recordProviderError(
                task.getUserId(),
                task.getProjectId(),
                task.getId(),
                "image_generation:" + blankToDefault(task.getImageType(), "unknown"),
                String.join("\n", providerFailureMessages)
            );
        }
        if (lastFailure != null) {
            throw new AiImageGenerationException("生成服务繁忙，请稍后重试", lastFailure, attemptLogs);
        }
        throw new AiImageGenerationException("生成服务繁忙，请稍后重试", new AiImageException("上游生图无响应"), attemptLogs);
    }

    private GeneratedImage generateWithConfig(ProductProjectEntity project, ImageTaskEntity task, List<ProductImageEntity> referenceImages, AiRuntimeConfigService.EffectiveImageConfig config) throws Exception {
        String model = model(task, config);
        List<String> refs = referenceImages(task, referenceImages, config, model);
        String size = providerRequestSize(task, config);
        if (config.pullWorker()) {
            return generateWithWorker(project, task, refs, config, model);
        }
        boolean gemini = isGemini(model, config);
        if (AiImageResponseAdapter.isXinZhiZhu(config.provider(), config.baseUrl())) {
            if (!refs.isEmpty()) {
                return generateXinZhiZhuEdit(project, task, refs, config, model, size);
            }
            Map<String, Object> request = xinZhiZhuImageGenerationRequest(project, task, refs, config);
            URI endpoint = endpoint(config.baseUrl(), "/v1/images/generations");
            log.info("Submitting image task {} to provider={} model={} size={} endpoint={}", task.getId(), config.provider(), model, size, endpoint);
            JsonNode response = webClient.post()
                .uri(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), clientResponse -> clientResponse.bodyToMono(String.class)
                    .map(body -> new AiImageHttpException(clientResponse.statusCode().value(), "上游生图失败：" + clientResponse.statusCode().value() + " " + summarize(body))))
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(Math.max(config.timeoutSeconds(), 180)));
            if (response == null) {
                throw new AiImageException("上游生图无响应");
            }
            response = resolveImageResponse(response, config);
            throwIfError(response);
            byte[] bytes = extractImageBytes(response, config);
            if (bytes.length == 0) {
                throw new AiImageException("上游生图未返回可下载图片");
            }
            AiImageSizes.Size returned = readImageSize(bytes);
            if (returned == null) {
                throw new AiImageException("上游返回图片解码失败");
            }
            return generatedImage(bytes, config, model, returned.value(), response);
        }
        if (isYunfei(config) && !refs.isEmpty()) {
            return generateYunfeiEdit(project, task, refs, config, model, size);
        }
        if (AiImageResponseAdapter.isRuaChat(config.provider(), config.baseUrl()) && !refs.isEmpty()) {
            return generateRuaChatEdit(project, task, refs, config, model, size);
        }
        if (AiImageResponseAdapter.isRuaChat(config.provider(), config.baseUrl()) && useCurlTransport(config)) {
            URI ruaEndpoint = endpoint(config.baseUrl(), "/v1/images/generations");
            Map<String, Object> request = ruaChatImageGenerationRequest(project, task, refs, config);
            return submitCurlJsonImageRequest(ruaEndpoint, request, config, model, "上游生图失败");
        }
        Map<String, Object> request = gemini
            ? geminiRequest(project, task, refs, config)
            : imageGenerationRequest(project, task, refs, config);
        URI endpoint = gemini ? geminiEndpoint(config.baseUrl(), model) : endpoint(config.baseUrl(), "/v1/images/generations");
        log.info("Submitting image task {} to provider={} model={} size={} endpoint={}", task.getId(), config.provider(), model, size, endpoint);

        JsonNode response = webClient.post()
            .uri(endpoint)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .onStatus(status -> !status.is2xxSuccessful(), clientResponse -> clientResponse.bodyToMono(String.class)
                .map(body -> new AiImageHttpException(clientResponse.statusCode().value(), "上游生图失败：" + clientResponse.statusCode().value() + " " + summarize(body))))
            .bodyToMono(JsonNode.class)
            .block(Duration.ofSeconds(config.timeoutSeconds()));

        if (response == null) {
            throw new AiImageException("上游生图无响应");
        }
        response = resolveImageResponse(response, config);
        throwIfError(response);
        byte[] bytes = extractImageBytes(response, config);
        if (bytes.length == 0) {
            throw new AiImageException("上游生图未返回可下载图片");
        }
        AiImageSizes.Size returned = readImageSize(bytes);
        if (returned == null) {
            throw new AiImageException("上游返回图片解码失败");
        }
        return generatedImage(bytes, config, model, returned.value(), response);
    }

    private GeneratedImage generateWithWorker(
        ProductProjectEntity project,
        ImageTaskEntity task,
        List<String> refs,
        AiRuntimeConfigService.EffectiveImageConfig config,
        String model
    ) {
        if (workerJobs == null) {
            throw new com.openimage.ai.worker.AiWorkerProviderException(
                "LOCAL_WORKER_UNAVAILABLE",
                "本机 Worker 服务未加载"
            );
        }
        Map<String, Object> request = new LinkedHashMap<>(
            imageGenerationRequest(project, task, refs, config)
        );
        request.remove("reference_images");
        request.remove("image_urls");
        String protocol = refs.isEmpty()
            ? "openai_images_generations"
            : "openai_images_edits";
        List<AiWorkerJobService.InputSource> inputs = new ArrayList<>();
        for (int index = 0; index < refs.size(); index++) {
            inputs.add(new AiWorkerJobService.InputSource(
                refs.get(index),
                "reference-" + (index + 1) + ".img",
                "application/octet-stream",
                0
            ));
        }
        AiWorkerJobService.ImageResult result = workerJobs.executeImage(
            config,
            task.getId(),
            protocol,
            request,
            inputs
        );
        AiImageSizes.Size returned = readImageSize(result.bytes());
        if (returned == null) {
            throw new AiImageException("本机 Worker 返回图片解码失败");
        }
        BigDecimal actualCost = config.actualCost() == null
            ? AiRuntimeConfigService.LOCAL_IMAGE_COST_CNY
            : config.actualCost();
        return new GeneratedImage(
            result.bytes(),
            config.provider(),
            model,
            actualCost,
            returned.value(),
            new CostDetails(actualCost, actualCost, "CNY", BigDecimal.ONE, "local_worker_fixed"),
            List.of()
        );
    }

    private String imageRiskText(ProductProjectEntity project, ImageTaskEntity task) {
        StringBuilder out = new StringBuilder();
        if (project != null) {
            out.append(project.getProductName()).append('\n')
                .append(project.getBrand()).append('\n')
                .append(project.getOriginalInfo()).append('\n')
                .append(project.getGeneratedCopy()).append('\n')
                .append(project.getConfirmedFacts()).append('\n');
        }
        if (task != null) {
            out.append(task.getPromptSnapshot());
        }
        return out.toString();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private GeneratedImage generateYunfeiEdit(ProductProjectEntity project, ImageTaskEntity task, List<String> refs, AiRuntimeConfigService.EffectiveImageConfig config, String model, String size) {
        URI endpoint = endpoint(config.baseUrl(), "/v1/images/edits");
        String requestSize = requestSize(task, config);
        log.info("Submitting image edit task {} to provider={} model={} size={} endpoint={} refs={}", task.getId(), config.provider(), model, size, endpoint, refs.size());
        MultipartPayload body = yunfeiEditMultipartBody(project, task, refs, config, requestSize);
        return submitMultipartImageRequest(endpoint, body, config, model, "上游图生图失败");
    }

    private MultipartPayload yunfeiEditMultipartBody(ProductProjectEntity project, ImageTaskEntity task, List<String> refs, AiRuntimeConfigService.EffectiveImageConfig config, String requestSize) {
        String boundary = "----PicPilotYunfei" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        multipartText(body, boundary, "model", "gpt-image-2");
        multipartText(body, boundary, "prompt", prompt(project, task, true, config));
        multipartText(body, boundary, "size", requestSize);
        multipartText(body, boundary, "response_format", "b64_json");
        byte[] bytes = downloadReferenceImage(refs.get(0), config);
        MediaType contentType = imageContentType(bytes);
        multipartFile(body, boundary, "image", "reference" + imageExtension(contentType), contentType.toString(), bytes);
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new MultipartPayload(body.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private GeneratedImage generateXinZhiZhuEdit(ProductProjectEntity project, ImageTaskEntity task, List<String> refs, AiRuntimeConfigService.EffectiveImageConfig config, String model, String size) {
        URI endpoint = endpoint(config.baseUrl(), "/v1/images/edits");
        String requestSize = requestSize(task, config);
        log.info("Submitting image edit task {} to provider={} model={} size={} endpoint={} refs={}", task.getId(), config.provider(), model, size, endpoint, refs.size());
        MultipartPayload body = xinZhiZhuEditMultipartBody(project, task, refs, config, requestSize);
        return submitMultipartImageRequest(endpoint, body, config, model, "上游图生图失败");
    }

    private MultipartPayload xinZhiZhuEditMultipartBody(ProductProjectEntity project, ImageTaskEntity task, List<String> refs, AiRuntimeConfigService.EffectiveImageConfig config, String requestSize) {
        String boundary = "----PicPilotXinZhiZhu" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        multipartText(body, boundary, "model", "gpt-image-2");
        multipartText(body, boundary, "prompt", prompt(project, task, true, config));
        multipartText(body, boundary, "size", requestSize);
        multipartText(body, boundary, "quality", "auto");
        multipartText(body, boundary, "output_format", "png");
        multipartText(body, boundary, "response_format", "b64_json");
        multipartText(body, boundary, "n", "1");
        int index = 0;
        for (String ref : refs) {
            index += 1;
            byte[] bytes = downloadReferenceImage(ref, config);
            MediaType contentType = imageContentType(bytes);
            multipartFile(body, boundary, "image", "reference-" + index + imageExtension(contentType), contentType.toString(), bytes);
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new MultipartPayload(body.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private GeneratedImage generateRuaChatEdit(ProductProjectEntity project, ImageTaskEntity task, List<String> refs, AiRuntimeConfigService.EffectiveImageConfig config, String model, String size) {
        URI endpoint = endpoint(config.baseUrl(), "/v1/images/edits");
        String requestSize = requestSize(task, config);
        log.info("Submitting image edit task {} to provider={} model={} size={} endpoint={} refs={}", task.getId(), config.provider(), model, size, endpoint, refs.size());
        try {
            if (useCurlTransport(config)) {
                return submitRuaChatEditWithCurl(endpoint, project, task, refs, config, model, requestSize);
            }
            MultipartPayload body = ruaChatEditMultipartBody(project, task, refs, config, requestSize);
            return submitMultipartImageRequest(endpoint, body, config, model, "上游图生图失败");
        } catch (AiImageHttpException exception) {
            if (exception.statusCode() == 400 || exception.statusCode() == 404 || exception.statusCode() == 405) {
                log.warn("Image edit endpoint failed for task {} on rua.chat, trying reference_images generation fallback: {}",
                    task.getId(), summarize(exception.getMessage()));
                return generateRuaChatReferenceGeneration(project, task, refs, config, model);
            }
            throw exception;
        }
    }

    private MultipartPayload ruaChatEditMultipartBody(ProductProjectEntity project, ImageTaskEntity task, List<String> refs, AiRuntimeConfigService.EffectiveImageConfig config, String requestSize) {
        String boundary = "----PicPilotRua" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        multipartText(body, boundary, "model", "gpt-image-2");
        multipartText(body, boundary, "prompt", prompt(project, task, true, config));
        multipartText(body, boundary, "size", requestSize);
        multipartText(body, boundary, "output_format", "png");
        multipartText(body, boundary, "quality", "auto");
        multipartText(body, boundary, "moderation", "low");
        multipartText(body, boundary, "n", "1");
        int index = 0;
        for (String ref : refs.stream().limit(4).toList()) {
            index += 1;
            byte[] bytes = downloadReferenceImage(ref, config);
            MediaType contentType = imageContentType(bytes);
            multipartFile(body, boundary, "image", "reference-" + index + imageExtension(contentType), contentType.toString(), bytes);
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new MultipartPayload(body.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    private GeneratedImage generateRuaChatReferenceGeneration(ProductProjectEntity project, ImageTaskEntity task, List<String> refs, AiRuntimeConfigService.EffectiveImageConfig config, String model) {
        URI endpoint = endpoint(config.baseUrl(), "/v1/images/generations");
        Map<String, Object> request = ruaChatImageGenerationRequest(project, task, refs, config);
        if (useCurlTransport(config)) {
            return submitCurlJsonImageRequest(endpoint, request, config, model, "上游参考图生图失败");
        }
        return submitJsonImageRequest(endpoint, request, config, model, "上游参考图生图失败");
    }

    private GeneratedImage submitRuaChatEditWithCurl(URI endpoint, ProductProjectEntity project, ImageTaskEntity task, List<String> refs, AiRuntimeConfigService.EffectiveImageConfig config, String model, String requestSize) {
        List<Path> tempFiles = new ArrayList<>();
        try {
            List<String> args = new ArrayList<>();
            args.add("--http1.1");
            args.add("-sS");
            args.add("--max-time");
            args.add(String.valueOf(config.timeoutSeconds()));
            Path configFile = curlAuthConfig(config);
            tempFiles.add(configFile);
            args.add("--config");
            args.add(configFile.toString());
            args.add("-H");
            args.add("Accept: application/json");
            Path promptFile = Files.createTempFile("picpilot-rua-prompt-", ".txt");
            Files.writeString(promptFile, prompt(project, task, true, config), StandardCharsets.UTF_8);
            tempFiles.add(promptFile);
            args.add("-F");
            args.add("model=gpt-image-2");
            args.add("-F");
            args.add("prompt=<" + promptFile);
            args.add("-F");
            args.add("size=" + requestSize);
            args.add("-F");
            args.add("output_format=png");
            args.add("-F");
            args.add("quality=auto");
            args.add("-F");
            args.add("moderation=low");
            args.add("-F");
            args.add("n=1");
            int index = 0;
            for (String ref : refs.stream().limit(4).toList()) {
                index += 1;
                byte[] bytes = downloadReferenceImage(ref, config);
                MediaType type = imageContentType(bytes);
                Path file = Files.createTempFile("picpilot-rua-ref-" + index + "-", imageExtension(type));
                Files.write(file, bytes);
                tempFiles.add(file);
                args.add("-F");
                args.add("image=@" + file + ";type=" + type);
            }
            args.add(endpoint.toString());
            JsonNode response = runCurlJson(args, config.timeoutSeconds(), "上游图生图失败");
            return generatedFromResponse(response, config, model, "上游图生图失败");
        } catch (AiImageHttpException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiImageException("上游图生图失败：" + summarize(exception.getMessage()));
        } finally {
            deleteTempFiles(tempFiles);
        }
    }

    private GeneratedImage submitCurlJsonImageRequest(URI endpoint, Map<String, Object> request, AiRuntimeConfigService.EffectiveImageConfig config, String model, String errorPrefix) {
        List<Path> tempFiles = new ArrayList<>();
        try {
            Path configFile = curlAuthConfig(config);
            Path bodyFile = Files.createTempFile("picpilot-rua-json-", ".json");
            Files.writeString(bodyFile, json.writeValueAsString(request), StandardCharsets.UTF_8);
            tempFiles.add(configFile);
            tempFiles.add(bodyFile);
            JsonNode response = runCurlJson(List.of(
                "--http1.1",
                "-sS",
                "--max-time", String.valueOf(config.timeoutSeconds()),
                "--config", configFile.toString(),
                "-H", "Content-Type: application/json",
                "-H", "Accept: application/json",
                "--data-binary", "@" + bodyFile,
                endpoint.toString()
            ), config.timeoutSeconds(), errorPrefix);
            return generatedFromResponse(response, config, model, errorPrefix);
        } catch (AiImageHttpException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiImageException(errorPrefix + "：" + summarize(exception.getMessage()));
        } finally {
            deleteTempFiles(tempFiles);
        }
    }

    private JsonNode runCurlJson(List<String> args, int timeoutSeconds, String errorPrefix) throws Exception {
        Path script = Files.createTempFile("picpilot-rua-curl-", ".sh");
        Path output = Files.createTempFile("picpilot-rua-curl-output-", ".json");
        try {
            StringBuilder command = new StringBuilder("#!/bin/sh\nset -eu\ncurl -w '\\nHTTP_STATUS:%{http_code}\\n'");
            for (String arg : args) {
                command.append(' ').append(shellQuote(arg));
            }
            command.append('\n');
            Files.writeString(script, command.toString(), StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("sh", script.toString())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
            boolean finished = process.waitFor(Math.max(timeoutSeconds + 10, 15), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new AiImageException(errorPrefix.replace("失败", "超时"));
            }
            String body = Files.readString(output, StandardCharsets.UTF_8);
            CurlResponse parsed = parseCurlResponse(body);
            if (parsed.statusCode() < 200 || parsed.statusCode() >= 300) {
                throw new AiImageHttpException(parsed.statusCode(), errorPrefix + "：" + parsed.statusCode() + " " + summarize(parsed.body()));
            }
            if (process.exitValue() != 0 && parsed.statusCode() == 0) {
                throw new AiImageException(errorPrefix + "：" + summarize(parsed.body()));
            }
            return json.readTree(parsed.body());
        } finally {
            try {
                Files.deleteIfExists(script);
            } catch (Exception ignored) {
                // Temporary shell script contains no API key, but remove it best-effort.
            }
            try {
                Files.deleteIfExists(output);
            } catch (Exception ignored) {
                // Temporary response file is best-effort cleanup.
            }
        }
    }

    private String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private CurlResponse parseCurlResponse(String output) {
        String marker = "\nHTTP_STATUS:";
        int index = output.lastIndexOf(marker);
        if (index < 0) {
            return new CurlResponse(0, output);
        }
        String body = output.substring(0, index);
        String statusText = output.substring(index + marker.length()).trim();
        int end = statusText.indexOf('\n');
        if (end >= 0) {
            statusText = statusText.substring(0, end).trim();
        }
        try {
            return new CurlResponse(Integer.parseInt(statusText), body);
        } catch (NumberFormatException ignored) {
            return new CurlResponse(0, body);
        }
    }

    private Path curlAuthConfig(AiRuntimeConfigService.EffectiveImageConfig config) throws Exception {
        Path file = Files.createTempFile("picpilot-rua-curl-", ".conf");
        String apiKey = curlApiKey(config);
        Files.writeString(file, "header = \"Authorization: Bearer " + apiKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n", StandardCharsets.UTF_8);
        return file;
    }

    private String curlApiKey(AiRuntimeConfigService.EffectiveImageConfig config) {
        if (AiImageResponseAdapter.isRuaChat(config.provider(), config.baseUrl())) {
            String rua = System.getenv("RUA_CHAT_API_KEY");
            if (rua != null && !rua.trim().isBlank()) {
                return rua.trim();
            }
            String openimageRua = System.getenv("OPENIMAGE_RUA_CHAT_API_KEY");
            if (openimageRua != null && !openimageRua.trim().isBlank()) {
                return openimageRua.trim();
            }
        }
        return config.apiKey() == null ? "" : config.apiKey().trim();
    }

    private GeneratedImage generatedFromResponse(JsonNode response, AiRuntimeConfigService.EffectiveImageConfig config, String model, String errorPrefix) {
        if (response == null) {
            throw new AiImageException(errorPrefix.replace("失败", "无响应"));
        }
        throwIfError(response);
        byte[] bytes = extractImageBytes(response, config);
        if (bytes.length == 0) {
            throw new AiImageException(errorPrefix.replace("失败", "未返回可下载图片"));
        }
        AiImageSizes.Size returned = readImageSize(bytes);
        if (returned == null) {
            throw new AiImageException("上游返回图片解码失败");
        }
        return generatedImage(bytes, config, model, returned.value(), response);
    }

    private GeneratedImage generatedImage(
        byte[] bytes,
        AiRuntimeConfigService.EffectiveImageConfig config,
        String model,
        String returnedSize,
        JsonNode response
    ) {
        CostDetails details = costDetails(config, returnedSize, response);
        return new GeneratedImage(
            bytes,
            config.provider(),
            model,
            details.actualCostCny(),
            returnedSize,
            details,
            List.of()
        );
    }

    private CostDetails costDetails(AiRuntimeConfigService.EffectiveImageConfig config, String returnedSize, JsonNode response) {
        String effectiveSize = returnedSize == null || returnedSize.isBlank() ? config.size() : returnedSize;
        if (AiImageResponseAdapter.isAishuch(config.provider(), config.baseUrl())) {
            BigDecimal credits = AiImageResponseAdapter.creditsCost(response)
                .filter(value -> value.signum() >= 0)
                .orElse(null);
            if (credits != null) {
                BigDecimal rate = AiImageCostCalculator.apimartCreditCnyRate();
                return new CostDetails(
                    AiImageCostCalculator.apimartCreditsToCny(credits),
                    credits,
                    "APIMART_CREDIT",
                    rate,
                    "upstream_credits_cost"
                );
            }
            BigDecimal usd = AiImageResponseAdapter.usdCost(response)
                .filter(value -> value.signum() >= 0)
                .orElse(null);
            if (usd != null) {
                BigDecimal rate = AiImageCostCalculator.apimartUsdCnyRate();
                return new CostDetails(
                    AiImageCostCalculator.apimartUsdToCny(usd),
                    usd,
                    "USD",
                    rate,
                    "upstream_cost"
                );
            }
            credits = AiImageCostCalculator.apimartTierCredits(effectiveSize);
            BigDecimal rate = AiImageCostCalculator.apimartCreditCnyRate();
            return new CostDetails(
                AiImageCostCalculator.apimartCreditsToCny(credits),
                credits,
                "APIMART_CREDIT",
                rate,
                "tier_fallback"
            );
        }
        BigDecimal actualCost = AiImageCostCalculator.cost(
            config.provider(),
            config.baseUrl(),
            config.model(),
            effectiveSize,
            config.actualCost()
        );
        boolean rua = AiImageResponseAdapter.isRuaChat(config.provider(), config.baseUrl());
        return new CostDetails(
            actualCost,
            rua ? actualCost : null,
            rua ? "CNY" : "",
            rua ? BigDecimal.ONE : null,
            rua ? "fixed_price" : "provider_price_table"
        );
    }

    private void deleteTempFiles(List<Path> files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
                // Temporary request files do not affect task state.
            }
        }
    }

    private boolean useCurlTransport(AiRuntimeConfigService.EffectiveImageConfig config) {
        String baseUrl = config.baseUrl() == null ? "" : config.baseUrl().toLowerCase(Locale.ROOT);
        String provider = config.provider() == null ? "" : config.provider().toLowerCase(Locale.ROOT);
        return provider.contains("rua") && baseUrl.contains("api.rua.chat");
    }

    private GeneratedImage submitMultipartImageRequest(URI endpoint, MultipartPayload request, AiRuntimeConfigService.EffectiveImageConfig config, String model, String errorPrefix) {
        JsonNode response;
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(Math.max(config.timeoutSeconds(), 10), 30)))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .version(HttpClient.Version.HTTP_1_1)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .header(HttpHeaders.CONTENT_TYPE, request.contentType())
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.USER_AGENT, "curl/8.5.0")
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.bytes()))
                .build();
            HttpResponse<String> httpResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new AiImageHttpException(httpResponse.statusCode(), errorPrefix + "：" + httpResponse.statusCode() + " " + summarize(httpResponse.body()));
            }
            response = json.readTree(httpResponse.body());
        } catch (AiImageHttpException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiImageException(errorPrefix.replace("失败", "被中断"));
        } catch (Exception exception) {
            throw new AiImageException(errorPrefix + "：" + summarize(exception.getMessage()));
        }
        if (response == null) {
            throw new AiImageException(errorPrefix.replace("失败", "无响应"));
        }
        throwIfError(response);
        byte[] bytes = extractImageBytes(response, config);
        if (bytes.length == 0) {
            throw new AiImageException(errorPrefix.replace("失败", "未返回可下载图片"));
        }
        AiImageSizes.Size returned = readImageSize(bytes);
        if (returned == null) {
            throw new AiImageException("上游返回图片解码失败");
        }
        return generatedImage(bytes, config, model, returned.value(), response);
    }

    private void multipartText(ByteArrayOutputStream out, String boundary, String name, String value) {
        out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void multipartFile(ByteArrayOutputStream out, String boundary, String name, String filename, String contentType, byte[] bytes) {
        out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(bytes);
        out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private GeneratedImage submitJsonImageRequest(URI endpoint, Map<String, Object> request, AiRuntimeConfigService.EffectiveImageConfig config, String model, String errorPrefix) {
        JsonNode response = webClient.post()
            .uri(endpoint)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .onStatus(status -> !status.is2xxSuccessful(), clientResponse -> clientResponse.bodyToMono(String.class)
                .map(responseBody -> new AiImageHttpException(clientResponse.statusCode().value(), errorPrefix + "：" + clientResponse.statusCode().value() + " " + summarize(responseBody))))
            .bodyToMono(JsonNode.class)
            .block(Duration.ofSeconds(config.timeoutSeconds()));
        if (response == null) {
            throw new AiImageException(errorPrefix.replace("失败", "无响应"));
        }
        throwIfError(response);
        byte[] bytes = extractImageBytes(response, config);
        if (bytes.length == 0) {
            throw new AiImageException(errorPrefix.replace("失败", "未返回可下载图片"));
        }
        AiImageSizes.Size returned = readImageSize(bytes);
        if (returned == null) {
            throw new AiImageException("上游返回图片解码失败");
        }
        return generatedImage(bytes, config, model, returned.value(), response);
    }

    private Map<String, Object> imageGenerationRequest(ProductProjectEntity project, ImageTaskEntity task, List<String> refs, AiRuntimeConfigService.EffectiveImageConfig config) {
        if (AiImageResponseAdapter.isXinZhiZhu(config.provider(), config.baseUrl())) {
            return xinZhiZhuImageGenerationRequest(project, task, refs, config);
        }
        if (AiImageResponseAdapter.isAishuch(config.provider(), config.baseUrl())) {
            return aishuchImageGenerationRequest(project, task, refs, config);
        }
        if (AiImageResponseAdapter.isRuaChat(config.provider(), config.baseUrl())) {
            return ruaChatImageGenerationRequest(project, task, refs, config);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model(task, config));
        request.put("prompt", prompt(project, task, !refs.isEmpty(), config));
        request.put("size", requestSize(task, config));
        request.put("response_format", config.responseFormat());
        request.put("n", 1);
        if (!refs.isEmpty()) {
            request.put("reference_images", refs);
        }
        return request;
    }

    private Map<String, Object> aishuchImageGenerationRequest(ProductProjectEntity project, ImageTaskEntity task, List<String> refs, AiRuntimeConfigService.EffectiveImageConfig config) {
        String generationSize = requestSize(task, config);
        String requestSize = aishuchSize(generationSize);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model(task, config));
        request.put("prompt", prompt(project, task, !refs.isEmpty(), config));
        request.put("n", 1);
        request.put("size", requestSize);
        request.put("resolution", resolutionLabel(generationSize).toLowerCase(Locale.ROOT));
        if (!refs.isEmpty()) {
            request.put("image_urls", refs);
        }
        return request;
    }

    private Map<String, Object> ruaChatImageGenerationRequest(ProductProjectEntity project, ImageTaskEntity task, List<String> refs, AiRuntimeConfigService.EffectiveImageConfig config) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "gpt-image-2");
        request.put("prompt", prompt(project, task, !refs.isEmpty(), config));
        request.put("size", requestSize(task, config));
        request.put("output_format", "png");
        request.put("quality", "auto");
        request.put("moderation", "low");
        request.put("n", 1);
        if (!refs.isEmpty()) {
            request.put("reference_images", refs);
        }
        return request;
    }

    private Map<String, Object> xinZhiZhuImageGenerationRequest(ProductProjectEntity project, ImageTaskEntity task, List<String> refs, AiRuntimeConfigService.EffectiveImageConfig config) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model(task, config));
        request.put("prompt", prompt(project, task, !refs.isEmpty(), config));
        request.put("size", requestSize(task, config));
        request.put("quality", "auto");
        request.put("output_format", "png");
        request.put("n", 1);
        if (!refs.isEmpty()) {
            request.put("reference_images", refs);
        }
        return request;
    }

    private Map<String, Object> geminiRequest(ProductProjectEntity project, ImageTaskEntity task, List<String> refs, AiRuntimeConfigService.EffectiveImageConfig config) {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", prompt(project, task, !refs.isEmpty(), config));
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(textPart);
        for (String ref : refs) {
            parts.add(Map.of("fileData", Map.of("fileUri", ref)));
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("parts", parts);
        Map<String, Object> imageConfig = new LinkedHashMap<>();
        imageConfig.put("aspectRatio", aspectRatio(requestSize(task, config)));
        imageConfig.put("resolution", resolutionLabel(requestSize(task, config)));
        return Map.of(
            "contents", List.of(content),
            "generationConfig", Map.of("imageConfig", imageConfig)
        );
    }

    private JsonNode resolveImageResponse(JsonNode response, AiRuntimeConfigService.EffectiveImageConfig config) {
        if (AiImageResponseAdapter.firstImage(response).isPresent()) {
            return response;
        }
        if (AiImageResponseAdapter.hasFailure(response)) {
            throw new AiImageException("上游生图失败：" + summarize(AiImageResponseAdapter.failureSummary(response)));
        }
        boolean shouldPoll = AiImageResponseAdapter.isAishuch(config.provider(), config.baseUrl())
            || AiImageResponseAdapter.status(response).map(AiImageResponseAdapter::isPendingStatus).orElse(false);
        if (!shouldPoll) {
            return response;
        }
        String taskId = AiImageResponseAdapter.firstTaskId(response).orElse("");
        if (taskId.isBlank()) {
            return response;
        }
        if (!AiImageResponseAdapter.isAishuch(config.provider(), config.baseUrl())) {
            throw new AiImageException("上游返回异步任务 " + taskId + "，但当前供应商未配置任务查询适配");
        }
        return pollAishuchTask(config, taskId);
    }

    private JsonNode pollAishuchTask(AiRuntimeConfigService.EffectiveImageConfig config, String taskId) {
        URI taskEndpoint = endpoint(config.baseUrl(), "/v1/tasks/" + AiImageResponseAdapter.pathSegment(taskId));
        long deadline = System.nanoTime() + Duration.ofSeconds(Math.max(config.timeoutSeconds(), 30)).toNanos();
        JsonNode latest = null;
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            attempt += 1;
            latest = webClient.get()
                .uri(taskEndpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), clientResponse -> clientResponse.bodyToMono(String.class)
                    .map(body -> new AiImageHttpException(clientResponse.statusCode().value(), "上游任务查询失败：" + clientResponse.statusCode().value() + " " + summarize(body))))
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(Math.min(Math.max(config.timeoutSeconds(), 15), 60)));

            if (latest != null && AiImageResponseAdapter.firstImage(latest).isPresent()) {
                return latest;
            }
            if (latest != null && AiImageResponseAdapter.hasFailure(latest)) {
                throw new AiImageException("上游生图失败：" + summarize(AiImageResponseAdapter.failureSummary(latest)));
            }
            String status = latest == null ? "" : AiImageResponseAdapter.status(latest).orElse("");
            if (!status.isBlank() && !AiImageResponseAdapter.isPendingStatus(status) && !"completed".equalsIgnoreCase(status) && !"success".equalsIgnoreCase(status)) {
                throw new AiImageException("上游任务状态异常：" + summarize(status));
            }
            sleepBeforeNextPoll(attempt);
        }
        String status = latest == null ? "无任务响应" : AiImageResponseAdapter.status(latest).orElse("未知");
        throw new AiImageException("上游生图任务超时：" + summarize(status));
    }

    private void sleepBeforeNextPoll(int attempt) {
        try {
            Thread.sleep(attempt <= 3 ? 3000L : 5000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiImageException("上游任务查询被中断");
        }
    }

    private URI endpoint(String baseUrl, String path) {
        String base = baseUrl == null || baseUrl.isBlank() ? properties.baseUrlValue() : baseUrl;
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (normalized.endsWith("/v1") && path.startsWith("/v1/")) {
            return URI.create(normalized + path.substring(3));
        }
        if (normalized.endsWith("/v1beta") && path.startsWith("/v1beta/")) {
            return URI.create(normalized + path.substring(7));
        }
        return URI.create(normalized + path);
    }

    private URI geminiEndpoint(String baseUrl, String model) {
        return endpoint(baseUrl, "/v1beta/models/" + AiImageResponseAdapter.pathSegment(model) + ":generateContent");
    }

    private boolean isGemini(String model, AiRuntimeConfigService.EffectiveImageConfig config) {
        String text = ((model == null ? "" : model) + " " + (config.provider() == null ? "" : config.provider())).toLowerCase(Locale.ROOT);
        return text.contains("gemini");
    }

    private String model(ImageTaskEntity task, AiRuntimeConfigService.EffectiveImageConfig config) {
        if (config.pullWorker()) {
            return blankToDefault(config.model(), "gpt-image-2");
        }
        if (AiImageResponseAdapter.isRuaChat(config.provider(), config.baseUrl())) {
            return "gpt-image-2";
        }
        String model = task.getModel() == null || task.getModel().isBlank() ? config.model() : task.getModel();
        return modelForSize(config, model, size(task, config));
    }

    private String modelForSize(AiRuntimeConfigService.EffectiveImageConfig config, String model, String size) {
        String normalizedModel = model == null || model.isBlank() ? "gpt-image-2" : model.trim();
        if (isYunfei(config)) {
            return "gpt-image-2";
        }
        if (!isGptImage2Family(normalizedModel)) {
            return normalizedModel;
        }
        if (!isHfsyapi(config)) {
            return "gpt-image-2";
        }
        AiImageSizes.Size parsed = AiImageSizes.parse(size);
        if (parsed == null) {
            return normalizedModel;
        }
        return Math.max(parsed.width(), parsed.height()) >= 1800 ? "gpt-image-2pro" : "gpt-image-2";
    }

    private boolean isGptImage2Family(String value) {
        return "gpt-image-2".equalsIgnoreCase(value) || "gpt-image-2pro".equalsIgnoreCase(value);
    }

    private String size(ImageTaskEntity task, AiRuntimeConfigService.EffectiveImageConfig config) {
        try {
            JsonNode node = json.readTree(task.getPromptSnapshot());
            JsonNode value = node.get("generationSize");
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return AiImageSizes.normalizeGenerationSize(value.asText(), config.size());
            }
        } catch (Exception ignored) {
            // Prompt snapshot is best-effort metadata; fall back to global size.
        }
        return AiImageSizes.normalizeGenerationSize(config.size(), AiImageSizes.DEFAULT_SIZE);
    }

    private String requestSize(ImageTaskEntity task, AiRuntimeConfigService.EffectiveImageConfig config) {
        String target = size(task, config);
        if (AiImageResponseAdapter.isXinZhiZhu(config.provider(), config.baseUrl())) {
            AiImageSizes.Size parsed = AiImageSizes.parse(target);
            if (parsed == null || parsed.width() != parsed.height()) {
                return target;
            }
            int max = Math.max(parsed.width(), parsed.height());
            if (max >= 2800) {
                return "2880x2880";
            }
            if (max >= 1800) {
                return "2048x2048";
            }
            return "1600x1600";
        }
        if (!AiImageResponseAdapter.isRuaChat(config.provider(), config.baseUrl())) {
            return target;
        }
        AiImageSizes.Size parsed = AiImageSizes.parse(target);
        if (parsed == null || parsed.width() != parsed.height()) {
            return target;
        }
        int max = Math.max(parsed.width(), parsed.height());
        return max >= 2800 ? "2880x2880" : "1600x1600";
    }

    private String providerRequestSize(ImageTaskEntity task, AiRuntimeConfigService.EffectiveImageConfig config) {
        String target = requestSize(task, config);
        if (AiImageResponseAdapter.isAishuch(config.provider(), config.baseUrl())) {
            return aishuchSize(target) + "@" + resolutionLabel(target).toLowerCase(Locale.ROOT);
        }
        return target;
    }

    private String snapshotText(ImageTaskEntity task, String field) {
        try {
            JsonNode node = json.readTree(task.getPromptSnapshot());
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        } catch (Exception ignored) {
            // Older tasks do not contain runtime provider metadata.
        }
        return "";
    }

    private String aspectRatio(String size) {
        AiImageSizes.Size parsed = AiImageSizes.parse(size);
        if (parsed == null) {
            return "1:1";
        }
        int gcd = gcd(parsed.width(), parsed.height());
        return (parsed.width() / gcd) + ":" + (parsed.height() / gcd);
    }

    private String aishuchSize(String size) {
        String mapped = AISHUCH_PIXEL_SIZES.get(size);
        if (mapped != null) {
            return mapped;
        }
        String ratio = AISHUCH_SIZE_RATIOS.getOrDefault(size, aspectRatio(size));
        return "7:3".equals(ratio) ? "21:9" : ratio;
    }

    private String resolutionLabel(String size) {
        AiImageSizes.Size parsed = AiImageSizes.parse(size);
        if (parsed == null) {
            return "1K";
        }
        int max = Math.max(parsed.width(), parsed.height());
        if (max >= 2800) {
            return "4K";
        }
        if (max >= 1800) {
            return "2K";
        }
        return "1K";
    }

    private int gcd(int left, int right) {
        int a = Math.abs(left);
        int b = Math.abs(right);
        while (b != 0) {
            int next = a % b;
            a = b;
            b = next;
        }
        return a == 0 ? 1 : a;
    }

    private String prompt(ProductProjectEntity project, ImageTaskEntity task, boolean hasReferences, AiRuntimeConfigService.EffectiveImageConfig config) {
        String base = basePrompt(project, task, hasReferences, config);
        String legacyIntent = legacyIntentBlock(task, hasReferences);
        String adapter = providerPromptAdapter(config);
        if (!legacyIntent.isBlank()) {
            base = base + "\n\nIntent and reference constraints:\n" + legacyIntent;
        }
        return adapter.isBlank() ? base : base + "\n\nProvider adapter:\n" + adapter;
    }

    private String basePrompt(ProductProjectEntity project, ImageTaskEntity task, boolean hasReferences, AiRuntimeConfigService.EffectiveImageConfig config) {
        String modelPrompt = modelPrompt(task);
        if ("image_translation".equals(snapshotText(task, "taskKind"))) {
            return modelPrompt;
        }
        if ("commerce_suite".equals(snapshotText(task, "taskKind"))) {
            if (usesDirectCommerceSuitePrompt(config)) {
                return modelPrompt;
            }
            return """
                Create one image in a coherent platform ecommerce suite.
                %s
                The model must generate the final ecommerce image directly, including crisp readable in-image text from the confirmed visible text plan.
                Do not leave blank text panels for later editing. Do not add platform logos, marketplace UI, QR codes, watermarks, fake badges, invented prices, or unconfirmed claims.

                Suite image brief:
                %s
                """.formatted(referenceInstruction(hasReferences), modelPrompt);
        }
        if ("creative".equals(task.getImageType())) {
            return modelPrompt;
        }
        return """
            Create an Amazon-ready ecommerce listing image.
            Product identity: use the target-language Listing title and confirmed facts below. Do not render source-language product names as visible text unless they are explicitly included in the allowed target-language copy.
            Image type: %s
            %s
            Requirements and confirmed facts:
            %s
            Do not invent certifications, exact dimensions, compatibility, or package quantity.
            Render only the required short, readable information-layer text from the model prompt, and render it in the requested Listing language.
            """.formatted(task.getImageType(), referenceInstruction(hasReferences), modelPrompt);
    }

    private String modelPrompt(ImageTaskEntity task) {
        try {
            JsonNode node = json.readTree(task.getPromptSnapshot());
            JsonNode value = node.get("modelPrompt");
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText() + retryInstruction(node);
            }
        } catch (Exception ignored) {
            // Older tasks may not contain a dedicated model prompt.
        }
        return task.getPromptSnapshot();
    }

    private String retryInstruction(JsonNode node) {
        JsonNode retry = node == null ? null : node.get("retryReference");
        if (retry == null || retry.isMissingNode() || retry.isNull()) {
            return "";
        }
        String text = retry.path("text").asText("").trim();
        String imageUrl = retry.path("imageUrl").asText("").trim();
        if (text.isBlank() && imageUrl.isBlank()) {
            return "";
        }
        StringBuilder instruction = new StringBuilder("\n\nAdditional regeneration request:\n");
        if (!text.isBlank()) {
            instruction.append("- User notes: ").append(text).append('\n');
        }
        if (!imageUrl.isBlank()) {
            instruction.append("- An additional retry reference image is provided in reference_images; use it for this same image slot.\n");
        }
        instruction.append("- Keep the original image type, product facts, and compliance rules unchanged.");
        return instruction.toString();
    }

    private String referenceInstruction(boolean hasReferences) {
        if (!hasReferences) {
            return "No reference image is provided; follow the text prompt only.";
        }
        return "Uploaded images are optional visual inputs in stable upload order. Follow any per-image roles or exclusions stated by the user, do not force every image into the result, and do not let images override the text request. Preserve a product only when an image is designated as its identity source; perform any explicit replacement.";
    }

    private String providerPromptAdapter(AiRuntimeConfigService.EffectiveImageConfig config) {
        if (config == null) {
            return "";
        }
        if (AiImageResponseAdapter.isXinZhiZhu(config.provider(), config.baseUrl())) {
            return "";
        }
        if (isHfsyapi(config)) {
            return "FIRST PRIORITY: follow the user's text request and any explicit role or exclusion assigned to uploaded visual inputs in stable upload order. Do not force every image into the result. Preserve a designated product source exactly; if the user explicitly requests replacement, replace the original with the designated product source and preserve that replacement. Apply scene/layout/style assets only to their assigned roles. Do not invent prices, discounts, coupons, sale tags, new-product badges, certifications, QR codes, marketplace UI, or extra claims unless the user provides those exact words or numbers.";
        }
        if (AiImageResponseAdapter.isAishuch(config.provider(), config.baseUrl())) {
            return "Treat uploaded images as optional visual inputs in stable upload order and follow the user's explicit per-image roles or exclusions. Preserve only the designated product source; perform any explicit replacement instead of preserving the object being replaced. Use style/composition assets only for those roles. Return the requested aspect ratio and size as closely as possible.";
        }
        return "";
    }

    private boolean usesDirectCommerceSuitePrompt(AiRuntimeConfigService.EffectiveImageConfig config) {
        if (config == null) {
            return false;
        }
        return isYunfei(config)
            || AiImageResponseAdapter.isXinZhiZhu(config.provider(), config.baseUrl())
            || AiImageResponseAdapter.isRuaChat(config.provider(), config.baseUrl());
    }

    private String legacyIntentBlock(ImageTaskEntity task, boolean hasReferences) {
        if (!hasReferences || !snapshotText(task, "intent").isBlank()) {
            return "";
        }
        String text = modelPrompt(task).toLowerCase(Locale.ROOT);
        List<String> lines = new ArrayList<>();
        boolean replacement = CreativePromptPolicy.requestsReferenceReplacement(text);
        if (replacement) {
            lines.add("Follow the user's explicit per-reference roles and perform the requested subject replacement. Preserve the replacement subject from its designated identity reference; do not preserve the original object that the user asked to replace.");
        } else {
            lines.add("Reference image is the source of truth for the designated product. Do not replace the product unless the user explicitly requests replacement.");
        }
        if (replacement) {
            lines.add("Use composition, camera, perspective, scene, background, lighting, and style references only for their user-assigned roles. Do not merge the identities of different reference subjects.");
        } else if (containsAny(text, "换背景", "背景", "background", "environment", "lighting")) {
            lines.add("Keep the product from the reference image unchanged. Only modify the requested background, environment, lighting, props, or atmosphere. Do not alter product color, shape, print, embroidery, collar, sleeve, hem, seams, or material texture unless explicitly requested.");
        } else if (containsAny(text, "1688", "详情页", "海报", "卖点", "模块", "主图", "电商", "淘宝", "拼多多", "小红书", "京东", "poster", "ecommerce")) {
            lines.add("Create an ecommerce commercial layout, but the referenced product remains the main subject and must not be replaced. Use clean composition, clear hierarchy, and platform-appropriate commercial style. Render only exact user-provided visible text when requested. Do not invent unprovided prices, discounts, certifications, badges, QR codes, marketplace UI, brand logos, or claims.");
        } else {
            lines.add("Use the reference image as the source of truth for the main subject. Preserve the referenced product's category, silhouette, color, material, pattern, structure, proportions, and key visible details. Do not replace the product with a different item. Do not redesign the product unless the user explicitly asks for replacement. If creative style conflicts with product preservation, product preservation wins.");
        }
        lines.add(switch (CreativePromptPolicy.visibleTextIntent(text)) {
            case ADD_EXACT_TEXT -> "Render only the exact visible wording explicitly provided by the user. Do not add extra claims, prices, discounts, coupons, sale tags, new-product badges, certification seals, QR codes, platform buttons, watermarks, or unrelated logos.";
            case PRESERVE_EXISTING_TEXT -> "Preserve existing product or packaging text and artwork from the designated reference, but add no new headline, slogan, promotion, badge, price, QR code, marketplace UI, watermark, or unrelated logo.";
            case NO_NEW_TEXT -> "Add no new readable text, logos, watermarks, labels, QR codes, or platform UI.";
        });
        return String.join("\n", lines);
    }

    private boolean containsAny(String value, String... needles) {
        String text = value == null ? "" : value;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String sizeWarning(String requestSize, String returnedSize) {
        AiImageSizes.Size requested = AiImageSizes.parse(requestSize);
        AiImageSizes.Size returned = AiImageSizes.parse(returnedSize);
        if (requested == null || returned == null) {
            return "";
        }
        return requested.width() * returned.height() == requested.height() * returned.width() ? "" : "size_mismatch";
    }

    private List<String> referenceImages(ImageTaskEntity task, List<ProductImageEntity> images, AiRuntimeConfigService.EffectiveImageConfig config, String resolvedModel) {
        if (shouldIgnoreReferences(task) || images == null || images.isEmpty()) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        int maxRefs = referenceLimit(config, resolvedModel);
        for (ProductImageEntity image : images) {
            if (refs.size() >= maxRefs) break;
            if (isHfsyapi(config) && image.getFileSize() != null && image.getFileSize() > HFSY_REFERENCE_IMAGE_BYTES) {
                throw new AiImageException("参考图超过 hfsyapi 单张 5MB 限制，正在尝试兜底供应商");
            }
            String absolute = absoluteUrl(image.getUrl(), config);
            if (!absolute.isBlank()) {
                validateReferenceUrl(absolute);
                refs.add(absolute);
            }
        }
        return refs;
    }

    private boolean shouldIgnoreReferences(ImageTaskEntity task) {
        if ("none".equalsIgnoreCase(snapshotText(task, "reference_policy"))) {
            return true;
        }
        return CreativePromptPolicy.ignoresAllReferences(modelPrompt(task));
    }

    private int nonEmptyReferenceCount(List<ProductImageEntity> images) {
        if (images == null || images.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ProductImageEntity image : images) {
            if (image != null && image.getUrl() != null && !image.getUrl().isBlank()) {
                count += 1;
            }
        }
        return count;
    }

    private int referenceLimit(AiRuntimeConfigService.EffectiveImageConfig config, String resolvedModel) {
        String model = resolvedModel == null || resolvedModel.isBlank() ? config.model() : resolvedModel;
        if (isGemini(model, config)) {
            return 4;
        }
        if (isHfsyapi(config) && "gpt-image-2pro".equalsIgnoreCase(model)) {
            return 4;
        }
        if (isYunfei(config)) {
            return 1;
        }
        if (AiImageResponseAdapter.isRuaChat(config.provider(), config.baseUrl())) {
            return 4;
        }
        if (AiImageResponseAdapter.isXinZhiZhu(config.provider(), config.baseUrl())) {
            return 5;
        }
        return 16;
    }

    private String absoluteUrl(String url, AiRuntimeConfigService.EffectiveImageConfig config) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String normalized = url.trim();
        String proxied = publicFileProxyUrl(normalized, config);
        if (!proxied.isBlank()) {
            return proxied;
        }
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return normalized;
        }
        String base = config.publicBaseUrl();
        if (base == null || base.isBlank()) {
            return "";
        }
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return cleanBase + (normalized.startsWith("/") ? normalized : "/" + normalized);
    }

    private String publicFileProxyUrl(String url, AiRuntimeConfigService.EffectiveImageConfig config) {
        String publicPath = publicFilePath(url);
        if (publicPath.isBlank()) {
            return "";
        }
        String base = config.publicBaseUrl();
        if (base == null || base.isBlank()) {
            return "";
        }
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return cleanBase + publicPath;
    }

    private String publicFilePath(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String value = url.trim();
        if (value.startsWith("/api/public/files/")) {
            return value;
        }
        if (value.startsWith("projects/") || value.startsWith("avatars/") || value.startsWith("benefits/")) {
            return "/api/public/files/" + value;
        }
        if (!(value.startsWith("http://") || value.startsWith("https://"))) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            String path = uri.getPath() == null ? "" : uri.getPath();
            int projectIndex = path.indexOf("/projects/");
            boolean looksLikeMinio = uri.getPort() == 19000 || path.contains("/openimage/projects/");
            if (looksLikeMinio && projectIndex >= 0) {
                return "/api/public/files" + path.substring(projectIndex);
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private void validateReferenceUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new AiImageException("参考图地址无效，请重新上传或粘贴公网图片链接");
            }
            String lower = host.toLowerCase();
            if ("localhost".equals(lower) || lower.startsWith("127.") || lower.startsWith("10.") || lower.startsWith("192.168.") || lower.startsWith("169.254.")) {
                throw new AiImageException("参考图公网访问地址未配置，AI 无法读取本地/内网图片。请联系管理员配置 OPENIMAGE_AI_IMAGE_PUBLIC_BASE_URL，或粘贴公网图片链接。");
            }
        } catch (AiImageException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiImageException("参考图地址无效，请重新上传或粘贴公网图片链接");
        }
    }

    private boolean isHfsyapi(AiRuntimeConfigService.EffectiveImageConfig config) {
        String text = ((config.provider() == null ? "" : config.provider()) + " " + (config.baseUrl() == null ? "" : config.baseUrl())).toLowerCase(Locale.ROOT);
        return text.contains("hfsyapi");
    }

    private boolean isYunfei(AiRuntimeConfigService.EffectiveImageConfig config) {
        String text = ((config.provider() == null ? "" : config.provider()) + " " + (config.baseUrl() == null ? "" : config.baseUrl())).toLowerCase(Locale.ROOT);
        return text.contains("yunfei") || text.contains("img.yunfei.best");
    }

    private Semaphore providerSlot(AiRuntimeConfigService.EffectiveImageConfig config) {
        if (AiImageResponseAdapter.isRuaChat(config.provider(), config.baseUrl())) {
            return ruaChatSlots;
        }
        if (AiImageResponseAdapter.isXinZhiZhu(config.provider(), config.baseUrl())) {
            return xinZhiZhuSlots;
        }
        if (!isYunfei(config)) {
            return null;
        }
        String text = ((config.id() == null ? "" : config.id()) + " " + (config.provider() == null ? "" : config.provider())).toLowerCase(Locale.ROOT);
        return text.contains("hires") || text.contains("2k") || text.contains("4k") ? yunfeiHiresSlots : yunfeiOneKSlots;
    }

    private boolean acquireProviderSlot(Semaphore slot) {
        if (slot == null) {
            return false;
        }
        try {
            slot.acquire();
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiImageException("等待上游供应商并发槽被中断");
        }
    }

    private static int intEnv(String key, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(System.getenv().getOrDefault(key, "").trim()));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void throwIfError(JsonNode response) {
        if (AiImageResponseAdapter.hasFailure(response)) {
            throw new AiImageException("上游生图失败：" + summarize(AiImageResponseAdapter.failureSummary(response)));
        }
        String value = AiImageResponseAdapter.status(response).orElse("");
        if (!value.isBlank() && AiImageResponseAdapter.isPendingStatus(value)) {
            throw new AiImageException("上游返回异步状态 " + value + "，但响应中没有可落盘图片");
        }
    }

    private byte[] extractImageBytes(JsonNode response, AiRuntimeConfigService.EffectiveImageConfig config) {
        AiImageResponseAdapter.ImagePayload image = AiImageResponseAdapter.firstImage(response).orElse(null);
        if (image == null || image.value().isBlank()) {
            return new byte[0];
        }
        if (image.base64()) {
            return decodeBase64Image(image.value());
        }
        byte[] downloaded = downloadImage(image.value(), config);
        return downloaded == null ? new byte[0] : downloaded;
    }

    private byte[] decodeBase64Image(String value) {
        String payload = value;
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma >= 0) {
            payload = payload.substring(comma + 1);
        }
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException ignored) {
            return Base64.getUrlDecoder().decode(payload);
        }
    }

    private byte[] downloadImage(String url, AiRuntimeConfigService.EffectiveImageConfig config) {
        return webClient.get()
            .uri(URI.create(url))
            .accept(MediaType.IMAGE_PNG, MediaType.IMAGE_JPEG, MediaType.APPLICATION_OCTET_STREAM)
            .retrieve()
            .bodyToMono(byte[].class)
            .block(Duration.ofSeconds(config.timeoutSeconds()));
    }

    private byte[] downloadReferenceImage(String url, AiRuntimeConfigService.EffectiveImageConfig config) {
        byte[] bytes = downloadImage(url, config);
        if (bytes == null || bytes.length == 0) {
            throw new AiImageException("参考图下载失败，正在尝试兜底供应商");
        }
        if (readImageSize(bytes) == null) {
            throw new AiImageException("参考图解码失败，正在尝试兜底供应商");
        }
        return bytes;
    }

    private MediaType imageContentType(byte[] bytes) {
        if (bytes != null && bytes.length >= 12) {
            if ((bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47) {
                return MediaType.IMAGE_PNG;
            }
            if ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8) {
                return MediaType.IMAGE_JPEG;
            }
            if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
                return MediaType.parseMediaType("image/webp");
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private String imageExtension(MediaType contentType) {
        if (MediaType.IMAGE_JPEG.equals(contentType)) {
            return ".jpg";
        }
        if (MediaType.parseMediaType("image/webp").equals(contentType)) {
            return ".webp";
        }
        if (MediaType.IMAGE_PNG.equals(contentType)) {
            return ".png";
        }
        return ".img";
    }

    private AiImageSizes.Size readImageSize(byte[] bytes) {
        AiImageSizes.Size webpSize = readWebpSize(bytes);
        if (webpSize != null) {
            return webpSize;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            return image == null ? null : new AiImageSizes.Size(image.getWidth(), image.getHeight());
        } catch (Exception ignored) {
            return null;
        }
    }

    private AiImageSizes.Size readWebpSize(byte[] bytes) {
        if (bytes == null || bytes.length < 30
            || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F' || bytes[3] != 'F'
            || bytes[8] != 'W' || bytes[9] != 'E' || bytes[10] != 'B' || bytes[11] != 'P') {
            return null;
        }
        String chunk = new String(bytes, 12, 4, StandardCharsets.US_ASCII);
        if ("VP8X".equals(chunk)) {
            return positiveSize(1 + littleEndian24(bytes, 24), 1 + littleEndian24(bytes, 27));
        }
        if ("VP8 ".equals(chunk)
            && unsigned(bytes[23]) == 0x9d
            && unsigned(bytes[24]) == 0x01
            && unsigned(bytes[25]) == 0x2a) {
            return positiveSize(littleEndian16(bytes, 26) & 0x3fff, littleEndian16(bytes, 28) & 0x3fff);
        }
        if ("VP8L".equals(chunk) && bytes.length >= 25 && unsigned(bytes[20]) == 0x2f) {
            int b0 = unsigned(bytes[21]);
            int b1 = unsigned(bytes[22]);
            int b2 = unsigned(bytes[23]);
            int b3 = unsigned(bytes[24]);
            return positiveSize(
                1 + b0 + ((b1 & 0x3f) << 8),
                1 + ((b1 & 0xc0) >> 6) + (b2 << 2) + ((b3 & 0x0f) << 10)
            );
        }
        return null;
    }

    private AiImageSizes.Size positiveSize(int width, int height) {
        return width > 0 && height > 0 ? new AiImageSizes.Size(width, height) : null;
    }

    private int littleEndian16(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) | (unsigned(bytes[offset + 1]) << 8);
    }

    private int littleEndian24(byte[] bytes, int offset) {
        return unsigned(bytes[offset])
            | (unsigned(bytes[offset + 1]) << 8)
            | (unsigned(bytes[offset + 2]) << 16);
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private String errorType(Exception exception) {
        if (exception instanceof AiImageHttpException httpException) {
            int status = httpException.statusCode();
            if (status == 524) {
                return "http_524";
            }
            return status >= 500 ? "http_5xx" : "http_" + status;
        }
        if (exception instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            if (status == 524) {
                return "http_524";
            }
            return status >= 500 ? "http_5xx" : "http_" + status;
        }
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("timeout") || message.contains("timed out") || message.contains("超时")) {
            return "timeout";
        }
        if (message.contains("decode") || message.contains("解码")) {
            return "decode_failed";
        }
        if (message.contains("download") || message.contains("下载")) {
            return "download_failed";
        }
        if (message.contains("未返回") || message.contains("no image") || message.contains("无响应")) {
            return "no_image";
        }
        if (message.contains("connection") || message.contains("premature") || message.contains("连接")) {
            return "connection";
        }
        return exception.getClass().getSimpleName();
    }

    private boolean isImageProviderCircuitOpen(AiRuntimeConfigService.EffectiveImageConfig config) {
        String key = imageProviderCircuitKey(config);
        Long openUntil = imageProviderCircuitOpenUntil.get(key);
        if (openUntil == null) {
            return false;
        }
        if (openUntil <= System.currentTimeMillis()) {
            imageProviderCircuitOpenUntil.remove(key);
            return false;
        }
        return true;
    }

    private void openImageProviderCircuitIfNeeded(AiRuntimeConfigService.EffectiveImageConfig config, Exception exception) {
        String type = errorType(exception);
        long duration = switch (type) {
            case "http_401", "http_403" -> IMAGE_PROVIDER_AUTH_CIRCUIT_MS;
            case "http_429", "http_5xx", "http_524", "timeout", "connection", "no_image" -> IMAGE_PROVIDER_TRANSIENT_CIRCUIT_MS;
            default -> 0L;
        };
        if (duration > 0L) {
            imageProviderCircuitOpenUntil.put(imageProviderCircuitKey(config), System.currentTimeMillis() + duration);
        }
    }

    private void closeImageProviderCircuit(AiRuntimeConfigService.EffectiveImageConfig config) {
        imageProviderCircuitOpenUntil.remove(imageProviderCircuitKey(config));
    }

    private String imageProviderCircuitKey(AiRuntimeConfigService.EffectiveImageConfig config) {
        String id = config == null ? "" : blankToDefault(config.id(), "");
        if (!id.isBlank()) {
            return id;
        }
        if (config == null) {
            return "";
        }
        return blankToDefault(config.provider(), "")
            + "|" + blankToDefault(config.baseUrl(), "")
            + "|" + blankToDefault(config.model(), "");
    }

    private static String summarize(String value) {
        if (value == null) return "";
        String trimmed = value.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }

    private String appendNote(String current, String note) {
        if (current == null || current.isBlank()) {
            return note == null ? "" : note;
        }
        return note == null || note.isBlank() ? current : current + ";" + note;
    }

    public record GeneratedImage(
        byte[] bytes,
        String provider,
        String model,
        BigDecimal actualCost,
        String returnedSize,
        CostDetails costDetails,
        List<ProviderAttempt> attempts
    ) {
        public GeneratedImage(byte[] bytes, String provider, String model, BigDecimal actualCost, String returnedSize, List<ProviderAttempt> attempts) {
            this(bytes, provider, model, actualCost, returnedSize, new CostDetails(actualCost, null, "", null, "stored"), attempts);
        }
    }

    public record CostDetails(
        BigDecimal actualCostCny,
        BigDecimal upstreamCost,
        String upstreamCostUnit,
        BigDecimal conversionRate,
        String source
    ) {}

    private record MultipartPayload(byte[] bytes, String contentType) {}

    private record CurlResponse(int statusCode, String body) {}

    public record ProviderAttempt(
        String provider,
        String model,
        String requestSize,
        String returnedSize,
        long elapsedMs,
        String status,
        String errorType,
        String errorMessage,
        boolean success,
        String notes,
        String intent,
        String textMode,
        String referencePolicy,
        int referenceCount,
        String promptAdapter,
        String warning,
        BigDecimal actualCostCny,
        BigDecimal upstreamCost,
        String upstreamCostUnit,
        BigDecimal conversionRate,
        String costSource
    ) {
        public ProviderAttempt(
            String provider,
            String model,
            String requestSize,
            String returnedSize,
            long elapsedMs,
            String status,
            String errorType,
            String errorMessage,
            boolean success,
            String notes,
            String intent,
            String textMode,
            String referencePolicy,
            int referenceCount,
            String promptAdapter,
            String warning
        ) {
            this(
                provider,
                model,
                requestSize,
                returnedSize,
                elapsedMs,
                status,
                errorType,
                errorMessage,
                success,
                notes,
                intent,
                textMode,
                referencePolicy,
                referenceCount,
                promptAdapter,
                warning,
                null,
                null,
                "",
                null,
                ""
            );
        }
    }

    public static class AiImageGenerationException extends RuntimeException {
        private final List<ProviderAttempt> attempts;

        AiImageGenerationException(String message, Throwable cause, List<ProviderAttempt> attempts) {
            super(message, cause);
            this.attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }

        public List<ProviderAttempt> attempts() {
            return attempts;
        }
    }

    public static class AiImageDeferredException extends RuntimeException {
        private final String jobId;
        private final List<ProviderAttempt> attempts;

        public AiImageDeferredException(String jobId, String message, List<ProviderAttempt> attempts) {
            super(message);
            this.jobId = jobId;
            this.attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }

        public String jobId() {
            return jobId;
        }

        public List<ProviderAttempt> attempts() {
            return attempts;
        }
    }

    private static class AiImageException extends RuntimeException {
        AiImageException(String message) {
            super(message);
        }
    }

    private static class AiImageHttpException extends AiImageException {
        private final int statusCode;

        AiImageHttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        int statusCode() {
            return statusCode;
        }
    }
}
