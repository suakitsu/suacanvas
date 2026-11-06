package com.openimage.video;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.CreditLedgerEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.CreditLedgerMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.domain.mapper.VideoTaskMapper;
import com.openimage.risk.RiskControlService;
import com.openimage.security.CurrentUser;
import com.openimage.storage.LocalStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class VideoTaskService {
    private static final List<String> REFUNDABLE_STATUSES = List.of("failed", "timeout", "cancelled");
    private static final List<String> DELETABLE_STATUSES = List.of("completed", "failed", "timeout", "cancelled");

    private final VideoTaskMapper tasks;
    private final UserMapper users;
    private final CreditLedgerMapper ledgers;
    private final LocalStorageService storage;
    private final VideoConfigService config;
    private final RiskControlService riskControl;
    private final ObjectMapper json;

    public VideoTaskService(
        VideoTaskMapper tasks,
        UserMapper users,
        CreditLedgerMapper ledgers,
        LocalStorageService storage,
        VideoConfigService config,
        RiskControlService riskControl,
        ObjectMapper json
    ) {
        this.tasks = tasks;
        this.users = users;
        this.ledgers = ledgers;
        this.storage = storage;
        this.config = config;
        this.riskControl = riskControl;
        this.json = json;
    }

    @Transactional
    public VideoDtos.VideoTaskView create(
        CurrentUser user,
        String title,
        String prompt,
        String aspectRatio,
        String mode,
        List<String> linkedImages,
        List<MultipartFile> uploadedImages
    ) throws Exception {
        return create(
            user, title, prompt, aspectRatio, mode, null, null, null, null, null,
            linkedImages, uploadedImages, "internal-" + UUID.randomUUID()
        );
    }

    @Transactional
    public VideoDtos.VideoTaskView create(
        CurrentUser user,
        String title,
        String prompt,
        String aspectRatio,
        String mode,
        String modelCode,
        Integer durationSeconds,
        String resolution,
        Boolean generateAudio,
        Long seed,
        List<String> linkedImages,
        List<MultipartFile> uploadedImages
    ) throws Exception {
        return create(
            user, title, prompt, aspectRatio, mode, modelCode, durationSeconds,
            resolution, generateAudio, seed, linkedImages, uploadedImages,
            "internal-" + UUID.randomUUID()
        );
    }

    @Transactional
    public VideoDtos.VideoTaskView create(
        CurrentUser user,
        String title,
        String prompt,
        String aspectRatio,
        String mode,
        String modelCode,
        Integer durationSeconds,
        String resolution,
        Boolean generateAudio,
        Long seed,
        List<String> linkedImages,
        List<MultipartFile> uploadedImages,
        String idempotencyKey
    ) throws Exception {
        return create(
            user, title, prompt, aspectRatio, mode, modelCode, durationSeconds,
            resolution, generateAudio, seed, linkedImages, uploadedImages, "",
            idempotencyKey
        );
    }

    @Transactional
    public VideoDtos.VideoTaskView create(
        CurrentUser user,
        String title,
        String prompt,
        String aspectRatio,
        String mode,
        String modelCode,
        Integer durationSeconds,
        String resolution,
        Boolean generateAudio,
        Long seed,
        List<String> linkedImages,
        List<MultipartFile> uploadedImages,
        String originalPrompt,
        String idempotencyKey
    ) throws Exception {
        if (user == null || user.id() == null || user.id().isBlank()) {
            throw new IllegalArgumentException("请先登录后再创建视频任务");
        }
        String requestKey = normalizeIdempotencyKey(idempotencyKey);
        VideoConfigService.RuntimeSettings runtime = config.current();
        if (!runtime.enabled()) {
            throw new IllegalArgumentException("商品视频功能已由管理员关闭");
        }
        String normalizedPrompt = clean(prompt);
        if (normalizedPrompt.length() < 4) {
            throw new IllegalArgumentException("请填写至少 4 个字符的商品视频描述");
        }
        if (normalizedPrompt.length() > 4000) {
            throw new IllegalArgumentException("商品视频描述不能超过 4000 个字符");
        }
        String taskId = "vid_" + id();
        String normalizedMode;
        String normalizedRatio;
        String requestedResolution;
        int requestedDuration;
        String provider;
        String selectedModel;
        String routingReason;
        int listedPrice;
        int cost;
        int maxReferenceImages;
        boolean selectedGenerateAudio = false;
        Long selectedSeed = null;

        if (modelCode != null && !modelCode.isBlank()) {
            VideoConfigService.ModelSelection selection = config.selectModel(
                modelCode, resolution, durationSeconds, aspectRatio
            );
            normalizedMode = selection.code();
            normalizedRatio = selection.aspectRatio();
            requestedResolution = selection.resolution();
            requestedDuration = selection.durationSeconds();
            provider = selection.provider();
            selectedModel = selection.model();
            routingReason = selection.routingReason();
            listedPrice = selection.price();
            cost = config.billableCreditsFor(selection);
            maxReferenceImages = selection.maxReferenceImages();
            selectedGenerateAudio = selection.audioToggle() && !Boolean.FALSE.equals(generateAudio);
            if (seed != null) {
                if (seed < 0 || seed > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("随机种子必须在 0 到 2147483647 之间");
                }
                selectedSeed = seed;
            }
        } else {
            normalizedRatio = "16:9".equals(aspectRatio) ? "16:9" : "9:16";
            normalizedMode = config.normalizeMode(mode);
            VideoConfigService.TierSettings tier = "quality".equals(normalizedMode) ? runtime.quality() : runtime.standard();
            if (!tier.enabled()) {
                throw new IllegalArgumentException(("quality".equals(normalizedMode) ? "品质" : "标准") + "模式已由管理员关闭");
            }
            VideoConfigService.ProviderAssignment assignment = config.assignmentFor(normalizedMode, taskId, normalizedPrompt);
            provider = assignment.provider();
            selectedModel = assignment.model().isBlank() ? model(provider) : assignment.model();
            routingReason = assignment.reason();
            requestedResolution = tier.resolution();
            requestedDuration = tier.durationSeconds();
            listedPrice = tier.price();
            cost = config.billableCreditsFor(normalizedMode, provider);
            maxReferenceImages = "agnes".equals(provider) ? 1 : 3;
        }

        UserEntity account = users.lockById(user.id());
        if (account == null || account.getDeleted() != 0) {
            throw new IllegalArgumentException("用户不存在");
        }
        VideoTaskEntity existing = tasks.findByUserAndIdempotencyKey(user.id(), requestKey);
        if (existing != null) {
            log.info("video_task_idempotent_replay task_id={} user_id={}", existing.getId(), user.id());
            return view(existing);
        }

        riskControl.checkBeforeProvider(user.id(), "", taskId, "product_video", normalizedPrompt, "user_input");

        List<String> sourceUrls = new ArrayList<>();
        if (linkedImages != null) {
            for (String value : linkedImages) {
                String url = clean(value);
                if (url.isBlank()) continue;
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    throw new IllegalArgumentException("商品图片链接必须以 http:// 或 https:// 开头");
                }
                if ("gemini".equals(provider)) validateGeminiLinkedImage(url);
                sourceUrls.add(url);
            }
        }
        List<MultipartFile> files = uploadedImages == null ? List.of() : uploadedImages.stream()
            .filter(file -> file != null && !file.isEmpty())
            .toList();
        if (sourceUrls.size() + files.size() > maxReferenceImages) {
            throw new IllegalArgumentException(maxReferenceImages == 1
                ? "Agnes V2.0 仅支持 1 张商品参考图"
                : "所选模型最多使用 " + maxReferenceImages + " 张商品参考图");
        }
        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            LocalStorageService.StoredFile stored = "gemini".equals(provider)
                ? storage.saveProjectImageForJpegOrPng(
                    user.id(), "video-" + taskId, "source-" + (index + 1), file
                )
                : storage.saveProjectImage(
                    user.id(), "video-" + taskId, "source-" + (index + 1), file
                );
            sourceUrls.add(stored.url());
        }
        if (sourceUrls.isEmpty()) {
            throw new IllegalArgumentException("请至少上传或填写一张商品参考图");
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (cost > 0) {
            deduct(account, taskId, cost, now);
        }

        VideoTaskEntity task = new VideoTaskEntity();
        task.setId(taskId);
        task.setUserId(user.id());
        task.setIdempotencyKey(requestKey);
        task.setTitle(title(title, normalizedPrompt));
        task.setPrompt(normalizedPrompt);
        task.setSourceImageUrls(json.writeValueAsString(sourceUrls));
        task.setAspectRatio(normalizedRatio);
        task.setDurationSeconds(requestedDuration);
        task.setRequestedResolution(requestedResolution);
        task.setMode(normalizedMode);
        task.setStatus("queued");
        task.setProgress(0);
        task.setProvider(provider);
        task.setModel(selectedModel);
        task.setRoutingReason(routingReason);
        task.setUpstreamTaskId("");
        task.setResultUrl("");
        task.setCreditsCost(cost);
        task.setActualCost(BigDecimal.ZERO);
        task.setProviderAttempts("[]");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mock_mode", config.mockMode());
        metadata.put("selected_provider", provider);
        metadata.put("selected_model_code", normalizedMode);
        metadata.put("routing_reason", routingReason);
        metadata.put("route_locked", true);
        metadata.put("assigned_provider", provider);
        metadata.put("assigned_model", selectedModel);
        metadata.put("fixed_primary_model", selectedModel);
        metadata.put("listed_price", listedPrice);
        metadata.put("generate_audio", selectedGenerateAudio);
        metadata.put("script_mode", VideoPromptPolicy.modeFor(normalizedPrompt, provider));
        String cleanedOriginalPrompt = clean(originalPrompt);
        if (!cleanedOriginalPrompt.isBlank() && !cleanedOriginalPrompt.equals(normalizedPrompt)) {
            metadata.put("original_prompt", cleanedOriginalPrompt);
        }
        if (selectedSeed != null) metadata.put("seed", selectedSeed);
        task.setMetadata(json.writeValueAsString(metadata));
        task.setSafeError("");
        task.setRawError("");
        task.setQueuedAt(now);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setHiddenByUser(0);
        task.setDeleted(0);
        tasks.insert(task);
        log.info(
            "video_task_created task_id={} user_id={} mode={} provider={} model={} routing_reason={} credits={}",
            taskId, user.id(), normalizedMode, provider, task.getModel(), routingReason, cost
        );
        return view(task);
    }

    private void validateGeminiLinkedImage(String value) {
        try {
            String path = URI.create(value).getPath();
            String normalized = path == null ? "" : path.toLowerCase();
            if (normalized.endsWith(".jpg")
                || normalized.endsWith(".jpeg")
                || normalized.endsWith(".png")) {
                return;
            }
        } catch (Exception ignored) {
            // The public error below intentionally avoids exposing URI parser details.
        }
        throw new IllegalArgumentException("Gemini 参考图链接仅支持 JPG 或 PNG；其他格式请直接上传，系统会自动转换");
    }

    public List<VideoDtos.VideoTaskView> listMine(CurrentUser user) {
        return tasks.selectList(new LambdaQueryWrapper<VideoTaskEntity>()
                .eq(VideoTaskEntity::getUserId, user.id())
                .eq(VideoTaskEntity::getDeleted, 0)
                .eq(VideoTaskEntity::getHiddenByUser, 0)
                .orderByDesc(VideoTaskEntity::getCreatedAt)
                .last("limit 100"))
            .stream().map(this::view).toList();
    }

    public VideoDtos.VideoTaskView get(CurrentUser user, String id) {
        VideoTaskEntity task = own(user, id);
        return view(task);
    }

    @Transactional
    public Map<String, Object> delete(CurrentUser user, String id) {
        VideoTaskEntity task = own(user, id);
        if (!DELETABLE_STATUSES.contains(task.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "视频正在生成，完成后才能删除");
        }
        int updated = tasks.hideTerminalOwned(id, user.id(), OffsetDateTime.now());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "视频任务状态已变化，请刷新后重试");
        }
        return Map.of("task_id", id, "hidden", true);
    }

    @Transactional
    public void refundIfNeeded(String taskId, String reason) {
        VideoTaskEntity task = tasks.lockById(taskId);
        if (task == null || task.getDeleted() != 0 || task.getRefundedAt() != null) return;
        if (!REFUNDABLE_STATUSES.contains(task.getStatus())) {
            log.warn(
                "video_refund_rejected task_id={} user_id={} status={} reason={}",
                taskId, task.getUserId(), task.getStatus(), safe(reason)
            );
            return;
        }
        int cost = task.getCreditsCost() == null ? 0 : task.getCreditsCost();
        OffsetDateTime now = OffsetDateTime.now();
        if (cost > 0) {
            UserEntity user = users.lockById(task.getUserId());
            if (user != null && user.getDeleted() == 0) {
                int balance = user.getCredits() + cost;
                user.setCredits(balance);
                user.setUpdatedAt(now);
                users.updateById(user);

                CreditLedgerEntity ledger = new CreditLedgerEntity();
                ledger.setId("cl_" + id());
                ledger.setUserId(task.getUserId());
                ledger.setChangeAmount(cost);
                ledger.setBalanceAfter(balance);
                ledger.setBizType("video_refund");
                ledger.setBizId(taskId);
                ledger.setRemark("商品视频生成失败退款：" + clean(reason));
                ledger.setCreatedAt(now);
                ledger.setDeleted(0);
                ledgers.insert(ledger);
            }
        }
        task.setRefundedAt(now);
        task.setUpdatedAt(now);
        tasks.updateById(task);
        log.info("video_task_refund task_id={} user_id={} credits={} reason={}", taskId, task.getUserId(), cost, safe(reason));
    }

    @Transactional
    public boolean completeSuccessfully(VideoTaskEntity completed) {
        if (completed == null || completed.getId() == null) return false;
        VideoTaskEntity task = tasks.lockById(completed.getId());
        if (task == null || task.getDeleted() != 0) return false;

        task.setStatus("completed");
        task.setProgress(100);
        task.setResultUrl(completed.getResultUrl());
        task.setActualWidth(completed.getActualWidth());
        task.setActualHeight(completed.getActualHeight());
        task.setActualSeconds(completed.getActualSeconds());
        task.setActualCost(completed.getActualCost());
        task.setMetadata(completed.getMetadata());
        task.setSafeError("");
        task.setRawError("");
        task.setCompletedAt(completed.getCompletedAt());
        task.setUpdatedAt(completed.getUpdatedAt());

        boolean recoveredRefund = task.getRefundedAt() != null;
        if (recoveredRefund) {
            reverseSuccessfulRefund(task);
        }
        tasks.updateById(task);

        completed.setRefundedAt(task.getRefundedAt());
        completed.setMetadata(task.getMetadata());
        log.info(
            "video_success_settled task_id={} user_id={} credits={} recovered_refund={}",
            task.getId(), task.getUserId(), task.getCreditsCost(), recoveredRefund
        );
        return true;
    }

    public VideoDtos.VideoTaskView view(VideoTaskEntity task) {
        return new VideoDtos.VideoTaskView(
            task.getId(), task.getTitle(), task.getPrompt(), stringList(task.getSourceImageUrls()),
            task.getAspectRatio(), task.getDurationSeconds(), task.getRequestedResolution(), task.getMode(),
            task.getStatus(), task.getProgress(), task.getProvider(), task.getModel(), task.getRoutingReason(), queueAhead(task), task.getUpstreamTaskId(),
            task.getResultUrl(), task.getCreditsCost(), publicMetadata(task), task.getSafeError(), task.getRefundedAt(),
            task.getQueuedAt(), task.getSubmittedAt(), task.getCompletedAt(),
            task.getCreatedAt(), task.getUpdatedAt()
        );
    }

    private Map<String, Object> publicMetadata(VideoTaskEntity task) {
        Map<String, Object> metadata = map(task.getMetadata());
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("mock_mode", "selected_model_code", "listed_price", "generate_audio")) {
            if (metadata.containsKey(key)) result.put(key, metadata.get(key));
        }
        return result;
    }

    private Integer queueAhead(VideoTaskEntity task) {
        if (!"agnes".equals(task.getProvider()) || !"queued".equals(task.getStatus())) return null;
        OffsetDateTime queuedAt = task.getCreatedAt() == null ? task.getQueuedAt() : task.getCreatedAt();
        return queuedAt == null ? 0 : Math.max(0, tasks.countAgnesAhead(queuedAt, task.getId()));
    }

    private void deduct(UserEntity account, String taskId, int cost, OffsetDateTime now) {
        String userId = account.getId();
        if (account.getCredits() < cost) {
            throw new IllegalArgumentException("积分不足，本次商品视频需要 " + cost + " 积分");
        }
        int balance = account.getCredits() - cost;
        account.setCredits(balance);
        account.setUpdatedAt(now);
        users.updateById(account);

        CreditLedgerEntity ledger = new CreditLedgerEntity();
        ledger.setId("cl_" + id());
        ledger.setUserId(userId);
        ledger.setChangeAmount(-cost);
        ledger.setBalanceAfter(balance);
        ledger.setBizType("video_generate");
        ledger.setBizId(taskId);
        ledger.setRemark("创建商品视频任务");
        ledger.setCreatedAt(now);
        ledger.setDeleted(0);
        ledgers.insert(ledger);
    }

    private void reverseSuccessfulRefund(VideoTaskEntity task) {
        int cost = task.getCreditsCost() == null ? 0 : task.getCreditsCost();
        OffsetDateTime now = OffsetDateTime.now();
        if (cost > 0) {
            UserEntity account = users.lockById(task.getUserId());
            if (account == null || account.getDeleted() != 0) {
                throw new IllegalStateException("视频成功结算失败：用户不存在");
            }
            int balance = account.getCredits() - cost;
            account.setCredits(balance);
            account.setUpdatedAt(now);
            users.updateById(account);

            CreditLedgerEntity ledger = new CreditLedgerEntity();
            ledger.setId("cl_" + id());
            ledger.setUserId(task.getUserId());
            ledger.setChangeAmount(-cost);
            ledger.setBalanceAfter(balance);
            ledger.setBizType("video_success_recovery");
            ledger.setBizId(task.getId());
            ledger.setRemark("视频已成功，冲正此前误退款");
            ledger.setCreatedAt(now);
            ledger.setDeleted(0);
            ledgers.insert(ledger);
        }
        Map<String, Object> metadata = map(task.getMetadata());
        metadata.put("refund_reversed_at", now.toString());
        metadata.put("refund_reversed_credits", cost);
        try {
            task.setMetadata(json.writeValueAsString(metadata));
        } catch (Exception exception) {
            throw new IllegalStateException("视频成功结算记录失败", exception);
        }
        task.setRefundedAt(null);
    }

    private VideoTaskEntity own(CurrentUser user, String id) {
        VideoTaskEntity task = tasks.selectById(id);
        if (task == null || task.getDeleted() != 0 || Integer.valueOf(1).equals(task.getHiddenByUser()) || !task.getUserId().equals(user.id())) {
            throw new IllegalArgumentException("视频任务不存在");
        }
        return task;
    }

    private String title(String value, String prompt) {
        String title = clean(value);
        if (title.isBlank()) title = prompt.length() <= 24 ? prompt : prompt.substring(0, 24);
        return title.length() <= 120 ? title : title.substring(0, 120);
    }

    private String model(String provider) {
        return switch (provider) {
            case "agnes" -> "agnes-video-v2.0";
            case "gemini" -> "gemini-omni-flash-preview";
            case "seedance" -> "doubao-seedance-2.0-fast";
            case "hfsy" -> "sd-2.0-fast";
            default -> "mock-video-async";
        };
    }

    private List<String> stringList(String value) {
        try { return json.readValue(value == null ? "[]" : value, new TypeReference<>() {}); }
        catch (Exception ignored) { return List.of(); }
    }

    private Map<String, Object> map(String value) {
        try { return json.readValue(value == null ? "{}" : value, new TypeReference<>() {}); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeIdempotencyKey(String value) {
        String key = clean(value);
        if (key.length() < 16 || key.length() > 128 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("视频请求标识无效，请刷新页面后重试");
        }
        return key;
    }

    private String safe(String value) {
        String sanitized = clean(value)
            .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._-]+", "Bearer ***")
            .replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-***");
        return sanitized.length() <= 300 ? sanitized : sanitized.substring(0, 300);
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
