package com.openimage.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.SystemSettingEntity;
import com.openimage.domain.mapper.SystemSettingMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiRuntimeConfigService {
    private static final String SETTING_KEY = "ai_runtime_config";
    public static final String EXECUTION_HTTP = "http";
    public static final String EXECUTION_PULL_WORKER = "pull_worker";
    public static final String OFFLINE_QUEUE = "queue";
    public static final String OFFLINE_FALLBACK = "fallback";
    public static final BigDecimal LOCAL_IMAGE_COST_CNY = new BigDecimal("0.001000");
    private static final List<String> GPT_TEXT_MODELS = List.of("gpt-5.5", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna");
    private static final String DEFAULT_GPT_TEXT_MODEL = "gpt-5.5";

    private final SystemSettingMapper settings;
    private final ObjectMapper json;
    private final AiImageProperties imageDefaults;
    private final boolean textEnabledDefault;
    private final String textBaseUrlDefault;
    private final String textApiKeyDefault;
    private final String textModelDefault;
    private final HttpClient http;

    public AiRuntimeConfigService(
        SystemSettingMapper settings,
        ObjectMapper json,
        AiImageProperties imageDefaults,
        @Value("${openimage.ai.text.deepseek-enabled:false}") boolean textEnabledDefault,
        @Value("${openimage.ai.text.deepseek-base-url:https://api.deepseek.com}") String textBaseUrlDefault,
        @Value("${openimage.ai.text.deepseek-key:}") String textApiKeyDefault,
        @Value("${openimage.ai.text.deepseek-model:deepseek-v4-flash}") String textModelDefault
    ) {
        this.settings = settings;
        this.json = json;
        this.imageDefaults = imageDefaults;
        this.textEnabledDefault = textEnabledDefault;
        this.textBaseUrlDefault = trimTrailingSlash(blankToDefault(textBaseUrlDefault, "https://api.deepseek.com"));
        this.textApiKeyDefault = textApiKeyDefault == null ? "" : textApiKeyDefault.trim();
        this.textModelDefault = blankToDefault(textModelDefault, "deepseek-v4-flash");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    public Map<String, Object> adminView() {
        Map<String, Object> stored = readStored();
        List<Map<String, Object>> imageProviders = providerList(stored, "image");
        List<Map<String, Object>> textProviders = providerList(stored, "text");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("image_providers", adminProviderViews(imageProviders));
        row.put("text_providers", adminProviderViews(textProviders));
        row.put("image", adminImageView(effectiveImage(imageProviders)));
        row.put("text", adminTextView(effectiveText(textProviders)));
        row.put("can_rollback", stored.get("previous") instanceof Map<?, ?>);
        row.put("updated_at", text(stored.get("updated_at"), ""));
        return row;
    }

    public Map<String, Object> save(UpdateRequest request) {
        Map<String, Object> stored = readStored();
        List<Map<String, Object>> currentImage = providerList(stored, "image");
        List<Map<String, Object>> currentText = providerList(stored, "text");
        List<Map<String, Object>> image = mergeProviders(currentImage, request == null ? null : request.imageProviders(), request == null ? null : request.image(), "image");
        List<Map<String, Object>> text = mergeProviders(currentText, request == null ? null : request.textProviders(), request == null ? null : request.text(), "text");
        validateProviders(image, text);
        List<Map<String, Object>> imageArchive = mergeProviderArchive(stored, "image", currentImage, image);
        List<Map<String, Object>> textArchive = mergeProviderArchive(stored, "text", currentText, text);

        Map<String, Object> next = new LinkedHashMap<>();
        next.put("image_providers", image);
        next.put("text_providers", text);
        next.put("image_provider_archive", imageArchive);
        next.put("text_provider_archive", textArchive);
        next.put("image", effectiveProviderMap(image, "image"));
        next.put("text", effectiveProviderMap(text, "text"));
        next.put("previous", Map.of(
            "image_providers", currentImage,
            "text_providers", currentText,
            "image_provider_archive", providerArchive(stored, "image"),
            "text_provider_archive", providerArchive(stored, "text")
        ));
        next.put("updated_at", OffsetDateTime.now().toString());
        writeStored(next);
        return adminView();
    }

    public Map<String, Object> rollback() {
        Map<String, Object> stored = readStored();
        Object previous = stored.get("previous");
        if (!(previous instanceof Map<?, ?> previousMap)) {
            throw new IllegalArgumentException("暂无可回滚的 AI 配置");
        }
        List<Map<String, Object>> currentImage = providerList(stored, "image");
        List<Map<String, Object>> currentText = providerList(stored, "text");
        List<Map<String, Object>> image = providerListFromMap(previousMap, "image");
        List<Map<String, Object>> text = providerListFromMap(previousMap, "text");
        List<Map<String, Object>> imageArchive = providerArchiveFromMap(previousMap, "image");
        List<Map<String, Object>> textArchive = providerArchiveFromMap(previousMap, "text");

        Map<String, Object> next = new LinkedHashMap<>();
        next.put("image_providers", image);
        next.put("text_providers", text);
        next.put("image_provider_archive", imageArchive);
        next.put("text_provider_archive", textArchive);
        next.put("image", effectiveProviderMap(image, "image"));
        next.put("text", effectiveProviderMap(text, "text"));
        next.put("previous", Map.of(
            "image_providers", currentImage,
            "text_providers", currentText,
            "image_provider_archive", providerArchive(stored, "image"),
            "text_provider_archive", providerArchive(stored, "text")
        ));
        next.put("updated_at", OffsetDateTime.now().toString());
        writeStored(next);
        return adminView();
    }

    public Map<String, Object> validate(UpdateRequest request) {
        Map<String, Object> stored = readStored();
        List<Map<String, Object>> image = mergeProviders(providerList(stored, "image"), request == null ? null : request.imageProviders(), request == null ? null : request.image(), "image");
        List<Map<String, Object>> text = mergeProviders(providerList(stored, "text"), request == null ? null : request.textProviders(), request == null ? null : request.text(), "text");
        validateProviders(image, text);
        return Map.of("ok", true, "message", "AI 配置格式检查通过。为避免误扣费，保存前不会实际调用生图或文案模型。");
    }

    public Map<String, Object> probe(String kind, UpdateRequest request) {
        String normalizedKind = "text".equalsIgnoreCase(kind) ? "text" : "image";
        Map<String, Object> stored = readStored();
        List<Map<String, Object>> image = mergeProviders(providerList(stored, "image"), request == null ? null : request.imageProviders(), request == null ? null : request.image(), "image");
        List<Map<String, Object>> text = mergeProviders(providerList(stored, "text"), request == null ? null : request.textProviders(), request == null ? null : request.text(), "text");
        return "text".equals(normalizedKind)
            ? probeText(effectiveText(text), text)
            : probeImage(effectiveImage(image), image);
    }

    public EffectiveImageConfig effectiveImage() {
        return effectiveImage(providerList(readStored(), "image"));
    }

    public EffectiveImageConfig effectiveImageForTask(String providerId, String provider, String model) {
        return effectiveImageForTask(providerId, provider, model, "");
    }

    public EffectiveImageConfig effectiveImageForTask(String providerId, String provider, String model, String size) {
        List<EffectiveImageConfig> chain = imageProviderChainForTask(providerId, provider, model, size);
        return chain.isEmpty() ? imageConfig(Map.of(), false) : chain.get(0);
    }

    public List<EffectiveImageConfig> imageProviderChainForTask(String providerId, String provider, String model) {
        return imageProviderChainForTask(providerId, provider, model, "");
    }

    public List<EffectiveImageConfig> imageProviderChainForTask(String providerId, String provider, String model, String size) {
        Map<String, Object> stored = readStored();
        List<Map<String, Object>> providers = providerList(stored, "image");
        List<Map<String, Object>> archive = providerArchive(stored, "image");
        Map<String, Object> raw = taskImageProvider(providers, providerId, provider, model);
        if (raw.isEmpty()) {
            raw = taskImageProvider(archive, providerId, provider, model);
        }
        List<EffectiveImageConfig> chain = new ArrayList<>();
        appendEnabledPullImageProviders(chain, providers, imageTier(size));
        Map<String, Object> primaryRaw = raw.isEmpty() ? firstEnabledImageConfigured(providers) : raw;
        boolean targetYunfei = isYunfeiTarget(providerId, provider) || isYunfeiProvider(primaryRaw);
        String tier = imageTier(size);
        if (targetYunfei) {
            Map<String, Object> yunfei = yunfeiProviderForSize(providers, tier, true);
            boolean forceEnabled = false;
            if (yunfei.isEmpty()) {
                yunfei = yunfeiProviderForSize(archive, tier, false);
                forceEnabled = !yunfei.isEmpty();
            }
            EffectiveImageConfig config = imageConfig(yunfei, forceEnabled);
            if (config.enabledAndConfigured() && !containsImageProvider(chain, config)) {
                chain.add(config);
            }
            appendImageFallbacks(chain, providers, tier);
            return chain;
        }
        boolean forcePrimary = !raw.isEmpty()
            && !EXECUTION_PULL_WORKER.equals(executionMode(primaryRaw));
        EffectiveImageConfig primary = imageConfig(primaryRaw, forcePrimary);
        if (primary.enabledAndConfigured()
            && supportsImageSize(primaryRaw, tier)
            && !containsImageProvider(chain, primary)) {
            chain.add(primary);
        }
        appendImageFallbacks(chain, providers, tier);
        return chain;
    }

    private void appendEnabledPullImageProviders(
        List<EffectiveImageConfig> chain,
        List<Map<String, Object>> providers,
        String tier
    ) {
        for (Map<String, Object> row : orderImageProviders(providers)) {
            if (!EXECUTION_PULL_WORKER.equals(executionMode(row)) || !supportsImageSize(row, tier)) {
                continue;
            }
            EffectiveImageConfig config = imageConfig(row, false);
            if (config.enabledAndConfigured() && !containsImageProvider(chain, config)) {
                chain.add(config);
            }
        }
    }

    private void appendImageFallbacks(List<EffectiveImageConfig> chain, List<Map<String, Object>> providers, String tier) {
        for (Map<String, Object> row : orderImageProviders(providers)) {
            if (!supportsImageSize(row, tier)) {
                continue;
            }
            EffectiveImageConfig config = imageConfig(row, false);
            if (!config.enabledAndConfigured()) {
                continue;
            }
            if (!containsImageProvider(chain, config)) {
                chain.add(config);
            }
        }
    }

    private boolean containsImageProvider(
        List<EffectiveImageConfig> chain,
        EffectiveImageConfig candidate
    ) {
        for (EffectiveImageConfig existing : chain) {
            if (sameProvider(candidate, existing.id(), existing.provider())) {
                return true;
            }
        }
        return false;
    }

    public EffectiveTextConfig effectiveText() {
        return effectiveText(providerList(readStored(), "text"));
    }

    public List<EffectiveTextConfig> textProviderChain() {
        List<EffectiveTextConfig> chain = new ArrayList<>();
        for (Map<String, Object> row : orderProviders(providerList(readStored(), "text"))) {
            EffectiveTextConfig config = textConfig(row);
            if (config.enabledAndConfigured()) {
                chain.add(config);
            }
        }
        return chain;
    }

    public List<WorkerProviderConfig> pullWorkerProviders() {
        Map<String, Object> stored = readStored();
        List<WorkerProviderConfig> providers = new ArrayList<>();
        for (Map<String, Object> row : orderImageProviders(providerList(stored, "image"))) {
            EffectiveImageConfig config = imageConfig(row, false);
            if (config.enabledAndConfigured() && config.pullWorker()) {
                providers.add(config.workerConfig("image"));
            }
        }
        for (Map<String, Object> row : orderProviders(providerList(stored, "text"))) {
            EffectiveTextConfig config = textConfig(row);
            if (config.enabledAndConfigured() && config.pullWorker()) {
                providers.add(config.workerConfig("text"));
            }
        }
        return providers;
    }

    public WorkerProviderConfig pullWorkerProvider(String providerId, String capability) {
        return pullWorkerProviders().stream()
            .filter(provider -> provider.id().equals(providerId) && provider.capability().equals(capability))
            .findFirst()
            .orElse(null);
    }

    private EffectiveImageConfig effectiveImage(List<Map<String, Object>> providers) {
        Map<String, Object> raw = firstEnabledImageConfigured(providers);
        return imageConfig(raw, false);
    }

    private EffectiveImageConfig imageConfig(Map<String, Object> raw, boolean forceEnabled) {
        boolean enabled = !raw.isEmpty() && (forceEnabled || bool(raw.get("enabled"), false));
        String apiKey = enabled ? providerApiKey(raw) : "";
        String provider = blankToDefault(text(raw.get("provider"), imageDefaults.providerName()), "hfsyapi");
        String providerId = text(raw.get("id"), "");
        String baseUrl = trimTrailingSlash(blankToDefault(text(raw.get("base_url"), imageDefaults.baseUrlValue()), "https://www.hfsyapi.cn"));
        String model = isYunfeiProvider(raw) || isYunfeiProvider(provider, baseUrl)
            ? "gpt-image-2"
            : blankToDefault(text(raw.get("model"), imageDefaults.modelValue()), "gpt-image-2");
        BigDecimal defaultActualCost = isLocalImageProvider(providerId, provider)
            ? LOCAL_IMAGE_COST_CNY
            : imageDefaults.actualCostValue();
        return new EffectiveImageConfig(
            enabled,
            provider,
            baseUrl,
            apiKey,
            model,
            imageDefaults.sizeValue(),
            imageDefaults.responseFormatValue(),
            decimalValue(raw.get("actual_cost"), defaultActualCost),
            imageDefaults.publicBaseUrl(),
            intValue(raw.get("timeout_seconds"), imageDefaults.timeoutSecondsValue()),
            providerId,
            executionMode(raw),
            localWaitSeconds(raw),
            offlineAction(raw),
            leaseSeconds(raw),
            maxConcurrency(raw)
        );
    }

    private Map<String, Object> taskImageProvider(List<Map<String, Object>> providers, String providerId, String provider, String model) {
        String targetId = text(providerId, "").trim();
        String targetProvider = text(provider, "").trim();
        String targetModel = text(model, "").trim();
        if (targetId.isBlank() && targetProvider.isBlank() && targetModel.isBlank()) {
            return Map.of();
        }
        Map<String, Object> providerOnly = Map.of();
        Map<String, Object> modelOnly = Map.of();
        for (Map<String, Object> row : providers) {
            String rowId = text(row.get("id"), "").trim();
            if (!targetId.isBlank() && rowId.equalsIgnoreCase(targetId)) {
                return row;
            }
            String rowProvider = text(row.get("provider"), "").trim();
            String rowModel = text(row.get("model"), "").trim();
            boolean providerMatches = !targetProvider.isBlank() && rowProvider.equalsIgnoreCase(targetProvider);
            if (!providerMatches && isYunfeiProvider(targetProvider, "") && isYunfeiProvider(row)) {
                providerMatches = true;
            }
            boolean modelMatches = !targetModel.isBlank() && compatibleImageModel(rowModel, targetModel);
            if (providerMatches && modelMatches) {
                return row;
            }
            if (providerMatches && providerOnly.isEmpty()) {
                providerOnly = row;
            }
            if (modelMatches && modelOnly.isEmpty()) {
                modelOnly = row;
            }
        }
        if (!providerOnly.isEmpty()) {
            return providerOnly;
        }
        return modelOnly;
    }

    private boolean compatibleImageModel(String configuredModel, String taskModel) {
        String configured = text(configuredModel, "").trim();
        String task = text(taskModel, "").trim();
        if (configured.equalsIgnoreCase(task)) {
            return true;
        }
        return isGptImage2Family(configured) && isGptImage2Family(task);
    }

    private boolean sameProvider(EffectiveImageConfig config, String providerId, String provider) {
        String leftId = text(config.id(), "").trim();
        String rightId = text(providerId, "").trim();
        if (!leftId.isBlank() && !rightId.isBlank()) {
            return leftId.equalsIgnoreCase(rightId);
        }
        String leftProvider = text(config.provider(), "").trim();
        String rightProvider = text(provider, "").trim();
        return !leftProvider.isBlank() && !rightProvider.isBlank() && leftProvider.equalsIgnoreCase(rightProvider);
    }

    private boolean isGptImage2Family(String value) {
        return "gpt-image-2".equalsIgnoreCase(value) || "gpt-image-2pro".equalsIgnoreCase(value);
    }

    private EffectiveTextConfig effectiveText(List<Map<String, Object>> providers) {
        Map<String, Object> raw = firstEnabledTextConfigured(providers);
        return textConfig(raw);
    }

    private EffectiveTextConfig textConfig(Map<String, Object> raw) {
        boolean enabled = raw != null && !raw.isEmpty() && bool(raw.get("enabled"), false);
        String provider = blankToDefault(text(raw == null ? null : raw.get("provider"), "deepseek"), "deepseek");
        String baseUrl = trimTrailingSlash(blankToDefault(text(raw == null ? null : raw.get("base_url"), textBaseUrlDefault), "https://api.deepseek.com"));
        String model = blankToDefault(text(raw == null ? null : raw.get("model"), textModelDefault), "deepseek-v4-flash");
        if (isOpenAiTextProvider(provider, baseUrl)) {
            model = normalizeGptTextModel(model);
        }
        return new EffectiveTextConfig(
            enabled,
            provider,
            baseUrl,
            enabled ? providerApiKey(raw) : "",
            model,
            enabled ? intValue(raw.get("timeout_seconds"), 45) : 45,
            text(raw == null ? null : raw.get("id"), ""),
            executionMode(raw),
            localWaitSeconds(raw),
            offlineAction(raw),
            leaseSeconds(raw),
            maxConcurrency(raw)
        );
    }

    private List<Map<String, Object>> providerList(Map<String, Object> stored, String kind) {
        String listKey = kind + "_providers";
        List<Map<String, Object>> rows = new ArrayList<>();
        Object value = stored.get(listKey);
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> row = normalizeProvider(safeMap(item), kind);
                if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
        }
        if (!rows.isEmpty()) {
            return orderPolicy("image".equals(kind) ? mergeImageEnvDefaults(rows) : mergeTextEnvDefaults(rows), kind);
        }
        Map<String, Object> legacy = safeMap(stored.get(kind));
        if (!legacy.isEmpty()) {
            List<Map<String, Object>> legacyRows = List.of(normalizeProvider(legacy, kind));
            return orderPolicy("image".equals(kind) ? mergeImageEnvDefaults(legacyRows) : mergeTextEnvDefaults(legacyRows), kind);
        }
        return defaultProviders(kind);
    }

    private List<Map<String, Object>> providerListFromMap(Map<?, ?> map, String kind) {
        Map<String, Object> row = new LinkedHashMap<>();
        map.forEach((key, value) -> row.put(String.valueOf(key), value));
        return providerList(row, kind);
    }

    private List<Map<String, Object>> providerArchive(Map<String, Object> stored, String kind) {
        String listKey = kind + "_provider_archive";
        List<Map<String, Object>> rows = new ArrayList<>();
        Object value = stored.get(listKey);
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> row = normalizeProvider(safeMap(item), kind);
                if (!row.isEmpty() && notBlank(text(row.get("id"), "")) && notBlank(text(row.get("api_key"), ""))) {
                    row.put("enabled", false);
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private List<Map<String, Object>> providerArchiveFromMap(Map<?, ?> map, String kind) {
        Map<String, Object> row = new LinkedHashMap<>();
        map.forEach((key, value) -> row.put(String.valueOf(key), value));
        return providerArchive(row, kind);
    }

    private List<Map<String, Object>> mergeProviders(List<Map<String, Object>> current, List<ProviderRequest> requested, ProviderRequest legacyRequest, String kind) {
        if (requested == null || requested.isEmpty()) {
            if (legacyRequest == null) {
                return orderPolicy(current.isEmpty() ? defaultProviders(kind) : current, kind);
            }
            Map<String, Object> merged = mergeProvider(current.isEmpty() ? defaultProvider(kind) : current.get(0), legacyRequest, kind);
            List<Map<String, Object>> next = new ArrayList<>(current);
            if (next.isEmpty()) {
                next.add(merged);
            } else {
                next.set(0, merged);
            }
            return orderPolicy(next, kind);
        }
        List<Map<String, Object>> next = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            ProviderRequest request = requested.get(index);
            Map<String, Object> existing = findExisting(current, request == null ? null : request.id(), index, kind);
            next.add(mergeProvider(existing, request, kind));
        }
        return orderPolicy(next, kind);
    }

    private List<Map<String, Object>> mergeProviderArchive(Map<String, Object> stored, String kind, List<Map<String, Object>> current, List<Map<String, Object>> next) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : providerArchive(stored, kind)) {
            byId.put(text(row.get("id"), ""), new LinkedHashMap<>(row));
        }
        List<String> nextIds = next.stream().map(row -> text(row.get("id"), "")).filter(this::notBlank).toList();
        for (Map<String, Object> row : current) {
            String id = text(row.get("id"), "");
            if (id.isBlank() || nextIds.contains(id) || !notBlank(providerApiKey(row))) {
                continue;
            }
            Map<String, Object> archived = new LinkedHashMap<>(row);
            archived.put("enabled", false);
            byId.put(id, archived);
        }
        return new ArrayList<>(byId.values());
    }

    private Map<String, Object> mergeProvider(Map<String, Object> current, ProviderRequest request, String kind) {
        Map<String, Object> next = new LinkedHashMap<>(normalizeProvider(current, kind));
        if (request == null) {
            return next;
        }
        next.put("id", notBlank(request.id()) ? request.id().trim() : text(next.get("id"), newProviderId(kind)));
        if (request.enabled() != null) {
            next.put("enabled", request.enabled());
        }
        if (notBlank(request.name())) {
            next.put("name", request.name().trim());
        }
        if (notBlank(request.provider())) {
            next.put("provider", request.provider().trim());
        }
        if (notBlank(request.baseUrl())) {
            next.put("base_url", trimTrailingSlash(request.baseUrl().trim()));
        }
        if (notBlank(request.model())) {
            next.put("model", request.model().trim());
        }
        if (notBlank(request.apiKey()) && !request.apiKey().contains("*")) {
            next.put("api_key", request.apiKey().trim());
        }
        String apiKeyRef = notBlank(request.apiKeyRef()) ? request.apiKeyRef().trim() : "";
        if (notBlank(request.envKey())) {
            next.put("env_key", request.envKey().trim());
        } else if (notBlank(apiKeyRef)) {
            next.put("env_key", apiKeyRef);
        }
        if (notBlank(apiKeyRef)) {
            next.put("api_key_ref", apiKeyRef);
        }
        if (request.priority() != null) {
            next.put("priority", request.priority());
        }
        if (request.timeoutSeconds() != null) {
            next.put("timeout_seconds", request.timeoutSeconds());
        }
        if (notBlank(request.executionMode())) {
            next.put("execution_mode", request.executionMode().trim());
        }
        if (request.localWaitSeconds() != null) {
            next.put("local_wait_seconds", request.localWaitSeconds());
        }
        if (notBlank(request.offlineAction())) {
            next.put("offline_action", request.offlineAction().trim());
        }
        if (request.leaseSeconds() != null) {
            next.put("lease_seconds", request.leaseSeconds());
        }
        if (request.maxConcurrency() != null) {
            next.put("max_concurrency", request.maxConcurrency());
        }
        if (request.actualCost() != null) {
            next.put("actual_cost", request.actualCost());
        }
        if (notBlank(request.supportedSizes())) {
            next.put("supported_sizes", request.supportedSizes().trim());
        }
        if (request.notes() != null) {
            next.put("notes", request.notes().trim());
        }
        return normalizeProvider(next, kind);
    }

    private Map<String, Object> findExisting(List<Map<String, Object>> current, String id, int index, String kind) {
        if (notBlank(id)) {
            for (Map<String, Object> item : current) {
                if (id.trim().equals(text(item.get("id"), ""))) {
                    return item;
                }
            }
        }
        if (index >= 0 && index < current.size()) {
            return current.get(index);
        }
        return editableProvider(kind);
    }

    private List<Map<String, Object>> orderPolicy(List<Map<String, Object>> rows, String kind) {
        return "image".equals(kind) ? orderImageProviders(rows) : orderProviders(rows);
    }

    private Map<String, Object> firstEnabledImageConfigured(List<Map<String, Object>> providers) {
        for (Map<String, Object> row : orderImageProviders(providers)) {
            if (bool(row.get("enabled"), false) && providerConfigured(row)) {
                return row;
            }
        }
        return Map.of();
    }

    private Map<String, Object> firstEnabledTextConfigured(List<Map<String, Object>> providers) {
        for (Map<String, Object> row : orderProviders(providers)) {
            if (bool(row.get("enabled"), false) && providerConfigured(row)) {
                return row;
            }
        }
        return Map.of();
    }

    private boolean isYunfeiProvider(Map<String, Object> row) {
        String text = providerText(row);
        return text.contains("yunfei") || text.contains("img.yunfei.best");
    }

    private boolean isYunfeiProvider(String provider, String baseUrl) {
        String text = ((provider == null ? "" : provider) + " " + (baseUrl == null ? "" : baseUrl)).toLowerCase();
        return text.contains("yunfei") || text.contains("img.yunfei.best");
    }

    private boolean isYunfeiTarget(String providerId, String provider) {
        return isYunfeiProvider(text(providerId, ""), text(provider, ""));
    }

    private Map<String, Object> yunfeiProviderForSize(List<Map<String, Object>> providers, String tier, boolean requireEnabled) {
        boolean hires = "2K".equals(tier) || "4K".equals(tier);
        Map<String, Object> generic = Map.of();
        for (Map<String, Object> row : orderImageProviders(providers)) {
            if (!isYunfeiProvider(row)) {
                continue;
            }
            if (requireEnabled && !bool(row.get("enabled"), false)) {
                continue;
            }
            if (!notBlank(providerApiKey(row))) {
                continue;
            }
            if (hires && isYunfeiHiresProvider(row)) {
                return row;
            }
            if (!hires && isYunfeiOneKProvider(row)) {
                return row;
            }
            if (supportsImageSize(row, tier) && generic.isEmpty()) {
                generic = row;
            }
        }
        return generic;
    }

    private boolean supportsImageSize(Map<String, Object> row, String tier) {
        if (tier.isBlank()) {
            return true;
        }
        String supported = text(row.get("supported_sizes"), "");
        if (supported.isBlank()) {
            return true;
        }
        for (String part : supported.split("[,;/\\s]+")) {
            if (tier.equals(part.trim().toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    private String imageTier(String size) {
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

    private boolean isYunfeiOneKProvider(Map<String, Object> row) {
        String text = providerText(row);
        String supported = text(row.get("supported_sizes"), "").trim().toUpperCase();
        return text.contains("1k") || "1K".equals(supported);
    }

    private boolean isYunfeiHiresProvider(Map<String, Object> row) {
        String text = providerText(row);
        return text.contains("hires") || text.contains("2k") || text.contains("4k");
    }

    private String providerText(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        return (text(row.get("id"), "") + " " + text(row.get("name"), "") + " " + text(row.get("provider"), "") + " " + text(row.get("base_url"), "")).toLowerCase();
    }

    private Map<String, Object> effectiveProviderMap(List<Map<String, Object>> providers, String kind) {
        Map<String, Object> effective = "image".equals(kind) ? firstEnabledImageConfigured(providers) : firstEnabledTextConfigured(providers);
        return effective.isEmpty() ? disabledProvider(kind) : effective;
    }

    private void validateProviders(List<Map<String, Object>> imageProviders, List<Map<String, Object>> textProviders) {
        validateProviderList("作图", imageProviders, true);
        validateProviderList("文案", textProviders, true);
    }

    private void validateProviderList(String label, List<Map<String, Object>> providers, boolean allowMultiple) {
        int enabledCount = 0;
        for (Map<String, Object> row : providers) {
            if (!bool(row.get("enabled"), false)) {
                continue;
            }
            enabledCount += 1;
            validateProvider(label, row);
        }
        if (!allowMultiple && enabledCount > 1) {
            throw new IllegalArgumentException(label + "服务一次只能启用一个供应商");
        }
    }

    private Map<String, Object> probeImage(EffectiveImageConfig config, List<Map<String, Object>> providers) {
        validateProviderList("作图", providers, true);
        if (!config.enabledAndConfigured()) {
            throw new IllegalArgumentException("作图 AI 当前全部禁用或未配置密钥");
        }
        if (config.pullWorker()) {
            return probeResult("image", config.provider(), config.model(), "pull_worker", "本机 Worker 配置检查通过；未调用任何真实模型。", 0);
        }
        String prompt = "Create a simple clean ecommerce product test image: one small blue cube on a white studio background, realistic lighting, no text, no logo, no watermark.";
        String size = imageProbeSize(config);
        boolean gemini = isGeminiImage(config.provider(), config.model());
        String endpoint = gemini
            ? endpoint(config.baseUrl(), "/v1beta/models/" + AiImageResponseAdapter.pathSegment(config.model()) + ":generateContent")
            : endpoint(config.baseUrl(), "/v1/images/generations");
        Map<String, Object> body = gemini ? geminiImageProbeBody(prompt, size) : imageProbeBody(config, prompt, size);
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(jsonRequest(endpoint, config.apiKey(), body, Math.min(Math.max(config.timeoutSeconds(), 30), 360)), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("作图真实测试失败：" + response.statusCode() + " " + summarize(response.body(), 360));
            }
            JsonNode payload = json.readTree(response.body());
            payload = resolveImageProbe(config, payload);
            if (AiImageResponseAdapter.hasFailure(payload)) {
                throw new IllegalArgumentException("作图真实测试失败：" + summarize(AiImageResponseAdapter.failureSummary(payload), 360));
            }
            if (AiImageResponseAdapter.firstImage(payload).isEmpty()) {
                throw new IllegalArgumentException("作图真实测试失败：上游未返回图片 URL 或 base64 图片数据");
            }
            return probeResult("image", config.provider(), config.model(), endpoint, "作图真实测试通过，上游已返回图片数据。", elapsedMillis(started));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("作图真实测试失败：" + summarize(exception.getMessage(), 360));
        }
    }

    private String imageProbeSize(EffectiveImageConfig config) {
        if (isYunfeiHiresProvider(Map.of("provider", config.provider(), "base_url", config.baseUrl()))) {
            return "2048x2048";
        }
        return AiImageSizes.normalizeGenerationSize(config.size(), AiImageSizes.DEFAULT_SIZE);
    }

    private Map<String, Object> imageProbeBody(EffectiveImageConfig config, String prompt, String size) {
        if (AiImageResponseAdapter.isAishuch(config.provider(), config.baseUrl())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", gptImage2Model(config.model()));
            body.put("prompt", prompt);
            body.put("size", size);
            body.put("aspectRatio", aspectRatio(size));
            body.put("resolution", resolutionLabel(size).toLowerCase());
            body.put("n", 1);
            return body;
        }
        if (AiImageResponseAdapter.isXinZhiZhu(config.provider(), config.baseUrl())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", gptImage2Model(config.model()));
            body.put("prompt", prompt);
            body.put("size", xinZhiZhuRequestSize(size));
            body.put("quality", "auto");
            body.put("output_format", "png");
            body.put("n", 1);
            return body;
        }
        if (AiImageResponseAdapter.isRuaChat(config.provider(), config.baseUrl())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", gptImage2Model(config.model()));
            body.put("prompt", prompt);
            body.put("size", ruaRequestSize(size));
            body.put("output_format", "png");
            body.put("quality", "auto");
            body.put("moderation", "low");
            body.put("n", 1);
            return body;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", imageProbeModel(config, size));
        body.put("prompt", prompt);
        body.put("size", size);
        body.put("response_format", config.responseFormat());
        body.put("n", 1);
        return body;
    }

    private Map<String, Object> geminiImageProbeBody(String prompt, String size) {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", prompt);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("parts", List.of(textPart));
        Map<String, Object> imageConfig = new LinkedHashMap<>();
        imageConfig.put("aspectRatio", aspectRatio(size));
        imageConfig.put("resolution", resolutionLabel(size));
        return Map.of(
            "contents", List.of(content),
            "generationConfig", Map.of("imageConfig", imageConfig)
        );
    }

    private JsonNode resolveImageProbe(EffectiveImageConfig config, JsonNode payload) throws Exception {
        if (AiImageResponseAdapter.firstImage(payload).isPresent() || AiImageResponseAdapter.hasFailure(payload)) {
            return payload;
        }
        boolean shouldPoll = AiImageResponseAdapter.isAishuch(config.provider(), config.baseUrl())
            || AiImageResponseAdapter.status(payload).map(AiImageResponseAdapter::isPendingStatus).orElse(false);
        if (!shouldPoll) {
            return payload;
        }
        String taskId = AiImageResponseAdapter.firstTaskId(payload).orElse("");
        if (taskId.isBlank()) {
            return payload;
        }
        if (!AiImageResponseAdapter.isAishuch(config.provider(), config.baseUrl())) {
            throw new IllegalArgumentException("作图真实测试返回异步任务 " + taskId + "，但当前供应商未配置任务查询适配");
        }
        return pollAishuchProbe(config, taskId);
    }

    private JsonNode pollAishuchProbe(EffectiveImageConfig config, String taskId) throws Exception {
        String taskEndpoint = endpoint(config.baseUrl(), "/v1/tasks/" + AiImageResponseAdapter.pathSegment(taskId));
        int totalSeconds = Math.min(Math.max(config.timeoutSeconds(), 60), 360);
        long deadline = System.nanoTime() + Duration.ofSeconds(totalSeconds).toNanos();
        JsonNode latest = null;
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            attempt += 1;
            HttpResponse<String> response = http.send(jsonGetRequest(taskEndpoint, config.apiKey(), 45), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("作图任务查询失败：" + response.statusCode() + " " + summarize(response.body(), 360));
            }
            latest = json.readTree(response.body());
            if (AiImageResponseAdapter.firstImage(latest).isPresent()) {
                return latest;
            }
            if (AiImageResponseAdapter.hasFailure(latest)) {
                throw new IllegalArgumentException("作图真实测试失败：" + summarize(AiImageResponseAdapter.failureSummary(latest), 360));
            }
            String status = AiImageResponseAdapter.status(latest).orElse("");
            if (!status.isBlank() && !AiImageResponseAdapter.isPendingStatus(status) && !"completed".equalsIgnoreCase(status) && !"success".equalsIgnoreCase(status)) {
                throw new IllegalArgumentException("作图任务状态异常：" + summarize(status, 80));
            }
            sleepBeforeNextPoll(attempt);
        }
        String status = latest == null ? "无任务响应" : AiImageResponseAdapter.status(latest).orElse("未知");
        throw new IllegalArgumentException("作图真实测试超时，上游任务状态：" + summarize(status, 80));
    }

    private Map<String, Object> probeText(EffectiveTextConfig config, List<Map<String, Object>> providers) {
        validateProviderList("文案", providers, true);
        if (!config.enabledAndConfigured()) {
            throw new IllegalArgumentException("文案 AI 当前全部禁用或未配置密钥");
        }
        if (config.pullWorker()) {
            return probeResult("text", config.provider(), config.model(), "pull_worker", "本机 Worker 配置检查通过；未调用任何真实模型。", 0);
        }
        String endpoint = chatEndpoint(config);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", List.of(
            Map.of("role", "system", "content", "You are a health check endpoint. Reply with exactly: OK"),
            Map.of("role", "user", "content", "Return OK.")
        ));
        body.put("temperature", 0);
        body.put("max_tokens", 16);
        body.put("stream", false);
        if (isDeepSeekCompatible(config.provider(), config.model())) {
            body.put("thinking", Map.of("type", "disabled"));
        }
        long started = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(jsonRequest(endpoint, config.apiKey(), body, Math.max(10, Math.min(config.timeoutSeconds(), 90))), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("文案真实测试失败：" + response.statusCode() + " " + summarize(response.body(), 360));
            }
            JsonNode payload = json.readTree(response.body());
            JsonNode error = payload.get("error");
            if (error != null && !error.isNull()) {
                throw new IllegalArgumentException("文案真实测试失败：" + summarize(error.toString(), 360));
            }
            String content = payload.path("choices").path(0).path("message").path("content").asText("").trim();
            if (content.isBlank()) {
                throw new IllegalArgumentException("文案真实测试失败：上游未返回可读文本");
            }
            return probeResult("text", config.provider(), config.model(), endpoint, "文案真实测试通过，上游返回：" + summarize(content, 80), elapsedMillis(started));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("文案真实测试失败：" + summarize(exception.getMessage(), 360));
        }
    }

    private HttpRequest jsonRequest(String endpoint, String apiKey, Map<String, Object> body, int timeoutSeconds) throws Exception {
        return HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
    }

    private HttpRequest jsonGetRequest(String endpoint, String apiKey, int timeoutSeconds) {
        return HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .GET()
            .build();
    }

    private void sleepBeforeNextPoll(int attempt) {
        try {
            Thread.sleep(attempt <= 3 ? 3000L : 5000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("作图任务查询被中断");
        }
    }

    private String endpoint(String baseUrl, String path) {
        String normalized = trimTrailingSlash(baseUrl);
        if (normalized.endsWith("/v1") && path.startsWith("/v1/")) {
            return normalized + path.substring(3);
        }
        if (normalized.endsWith("/v1beta") && path.startsWith("/v1beta/")) {
            return normalized + path.substring(7);
        }
        return normalized + path;
    }

    private String chatEndpoint(EffectiveTextConfig config) {
        String normalized = trimTrailingSlash(config.baseUrl());
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/chat/completions";
        }
        if (isDeepSeekCompatible(config.provider(), config.model())) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    private boolean isDeepSeekCompatible(String provider, String model) {
        String providerText = provider == null ? "" : provider.toLowerCase();
        String modelText = model == null ? "" : model.toLowerCase();
        return providerText.contains("deepseek") || modelText.contains("deepseek");
    }

    private boolean isGeminiImage(String provider, String model) {
        String text = ((provider == null ? "" : provider) + " " + (model == null ? "" : model)).toLowerCase();
        return text.contains("gemini");
    }

    private String gptImage2Model(String model) {
        String normalized = blankToDefault(model, "gpt-image-2").trim();
        return isGptImage2Family(normalized) ? "gpt-image-2" : normalized;
    }

    private String imageProbeModel(EffectiveImageConfig config, String size) {
        String normalized = blankToDefault(config.model(), "gpt-image-2").trim();
        if (!isGptImage2Family(normalized)) {
            return normalized;
        }
        if (!isHfsyapi(config.provider(), config.baseUrl())) {
            return "gpt-image-2";
        }
        AiImageSizes.Size parsed = AiImageSizes.parse(size);
        if (parsed == null) {
            return normalized;
        }
        return Math.max(parsed.width(), parsed.height()) >= 1800 ? "gpt-image-2pro" : "gpt-image-2";
    }

    private boolean isHfsyapi(String provider, String baseUrl) {
        String text = ((provider == null ? "" : provider) + " " + (baseUrl == null ? "" : baseUrl)).toLowerCase();
        return text.contains("hfsyapi");
    }

    private String aspectRatio(String size) {
        AiImageSizes.Size parsed = AiImageSizes.parse(size);
        if (parsed == null) {
            return "1:1";
        }
        int gcd = gcd(parsed.width(), parsed.height());
        return (parsed.width() / gcd) + ":" + (parsed.height() / gcd);
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

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private Map<String, Object> probeResult(String kind, String provider, String model, String endpoint, String message, long latencyMillis) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ok", true);
        row.put("kind", kind);
        row.put("provider", provider);
        row.put("model", model);
        row.put("endpoint", endpoint);
        row.put("message", message);
        row.put("latency_ms", latencyMillis);
        return row;
    }

    private void validateProvider(String label, Map<String, Object> provider) {
        String model = text(provider.get("model"), "");
        if (model.isBlank()) {
            throw new IllegalArgumentException(label + "模型不能为空");
        }
        if (EXECUTION_PULL_WORKER.equals(executionMode(provider))) {
            String providerId = text(provider.get("id"), "");
            String providerName = text(provider.get("provider"), "");
            if ("local-cliproxy-image".equals(providerId)
                || "local-cliproxy-image".equals(providerName)) {
                if (!"gpt-image-2".equals(model)) {
                    throw new IllegalArgumentException(label + "图片模型固定为 gpt-image-2");
                }
            }
            if ("local-cliproxy-text".equals(providerId)
                || "local-cliproxy-text".equals(providerName)) {
                if (!GPT_TEXT_MODELS.contains(model.trim().toLowerCase())) {
                    throw new IllegalArgumentException(label + "文案模型不在 Local Worker v1 目录中");
                }
            }
            if (!List.of(OFFLINE_QUEUE, OFFLINE_FALLBACK).contains(offlineAction(provider))) {
                throw new IllegalArgumentException(label + "本机 Worker 离线策略只能是 queue 或 fallback");
            }
            localWaitSeconds(provider);
            leaseSeconds(provider);
            maxConcurrency(provider);
            return;
        }
        String baseUrl = text(provider.get("base_url"), "");
        String apiKey = providerApiKey(provider);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(label + "服务地址不能为空");
        }
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException(label + "服务地址必须是 http 或 https URL");
            }
            if (isUnsafeHost(uri.getHost())) {
                throw new IllegalArgumentException(label + "服务地址不能指向本机、内网或云元数据地址");
            }
        } catch (IllegalArgumentException exception) {
            throw exception.getMessage() == null || !exception.getMessage().startsWith(label)
                ? new IllegalArgumentException(label + "服务地址格式不正确")
                : exception;
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(label + " API Key 不能为空");
        }
    }

    private List<Map<String, Object>> adminProviderViews(List<Map<String, Object>> providers) {
        return providers.stream().map(this::adminProviderView).toList();
    }

    private Map<String, Object> adminProviderView(Map<String, Object> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        String apiKey = providerApiKey(raw);
        row.put("id", text(raw.get("id"), ""));
        row.put("name", text(raw.get("name"), text(raw.get("provider"), "")));
        row.put("provider", text(raw.get("provider"), ""));
        row.put("enabled", bool(raw.get("enabled"), false));
        row.put("base_url", text(raw.get("base_url"), ""));
        row.put("model", text(raw.get("model"), ""));
        row.put("api_key", "");
        row.put("api_key_set", !apiKey.isBlank());
        row.put("api_key_masked", mask(apiKey));
        row.put("api_key_ref", text(raw.get("api_key_ref"), text(raw.get("env_key"), "")));
        row.put("env_key", text(raw.get("env_key"), ""));
        row.put("priority", intValue(raw.get("priority"), providerPriority(raw)));
        row.put("timeout_seconds", intValue(raw.get("timeout_seconds"), imageDefaults.timeoutSecondsValue()));
        row.put("execution_mode", executionMode(raw));
        row.put("local_wait_seconds", localWaitSeconds(raw));
        row.put("offline_action", offlineAction(raw));
        row.put("lease_seconds", leaseSeconds(raw));
        row.put("max_concurrency", maxConcurrency(raw));
        if (isLocalImageProvider(text(raw.get("id"), ""), text(raw.get("provider"), ""))) {
            row.put("actual_cost", decimalValue(raw.get("actual_cost"), LOCAL_IMAGE_COST_CNY));
        }
        row.put("supported_sizes", text(raw.get("supported_sizes"), ""));
        row.put("notes", text(raw.get("notes"), ""));
        return row;
    }

    private Map<String, Object> adminImageView(EffectiveImageConfig config) {
        Map<String, Object> row = providerView(config.enabled(), config.provider(), config.baseUrl(), config.apiKey(), config.model());
        row.put("size", config.size());
        row.put("response_format", config.responseFormat());
        return row;
    }

    private Map<String, Object> adminTextView(EffectiveTextConfig config) {
        return providerView(config.enabled(), config.provider(), config.baseUrl(), config.apiKey(), config.model());
    }

    private Map<String, Object> providerView(boolean enabled, String provider, String baseUrl, String apiKey, String model) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("enabled", enabled);
        row.put("provider", provider);
        row.put("base_url", baseUrl);
        row.put("model", model);
        row.put("api_key", "");
        row.put("api_key_set", apiKey != null && !apiKey.isBlank());
        row.put("api_key_masked", mask(apiKey));
        return row;
    }

    private Map<String, Object> normalizeProvider(Map<String, Object> raw, String kind) {
        if (raw == null || raw.isEmpty()) {
            return defaultProvider(kind);
        }
        Map<String, Object> fallback = defaultProvider(kind);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", text(raw.get("id"), text(fallback.get("id"), newProviderId(kind))));
        row.put("name", text(raw.get("name"), text(raw.get("provider"), text(fallback.get("name"), ""))));
        row.put("provider", text(raw.get("provider"), text(fallback.get("provider"), "")));
        row.put("enabled", bool(raw.get("enabled"), false));
        row.put("base_url", trimTrailingSlash(text(raw.get("base_url"), text(fallback.get("base_url"), ""))));
        row.put("api_key", text(raw.get("api_key"), text(fallback.get("api_key"), "")));
        row.put("api_key_ref", text(raw.get("api_key_ref"), text(raw.get("env_key"), "")));
        row.put("model", text(raw.get("model"), text(fallback.get("model"), "")));
        if ("image".equals(kind) && isYunfeiProvider(row)) {
            row.put("model", "gpt-image-2");
        }
        if ("text".equals(kind) && isOpenAiTextProvider(text(row.get("provider"), ""), text(row.get("base_url"), ""))) {
            row.put("model", normalizeGptTextModel(text(row.get("model"), DEFAULT_GPT_TEXT_MODEL)));
        }
        row.put("env_key", text(raw.get("env_key"), text(fallback.get("env_key"), "")));
        int priorityFallback = providerPriority(row);
        row.put("priority", intValue(raw.get("priority"), priorityFallback));
        row.put("timeout_seconds", intValue(raw.get("timeout_seconds"), intValue(fallback.get("timeout_seconds"), imageDefaults.timeoutSecondsValue())));
        row.put("execution_mode", normalizeExecutionMode(text(raw.get("execution_mode"), text(fallback.get("execution_mode"), EXECUTION_HTTP))));
        String providerId = text(row.get("id"), "");
        if ("local-cliproxy-image".equals(providerId)) {
            row.put("provider", "local-cliproxy-image");
            row.put("model", "gpt-image-2");
            row.put("execution_mode", EXECUTION_PULL_WORKER);
            row.put("actual_cost", decimalValue(raw.get("actual_cost"), LOCAL_IMAGE_COST_CNY));
        } else if ("local-cliproxy-text".equals(providerId)) {
            row.put("provider", "local-cliproxy-text");
            String localTextModel = text(row.get("model"), DEFAULT_GPT_TEXT_MODEL).trim().toLowerCase();
            row.put("model", GPT_TEXT_MODELS.contains(localTextModel) ? localTextModel : DEFAULT_GPT_TEXT_MODEL);
            row.put("execution_mode", EXECUTION_PULL_WORKER);
        }
        row.put("local_wait_seconds", boundedInt(raw.get("local_wait_seconds"), intValue(fallback.get("local_wait_seconds"), 25), 1, 300));
        row.put("offline_action", normalizeOfflineAction(text(raw.get("offline_action"), text(fallback.get("offline_action"), OFFLINE_FALLBACK))));
        row.put("lease_seconds", boundedInt(raw.get("lease_seconds"), intValue(fallback.get("lease_seconds"), 120), 30, 1800));
        row.put("max_concurrency", boundedInt(raw.get("max_concurrency"), intValue(fallback.get("max_concurrency"), 1), 1, 32));
        if (EXECUTION_PULL_WORKER.equals(executionMode(row))) {
            row.put("base_url", text(raw.get("base_url"), ""));
            row.put("api_key", "");
            row.put("api_key_ref", "");
            row.put("env_key", "");
        }
        row.put("supported_sizes", text(raw.get("supported_sizes"), text(fallback.get("supported_sizes"), "")));
        row.put("notes", text(raw.get("notes"), text(fallback.get("notes"), "")));
        return row;
    }

    private List<Map<String, Object>> defaultProviders(String kind) {
        if (!"image".equals(kind)) {
            return orderProviders(mergeTextEnvDefaults(List.of(defaultLocalTextProvider(), defaultProvider(kind))));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(defaultLocalImageProvider());
        rows.add(defaultYunfeiOneKProvider());
        rows.add(defaultYunfeiHiresProvider());
        rows.add(defaultRuaProvider());
        rows.add(defaultProvider(kind));
        rows.add(defaultXinZhiZhuProvider());
        rows.add(defaultAishuchProvider());
        return rows;
    }

    private Map<String, Object> defaultProvider(String kind) {
        boolean image = "image".equals(kind);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", image ? "image_default" : "text_default");
        row.put("name", image ? "默认作图 AI" : "默认文案 AI");
        row.put("provider", image ? imageDefaults.providerName() : "deepseek");
        row.put("enabled", image ? Boolean.TRUE.equals(imageDefaults.enabled()) : textEnabledDefault);
        row.put("base_url", image ? trimTrailingSlash(imageDefaults.baseUrlValue()) : textBaseUrlDefault);
        row.put("api_key", image ? text(imageDefaults.apiKey(), "") : textApiKeyDefault);
        row.put("model", image ? imageDefaults.modelValue() : textModelDefault);
        row.put("env_key", "");
        row.put("priority", providerPriority(row));
        row.put("timeout_seconds", image ? imageDefaults.timeoutSecondsValue() : 45);
        row.put("execution_mode", EXECUTION_HTTP);
        row.put("local_wait_seconds", 25);
        row.put("offline_action", OFFLINE_FALLBACK);
        row.put("lease_seconds", 120);
        row.put("max_concurrency", 1);
        if (image) {
            row.put("actual_cost", imageDefaults.actualCostValue());
        }
        row.put("supported_sizes", image ? "1K,2K,4K" : "");
        row.put("notes", "");
        return row;
    }

    private Map<String, Object> defaultLocalImageProvider() {
        Map<String, Object> row = defaultProvider("image");
        row.put("id", "local-cliproxy-image");
        row.put("name", "本机 CLIProxy 作图");
        row.put("provider", "local-cliproxy-image");
        row.put("enabled", false);
        row.put("base_url", "");
        row.put("api_key", "");
        row.put("env_key", "");
        row.put("model", "gpt-image-2");
        row.put("priority", -100);
        row.put("execution_mode", EXECUTION_PULL_WORKER);
        row.put("local_wait_seconds", 25);
        row.put("offline_action", OFFLINE_FALLBACK);
        row.put("lease_seconds", 180);
        row.put("max_concurrency", 1);
        row.put("actual_cost", LOCAL_IMAGE_COST_CNY);
        row.put("supported_sizes", "1K,2K,4K");
        row.put("notes", "由本机 Worker 长轮询领取；未领取时按离线策略处理");
        return row;
    }

    private Map<String, Object> defaultLocalTextProvider() {
        Map<String, Object> row = defaultProvider("text");
        row.put("id", "local-cliproxy-text");
        row.put("name", "本机 CLIProxy 文案");
        row.put("provider", "local-cliproxy-text");
        row.put("enabled", false);
        row.put("base_url", "");
        row.put("api_key", "");
        row.put("env_key", "");
        row.put("model", DEFAULT_GPT_TEXT_MODEL);
        row.put("priority", -100);
        row.put("execution_mode", EXECUTION_PULL_WORKER);
        row.put("timeout_seconds", 90);
        row.put("local_wait_seconds", 20);
        row.put("offline_action", OFFLINE_FALLBACK);
        row.put("lease_seconds", 120);
        row.put("max_concurrency", 1);
        row.put("supported_sizes", "");
        row.put("notes", "由本机 Worker 长轮询领取；模型可在后台调整");
        return row;
    }

    private Map<String, Object> defaultAssistantTextProvider() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "text_assistant");
        row.put("name", "ChatGPT/98Pro 文案");
        row.put("provider", "chatgpt");
        row.put("enabled", notBlank(text(System.getenv("OPENIMAGE_ASSISTANT_API_KEY"), "")) || notBlank(text(System.getenv("OPENIMAGE_ASSISTANT_API_KEYS"), "")));
        row.put("base_url", blankToDefault(text(System.getenv("OPENIMAGE_ASSISTANT_BASE_URL"), ""), "https://api.openai.com"));
        row.put("api_key", "");
        row.put("env_key", "OPENIMAGE_ASSISTANT_API_KEY");
        row.put("model", normalizeGptTextModel(text(System.getenv("OPENIMAGE_ASSISTANT_MODEL"), DEFAULT_GPT_TEXT_MODEL)));
        row.put("priority", 1);
        row.put("timeout_seconds", intEnv("OPENIMAGE_ASSISTANT_TIMEOUT_SECONDS", 45));
        row.put("supported_sizes", "");
        row.put("notes", "用于文案 Agent、Listing 文案和 Prompt 优化的优先文案模型");
        return row;
    }

    private Map<String, Object> defaultOtokapiTextProvider() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "text_otokapi");
        row.put("name", "OtokAPI 文案");
        row.put("provider", "otokapi");
        row.put("enabled", notBlank(text(System.getenv("OPENIMAGE_OTOKAPI_API_KEY"), ""))
            || notBlank(text(System.getenv("OPENIMAGE_OTOKAPI_API_KEYS"), ""))
            || notBlank(text(System.getenv("OTOKAPI_API_KEY"), "")));
        row.put("base_url", blankToDefault(text(System.getenv("OPENIMAGE_OTOKAPI_BASE_URL"), ""), "https://otokapi.com"));
        row.put("api_key", "");
        row.put("env_key", "OPENIMAGE_OTOKAPI_API_KEY");
        row.put("model", normalizeGptTextModel(text(System.getenv("OPENIMAGE_OTOKAPI_MODEL"), DEFAULT_GPT_TEXT_MODEL)));
        row.put("priority", 3);
        row.put("timeout_seconds", intEnv("OPENIMAGE_OTOKAPI_TIMEOUT_SECONDS", 60));
        row.put("supported_sizes", "");
        row.put("notes", "OpenAI 兼容文案供应商；支持 gpt-5.5 与 GPT-5.6 Sol / Terra / Luna");
        return row;
    }

    private Map<String, Object> defaultYunfeiOneKProvider() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "image_yunfei_1k");
        row.put("name", "Yunfei 1K 作图");
        row.put("provider", "yunfei-1k");
        row.put("enabled", notBlank(text(System.getenv("OPENIMAGE_YUNFEI_1K_API_KEY"), "")));
        row.put("base_url", "https://img.yunfei.best");
        row.put("api_key", "");
        row.put("env_key", "OPENIMAGE_YUNFEI_1K_API_KEY");
        row.put("model", "gpt-image-2");
        row.put("priority", 0);
        row.put("timeout_seconds", imageDefaults.timeoutSecondsValue());
        row.put("supported_sizes", "1K");
        row.put("notes", "Yunfei 1K 专用密钥；模型固定为 gpt-image-2");
        return row;
    }

    private Map<String, Object> defaultYunfeiHiresProvider() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "image_yunfei_hires");
        row.put("name", "Yunfei 2K/4K 作图");
        row.put("provider", "yunfei-hires");
        row.put("enabled", notBlank(text(System.getenv("OPENIMAGE_YUNFEI_HIRES_API_KEY"), "")));
        row.put("base_url", "https://img.yunfei.best");
        row.put("api_key", "");
        row.put("env_key", "OPENIMAGE_YUNFEI_HIRES_API_KEY");
        row.put("model", "gpt-image-2");
        row.put("priority", 0);
        row.put("timeout_seconds", imageDefaults.timeoutSecondsValue());
        row.put("supported_sizes", "2K,4K");
        row.put("notes", "Yunfei 2K/4K 专用密钥；模型固定为 gpt-image-2");
        return row;
    }

    private Map<String, Object> defaultRuaProvider() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "image_rua_chat");
        row.put("name", "rua.chat 作图");
        row.put("provider", "rua.chat");
        row.put("enabled", notBlank(text(System.getenv("RUA_CHAT_API_KEY"), "")) || notBlank(text(System.getenv("OPENIMAGE_RUA_CHAT_API_KEY"), "")));
        row.put("base_url", "https://api.rua.chat");
        row.put("api_key", "");
        row.put("env_key", "RUA_CHAT_API_KEY");
        row.put("model", "gpt-image-2");
        row.put("priority", 1);
        row.put("timeout_seconds", imageDefaults.timeoutSecondsValue());
        row.put("supported_sizes", "1K,2K,4K");
        row.put("notes", "优先尝试；请求使用 quality=auto/output_format=png/moderation=low");
        return row;
    }

    private Map<String, Object> defaultXinZhiZhuProvider() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "image_xin_zhizhu");
        row.put("name", "Xin-Zhi-Zhu 作图");
        row.put("provider", "xin-zhi-zhu");
        row.put("enabled", notBlank(text(System.getenv("XIN_ZHI_ZHU_API_KEY"), "")) || notBlank(text(System.getenv("OPENIMAGE_XIN_ZHI_ZHU_API_KEY"), "")));
        row.put("base_url", "https://api.xin-zhi-zhu.com");
        row.put("api_key", "");
        row.put("api_key_ref", "XIN_ZHI_ZHU_API_KEY");
        row.put("env_key", "XIN_ZHI_ZHU_API_KEY");
        row.put("model", "gpt-image-2");
        row.put("priority", 2);
        row.put("timeout_seconds", 240);
        row.put("supported_sizes", "1K,2K,4K");
        row.put("notes", "主力作图供应商；1K/2K/4K 分别映射到 1600/2048/2880");
        return row;
    }

    private Map<String, Object> defaultAishuchProvider() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "image_aishuch");
        row.put("name", "aishuch 作图");
        row.put("provider", "aishuch");
        row.put("enabled", notBlank(text(System.getenv("AISHUCH_API_KEY"), "")) || notBlank(text(System.getenv("OPENIMAGE_AISHUCH_API_KEY"), "")));
        row.put("base_url", "https://api.aishuch.com");
        row.put("api_key", "");
        row.put("env_key", "AISHUCH_API_KEY");
        row.put("model", "gpt-image-2");
        row.put("priority", 4);
        row.put("timeout_seconds", imageDefaults.timeoutSecondsValue());
        row.put("supported_sizes", "1K,2K,4K");
        row.put("notes", "第四兜底供应商");
        return row;
    }

    private Map<String, Object> disabledProvider(String kind) {
        Map<String, Object> row = defaultProvider(kind);
        row.put("enabled", false);
        row.put("api_key", "");
        return row;
    }

    private Map<String, Object> editableProvider(String kind) {
        Map<String, Object> row = disabledProvider(kind);
        row.put("id", newProviderId(kind));
        row.put("name", "image".equals(kind) ? "作图 AI" : "文案 AI");
        return row;
    }

    private String newProviderId(String kind) {
        return kind + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private boolean isUnsafeHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String lower = host.toLowerCase();
        if ("localhost".equals(lower) || lower.endsWith(".localhost") || lower.endsWith(".local")
            || "metadata.google.internal".equals(lower)) {
            return true;
        }
        if (lower.contains(":")) {
            return "::1".equals(lower) || lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80:");
        }
        String[] parts = lower.split("\\.");
        if (parts.length == 4) {
            try {
                int a = Integer.parseInt(parts[0]);
                int b = Integer.parseInt(parts[1]);
                int c = Integer.parseInt(parts[2]);
                int d = Integer.parseInt(parts[3]);
                if (a < 0 || a > 255 || b < 0 || b > 255 || c < 0 || c > 255 || d < 0 || d > 255) {
                    return true;
                }
                return a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 198 && (b == 18 || b == 19));
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private Map<String, Object> readStored() {
        SystemSettingEntity setting = settings.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
            .eq(SystemSettingEntity::getDeleted, 0)
            .eq(SystemSettingEntity::getSettingKey, SETTING_KEY));
        if (setting == null || setting.getSettingValue() == null || setting.getSettingValue().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(setting.getSettingValue(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private void writeStored(Map<String, Object> value) {
        SystemSettingEntity setting = settings.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
            .eq(SystemSettingEntity::getDeleted, 0)
            .eq(SystemSettingEntity::getSettingKey, SETTING_KEY));
        OffsetDateTime now = OffsetDateTime.now();
        try {
            if (setting == null) {
                setting = new SystemSettingEntity();
                setting.setId("setting_" + UUID.randomUUID().toString().replace("-", ""));
                setting.setSettingKey(SETTING_KEY);
                setting.setDescription("后台 AI 图片与文案运行配置");
                setting.setCreatedAt(now);
                setting.setDeleted(0);
                setting.setSettingValue(json.writeValueAsString(value));
                setting.setUpdatedAt(now);
                settings.insert(setting);
            } else {
                setting.setSettingValue(json.writeValueAsString(value));
                setting.setUpdatedAt(now);
                settings.updateById(setting);
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("AI 配置保存失败");
        }
    }

    private Map<String, Object> safeMap(Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> row.put(String.valueOf(key), item));
        }
        return row;
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    private String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? fallback : text;
    }

    private boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private BigDecimal decimalValue(Object value, BigDecimal fallback) {
        BigDecimal resolved = fallback == null ? BigDecimal.ZERO : fallback;
        if (value == null) {
            return resolved;
        }
        try {
            BigDecimal parsed = new BigDecimal(String.valueOf(value).trim());
            return parsed.signum() >= 0 && parsed.compareTo(new BigDecimal("1000")) <= 0
                ? parsed
                : resolved;
        } catch (NumberFormatException ignored) {
            return resolved;
        }
    }

    private boolean isLocalImageProvider(String providerId, String provider) {
        return "local-cliproxy-image".equalsIgnoreCase(providerId == null ? "" : providerId.trim())
            || "local-cliproxy-image".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    private int boundedInt(Object value, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, intValue(value, fallback)));
    }

    private boolean providerConfigured(Map<String, Object> row) {
        return EXECUTION_PULL_WORKER.equals(executionMode(row)) || notBlank(providerApiKey(row));
    }

    private String executionMode(Map<String, Object> row) {
        return normalizeExecutionMode(text(row == null ? null : row.get("execution_mode"), EXECUTION_HTTP));
    }

    private String normalizeExecutionMode(String value) {
        return EXECUTION_PULL_WORKER.equalsIgnoreCase(value) ? EXECUTION_PULL_WORKER : EXECUTION_HTTP;
    }

    private int localWaitSeconds(Map<String, Object> row) {
        return boundedInt(row == null ? null : row.get("local_wait_seconds"), 25, 1, 300);
    }

    private String offlineAction(Map<String, Object> row) {
        return normalizeOfflineAction(text(row == null ? null : row.get("offline_action"), OFFLINE_FALLBACK));
    }

    private String normalizeOfflineAction(String value) {
        return OFFLINE_QUEUE.equalsIgnoreCase(value) ? OFFLINE_QUEUE : OFFLINE_FALLBACK;
    }

    private int leaseSeconds(Map<String, Object> row) {
        return boundedInt(row == null ? null : row.get("lease_seconds"), 120, 30, 1800);
    }

    private int maxConcurrency(Map<String, Object> row) {
        return boundedInt(row == null ? null : row.get("max_concurrency"), 1, 1, 32);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private List<Map<String, Object>> mergeImageEnvDefaults(List<Map<String, Object>> rows) {
        List<Map<String, Object>> merged = new ArrayList<>(rows);
        Map<String, Object> localWorker = defaultLocalImageProvider();
        if (merged.stream().noneMatch(row -> sameImageProvider(row, localWorker))) {
            merged.add(localWorker);
        }
        Map<String, Object> yunfeiOneK = defaultYunfeiOneKProvider();
        if (merged.stream().noneMatch(row -> sameImageProvider(row, yunfeiOneK))) {
            merged.add(yunfeiOneK);
        }
        Map<String, Object> yunfeiHires = defaultYunfeiHiresProvider();
        if (merged.stream().noneMatch(row -> sameImageProvider(row, yunfeiHires))) {
            merged.add(yunfeiHires);
        }
        Map<String, Object> rua = defaultRuaProvider();
        if (merged.stream().noneMatch(row -> sameImageProvider(row, rua))) {
            merged.add(rua);
        }
        Map<String, Object> hfsyapi = defaultProvider("image");
        if (merged.stream().noneMatch(row -> sameImageProvider(row, hfsyapi))) {
            merged.add(hfsyapi);
        }
        Map<String, Object> xinZhiZhu = defaultXinZhiZhuProvider();
        if (merged.stream().noneMatch(row -> sameImageProvider(row, xinZhiZhu))) {
            merged.add(xinZhiZhu);
        }
        Map<String, Object> aishuch = defaultAishuchProvider();
        if (merged.stream().noneMatch(row -> sameImageProvider(row, aishuch))) {
            merged.add(aishuch);
        }
        return merged;
    }

    private List<Map<String, Object>> mergeTextEnvDefaults(List<Map<String, Object>> rows) {
        List<Map<String, Object>> merged = new ArrayList<>(rows);
        Map<String, Object> localWorker = defaultLocalTextProvider();
        if (merged.stream().noneMatch(row -> sameProvider(row, localWorker))) {
            merged.add(localWorker);
        }
        Map<String, Object> assistant = defaultAssistantTextProvider();
        if (merged.stream().noneMatch(row -> sameProvider(row, assistant))) {
            merged.add(assistant);
        }
        Map<String, Object> otokapi = defaultOtokapiTextProvider();
        if (merged.stream().noneMatch(row -> sameProvider(row, otokapi))) {
            merged.add(otokapi);
        }
        Map<String, Object> deepseek = defaultProvider("text");
        if (merged.stream().noneMatch(row -> sameProvider(row, deepseek))) {
            merged.add(deepseek);
        }
        return merged;
    }

    private List<Map<String, Object>> orderImageProviders(List<Map<String, Object>> providers) {
        return orderProviders(providers);
    }

    private List<Map<String, Object>> orderProviders(List<Map<String, Object>> providers) {
        List<Map<String, Object>> ordered = new ArrayList<>(providers);
        ordered.sort((left, right) -> {
            int priorityCompare = Integer.compare(
                intValue(left.get("priority"), providerPriority(left)),
                intValue(right.get("priority"), providerPriority(right))
            );
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return Integer.compare(providerPriority(left), providerPriority(right));
        });
        return ordered;
    }

    private boolean sameImageProvider(Map<String, Object> left, Map<String, Object> right) {
        return sameProvider(left, right);
    }

    private boolean sameProvider(Map<String, Object> left, Map<String, Object> right) {
        String leftId = text(left.get("id"), "");
        String rightId = text(right.get("id"), "");
        if (!leftId.isBlank() && !rightId.isBlank() && leftId.equalsIgnoreCase(rightId)) {
            return true;
        }
        String leftProvider = text(left.get("provider"), "");
        String rightProvider = text(right.get("provider"), "");
        return !leftProvider.isBlank() && !rightProvider.isBlank() && leftProvider.equalsIgnoreCase(rightProvider);
    }

    private int providerPriority(Map<String, Object> row) {
        String text = (text(row.get("provider"), "") + " " + text(row.get("base_url"), "")).toLowerCase();
        if (text.contains("local-cliproxy")) {
            return -100;
        }
        if (text.contains("yunfei") || text.contains("img.yunfei.best")) {
            return 0;
        }
        if (text.contains("rua") || text.contains("rua.chat")) {
            return 1;
        }
        if (text.contains("hfsyapi")) {
            return 2;
        }
        if (text.contains("xin-zhi-zhu") || text.contains("xinzhizhu") || text.contains("xinzhi")) {
            return 3;
        }
        if (text.contains("aishuch") || text.contains("apimart")) {
            return 4;
        }
        if (text.contains("chatgpt") || text.contains("openai") || text.contains("98pro")) {
            return 1;
        }
        if (text.contains("otokapi")) {
            return 2;
        }
        if (text.contains("deepseek")) {
            return 3;
        }
        return 100;
    }

    private String providerApiKey(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return "";
        }
        String apiKey;
        String envKey = text(row.get("env_key"), "");
        if (!envKey.isBlank()) {
            apiKey = text(System.getenv(envKey), "");
            if (!apiKey.isBlank()) {
                return apiKey;
            }
        }
        apiKey = text(row.get("api_key"), "");
        if (!apiKey.isBlank()) {
            return apiKey;
        }
        if (AiImageResponseAdapter.isRuaChat(text(row.get("provider"), ""), text(row.get("base_url"), ""))) {
            apiKey = text(System.getenv("RUA_CHAT_API_KEY"), "");
            if (!apiKey.isBlank()) {
                return apiKey;
            }
            return text(System.getenv("OPENIMAGE_RUA_CHAT_API_KEY"), "");
        }
        if (AiImageResponseAdapter.isXinZhiZhu(text(row.get("provider"), ""), text(row.get("base_url"), ""))) {
            apiKey = text(System.getenv("XIN_ZHI_ZHU_API_KEY"), "");
            if (!apiKey.isBlank()) {
                return apiKey;
            }
            return text(System.getenv("OPENIMAGE_XIN_ZHI_ZHU_API_KEY"), "");
        }
        if (AiImageResponseAdapter.isAishuch(text(row.get("provider"), ""), text(row.get("base_url"), ""))) {
            apiKey = text(System.getenv("AISHUCH_API_KEY"), "");
            if (!apiKey.isBlank()) {
                return apiKey;
            }
            return text(System.getenv("OPENIMAGE_AISHUCH_API_KEY"), "");
        }
        if (isYunfeiOneKProvider(row)) {
            return text(System.getenv("OPENIMAGE_YUNFEI_1K_API_KEY"), "");
        }
        if (isYunfeiHiresProvider(row)) {
            return text(System.getenv("OPENIMAGE_YUNFEI_HIRES_API_KEY"), "");
        }
        String provider = text(row.get("provider"), "").toLowerCase();
        String baseUrl = text(row.get("base_url"), "").toLowerCase();
        if (isOtokapiTextProvider(provider, baseUrl)) {
            apiKey = text(System.getenv("OPENIMAGE_OTOKAPI_API_KEY"), "");
            if (!apiKey.isBlank()) {
                return apiKey;
            }
            apiKey = firstApiKey(System.getenv("OPENIMAGE_OTOKAPI_API_KEYS"));
            if (!apiKey.isBlank()) {
                return apiKey;
            }
            return text(System.getenv("OTOKAPI_API_KEY"), "");
        }
        if (provider.contains("chatgpt") || provider.contains("openai") || provider.contains("98pro") || baseUrl.contains("api.openai.com") || baseUrl.contains("ai98pro")) {
            apiKey = text(System.getenv("OPENIMAGE_ASSISTANT_API_KEY"), "");
            if (!apiKey.isBlank()) {
                return apiKey;
            }
            return firstApiKey(System.getenv("OPENIMAGE_ASSISTANT_API_KEYS"));
        }
        if (provider.contains("deepseek") || baseUrl.contains("deepseek")) {
            return textApiKeyDefault;
        }
        return "";
    }

    private boolean isOtokapiTextProvider(String provider, String baseUrl) {
        String text = ((provider == null ? "" : provider) + " " + (baseUrl == null ? "" : baseUrl)).toLowerCase();
        return text.contains("otokapi");
    }

    private boolean isOpenAiTextProvider(String provider, String baseUrl) {
        String text = ((provider == null ? "" : provider) + " " + (baseUrl == null ? "" : baseUrl)).toLowerCase();
        return text.contains("openai")
            || text.contains("chatgpt")
            || text.contains("98pro")
            || text.contains("ai98pro")
            || text.contains("otokapi");
    }

    private String normalizeGptTextModel(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase().replace("_", "-").replace(" ", "");
        for (String model : GPT_TEXT_MODELS) {
            if (model.equals(normalized)) {
                return model;
            }
        }
        return switch (normalized) {
            case "5.5", "gpt-5.5" -> "gpt-5.5";
            case "5.6", "5.6sol", "5.6-sol", "gpt-5.6", "gpt-5.6sol", "gpt-5.6-sol" -> "gpt-5.6-sol";
            case "5.6terra", "5.6-terra", "gpt-5.6terra", "gpt-5.6-terra" -> "gpt-5.6-terra";
            case "5.6luna", "5.6-luna", "gpt-5.6luna", "gpt-5.6-luna" -> "gpt-5.6-luna";
            default -> DEFAULT_GPT_TEXT_MODEL;
        };
    }

    private String firstApiKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        for (String item : raw.split("[,;\\n\\r]+")) {
            String key = item.trim();
            if (!key.isBlank()) {
                return key;
            }
        }
        return "";
    }

    private int intEnv(String key, int fallback) {
        return intValue(System.getenv(key), fallback);
    }

    private String ruaRequestSize(String size) {
        AiImageSizes.Size parsed = AiImageSizes.parse(size);
        if (parsed == null || parsed.width() != parsed.height()) {
            return size;
        }
        int max = Math.max(parsed.width(), parsed.height());
        return max >= 2800 ? "2880x2880" : "1600x1600";
    }

    private String xinZhiZhuRequestSize(String size) {
        AiImageSizes.Size parsed = AiImageSizes.parse(size);
        if (parsed == null || parsed.width() != parsed.height()) {
            return size;
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

    private String summarize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    public record UpdateRequest(
        ProviderRequest image,
        ProviderRequest text,
        @JsonProperty("image_providers") List<ProviderRequest> imageProviders,
        @JsonProperty("text_providers") List<ProviderRequest> textProviders
    ) {}

    public record ProviderRequest(
        String id,
        String name,
        Boolean enabled,
        String provider,
        @JsonProperty("base_url") String baseUrl,
        @JsonProperty("api_key") String apiKey,
        @JsonProperty("api_key_ref") String apiKeyRef,
        @JsonProperty("env_key") String envKey,
        String model,
        Integer priority,
        @JsonProperty("timeout_seconds") Integer timeoutSeconds,
        @JsonProperty("execution_mode") String executionMode,
        @JsonProperty("local_wait_seconds") Integer localWaitSeconds,
        @JsonProperty("offline_action") String offlineAction,
        @JsonProperty("lease_seconds") Integer leaseSeconds,
        @JsonProperty("max_concurrency") Integer maxConcurrency,
        @JsonProperty("actual_cost") BigDecimal actualCost,
        @JsonProperty("supported_sizes") String supportedSizes,
        String notes
    ) {}

    public record EffectiveImageConfig(
        boolean enabled,
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        String size,
        String responseFormat,
        BigDecimal actualCost,
        String publicBaseUrl,
        int timeoutSeconds,
        String id,
        String executionMode,
        int localWaitSeconds,
        String offlineAction,
        int leaseSeconds,
        int maxConcurrency
    ) {
        public EffectiveImageConfig(
            boolean enabled,
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            String size,
            String responseFormat,
            BigDecimal actualCost,
            String publicBaseUrl,
            int timeoutSeconds,
            String id
        ) {
            this(
                enabled, provider, baseUrl, apiKey, model, size, responseFormat, actualCost,
                publicBaseUrl, timeoutSeconds, id, EXECUTION_HTTP, 25, OFFLINE_FALLBACK, 120, 1
            );
        }

        public boolean enabledAndConfigured() {
            return enabled && (pullWorker() || (apiKey != null && !apiKey.isBlank()));
        }

        public boolean pullWorker() {
            return EXECUTION_PULL_WORKER.equals(executionMode);
        }

        public WorkerProviderConfig workerConfig(String capability) {
            return new WorkerProviderConfig(
                id, provider, capability, model, localWaitSeconds, offlineAction, leaseSeconds, maxConcurrency
            );
        }
    }

    public record EffectiveTextConfig(
        boolean enabled,
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        String id,
        String executionMode,
        int localWaitSeconds,
        String offlineAction,
        int leaseSeconds,
        int maxConcurrency
    ) {
        public EffectiveTextConfig(
            boolean enabled,
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            int timeoutSeconds,
            String id
        ) {
            this(enabled, provider, baseUrl, apiKey, model, timeoutSeconds, id, EXECUTION_HTTP, 20, OFFLINE_FALLBACK, 120, 1);
        }

        public boolean enabledAndConfigured() {
            return enabled && (pullWorker() || (apiKey != null && !apiKey.isBlank()));
        }

        public boolean pullWorker() {
            return EXECUTION_PULL_WORKER.equals(executionMode);
        }

        public WorkerProviderConfig workerConfig(String capability) {
            return new WorkerProviderConfig(
                id, provider, capability, model, localWaitSeconds, offlineAction, leaseSeconds, maxConcurrency
            );
        }
    }

    public record WorkerProviderConfig(
        String id,
        String provider,
        String capability,
        String model,
        int localWaitSeconds,
        String offlineAction,
        int leaseSeconds,
        int maxConcurrency
    ) {
    }
}
