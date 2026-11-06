package com.openimage.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.common.BusinessTime;
import com.openimage.domain.entity.SystemSettingEntity;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.SystemSettingMapper;
import com.openimage.domain.mapper.VideoTaskMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class VideoConfigService {
    private static final Duration AGNES_CIRCUIT_COOLDOWN = Duration.ofMinutes(10);
    private static final String SETTING_KEY = "video_runtime_config";
    private static final int CONFIG_VERSION = 2;
    private static final String DEFAULT_AGNES_MODEL = "agnes-video-v2.0";
    private static final String LEGACY_AGNES_KEY_ID = "agnes-primary";
    private static final String ENVIRONMENT_AGNES_KEY_ID = "agnes-environment";
    private static final String SECRET_PREFIX = "v1:";
    private static final String DEFAULT_PUBLIC_MODEL = "gemini-omni";
    private static final List<String> SEEDANCE_RATIOS = List.of("9:16", "16:9", "1:1", "3:4", "4:3", "adaptive");
    private static final List<String> GEMINI_RATIOS = List.of("9:16", "16:9");
    private static final List<String> AGNES_RATIOS = List.of("9:16", "16:9", "1:1", "3:4", "4:3");
    private static final List<String> HFSY_RATIOS = List.of("9:16", "16:9", "1:1");
    private static final BigDecimal CNY_PER_CREDIT = new BigDecimal("0.0099");
    private static final BigDecimal MIN_REVENUE_AFTER_MARGIN = new BigDecimal("0.95");
    private static final Map<String, BigDecimal> HFSY_CALL_COSTS = Map.of(
        "sd-2.0-mini", new BigDecimal("1.20"),
        "sd-2.0-fast-480", new BigDecimal("2.00"),
        "sd-2.0-fast", new BigDecimal("3.00"),
        "sd-2.0-480", new BigDecimal("3.00"),
        "sd-2.0", new BigDecimal("4.00")
    );
    private static final List<HfsyModelMapping> HFSY_MODEL_MAPPINGS = List.of(
        new HfsyModelMapping("seedance-mini", "Seedance Mini", "480p", "sd-2.0-mini"),
        new HfsyModelMapping("seedance-mini", "Seedance Mini", "720p", "sd-2.0-mini"),
        new HfsyModelMapping("seedance-fast", "Seedance 2.0 Fast", "480p", "sd-2.0-fast-480"),
        new HfsyModelMapping("seedance-fast", "Seedance 2.0 Fast", "720p", "sd-2.0-fast"),
        new HfsyModelMapping("seedance-2", "Seedance 2.0", "480p", "sd-2.0-480"),
        new HfsyModelMapping("seedance-2", "Seedance 2.0", "720p", "sd-2.0")
    );
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final SystemSettingMapper settings;
    private final VideoTaskMapper tasks;
    private final ObjectMapper json;
    private final VideoProperties properties;

    public VideoConfigService(
        SystemSettingMapper settings,
        VideoTaskMapper tasks,
        ObjectMapper json,
        VideoProperties properties
    ) {
        this.settings = settings;
        this.tasks = tasks;
        this.json = json;
        this.properties = properties;
    }

    public RuntimeSettings current() {
        Map<String, Object> stored = read();
        boolean upgraded = integer(stored.get("config_version"), 0) >= CONFIG_VERSION;
        TierSettings standard = new TierSettings(
            bool(upgraded ? stored.get("standard_enabled") : null, true),
            boundedInt(upgraded ? stored.get("standard_duration_seconds") : null, 10, 3, 10),
            "720p",
            boundedInt(upgraded ? stored.get("standard_price") : null, 900, 0, 100000)
        );
        TierSettings quality = new TierSettings(
            bool(upgraded ? stored.get("quality_enabled") : null, true),
            boundedInt(upgraded ? stored.get("quality_duration_seconds") : null, 10, 3, 10),
            resolution(upgraded ? stored.get("quality_resolution") : null, "720p"),
            boundedInt(upgraded ? stored.get("quality_price") : null, 1200, 0, 100000)
        );
        return new RuntimeSettings(
            bool(stored.get("enabled"), false),
            bool(stored.get("paid_provider_requested"), false),
            bool(stored.get("gemini_enabled"), false),
            bool(stored.get("seedance_enabled"), false),
            bool(upgraded ? stored.get("agnes_route_enabled") : stored.get("agnes_requested"), false),
            standard,
            quality,
            boundedInt(upgraded ? stored.get("agnes_route_percent") : null, 0, 0, 25),
            boundedInt(upgraded ? stored.get("agnes_daily_limit") : null, 40, 0, 10000),
            boundedInt(upgraded ? stored.get("agnes_concurrency") : null, 1, 1, 100),
            boundedInt(upgraded ? stored.get("agnes_circuit_failures") : null, 3, 1, 100),
            boundedInt(stored.get("poll_seconds"), 5, 2, 60),
            boundedInt(stored.get("timeout_minutes"), properties.getTimeoutMinutes(), 5, 180),
            bool(stored.get("hfsy_enabled"), false)
        );
    }

    public VideoDtos.PublicConfig publicConfig() {
        RuntimeSettings runtime = current();
        boolean standardAvailable = providerAvailable(runtime, "gemini") || providerAvailable(runtime, "agnes");
        boolean qualityAvailable = providerAvailable(runtime, "seedance") || providerAvailable(runtime, "hfsy");
        return new VideoDtos.PublicConfig(
            runtime.enabled(),
            mockMode(),
            List.of("9:16", "16:9"),
            publicTier(runtime.standard(), standardAvailable),
            publicTier(runtime.quality(), qualityAvailable),
            DEFAULT_PUBLIC_MODEL,
            modelSettings().stream()
                .map(model -> publicModel(model, runtime))
                .toList()
        );
    }

    public Map<String, Object> adminView() {
        RuntimeSettings runtime = current();
        SecretState apimartSecret = apimartSecretState();
        SecretState agnesSecret = agnesSecretState();
        String agnesModel = agnesModel();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", runtime.enabled());
        view.put("paid_provider_requested", runtime.paidProviderRequested());
        view.put("paid_provider_env_enabled", properties.isPaidProviderEnabled());
        view.put("paid_provider_effective", paidEffective(runtime));
        view.put("gemini_enabled", runtime.geminiEnabled());
        view.put("gemini_effective", providerEffective(runtime, "gemini"));
        view.put("seedance_enabled", runtime.seedanceEnabled());
        view.put("seedance_effective", providerEffective(runtime, "seedance"));
        view.put("hfsy_enabled", runtime.hfsyEnabled());
        view.put("hfsy_effective", providerEffective(runtime, "hfsy"));
        view.put("hfsy_mappings", hfsyMappingsView());
        view.put("agnes_route_enabled", runtime.agnesRouteEnabled());
        view.put("agnes_env_enabled", properties.isAgnesEnabled());
        view.put("agnes_effective", agnesEffective(runtime));
        view.put("mock_env_enabled", properties.isMockEnabled());
        putSecretState(view, "apimart", apimartSecret);
        putSecretState(view, "agnes", agnesSecret);
        putSecretState(view, "hfsy", hfsySecretState());
        view.put("agnes_model", agnesModel);
        putTier(view, "standard", runtime.standard());
        putTier(view, "quality", runtime.quality());
        view.put("agnes_route_percent", runtime.agnesRoutePercent());
        view.put("agnes_daily_limit", runtime.agnesDailyLimit());
        view.put("agnes_concurrency", runtime.agnesConcurrency());
        view.put("agnes_circuit_failures", runtime.agnesCircuitFailures());
        view.put("poll_seconds", runtime.pollSeconds());
        view.put("timeout_minutes", runtime.timeoutMinutes());
        view.put("mock_mode", mockMode());
        view.put("agnes_keys", adminAgnesKeys());
        view.put("model_configs", modelSettings().stream()
            .map(model -> publicModel(model, runtime, true))
            .toList());
        view.put("models", Map.of(
            "standard", "gemini-omni-flash-preview",
            "standard_route", agnesModel,
            "quality", "doubao-seedance-2.0-fast"
        ));
        return view;
    }

    public Map<String, Object> save(VideoDtos.AdminConfigUpdate update) {
        RuntimeSettings current = current();
        Map<String, Object> stored = read();
        String agnesModel = requestedModel(update.agnesModel(), storedAgnesModel(stored));
        String encryptedAgnesKey = updatedEncryptedSecret(
            text(stored.get("agnes_api_key_encrypted")), update.agnesApiKey(), update.clearAgnesApiKey(), "Agnes"
        );
        List<StoredAgnesKey> agnesKeys = updatedAgnesKeys(
            storedAgnesKeys(stored), update.agnesKeys(), encryptedAgnesKey
        );
        if (update.agnesKeys() != null && agnesKeys.isEmpty()) {
            encryptedAgnesKey = "";
        }
        String encryptedApimartKey = updatedEncryptedSecret(
            text(stored.get("apimart_api_key_encrypted")), update.apimartApiKey(), update.clearApimartApiKey(), "APIMart"
        );
        String encryptedHfsyKey = updatedEncryptedSecret(
            text(stored.get("hfsy_api_key_encrypted")), update.hfsyApiKey(), update.clearHfsyApiKey(), "HFSY"
        );
        if (Boolean.TRUE.equals(update.clearHfsyApiKey()) && tasks.countActiveByProvider("hfsy") > 0) {
            throw new IllegalArgumentException("HFSY 仍有待提交或生成中的任务，暂时不能清除 API Key");
        }
        RuntimeSettings next = new RuntimeSettings(
            value(update.enabled(), current.enabled()),
            value(update.paidProviderRequested(), current.paidProviderRequested()),
            value(update.geminiEnabled(), current.geminiEnabled()),
            value(update.seedanceEnabled(), current.seedanceEnabled()),
            value(update.agnesRouteEnabled(), current.agnesRouteEnabled()),
            new TierSettings(
                value(update.standardEnabled(), current.standard().enabled()),
                boundedInt(update.standardDurationSeconds(), current.standard().durationSeconds(), 3, 10),
                "720p",
                boundedInt(update.standardPrice(), current.standard().price(), 0, 100000)
            ),
            new TierSettings(
                value(update.qualityEnabled(), current.quality().enabled()),
                boundedInt(update.qualityDurationSeconds(), current.quality().durationSeconds(), 3, 10),
                resolution(update.qualityResolution(), current.quality().resolution()),
                boundedInt(update.qualityPrice(), current.quality().price(), 0, 100000)
            ),
            boundedInt(update.agnesRoutePercent(), current.agnesRoutePercent(), 0, 25),
            boundedInt(update.agnesDailyLimit(), current.agnesDailyLimit(), 0, 10000),
            boundedInt(update.agnesConcurrency(), current.agnesConcurrency(), 1, 100),
            boundedInt(update.agnesCircuitFailures(), current.agnesCircuitFailures(), 1, 100),
            boundedInt(update.pollSeconds(), current.pollSeconds(), 2, 60),
            boundedInt(update.timeoutMinutes(), current.timeoutMinutes(), 5, 180),
            value(update.hfsyEnabled(), current.hfsyEnabled())
        );
        List<ModelSettings> models = updatedModelSettings(update.modelConfigs());
        boolean hfsyWillBeEffective = next.hfsyEnabled()
            && secretState(encryptedHfsyKey, properties.getHfsyApiKey()).usable();
        boolean anyRealProvider = anyRealProviderEffective(
            next, agnesKeys, encryptedAgnesKey, encryptedApimartKey, encryptedHfsyKey
        );
        validateRealPrices(next, models, anyRealProvider, hfsyWillBeEffective);
        write(next, agnesModel, encryptedAgnesKey, agnesKeys, encryptedApimartKey, encryptedHfsyKey, models);
        return adminView();
    }

    public ModelSelection selectModel(
        String requestedCode,
        String requestedResolution,
        Integer requestedDuration,
        String requestedAspectRatio
    ) {
        RuntimeSettings runtime = current();
        String normalizedCode = text(requestedCode);
        String code = normalizedCode.isBlank() ? DEFAULT_PUBLIC_MODEL : normalizedCode;
        ModelSettings model = modelSettings().stream()
            .filter(candidate -> candidate.code().equals(code))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("不支持的视频模型"));
        if (!model.enabled()) throw new IllegalArgumentException(model.name() + " 已由管理员关闭");

        String resolution = text(requestedResolution).toLowerCase();
        if (resolution.isBlank()) resolution = model.defaultResolution();
        int duration = requestedDuration == null ? model.defaultDurationSeconds() : requestedDuration;
        String aspectRatio = text(requestedAspectRatio);
        if (aspectRatio.isBlank()) aspectRatio = model.aspectRatios().get(0);
        if (!model.aspectRatios().contains(aspectRatio)) {
            throw new IllegalArgumentException(model.name() + " 不支持 " + aspectRatio + " 画面比例");
        }
        String finalResolution = resolution;
        int finalDuration = duration;
        VideoVariantSettings variant = model.variants().stream()
            .filter(candidate -> candidate.resolution().equals(finalResolution)
                && candidate.durationSeconds() == finalDuration)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(model.name() + " 不支持所选分辨率与时长组合"));
        if (!variant.enabled()) throw new IllegalArgumentException("所选视频规格已由管理员关闭");
        ProviderRoute route = routeFor(runtime, model, variant, aspectRatio);
        if ("disabled".equals(route.provider())) {
            throw new IllegalArgumentException(model.name() + " 当前没有可用的固定视频供应商");
        }
        if ("agnes".equals(route.provider())) {
            String blocked = agnesDirectBlockReason(runtime);
            if (!blocked.isBlank()) {
                throw new IllegalArgumentException("agnes_daily_limit".equals(blocked)
                    ? "Agnes 今日任务额度已用尽"
                    : "Agnes 暂时熔断，请稍后再试");
            }
        }
        return new ModelSelection(
            model.code(), model.name(), route.provider(), route.model(), resolution, duration,
            aspectRatio, effectivePrice(variant.price(), route), model.audioToggle(), model.maxReferenceImages(), route.reason()
        );
    }

    public int billableCreditsFor(ModelSelection selection) {
        if (selection == null) throw new IllegalArgumentException("请选择视频模型");
        if (mockMode()) return 0;
        if (!providerEffective(selection.provider())) {
            throw new IllegalArgumentException(selection.name() + " 尚未真正启用");
        }
        if (selection.price() < 1) {
            throw new IllegalArgumentException("付费视频规格售价必须大于或等于 1 积分");
        }
        int minimum = minimumCreditsForHfsyModel(selection.provider(), selection.model());
        if (minimum > 0 && selection.price() < minimum) {
            throw new IllegalArgumentException(
                selection.name() + " 当前售价低于 HFSY 成本保护线，至少需要 " + minimum + " 积分"
            );
        }
        return selection.price();
    }

    public String normalizeMode(String mode) {
        return "quality".equalsIgnoreCase(mode) || "fast".equalsIgnoreCase(mode) ? "quality" : "standard";
    }

    public TierSettings tierFor(String mode) {
        RuntimeSettings runtime = current();
        return "quality".equals(normalizeMode(mode)) ? runtime.quality() : runtime.standard();
    }

    public String providerFor(String mode, String routingKey) {
        return assignmentFor(mode, routingKey, "").provider();
    }

    public ProviderAssignment assignmentFor(String mode, String routingKey, String prompt) {
        RuntimeSettings runtime = current();
        String normalizedMode = normalizeMode(mode);
        TierSettings tier = "quality".equals(normalizedMode) ? runtime.quality() : runtime.standard();
        if (!runtime.enabled() || !tier.enabled()) {
            return new ProviderAssignment("disabled", "", "tier_disabled");
        }
        boolean gemini = providerAvailable(runtime, "gemini");
        boolean agnes = providerAvailable(runtime, "agnes");

        if ("quality".equals(normalizedMode)) {
            VideoVariantSettings variant = new VideoVariantSettings(
                tier.resolution(), tier.durationSeconds(), tier.price(), true
            );
            ProviderRoute route = routeFor(
                runtime,
                defaultModelSettings().stream()
                    .filter(model -> "seedance-fast".equals(model.code()))
                    .findFirst()
                    .orElseThrow(),
                variant,
                "16:9"
            );
            return new ProviderAssignment(route.provider(), route.model(), route.reason());
        }
        ProviderAssignment standardProviders = assignGeminiOrAgnes(runtime, routingKey, prompt, gemini, agnes);
        if (!"disabled".equals(standardProviders.provider())) return standardProviders;
        return new ProviderAssignment("disabled", "", "no_standard_provider");
    }

    public String providerFor(String mode) {
        return providerFor(mode, "default-route");
    }

    public String fallbackAfter(String provider) {
        return "";
    }

    public int configuredPriceFor(String mode) {
        return tierFor(mode).price();
    }

    public int billableCreditsFor(String mode, String provider) {
        if ("disabled".equals(provider)) throw new IllegalArgumentException("当前没有已启用且可用的视频模型");
        if (mockMode()) return 0;
        if (!providerEffective(provider)) throw new IllegalArgumentException("所选视频模型尚未真正启用");
        int price = configuredPriceFor(mode);
        if (price < 1) throw new IllegalArgumentException("付费视频档位售价必须大于或等于 1 积分");
        return price;
    }

    public boolean paidEffective() {
        return paidEffective(current());
    }

    public boolean agnesEffective() {
        return agnesEffective(current());
    }

    public boolean mockMode() {
        return properties.isMockEnabled();
    }

    public boolean providerEffective(String provider) {
        return providerEffective(current(), provider);
    }

    public String agnesApiKey() {
        return agnesKeyRuntimes(read()).stream()
            .filter(AgnesKeyRuntime::usable)
            .map(AgnesKeyRuntime::secret)
            .findFirst()
            .orElse("");
    }

    public String agnesApiKeyFor(VideoTaskEntity task) {
        String keyId = agnesKeyId(task);
        if (!keyId.isBlank()) {
            return agnesKeyRuntimes(read()).stream()
                .filter(key -> key.id().equals(keyId) && key.usable())
                .map(AgnesKeyRuntime::secret)
                .findFirst()
                .orElse("");
        }
        return agnesApiKey();
    }

    public List<AgnesKeyRuntime> dispatchableAgnesKeys() {
        List<AgnesKeyRuntime> usable = agnesKeyRuntimes(read()).stream()
            .filter(key -> key.enabled() && key.usable())
            .toList();
        if (!usable.isEmpty() || !mockMode()) return usable;
        return List.of(new AgnesKeyRuntime(
            "agnes-mock", "Mock Key", true, 100, "", "mock", "mock", true, false
        ));
    }

    public String agnesKeyId(VideoTaskEntity task) {
        if (task == null || task.getMetadata() == null || task.getMetadata().isBlank()) return "";
        try {
            Map<String, Object> metadata = json.readValue(task.getMetadata(), new TypeReference<>() {});
            return text(metadata.get("agnes_key_id"));
        } catch (Exception ignored) {
            return "";
        }
    }

    public String apimartApiKey() {
        return configuredSecret("apimart_api_key_encrypted", properties.getApimartApiKey());
    }

    public String hfsyApiKey() {
        return configuredSecret("hfsy_api_key_encrypted", properties.getHfsyApiKey());
    }

    public String agnesModel() {
        return storedAgnesModel(read());
    }

    public boolean agnesCircuitOpen() {
        RuntimeSettings runtime = current();
        AgnesCircuitState state = agnesCircuitState(runtime.agnesCircuitFailures());
        return state.failures() >= runtime.agnesCircuitFailures()
            && state.latestFailureAt() != null
            && state.latestFailureAt().isAfter(OffsetDateTime.now().minus(AGNES_CIRCUIT_COOLDOWN));
    }

    private String agnesRouteReason(RuntimeSettings runtime, String routingKey, String prompt) {
        if (!runtime.agnesRouteEnabled() || runtime.agnesRoutePercent() <= 0) return "agnes_route_disabled";
        if (runtime.agnesDailyLimit() <= 0 || agnesTasksToday() >= runtime.agnesDailyLimit()) return "agnes_daily_limit";
        if (agnesCircuitOpen()) return "agnes_circuit_open";
        if (!isAgnesSceneSuitable(prompt)) return "agnes_scene_unsuitable";
        int bucket = Math.floorMod((routingKey == null ? "" : routingKey).hashCode(), 100);
        return bucket < runtime.agnesRoutePercent() ? "agnes_selected" : "agnes_percentage_miss";
    }

    private ProviderAssignment assignGeminiOrAgnes(
        RuntimeSettings runtime,
        String routingKey,
        String prompt,
        boolean gemini,
        boolean agnes
    ) {
        if (gemini && agnes) {
            String agnesReason = agnesRouteReason(runtime, routingKey, prompt);
            if ("agnes_selected".equals(agnesReason)) {
                return new ProviderAssignment("agnes", agnesModel(), agnesReason);
            }
            return new ProviderAssignment("gemini", "gemini-omni-flash-preview", agnesReason);
        }
        if (agnes) {
            String blocked = agnesDirectBlockReason(runtime);
            if (blocked.isBlank()) {
                return new ProviderAssignment("agnes", agnesModel(), "agnes_only_available");
            }
            return new ProviderAssignment("disabled", "", blocked);
        }
        if (gemini) {
            return new ProviderAssignment("gemini", "gemini-omni-flash-preview", "gemini_only_available");
        }
        return new ProviderAssignment("disabled", "", "no_standard_provider");
    }

    private String agnesDirectBlockReason(RuntimeSettings runtime) {
        if (runtime.agnesDailyLimit() <= 0 || agnesTasksToday() >= runtime.agnesDailyLimit()) return "agnes_daily_limit";
        if (agnesCircuitOpen()) return "agnes_circuit_open";
        return "";
    }

    private long agnesTasksToday() {
        return recentTasksToday().stream().filter(this::hasAgnesRoute).count();
    }

    boolean isAgnesSceneSuitable(String prompt) {
        String normalized = prompt == null ? "" : prompt.toLowerCase();
        List<String> highRisk = List.of(
            "字幕", "翻译", "改字", "替换文字", "口播", "多镜头", "快速切换", "快速转场", "换场",
            "logo变化", "修改logo", "change logo", "replace text", "subtitles", "multi-scene", "fast cut"
        );
        return highRisk.stream().noneMatch(normalized::contains);
    }

    private AgnesCircuitState agnesCircuitState(int stopAt) {
        int failures = 0;
        OffsetDateTime latestFailureAt = null;
        for (VideoTaskEntity task : recentTasksToday()) {
            if (!hasAgnesRoute(task)) continue;
            if (hasAgnesFailure(task)) {
                failures++;
                OffsetDateTime failedAt = latestAgnesFailureAt(task);
                if (failedAt != null && (latestFailureAt == null || failedAt.isAfter(latestFailureAt))) {
                    latestFailureAt = failedAt;
                }
                if (failures >= stopAt) return new AgnesCircuitState(failures, latestFailureAt);
            } else if ("completed".equals(task.getStatus()) && "agnes".equals(task.getProvider())) {
                return new AgnesCircuitState(0, null);
            }
        }
        return new AgnesCircuitState(failures, latestFailureAt);
    }

    private List<VideoTaskEntity> recentTasksToday() {
        OffsetDateTime start = BusinessTime.startOfDay(BusinessTime.today());
        List<VideoTaskEntity> rows = tasks.selectList(new LambdaQueryWrapper<VideoTaskEntity>()
            .eq(VideoTaskEntity::getDeleted, 0)
            .ge(VideoTaskEntity::getCreatedAt, start)
            .orderByDesc(VideoTaskEntity::getCreatedAt)
            .last("limit 10000"));
        return rows == null ? List.of() : rows;
    }

    private boolean hasAgnesRoute(VideoTaskEntity task) {
        if ("agnes".equals(task.getProvider())) return true;
        return attempts(task).stream().anyMatch(row -> "agnes".equals(String.valueOf(row.get("provider"))));
    }

    private boolean hasAgnesFailure(VideoTaskEntity task) {
        return attempts(task).stream().anyMatch(row ->
            "agnes".equals(String.valueOf(row.get("provider")))
                && "failed".equals(String.valueOf(row.get("status")))
        );
    }

    private OffsetDateTime latestAgnesFailureAt(VideoTaskEntity task) {
        OffsetDateTime latest = null;
        for (Map<String, Object> row : attempts(task)) {
            if (!"agnes".equals(String.valueOf(row.get("provider")))
                || !"failed".equals(String.valueOf(row.get("status")))) {
                continue;
            }
            try {
                OffsetDateTime time = OffsetDateTime.parse(String.valueOf(row.get("time")));
                if (latest == null || time.isAfter(latest)) latest = time;
            } catch (Exception ignored) { }
        }
        if (latest != null) return latest;
        if (task.getCompletedAt() != null) return task.getCompletedAt();
        if (task.getUpdatedAt() != null) return task.getUpdatedAt();
        return task.getCreatedAt();
    }

    private List<Map<String, Object>> attempts(VideoTaskEntity task) {
        try {
            return json.readValue(task.getProviderAttempts() == null ? "[]" : task.getProviderAttempts(), new TypeReference<>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private record AgnesCircuitState(int failures, OffsetDateTime latestFailureAt) {}

    private boolean paidEffective(RuntimeSettings runtime) {
        return runtime.paidProviderRequested()
            && properties.isPaidProviderEnabled()
            && apimartSecretState().usable();
    }

    private boolean agnesEffective(RuntimeSettings runtime) {
        return runtime.agnesRouteEnabled()
            && properties.isAgnesEnabled()
            && agnesKeyRuntimes(read()).stream().anyMatch(key -> key.enabled() && key.usable());
    }

    private boolean hfsyEffective(RuntimeSettings runtime) {
        return runtime.hfsyEnabled() && hfsySecretState().usable();
    }

    private boolean providerAvailable(RuntimeSettings runtime, String provider) {
        if (!mockMode()) return providerEffective(runtime, provider);
        return switch (provider) {
            case "gemini" -> runtime.paidProviderRequested() && runtime.geminiEnabled();
            case "seedance" -> runtime.paidProviderRequested() && runtime.seedanceEnabled();
            case "agnes" -> runtime.agnesRouteEnabled();
            case "hfsy" -> runtime.hfsyEnabled();
            default -> false;
        };
    }

    private boolean providerEffective(RuntimeSettings runtime, String provider) {
        return switch (provider) {
            case "gemini" -> paidEffective(runtime) && runtime.geminiEnabled();
            case "seedance" -> paidEffective(runtime) && runtime.seedanceEnabled();
            case "agnes" -> agnesEffective(runtime);
            case "hfsy" -> hfsyEffective(runtime);
            default -> false;
        };
    }

    private boolean apimartEffective(RuntimeSettings runtime, String encryptedApimartKey) {
        return runtime.paidProviderRequested()
            && properties.isPaidProviderEnabled()
            && secretState(encryptedApimartKey, properties.getApimartApiKey()).usable();
    }

    private boolean anyRealProviderEffective(
        RuntimeSettings runtime,
        List<StoredAgnesKey> agnesKeys,
        String encryptedAgnesKey,
        String encryptedApimartKey,
        String encryptedHfsyKey
    ) {
        boolean apimart = apimartEffective(runtime, encryptedApimartKey)
            && (runtime.geminiEnabled() || runtime.seedanceEnabled());
        boolean agnes = runtime.agnesRouteEnabled()
            && properties.isAgnesEnabled()
            && hasUsableAgnesSecret(agnesKeys, encryptedAgnesKey);
        boolean hfsy = runtime.hfsyEnabled()
            && secretState(encryptedHfsyKey, properties.getHfsyApiKey()).usable();
        return apimart || agnes || hfsy;
    }

    private void validateRealPrices(
        RuntimeSettings runtime,
        List<ModelSettings> models,
        boolean anyRealProvider,
        boolean hfsyWillBeEffective
    ) {
        if (mockMode() || !anyRealProvider) return;
        if ((runtime.standard().enabled() && runtime.standard().price() < 1)
            || (runtime.quality().enabled() && runtime.quality().price() < 1)) {
            throw new IllegalArgumentException("付费上游生效时，标准模式和品质模式售价都必须大于或等于 1 积分");
        }
        boolean invalidModelPrice = models.stream()
            .filter(ModelSettings::enabled)
            .flatMap(model -> model.variants().stream())
            .anyMatch(variant -> variant.enabled() && variant.price() < 1);
        if (invalidModelPrice) {
            throw new IllegalArgumentException("付费上游生效时，已启用的视频规格售价必须大于或等于 1 积分");
        }
        if (!hfsyWillBeEffective) return;
        for (ModelSettings model : models) {
            if (!model.enabled() || !"seedance".equals(model.provider())) continue;
            for (VideoVariantSettings variant : model.variants()) {
                if (!variant.enabled()) continue;
                String routedModel = hfsyModel(model.code(), variant.resolution(), HFSY_RATIOS.get(0));
                int minimum = minimumCreditsForHfsyModel("hfsy", routedModel);
                if (minimum > 0 && variant.price() < minimum) {
                    throw new IllegalArgumentException(
                        model.name() + " " + variant.resolution() + " / "
                            + variant.durationSeconds() + " 秒售价不能低于 " + minimum
                            + " 积分，否则 HFSY 路由会亏损"
                    );
                }
            }
        }
    }

    private VideoDtos.PublicTier publicTier(TierSettings tier, boolean providerAvailable) {
        return new VideoDtos.PublicTier(
            tier.enabled() && providerAvailable,
            tier.durationSeconds(),
            tier.resolution(),
            tier.price()
        );
    }

    private VideoDtos.VideoModelConfig publicModel(ModelSettings model, RuntimeSettings runtime) {
        return publicModel(model, runtime, false);
    }

    private VideoDtos.VideoModelConfig publicModel(
        ModelSettings model,
        RuntimeSettings runtime,
        boolean showConfiguredVariants
    ) {
        List<String> aspectRatios = availableAspectRatios(model, runtime, showConfiguredVariants);
        List<VideoDtos.VideoVariant> variants = model.variants().stream()
            .map(variant -> new VideoDtos.VideoVariant(
                variant.resolution(),
                variant.durationSeconds(),
                effectiveVariantPrice(runtime, model, variant),
                variant.enabled() && (showConfiguredVariants
                    || aspectRatios.stream().anyMatch(ratio ->
                        !"disabled".equals(routeFor(runtime, model, variant, ratio).provider())))
            ))
            .toList();
        boolean available = variants.stream().anyMatch(VideoDtos.VideoVariant::enabled);
        return new VideoDtos.VideoModelConfig(
            model.code(),
            model.name(),
            model.description(),
            model.provider(),
            model.model(),
            model.enabled() && (showConfiguredVariants || available),
            model.defaultResolution(),
            model.defaultDurationSeconds(),
            model.durationSelectable(),
            model.audioToggle(),
            model.maxReferenceImages(),
            aspectRatios,
            model.variants().stream().map(VideoVariantSettings::resolution).distinct().toList(),
            model.variants().stream().map(VideoVariantSettings::durationSeconds).distinct().sorted().toList(),
            variants
        );
    }

    private List<String> availableAspectRatios(
        ModelSettings model,
        RuntimeSettings runtime,
        boolean showConfiguredVariants
    ) {
        if (showConfiguredVariants || !"seedance".equals(model.provider())) return model.aspectRatios();
        return model.aspectRatios().stream()
            .filter(ratio -> model.variants().stream().anyMatch(variant ->
                variant.enabled() && !"disabled".equals(routeFor(runtime, model, variant, ratio).provider())))
            .toList();
    }

    private ProviderRoute routeFor(
        RuntimeSettings runtime,
        ModelSettings model,
        VideoVariantSettings variant,
        String aspectRatio
    ) {
        if ("gemini".equals(model.provider())) {
            return providerAvailable(runtime, "gemini")
                ? new ProviderRoute("gemini", model.model(), "fixed_apimart_gemini")
                : ProviderRoute.DISABLED;
        }
        if ("agnes".equals(model.provider())) {
            return providerAvailable(runtime, "agnes")
                ? new ProviderRoute("agnes", agnesModel(), "fixed_agnes")
                : ProviderRoute.DISABLED;
        }
        if (!"seedance".equals(model.provider())) return ProviderRoute.DISABLED;

        String hfsyModel = hfsyModel(model.code(), variant.resolution(), aspectRatio);
        if (!hfsyModel.isBlank() && providerAvailable(runtime, "hfsy")) {
            return new ProviderRoute("hfsy", hfsyModel, "fixed_hfsy_exact_match");
        }
        if (providerAvailable(runtime, "seedance")) {
            return new ProviderRoute("seedance", model.model(), "fixed_apimart_seedance");
        }
        return ProviderRoute.DISABLED;
    }

    private String hfsyModel(String code, String resolution, String aspectRatio) {
        if (!HFSY_RATIOS.contains(aspectRatio)) return "";
        String normalizedResolution = resolution == null ? "" : resolution.toLowerCase();
        return HFSY_MODEL_MAPPINGS.stream()
            .filter(mapping -> mapping.modelCode().equals(code)
                && mapping.resolution().equals(normalizedResolution))
            .map(HfsyModelMapping::hfsyModel)
            .findFirst()
            .orElse("");
    }

    private List<Map<String, Object>> hfsyMappingsView() {
        return HFSY_MODEL_MAPPINGS.stream().map(mapping -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("model_code", mapping.modelCode());
            value.put("model_name", mapping.modelName());
            value.put("resolution", mapping.resolution());
            value.put("hfsy_model", mapping.hfsyModel());
            value.put("cost_cny", HFSY_CALL_COSTS.get(mapping.hfsyModel()));
            value.put("minimum_credits", minimumCreditsForHfsyModel("hfsy", mapping.hfsyModel()));
            value.put("aspect_ratios", HFSY_RATIOS);
            return value;
        }).toList();
    }

    private int effectiveVariantPrice(
        RuntimeSettings runtime,
        ModelSettings model,
        VideoVariantSettings variant
    ) {
        int price = variant.price();
        for (String ratio : model.aspectRatios()) {
            price = Math.max(price, effectivePrice(price, routeFor(runtime, model, variant, ratio)));
        }
        return price;
    }

    private int effectivePrice(int configuredPrice, ProviderRoute route) {
        int minimum = route == null ? 0 : minimumCreditsForHfsyModel(route.provider(), route.model());
        return Math.max(configuredPrice, minimum);
    }

    private int minimumCreditsForHfsyModel(String provider, String model) {
        if (!"hfsy".equals(provider)) return 0;
        BigDecimal cost = HFSY_CALL_COSTS.get(model);
        if (cost == null || cost.signum() <= 0) return 0;
        return cost.divide(
            CNY_PER_CREDIT.multiply(MIN_REVENUE_AFTER_MARGIN),
            0,
            RoundingMode.CEILING
        ).intValue();
    }

    public List<ModelSettings> modelSettings() {
        List<ModelSettings> defaults = defaultModelSettings();
        Object raw = read().get("model_configs");
        if (!(raw instanceof List<?> rows)) return defaults;
        Map<String, Map<?, ?>> storedByCode = new HashMap<>();
        for (Object item : rows) {
            if (item instanceof Map<?, ?> row) storedByCode.put(text(row.get("code")), row);
        }
        return defaults.stream().map(model -> {
            Map<?, ?> row = storedByCode.get(model.code());
            if (row == null) return model;
            Map<String, Map<?, ?>> variantsByKey = new HashMap<>();
            if (row.get("variants") instanceof List<?> variants) {
                for (Object item : variants) {
                    if (!(item instanceof Map<?, ?> variant)) continue;
                    variantsByKey.put(variantKey(
                        text(variant.get("resolution")),
                        integer(variant.get("duration_seconds"), -1)
                    ), variant);
                }
            }
            List<VideoVariantSettings> mergedVariants = model.variants().stream().map(variant -> {
                Map<?, ?> stored = variantsByKey.get(variantKey(variant.resolution(), variant.durationSeconds()));
                if (stored == null) return variant;
                return new VideoVariantSettings(
                    variant.resolution(),
                    variant.durationSeconds(),
                    boundedInt(stored.get("price"), variant.price(), 0, 100000),
                    bool(stored.get("enabled"), variant.enabled())
                );
            }).toList();
            return model.withConfig(bool(row.get("enabled"), model.enabled()), mergedVariants);
        }).toList();
    }

    private List<ModelSettings> updatedModelSettings(List<VideoDtos.VideoModelConfigUpdate> requested) {
        List<ModelSettings> current = modelSettings();
        if (requested == null) return current;
        Map<String, VideoDtos.VideoModelConfigUpdate> updates = new HashMap<>();
        for (VideoDtos.VideoModelConfigUpdate update : requested) {
            if (update == null || text(update.code()).isBlank()) continue;
            if (updates.put(update.code(), update) != null) {
                throw new IllegalArgumentException("视频模型配置不能重复");
            }
        }
        for (String code : updates.keySet()) {
            if (current.stream().noneMatch(model -> model.code().equals(code))) {
                throw new IllegalArgumentException("不支持的视频模型配置：" + code);
            }
        }
        return current.stream().map(model -> {
            VideoDtos.VideoModelConfigUpdate update = updates.get(model.code());
            if (update == null) return model;
            Map<String, VideoDtos.VideoVariantUpdate> variantUpdates = new HashMap<>();
            if (update.variants() != null) {
                for (VideoDtos.VideoVariantUpdate variant : update.variants()) {
                    if (variant == null) continue;
                    String key = variantKey(text(variant.resolution()).toLowerCase(), variant.durationSeconds());
                    if (variantUpdates.put(key, variant) != null) {
                        throw new IllegalArgumentException(model.name() + " 的视频规格不能重复");
                    }
                }
            }
            for (String key : variantUpdates.keySet()) {
                if (model.variants().stream().noneMatch(variant ->
                    variantKey(variant.resolution(), variant.durationSeconds()).equals(key))) {
                    throw new IllegalArgumentException(model.name() + " 包含不支持的视频规格");
                }
            }
            List<VideoVariantSettings> variants = model.variants().stream().map(variant -> {
                VideoDtos.VideoVariantUpdate input = variantUpdates.get(
                    variantKey(variant.resolution(), variant.durationSeconds())
                );
                if (input == null) return variant;
                return new VideoVariantSettings(
                    variant.resolution(),
                    variant.durationSeconds(),
                    Math.max(0, Math.min(100000, input.price())),
                    input.enabled()
                );
            }).toList();
            return model.withConfig(update.enabled(), variants);
        }).toList();
    }

    private List<ModelSettings> defaultModelSettings() {
        return List.of(
            model(
                "gemini-omni", "Gemini Omni", "自然运动与商品镜头理解", "gemini",
                "gemini-omni-flash-preview", "720p", 10, false, false, 3, GEMINI_RATIOS,
                variant("720p", 10, 950)
            ),
            model(
                "agnes-v2", "Agnes V2.0", "单图排队生成，适合轻量商品动态", "agnes",
                DEFAULT_AGNES_MODEL, "720p", 10, false, false, 1, AGNES_RATIOS,
                variant("720p", 10, 100)
            ),
            model(
                "seedance-mini", "Seedance Mini", "轻量快速，适合批量商品短视频", "seedance",
                "doubao-seedance-2.0-mini", "720p", 10, true, true, 4, SEEDANCE_RATIOS,
                variant("480p", 5, 150), variant("480p", 8, 230),
                variant("480p", 10, 290), variant("480p", 15, 430),
                variant("720p", 5, 320), variant("720p", 8, 490),
                variant("720p", 10, 610), variant("720p", 15, 920)
            ),
            model(
                "seedance-fast", "Seedance 2.0 Fast", "速度与质量均衡，适合常规投放", "seedance",
                "doubao-seedance-2.0-fast", "720p", 10, true, true, 4, SEEDANCE_RATIOS,
                variant("480p", 5, 290), variant("480p", 8, 460),
                variant("480p", 10, 570), variant("480p", 15, 850),
                variant("720p", 5, 610), variant("720p", 8, 980),
                variant("720p", 10, 1220), variant("720p", 15, 1830)
            ),
            model(
                "seedance-2", "Seedance 2.0", "完整质量档，支持最高 4K", "seedance",
                "doubao-seedance-2.0", "720p", 10, true, true, 4, SEEDANCE_RATIOS,
                variant("480p", 5, 360), variant("480p", 8, 570),
                variant("480p", 10, 710), variant("480p", 15, 1060),
                variant("720p", 5, 760), variant("720p", 8, 1220),
                variant("720p", 10, 1520), variant("720p", 15, 2280),
                variant("1080p", 5, 1890), variant("1080p", 8, 3030),
                variant("1080p", 10, 3780), variant("1080p", 15, 5670),
                variant("4k", 5, 3850), variant("4k", 8, 6160),
                variant("4k", 10, 7700), variant("4k", 15, 11550)
            )
        );
    }

    private ModelSettings model(
        String code,
        String name,
        String description,
        String provider,
        String model,
        String defaultResolution,
        int defaultDuration,
        boolean durationSelectable,
        boolean audioToggle,
        int maxReferenceImages,
        List<String> aspectRatios,
        VideoVariantSettings... variants
    ) {
        return new ModelSettings(
            code, name, description, provider, model, true, defaultResolution, defaultDuration,
            durationSelectable, audioToggle, maxReferenceImages, aspectRatios, List.of(variants)
        );
    }

    private VideoVariantSettings variant(String resolution, int duration, int price) {
        return new VideoVariantSettings(resolution, duration, price, true);
    }

    private String variantKey(String resolution, int duration) {
        return resolution + ":" + duration;
    }

    private void putTier(Map<String, Object> view, String prefix, TierSettings tier) {
        view.put(prefix + "_enabled", tier.enabled());
        view.put(prefix + "_duration_seconds", tier.durationSeconds());
        view.put(prefix + "_resolution", tier.resolution());
        view.put(prefix + "_price", tier.price());
    }

    private Map<String, Object> read() {
        SystemSettingEntity entity = settings.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
            .eq(SystemSettingEntity::getSettingKey, SETTING_KEY)
            .eq(SystemSettingEntity::getDeleted, 0)
            .last("limit 1"));
        if (entity == null || entity.getSettingValue() == null || entity.getSettingValue().isBlank()) return Map.of();
        try {
            return json.readValue(entity.getSettingValue(), new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("视频运行配置无法解析", exception);
        }
    }

    private void write(
        RuntimeSettings runtime,
        String agnesModel,
        String encryptedAgnesKey,
        List<StoredAgnesKey> agnesKeys,
        String encryptedApimartKey,
        String encryptedHfsyKey,
        List<ModelSettings> models
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("config_version", CONFIG_VERSION);
        value.put("enabled", runtime.enabled());
        value.put("paid_provider_requested", runtime.paidProviderRequested());
        value.put("gemini_enabled", runtime.geminiEnabled());
        value.put("seedance_enabled", runtime.seedanceEnabled());
        value.put("hfsy_enabled", runtime.hfsyEnabled());
        value.put("agnes_route_enabled", runtime.agnesRouteEnabled());
        putTier(value, "standard", runtime.standard());
        putTier(value, "quality", runtime.quality());
        value.put("agnes_route_percent", runtime.agnesRoutePercent());
        value.put("agnes_daily_limit", runtime.agnesDailyLimit());
        value.put("agnes_concurrency", runtime.agnesConcurrency());
        value.put("agnes_circuit_failures", runtime.agnesCircuitFailures());
        value.put("agnes_model", agnesModel);
        if (notBlank(encryptedAgnesKey)) value.put("agnes_api_key_encrypted", encryptedAgnesKey);
        if (agnesKeys != null && !agnesKeys.isEmpty()) {
            value.put("agnes_keys", agnesKeys.stream().map(key -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", key.id());
                row.put("label", key.label());
                row.put("enabled", key.enabled());
                row.put("concurrency", key.concurrency());
                if (notBlank(key.encryptedSecret())) row.put("api_key_encrypted", key.encryptedSecret());
                return row;
            }).toList());
        }
        if (notBlank(encryptedApimartKey)) value.put("apimart_api_key_encrypted", encryptedApimartKey);
        if (notBlank(encryptedHfsyKey)) value.put("hfsy_api_key_encrypted", encryptedHfsyKey);
        value.put("model_configs", models.stream().map(model -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", model.code());
            row.put("enabled", model.enabled());
            row.put("variants", model.variants().stream().map(variant -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("resolution", variant.resolution());
                item.put("duration_seconds", variant.durationSeconds());
                item.put("price", variant.price());
                item.put("enabled", variant.enabled());
                return item;
            }).toList());
            return row;
        }).toList());
        value.put("poll_seconds", runtime.pollSeconds());
        value.put("timeout_minutes", runtime.timeoutMinutes());
        value.put("updated_at", OffsetDateTime.now().toString());
        try {
            SystemSettingEntity entity = settings.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
                .eq(SystemSettingEntity::getSettingKey, SETTING_KEY)
                .eq(SystemSettingEntity::getDeleted, 0)
                .last("limit 1"));
            OffsetDateTime now = OffsetDateTime.now();
            if (entity == null) {
                entity = new SystemSettingEntity();
                entity.setId("setting_video_runtime_config");
                entity.setSettingKey(SETTING_KEY);
                entity.setDescription("商品视频运行配置；后台 API Key 使用 AES-GCM 加密保存且不通过接口回显");
                entity.setCreatedAt(now);
                entity.setDeleted(0);
            }
            entity.setSettingValue(json.writeValueAsString(value));
            entity.setUpdatedAt(now);
            if (settings.selectById(entity.getId()) == null) settings.insert(entity);
            else settings.updateById(entity);
        } catch (Exception exception) {
            throw new IllegalStateException("视频运行配置保存失败", exception);
        }
    }

    private String resolution(Object value, String fallback) {
        String normalized = value == null ? "" : String.valueOf(value).trim().toLowerCase();
        return "480p".equals(normalized) || "720p".equals(normalized) ? normalized : fallback;
    }

    private String storedAgnesModel(Map<String, Object> stored) {
        String model = text(stored.get("agnes_model"));
        return validModel(model) ? model : DEFAULT_AGNES_MODEL;
    }

    private String requestedModel(String value, String fallback) {
        if (value == null) return fallback;
        String model = value.trim();
        if (!validModel(model)) {
            throw new IllegalArgumentException("Agnes 模型 ID 只能包含字母、数字、点、下划线、斜杠、冒号和连字符，长度不超过 128");
        }
        return model;
    }

    private boolean validModel(String model) {
        return model != null && model.matches("[A-Za-z0-9._:/-]{1,128}");
    }

    private List<StoredAgnesKey> storedAgnesKeys(Map<String, Object> stored) {
        Object raw = stored.get("agnes_keys");
        if (!(raw instanceof List<?> rows)) return List.of();
        List<StoredAgnesKey> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) continue;
            String id = text(row.get("id"));
            if (!validKeyId(id) || !ids.add(id)) continue;
            String label = text(row.get("label"));
            if (label.isBlank()) label = "Agnes Key " + (result.size() + 1);
            result.add(new StoredAgnesKey(
                id,
                label.substring(0, Math.min(label.length(), 40)),
                bool(row.get("enabled"), true),
                boundedInt(row.get("concurrency"), 1, 1, 100),
                text(row.get("api_key_encrypted"))
            ));
        }
        return List.copyOf(result);
    }

    private List<StoredAgnesKey> updatedAgnesKeys(
        List<StoredAgnesKey> existing,
        List<VideoDtos.AgnesKeyUpdate> requested,
        String legacyEncryptedSecret
    ) {
        if (requested == null) return existing == null ? List.of() : existing;
        if (requested.size() > 20) throw new IllegalArgumentException("Agnes API Key 最多配置 20 个");

        Map<String, StoredAgnesKey> previous = new HashMap<>();
        if (existing != null) existing.forEach(key -> previous.put(key.id(), key));
        if (!legacyEncryptedSecret.isBlank() && previous.isEmpty()) {
            previous.put(LEGACY_AGNES_KEY_ID, new StoredAgnesKey(
                LEGACY_AGNES_KEY_ID, "Agnes Key 1", true, 1, legacyEncryptedSecret
            ));
        }

        List<StoredAgnesKey> next = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < requested.size(); index++) {
            VideoDtos.AgnesKeyUpdate input = requested.get(index);
            String id = text(input.id());
            if (id.isBlank()) id = "agnes-" + UUID.randomUUID().toString().replace("-", "");
            if (!validKeyId(id) || !ids.add(id)) {
                throw new IllegalArgumentException("Agnes Key 编号无效或重复");
            }
            String label = text(input.label());
            if (label.isBlank()) label = "Agnes Key " + (index + 1);
            if (label.length() > 40) throw new IllegalArgumentException("Agnes Key 名称不能超过 40 个字符");

            StoredAgnesKey prior = previous.get(id);
            String encrypted = prior == null ? "" : prior.encryptedSecret();
            if (notBlank(input.apiKey())) {
                String plaintext = input.apiKey().trim();
                if (plaintext.length() > 4096) throw new IllegalArgumentException("Agnes API Key 长度不能超过 4096 个字符");
                encrypted = encryptSecret(plaintext);
            } else if (Boolean.TRUE.equals(input.clearApiKey())) {
                encrypted = "";
            }
            if (input.enabled() && encrypted.isBlank()) {
                throw new IllegalArgumentException(label + " 已启用，但尚未填写 API Key");
            }
            if ((encrypted.isBlank() || !input.enabled()) && tasks.countActiveAgnesByKey(id) > 0) {
                throw new IllegalArgumentException(label + " 仍有生成中的任务，暂时不能停用或清除");
            }
            next.add(new StoredAgnesKey(
                id, label, input.enabled(), Math.max(1, Math.min(100, input.concurrency())), encrypted
            ));
        }

        Set<String> nextIds = new HashSet<>(ids);
        for (StoredAgnesKey prior : previous.values()) {
            if (!nextIds.contains(prior.id()) && tasks.countActiveAgnesByKey(prior.id()) > 0) {
                throw new IllegalArgumentException(prior.label() + " 仍有生成中的任务，暂时不能删除");
            }
        }
        return List.copyOf(next);
    }

    private List<AgnesKeyRuntime> agnesKeyRuntimes(Map<String, Object> stored) {
        List<StoredAgnesKey> configured = storedAgnesKeys(stored);
        if (!configured.isEmpty()) {
            return configured.stream().map(key -> {
                try {
                    String secret = notBlank(key.encryptedSecret()) ? decryptSecret(key.encryptedSecret()) : "";
                    return new AgnesKeyRuntime(
                        key.id(), key.label(), key.enabled(), key.concurrency(), secret,
                        fingerprint(secret), "admin", notBlank(secret), false
                    );
                } catch (IllegalStateException ignored) {
                    return new AgnesKeyRuntime(
                        key.id(), key.label(), key.enabled(), key.concurrency(), "",
                        "invalid", "admin", false, true
                    );
                }
            }).toList();
        }

        String legacyEncrypted = text(stored.get("agnes_api_key_encrypted"));
        if (notBlank(legacyEncrypted)) {
            try {
                String secret = decryptSecret(legacyEncrypted);
                return List.of(new AgnesKeyRuntime(
                    LEGACY_AGNES_KEY_ID, "Agnes Key 1", true, 1, secret,
                    fingerprint(secret), "admin", notBlank(secret), false
                ));
            } catch (IllegalStateException ignored) {
                return List.of(new AgnesKeyRuntime(
                    LEGACY_AGNES_KEY_ID, "Agnes Key 1", true, 1, "",
                    "invalid", "admin", false, true
                ));
            }
        }

        String environmentSecret = text(properties.getAgnesApiKey());
        if (notBlank(environmentSecret)) {
            return List.of(new AgnesKeyRuntime(
                ENVIRONMENT_AGNES_KEY_ID, "服务器环境变量", true, 1, environmentSecret,
                fingerprint(environmentSecret), "environment", true, false
            ));
        }
        return List.of();
    }

    private List<Map<String, Object>> adminAgnesKeys() {
        List<AgnesKeyRuntime> keys = agnesKeyRuntimes(read());
        List<Map<String, Object>> metrics = tasks.selectAgnesKeyMetrics();
        Map<String, Map<String, Object>> metricsByKey = new HashMap<>();
        if (metrics != null) {
            for (Map<String, Object> row : metrics) {
                String keyId = text(row.get("key_id"));
                if (keyId.isBlank() && !keys.isEmpty()) keyId = keys.get(0).id();
                if (!keyId.isBlank()) metricsByKey.put(keyId, row);
            }
        }
        return keys.stream().map(key -> {
            Map<String, Object> metric = metricsByKey.getOrDefault(key.id(), Map.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", key.id());
            row.put("label", key.label());
            row.put("enabled", key.enabled());
            row.put("concurrency", key.concurrency());
            row.put("configured", key.usable());
            row.put("fingerprint", key.fingerprint());
            row.put("source", key.source());
            row.put("error", key.error());
            row.put("active_count", number(metric.get("active_count")));
            row.put("today_count", number(metric.get("today_count")));
            row.put("success_count", number(metric.get("success_count")));
            row.put("failure_count", number(metric.get("failure_count")));
            return row;
        }).toList();
    }

    private boolean hasUsableAgnesSecret(
        List<StoredAgnesKey> configured,
        String legacyEncryptedSecret
    ) {
        if (configured != null && !configured.isEmpty()) {
            return configured.stream().anyMatch(key -> {
                if (!key.enabled() || !notBlank(key.encryptedSecret())) return false;
                try { return notBlank(decryptSecret(key.encryptedSecret())); }
                catch (IllegalStateException ignored) { return false; }
            });
        }
        return secretState(legacyEncryptedSecret, properties.getAgnesApiKey()).usable();
    }

    private boolean validKeyId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,80}");
    }

    private String fingerprint(String secret) {
        if (!notBlank(secret)) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 4);
        } catch (Exception ignored) {
            return "";
        }
    }

    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? 0L : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private SecretState agnesSecretState() {
        List<AgnesKeyRuntime> keys = agnesKeyRuntimes(read());
        boolean stored = keys.stream().anyMatch(key -> "admin".equals(key.source()));
        boolean usable = keys.stream().anyMatch(AgnesKeyRuntime::usable);
        boolean error = keys.stream().anyMatch(AgnesKeyRuntime::error);
        String source = stored ? "admin" : usable ? "environment" : "none";
        if (error && !usable) source = "invalid";
        return new SecretState(stored, usable, source, error);
    }

    private SecretState apimartSecretState() {
        return secretState(text(read().get("apimart_api_key_encrypted")), properties.getApimartApiKey());
    }

    private SecretState hfsySecretState() {
        return secretState(text(read().get("hfsy_api_key_encrypted")), properties.getHfsyApiKey());
    }

    private SecretState secretState(String encrypted, String environmentValue) {
        if (notBlank(encrypted)) {
            try {
                return new SecretState(true, notBlank(decryptSecret(encrypted)), "admin", false);
            } catch (IllegalStateException ignored) {
                return new SecretState(true, false, "invalid", true);
            }
        }
        boolean environment = notBlank(environmentValue);
        return new SecretState(false, environment, environment ? "environment" : "none", false);
    }

    private void putSecretState(Map<String, Object> view, String prefix, SecretState state) {
        view.put(prefix + "_key_set", state.usable());
        view.put(prefix + "_stored_key_set", state.stored());
        view.put(prefix + "_key_source", state.source());
        view.put(prefix + "_key_error", state.error());
    }

    private String configuredSecret(String storedField, String environmentValue) {
        String encrypted = text(read().get(storedField));
        if (notBlank(encrypted)) return decryptSecret(encrypted);
        return text(environmentValue);
    }

    private String updatedEncryptedSecret(String existing, String input, Boolean clear, String providerName) {
        if (notBlank(input)) {
            String key = input.trim();
            if (key.length() > 4096) {
                throw new IllegalArgumentException(providerName + " API Key 长度不能超过 4096 个字符");
            }
            return encryptSecret(key);
        }
        return Boolean.TRUE.equals(clear) ? "" : existing;
    }

    private String encryptSecret(String plaintext) {
        try {
            byte[] nonce = new byte[12];
            SECURE_RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(SETTING_KEY.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(ciphertext, 0, packed, nonce.length, ciphertext.length);
            return SECRET_PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception exception) {
            throw new IllegalStateException("视频 API Key 加密保存失败", exception);
        }
    }

    private String decryptSecret(String encrypted) {
        try {
            if (!encrypted.startsWith(SECRET_PREFIX)) throw new IllegalArgumentException("unsupported secret format");
            byte[] packed = Base64.getDecoder().decode(encrypted.substring(SECRET_PREFIX.length()));
            if (packed.length <= 28) throw new IllegalArgumentException("invalid secret payload");
            byte[] nonce = new byte[12];
            byte[] ciphertext = new byte[packed.length - nonce.length];
            System.arraycopy(packed, 0, nonce, 0, nonce.length);
            System.arraycopy(packed, nonce.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(SETTING_KEY.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("视频 API Key 加密配置无法解密，请在管理员后台重新填写", exception);
        }
    }

    private SecretKeySpec encryptionKey() throws Exception {
        String secret = text(properties.getConfigEncryptionKey());
        if (!notBlank(secret)) throw new IllegalStateException("VIDEO_CONFIG_ENCRYPTION_KEY 未配置");
        byte[] key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean value(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private int integer(Object value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private int boundedInt(Object value, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, integer(value, fallback)));
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public record TierSettings(boolean enabled, int durationSeconds, String resolution, int price) {}

    public record RuntimeSettings(
        boolean enabled,
        boolean paidProviderRequested,
        boolean geminiEnabled,
        boolean seedanceEnabled,
        boolean agnesRouteEnabled,
        TierSettings standard,
        TierSettings quality,
        int agnesRoutePercent,
        int agnesDailyLimit,
        int agnesConcurrency,
        int agnesCircuitFailures,
        int pollSeconds,
        int timeoutMinutes,
        boolean hfsyEnabled
    ) {
        public RuntimeSettings(
            boolean enabled,
            boolean paidProviderRequested,
            boolean geminiEnabled,
            boolean seedanceEnabled,
            boolean agnesRouteEnabled,
            TierSettings standard,
            TierSettings quality,
            int agnesRoutePercent,
            int agnesDailyLimit,
            int agnesConcurrency,
            int agnesCircuitFailures,
            int pollSeconds,
            int timeoutMinutes
        ) {
            this(
                enabled, paidProviderRequested, geminiEnabled, seedanceEnabled, agnesRouteEnabled,
                standard, quality, agnesRoutePercent, agnesDailyLimit, agnesConcurrency,
                agnesCircuitFailures, pollSeconds, timeoutMinutes, false
            );
        }
    }

    public record ProviderAssignment(String provider, String model, String reason) {}

    public record ModelSelection(
        String code,
        String name,
        String provider,
        String model,
        String resolution,
        int durationSeconds,
        String aspectRatio,
        int price,
        boolean audioToggle,
        int maxReferenceImages,
        String routingReason
    ) {}

    private record ProviderRoute(String provider, String model, String reason) {
        private static final ProviderRoute DISABLED = new ProviderRoute("disabled", "", "fixed_route_unavailable");
    }

    public record VideoVariantSettings(
        String resolution,
        int durationSeconds,
        int price,
        boolean enabled
    ) {}

    public record ModelSettings(
        String code,
        String name,
        String description,
        String provider,
        String model,
        boolean enabled,
        String defaultResolution,
        int defaultDurationSeconds,
        boolean durationSelectable,
        boolean audioToggle,
        int maxReferenceImages,
        List<String> aspectRatios,
        List<VideoVariantSettings> variants
    ) {
        ModelSettings withConfig(boolean enabled, List<VideoVariantSettings> variants) {
            return new ModelSettings(
                code, name, description, provider, model, enabled, defaultResolution,
                defaultDurationSeconds, durationSelectable, audioToggle, maxReferenceImages, aspectRatios, variants
            );
        }
    }

    public record AgnesKeyRuntime(
        String id,
        String label,
        boolean enabled,
        int concurrency,
        String secret,
        String fingerprint,
        String source,
        boolean usable,
        boolean error
    ) {}

    private record StoredAgnesKey(
        String id,
        String label,
        boolean enabled,
        int concurrency,
        String encryptedSecret
    ) {}

    private record HfsyModelMapping(
        String modelCode,
        String modelName,
        String resolution,
        String hfsyModel
    ) {}

    private record SecretState(boolean stored, boolean usable, String source, boolean error) {}
}
