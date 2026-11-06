package com.openimage.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.AiImageCostCalculator;
import com.openimage.domain.entity.VideoTaskEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class VideoProviderGateway {
    private static final String APIMART_BASE = "https://api.apimart.ai";
    private static final String AGNES_BASE = "https://apihub.agnes-ai.com";
    private static final String HFSY_BASE = "https://www.hfsyapi.cn";
    private static final List<String> HFSY_RATIOS = List.of("9:16", "16:9", "1:1");
    private static final Set<String> HFSY_MODELS = Set.of(
        "sd-2.0-mini",
        "sd-2.0-fast-480",
        "sd-2.0-fast",
        "sd-2.0-480",
        "sd-2.0"
    );

    private final VideoProperties properties;
    private final VideoConfigService config;
    private final ObjectMapper json;
    private final HttpClient http;

    public VideoProviderGateway(VideoProperties properties, VideoConfigService config, ObjectMapper json) {
        this.properties = properties;
        this.config = config;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Submission submit(VideoTaskEntity task, String provider, List<String> sourceUrls) throws Exception {
        if ("agnes".equals(provider)) {
            String apiKey = config.agnesApiKeyFor(task);
            ensure(properties.isAgnesEnabled() && notBlank(apiKey), "Agnes 视频服务未由后端环境启用");
            return submitAgnes(task, sourceUrls, apiKey);
        }
        if ("hfsy".equals(provider)) {
            String apiKey = config.hfsyApiKey();
            ensure(notBlank(apiKey), "HFSY 视频服务尚未配置 API Key");
            return submitHfsy(task, sourceUrls, apiKey);
        }
        String apiKey = config.apimartApiKey();
        ensure(properties.isPaidProviderEnabled() && notBlank(apiKey), "APIMart 付费视频服务未由后端环境启用");
        return submitApimart(task, provider, sourceUrls, apiKey);
    }

    public Submission mockSubmission(String provider) {
        return mockSubmission(null, provider);
    }

    public Submission mockSubmission(VideoTaskEntity task, String provider) {
        String normalized = switch (provider) {
            case "agnes", "gemini", "seedance", "hfsy" -> provider;
            default -> "gemini";
        };
        String fallbackModel = switch (normalized) {
            case "agnes" -> config.agnesModel();
            case "seedance" -> "doubao-seedance-2.0-fast";
            case "hfsy" -> "sd-2.0-fast";
            default -> "gemini-omni-flash-preview";
        };
        String model = task != null && notBlank(task.getModel()) ? task.getModel() : fallbackModel;
        if ("agnes".equals(normalized) && !model.startsWith("agnes")) model = fallbackModel;
        if ("gemini".equals(normalized) && !model.startsWith("gemini")) model = fallbackModel;
        if ("seedance".equals(normalized) && !model.startsWith("doubao-seedance")) model = fallbackModel;
        if ("hfsy".equals(normalized) && !model.startsWith("sd-")) model = fallbackModel;
        return new Submission("mock_" + UUID.randomUUID().toString().replace("-", ""), normalized, model);
    }

    public PollResult poll(VideoTaskEntity task) throws Exception {
        if (task.getUpstreamTaskId() != null && task.getUpstreamTaskId().startsWith("mock_")) {
            return PollResult.running("generating", Math.max(20, task.getProgress() == null ? 20 : task.getProgress()));
        }
        if ("agnes".equals(task.getProvider())) {
            return pollAgnes(task);
        }
        if ("hfsy".equals(task.getProvider())) {
            return pollHfsy(task);
        }
        return pollApimart(task);
    }

    private Submission submitApimart(
        VideoTaskEntity task,
        String provider,
        List<String> sourceUrls,
        String apiKey
    ) throws Exception {
        Map<String, Object> body = apimartRequestBody(task, provider, sourceUrls);
        JsonNode response = sendJson(
            HttpRequest.newBuilder(URI.create(APIMART_BASE + "/v1/videos/generations"))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build()
        );
        String taskId = apimartTaskId(response);
        ensure(notBlank(taskId), "APIMart 创建任务成功但未返回 task_id");
        return new Submission(taskId, provider, String.valueOf(body.get("model")));
    }

    Map<String, Object> apimartRequestBody(
        VideoTaskEntity task,
        String provider,
        List<String> sourceUrls
    ) {
        String fallbackModel = "seedance".equals(provider)
            ? "doubao-seedance-2.0-fast"
            : "gemini-omni-flash-preview";
        String model = notBlank(task.getModel()) ? task.getModel() : fallbackModel;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<String> images = absoluteUrls(sourceUrls);
        body.put("prompt", VideoPromptPolicy.compileForProvider(task.getPrompt(), provider, images.size()));
        body.put("image_urls", images);
        if ("seedance".equals(provider)) {
            body.put("duration", task.getDurationSeconds());
            body.put("resolution", upstreamResolution(task.getRequestedResolution()));
            body.put("size", task.getAspectRatio());
            Map<String, Object> metadata = metadata(task);
            body.put("generate_audio", !Boolean.FALSE.equals(metadata.get("generate_audio")));
            if (metadata.get("seed") instanceof Number seed) body.put("seed", seed.longValue());
        } else {
            body.put("aspect_ratio", task.getAspectRatio());
        }
        String webhook = apimartWebhookBaseUrl();
        if (notBlank(webhook)) body.put("webhook", webhook);
        return body;
    }

    private Submission submitAgnes(VideoTaskEntity task, List<String> sourceUrls, String apiKey) throws Exception {
        ensure(!sourceUrls.isEmpty(), "Agnes 图生视频需要至少一张商品图");
        String model = notBlank(task.getModel()) ? task.getModel() : config.agnesModel();
        int[] dimensions = agnesDimensions(task.getAspectRatio());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", agnesPrompt(task.getPrompt()));
        body.put("negative_prompt", agnesNegativePrompt());
        body.put("image", absoluteUrl(sourceUrls.get(0)));
        body.put("width", dimensions[0]);
        body.put("height", dimensions[1]);
        body.put("num_frames", task.getDurationSeconds() * 24 + 1);
        body.put("frame_rate", 24);
        JsonNode response = sendJson(
            HttpRequest.newBuilder(URI.create(AGNES_BASE + "/v1/videos"))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build()
        );
        String videoId = firstText(response, "video_id", "data.video_id", "id", "data.id");
        ensure(notBlank(videoId), "Agnes 创建任务成功但未返回 video_id");
        return new Submission(videoId, "agnes", model);
    }

    private Submission submitHfsy(VideoTaskEntity task, List<String> sourceUrls, String apiKey) throws Exception {
        Map<String, Object> body = hfsyRequestBody(task, sourceUrls);
        JsonNode response = sendJson(
            HttpRequest.newBuilder(URI.create(HFSY_BASE + "/v1/video/create"))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build()
        );
        String taskId = hfsyTaskId(response);
        ensure(notBlank(taskId), "HFSY 创建任务返回成功，但任务编号缺失，已停止自动重试");
        return new Submission(taskId, "hfsy", String.valueOf(body.get("model")));
    }

    Map<String, Object> hfsyRequestBody(VideoTaskEntity task, List<String> sourceUrls) {
        String model = notBlank(task.getModel()) ? task.getModel() : "sd-2.0-fast";
        ensure(HFSY_MODELS.contains(model), "HFSY 固定路由模型不在允许列表");
        ensure(HFSY_RATIOS.contains(task.getAspectRatio()), "HFSY 固定路由不支持所选画面比例");
        int duration = task.getDurationSeconds() == null ? 10 : task.getDurationSeconds();
        ensure(duration >= 4 && duration <= 15, "HFSY 视频时长必须为 4 到 15 秒");
        List<String> images = absoluteUrls(sourceUrls);
        ensure(images.size() <= 4, "HFSY 最多支持 4 张参考图");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("ratio", task.getAspectRatio());
        body.put("prompt", VideoPromptPolicy.compileForProvider(task.getPrompt(), "hfsy", images.size()));
        body.put("images", images);
        body.put("duration", duration);
        body.put("size", hfsySize(model, task.getRequestedResolution()));
        body.put("watermark", false);
        return body;
    }

    private String hfsySize(String model, String resolution) {
        if (!"sd-2.0-mini".equals(model)) return "large";
        return "480p".equalsIgnoreCase(resolution) ? "small" : "large";
    }

    private int[] agnesDimensions(String aspectRatio) {
        return switch (notBlank(aspectRatio) ? aspectRatio : "16:9") {
            case "9:16" -> new int[]{720, 1280};
            case "1:1" -> new int[]{720, 720};
            case "3:4" -> new int[]{720, 960};
            case "4:3" -> new int[]{960, 720};
            default -> new int[]{1280, 720};
        };
    }

    private PollResult pollApimart(VideoTaskEntity task) throws Exception {
        String apiKey = config.apimartApiKey();
        ensure(properties.isPaidProviderEnabled() && notBlank(apiKey), "APIMart 付费视频服务未由后端环境启用");
        String id = URLEncoder.encode(task.getUpstreamTaskId(), StandardCharsets.UTF_8);
        JsonNode response = sendJson(
            HttpRequest.newBuilder(URI.create(APIMART_BASE + "/v1/tasks/" + id))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build()
        );
        return parseApimartPollResult(response, task.getProgress() == null ? 10 : task.getProgress());
    }

    PollResult parseApimartPollResult(JsonNode response, int fallbackProgress) {
        String status = normalizeStatus(firstText(response, "data.status", "status", "data.state", "state"));
        int progress = firstInt(response, fallbackProgress, "data.progress", "progress");
        if ("failed".equals(status)) {
            return PollResult.failed(firstText(response, "data.error.message", "error.message", "data.error", "message"), compactApimartMetadata(response));
        }
        if (!"completed".equals(status)) {
            return PollResult.running(status, Math.max(10, Math.min(95, progress)), compactApimartMetadata(response));
        }
        String resultUrl = apimartResultUrl(response);
        ensure(notBlank(resultUrl), "APIMart 任务已完成但未返回视频地址");
        return PollResult.completed(resultUrl, null, null, decimal(response, "data.seconds", "seconds"), compactApimartMetadata(response));
    }

    String apimartTaskId(JsonNode response) {
        return firstText(response,
            "data.0.task_id", "data.0.id",
            "task_id", "data.task_id", "id", "data.id");
    }

    String apimartResultUrl(JsonNode response) {
        return firstText(response,
            "data.result.videos.0.url.0", "data.result.videos.0.url",
            "result.videos.0.url.0", "result.videos.0.url",
            "data.result.video_url", "data.result.url", "data.video_url", "data.url",
            "result.video_url", "result.url", "video_url", "url");
    }

    private PollResult pollAgnes(VideoTaskEntity task) throws Exception {
        String apiKey = config.agnesApiKeyFor(task);
        ensure(properties.isAgnesEnabled() && notBlank(apiKey), "Agnes 视频服务未由后端环境启用");
        String id = URLEncoder.encode(task.getUpstreamTaskId(), StandardCharsets.UTF_8);
        JsonNode response = sendJson(
            HttpRequest.newBuilder(URI.create(AGNES_BASE + "/agnesapi?video_id=" + id))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build()
        );
        String status = normalizeStatus(firstText(response, "status", "data.status", "state", "data.state"));
        if ("failed".equals(status)) {
            return PollResult.failed(firstText(
                response, "error.message", "data.error.message", "error", "data.error", "message", "data.message"
            ), compactMetadata(response));
        }
        if (!"completed".equals(status)) {
            int progress = firstInt(response, task.getProgress() == null ? 10 : task.getProgress(), "progress", "data.progress");
            return PollResult.running(status, Math.max(10, Math.min(95, progress)), compactMetadata(response));
        }
        String resultUrl = firstText(response, "metadata.url", "data.metadata.url", "url", "data.url");
        Integer width = firstNullableInt(response,
            "metadata.size_mapping.width", "data.metadata.size_mapping.width", "width", "data.width");
        Integer height = firstNullableInt(response,
            "metadata.size_mapping.height", "data.metadata.size_mapping.height", "height", "data.height");
        String size = firstText(response, "size", "data.size");
        int[] parsed = parseSize(size);
        if (width == null && parsed[0] > 0) width = parsed[0];
        if (height == null && parsed[1] > 0) height = parsed[1];
        ensure(notBlank(resultUrl), "Agnes 任务已完成但 metadata.url 为空");
        return PollResult.completed(resultUrl, width, height, decimal(response, "seconds", "data.seconds"), compactMetadata(response));
    }

    private PollResult pollHfsy(VideoTaskEntity task) throws Exception {
        String apiKey = config.hfsyApiKey();
        ensure(notBlank(apiKey), "HFSY 视频服务尚未配置 API Key");
        String id = URLEncoder.encode(task.getUpstreamTaskId(), StandardCharsets.UTF_8);
        JsonNode response = sendJson(
            HttpRequest.newBuilder(URI.create(HFSY_BASE + "/v1/video/query?id=" + id))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build()
        );
        return parseHfsyPollResult(response, task);
    }

    PollResult parseHfsyPollResult(JsonNode response, VideoTaskEntity task) {
        String status = normalizeStatus(firstText(response, "status", "data.status", "state", "data.state"));
        Map<String, Object> metadata = compactHfsyMetadata(response, task == null ? "" : task.getModel());
        if ("failed".equals(status)) {
            return PollResult.failed(firstText(
                response, "fail_reason", "data.fail_reason", "error.message", "data.error.message",
                "error", "data.error", "message", "data.message"
            ), metadata);
        }
        int fallbackProgress = task == null || task.getProgress() == null ? 10 : task.getProgress();
        if (!"completed".equals(status)) {
            int progress = firstInt(response, fallbackProgress, "progress", "data.progress");
            return PollResult.running(status, Math.max(10, Math.min(95, progress)), metadata);
        }
        String resultUrl = firstText(
            response, "result_url", "data.result_url", "result.url", "data.result.url",
            "video_url", "data.video_url", "url", "data.url"
        );
        ensure(notBlank(resultUrl), "HFSY 任务已完成但未返回视频地址");
        return PollResult.completed(
            resultUrl,
            firstNullableInt(response, "width", "data.width", "result.width", "data.result.width"),
            firstNullableInt(response, "height", "data.height", "result.height", "data.result.height"),
            decimal(response, "seconds", "data.seconds", "duration", "data.duration"),
            metadata
        );
    }

    String hfsyTaskId(JsonNode response) {
        return firstText(response,
            "task_id", "data.task_id", "id", "data.id",
            "result.task_id", "data.result.task_id");
    }

    private JsonNode sendJson(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw upstreamError(response);
        }
        return json.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
    }

    private UpstreamHttpException upstreamError(HttpResponse<String> response) {
        String requestId = firstHeader(response, "x-request-id", "request-id", "cf-ray");
        int retryAfterSeconds = retryAfterSeconds(response);
        String responseBody = safeResponseBody(response.body());
        return new UpstreamHttpException(response.statusCode(), retryAfterSeconds, requestId, responseBody);
    }

    private String firstHeader(HttpResponse<?> response, String... names) {
        for (String name : names) {
            String value = response.headers().firstValue(name).orElse("").trim();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private int retryAfterSeconds(HttpResponse<?> response) {
        String value = response.headers().firstValue("retry-after").orElse("").trim();
        if (value.isBlank()) return 0;
        try { return Math.max(0, Math.min(300, Integer.parseInt(value))); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String safeResponseBody(String value) {
        String sanitized = value == null ? "" : value.trim()
            .replaceAll("\\s+", " ")
            .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._-]+", "Bearer ***")
            .replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-***");
        return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
    }

    private List<String> absoluteUrls(List<String> urls) {
        List<String> result = new ArrayList<>();
        for (String url : urls) {
            if (notBlank(url)) result.add(absoluteUrl(url));
        }
        ensure(!result.isEmpty(), "图生视频需要至少一张商品图");
        return result;
    }

    private String absoluteUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        String base = properties.getPublicBaseUrl() == null ? "" : properties.getPublicBaseUrl().trim();
        ensure(notBlank(base), "本地商品图需要配置 VIDEO_PUBLIC_BASE_URL 后才能提交给上游");
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + (url.startsWith("/") ? url : "/" + url);
    }

    private Map<String, Object> compactMetadata(JsonNode response) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        copy(metadata, "size", firstText(response, "size", "data.size"));
        copy(metadata, "seconds", firstText(response, "seconds", "data.seconds"));
        copy(metadata, "mapped_resolution", firstText(response, "metadata.size_mapping.resolution", "data.metadata.size_mapping.resolution"));
        copy(metadata, "actual_cost", firstText(response, "metadata.cost", "data.metadata.cost", "cost", "data.cost"));
        copy(metadata, "upstream_actual_time_seconds", firstText(response, "actual_time", "data.actual_time"));
        copy(metadata, "upstream_completed_epoch", firstText(response, "completed", "data.completed"));
        copy(metadata, "upstream_result_expires_at", firstText(
            response, "result.videos.0.expires_at", "data.result.videos.0.expires_at"
        ));
        Integer width = firstNullableInt(response, "metadata.size_mapping.width", "data.metadata.size_mapping.width");
        Integer height = firstNullableInt(response, "metadata.size_mapping.height", "data.metadata.size_mapping.height");
        if (width != null) metadata.put("mapped_width", width);
        if (height != null) metadata.put("mapped_height", height);
        return metadata;
    }

    private Map<String, Object> compactApimartMetadata(JsonNode response) {
        Map<String, Object> metadata = compactMetadata(response);
        metadata.remove("actual_cost");
        BigDecimal credits = decimal(response, "data.credits_cost", "credits_cost");
        if (credits != null && credits.signum() >= 0) {
            metadata.put("actual_cost", AiImageCostCalculator.apimartCreditsToCny(credits).toPlainString());
            metadata.put("upstream_cost", credits.toPlainString());
            metadata.put("upstream_cost_unit", "APIMART_CREDIT");
            metadata.put("cost_conversion_rate", AiImageCostCalculator.apimartCreditCnyRate().toPlainString());
            metadata.put("cost_source", "upstream_credits_cost");
            metadata.put("upstream_credits_cost", credits.toPlainString());
            return metadata;
        }
        BigDecimal usd = decimal(response, "data.cost", "data.metadata.cost", "metadata.cost", "cost");
        if (usd != null && usd.signum() >= 0) {
            metadata.put("actual_cost", AiImageCostCalculator.apimartUsdToCny(usd).toPlainString());
            metadata.put("upstream_cost", usd.toPlainString());
            metadata.put("upstream_cost_unit", "USD");
            metadata.put("cost_conversion_rate", AiImageCostCalculator.apimartUsdCnyRate().toPlainString());
            metadata.put("cost_source", "upstream_cost");
        }
        return metadata;
    }

    private Map<String, Object> compactHfsyMetadata(JsonNode response, String model) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        copy(metadata, "upstream_status", firstText(response, "status", "data.status"));
        copy(metadata, "upstream_quota", firstText(response, "quota", "data.quota"));
        BigDecimal cost = switch (model == null ? "" : model) {
            case "sd-2.0-mini" -> new BigDecimal("1.200000");
            case "sd-2.0-fast-480" -> new BigDecimal("2.000000");
            case "sd-2.0-fast" -> new BigDecimal("3.000000");
            case "sd-2.0-480" -> new BigDecimal("3.000000");
            case "sd-2.0" -> new BigDecimal("4.000000");
            default -> BigDecimal.ZERO;
        };
        if (cost.signum() > 0) {
            metadata.put("actual_cost", cost.toPlainString());
            metadata.put("upstream_cost", cost.toPlainString());
            metadata.put("upstream_cost_unit", "CNY_PER_CALL");
            metadata.put("cost_source", "hfsy_public_fixed_price");
        }
        return metadata;
    }

    private void copy(Map<String, Object> target, String key, String value) {
        if (notBlank(value)) target.put(key, value);
    }

    private String firstText(JsonNode node, String... paths) {
        for (String path : paths) {
            JsonNode value = path(node, path);
            if (value != null && !value.isNull() && !value.isContainerNode()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    private int firstInt(JsonNode node, int fallback, String... paths) {
        Integer value = firstNullableInt(node, paths);
        return value == null ? fallback : value;
    }

    private Integer firstNullableInt(JsonNode node, String... paths) {
        for (String path : paths) {
            JsonNode value = path(node, path);
            if (value != null && value.canConvertToInt()) return value.asInt();
            if (value != null && value.isTextual()) {
                try { return Integer.parseInt(value.asText().replace("%", "").trim()); } catch (Exception ignored) { }
            }
        }
        return null;
    }

    private BigDecimal decimal(JsonNode node, String... paths) {
        String value = firstText(node, paths);
        if (value.isBlank()) return null;
        try { return new BigDecimal(value); } catch (NumberFormatException ignored) { return null; }
    }

    private JsonNode path(JsonNode root, String dotted) {
        JsonNode current = root;
        for (String part : dotted.split("\\.")) {
            if (current == null) return null;
            if (current.isArray()) {
                try {
                    current = current.get(Integer.parseInt(part));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            } else {
                current = current.get(part);
            }
        }
        return current;
    }

    private String normalizeStatus(String value) {
        String status = value == null ? "" : value.trim().toLowerCase();
        if (status.contains("success") || status.contains("complete") || status.contains("finish")) return "completed";
        if (status.contains("fail") || status.contains("error") || status.contains("cancel")) return "failed";
        if (status.contains("queue") || status.contains("pending") || status.contains("submit")) return "submitted";
        return "generating";
    }

    private int[] parseSize(String value) {
        if (value == null) return new int[] {0, 0};
        String normalized = value.toLowerCase().replace(" ", "");
        String[] parts = normalized.split("x");
        if (parts.length != 2) return new int[] {0, 0};
        try { return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])}; }
        catch (NumberFormatException ignored) { return new int[] {0, 0}; }
    }

    private void ensure(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }

    private Map<String, Object> metadata(VideoTaskEntity task) {
        if (task == null || task.getMetadata() == null || task.getMetadata().isBlank()) return Map.of();
        try {
            return json.readValue(task.getMetadata(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String upstreamResolution(String resolution) {
        return "4k".equalsIgnoreCase(resolution) ? "4K" : resolution;
    }

    private String apimartWebhookBaseUrl() {
        String secret = properties.getWebhookSecret() == null ? "" : properties.getWebhookSecret().trim();
        String base = properties.getPublicBaseUrl() == null ? "" : properties.getPublicBaseUrl().trim();
        if (!notBlank(secret) || !notBlank(base)) return "";
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return normalized + "/api/public/video-webhooks/apimart/"
            + URLEncoder.encode(secret, StandardCharsets.UTF_8);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String agnesPrompt(String prompt) {
        return (prompt == null ? "" : prompt.trim()) + """


            Product continuity requirements: keep the exact same product as the only subject in every frame. Preserve its identity,
            silhouette, geometry, proportions, materials, colors, logo, labels, printed text, small details, accessories and object count.
            Use one continuous shot with physically plausible motion. No cuts, no scene replacement, no viewpoint jump and no object transformation.
            All product details must remain temporally coherent from the first frame to the last frame.
            """;
    }

    private String agnesNegativePrompt() {
        return "product morphing, identity change, shape change, geometry change, proportion change, color shift, "
            + "material change, logo change, label change, text change, misspelled text, extra text, duplicated product, "
            + "missing parts, extra parts, object replacement, object count change, accessory count change, packaging change, "
            + "background replacement, scene cut, shot transition, camera jump, sudden zoom, flicker, jitter, frame inconsistency, "
            + "temporal inconsistency, warping, melting, stretching, deformation, disassembly, floating parts, impossible motion, "
            + "blur, low detail, watermark, subtitles";
    }

    public record Submission(String upstreamTaskId, String provider, String model) {}

    public static final class UpstreamHttpException extends IllegalStateException {
        private static final List<Integer> TRANSIENT_STATUS_CODES = Arrays.asList(
            408, 429, 500, 502, 503, 504, 520, 522, 524
        );

        private final int statusCode;
        private final int retryAfterSeconds;
        private final String requestId;
        private final String responseBody;

        UpstreamHttpException(int statusCode, int retryAfterSeconds, String requestId, String responseBody) {
            super(message(statusCode, requestId, responseBody));
            this.statusCode = statusCode;
            this.retryAfterSeconds = retryAfterSeconds;
            this.requestId = requestId == null ? "" : requestId;
            this.responseBody = responseBody == null ? "" : responseBody;
        }

        public int statusCode() {
            return statusCode;
        }

        public int retryAfterSeconds() {
            return retryAfterSeconds;
        }

        public String requestId() {
            return requestId;
        }

        public String responseBody() {
            return responseBody;
        }

        public boolean transientFailure() {
            return TRANSIENT_STATUS_CODES.contains(statusCode);
        }

        private static String message(int statusCode, String requestId, String responseBody) {
            StringBuilder message = new StringBuilder("视频上游接口返回 HTTP ").append(statusCode);
            if (requestId != null && !requestId.isBlank()) message.append(" request_id=").append(requestId);
            if (responseBody != null && !responseBody.isBlank()) message.append(" response=").append(responseBody);
            return message.toString();
        }
    }

    public record PollResult(
        String status,
        int progress,
        String resultUrl,
        Integer width,
        Integer height,
        BigDecimal seconds,
        String error,
        Map<String, Object> metadata
    ) {
        static PollResult running(String status, int progress) {
            return running(status, progress, Map.of());
        }

        static PollResult running(String status, int progress, Map<String, Object> metadata) {
            return new PollResult(status, progress, "", null, null, null, "", metadata);
        }

        static PollResult completed(String resultUrl, Integer width, Integer height, BigDecimal seconds, Map<String, Object> metadata) {
            return new PollResult("completed", 100, resultUrl, width, height, seconds, "", metadata);
        }

        static PollResult failed(String error, Map<String, Object> metadata) {
            return new PollResult("failed", 100, "", null, null, null, error, metadata);
        }
    }
}
