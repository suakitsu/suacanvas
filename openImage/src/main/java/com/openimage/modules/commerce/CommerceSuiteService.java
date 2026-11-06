package com.openimage.modules.commerce;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.AiImageClient;
import com.openimage.ai.AiImageSizes;
import com.openimage.ai.AiRuntimeConfigService;
import com.openimage.domain.entity.CreditLedgerEntity;
import com.openimage.domain.entity.ImageTaskEntity;
import com.openimage.domain.entity.ProductImageEntity;
import com.openimage.domain.entity.ProductProjectEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.mapper.CreditLedgerMapper;
import com.openimage.domain.mapper.ImageTaskMapper;
import com.openimage.domain.mapper.ProductImageMapper;
import com.openimage.domain.mapper.ProductProjectMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.modules.project.ProjectService;
import com.openimage.modules.project.dto.ProjectDtos;
import com.openimage.risk.RiskControlService;
import com.openimage.security.CurrentUser;
import com.openimage.storage.LocalStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CommerceSuiteService {
    private static final int SUBMIT_DEDUPE_SECONDS = 120;
    private static final int SUBMIT_DEDUPE_WAIT_MILLIS = 30000;

    private static final Map<String, Map<String, String>> SIZE_BY_RATIO = Map.ofEntries(
        Map.entry("1024x1024", Map.of("1K", "1024x1024", "2K", "2048x2048", "4K", "2880x2880")),
        Map.entry("1040x832", Map.of("1K", "1040x832", "2K", "2080x1664", "4K", "3200x2560")),
        Map.entry("720x1280", Map.of("1K", "720x1280", "2K", "1152x2048", "4K", "2160x3840")),
        Map.entry("1280x720", Map.of("1K", "1280x720", "2K", "2048x1152", "4K", "3840x2160")),
        Map.entry("1024x768", Map.of("1K", "1024x768", "2K", "2048x1536", "4K", "3264x2448")),
        Map.entry("1008x672", Map.of("1K", "1008x672", "2K", "2016x1344", "4K", "3504x2336")),
        Map.entry("832x1040", Map.of("1K", "832x1040", "2K", "1664x2080", "4K", "2560x3200")),
        Map.entry("768x1024", Map.of("1K", "768x1024", "2K", "1536x2048", "4K", "2448x3264")),
        Map.entry("672x1008", Map.of("1K", "672x1008", "2K", "1344x2016", "4K", "2336x3504")),
        Map.entry("1344x576", Map.of("1K", "1344x576", "2K", "2016x864", "4K", "3696x1584"))
    );

    private final CommerceSuiteCatalogService catalog;
    private final ProductProjectMapper projects;
    private final ImageTaskMapper tasks;
    private final ProductImageMapper productImages;
    private final UserMapper users;
    private final CreditLedgerMapper ledgers;
    private final LocalStorageService storage;
    private final AiImageClient aiImage;
    private final AiRuntimeConfigService aiRuntimeConfig;
    private final ProjectService projectService;
    private final RiskControlService riskControl;
    private final ObjectMapper json;
    private final Set<String> submitLocks = ConcurrentHashMap.newKeySet();
    private final Map<String, DedupedCommerceSubmission> submitResults = new ConcurrentHashMap<>();

    public CommerceSuiteService(
        CommerceSuiteCatalogService catalog,
        ProductProjectMapper projects,
        ImageTaskMapper tasks,
        ProductImageMapper productImages,
        UserMapper users,
        CreditLedgerMapper ledgers,
        LocalStorageService storage,
        AiImageClient aiImage,
        AiRuntimeConfigService aiRuntimeConfig,
        ProjectService projectService,
        RiskControlService riskControl,
        ObjectMapper json
    ) {
        this.catalog = catalog;
        this.projects = projects;
        this.tasks = tasks;
        this.productImages = productImages;
        this.users = users;
        this.ledgers = ledgers;
        this.storage = storage;
        this.aiImage = aiImage;
        this.aiRuntimeConfig = aiRuntimeConfig;
        this.projectService = projectService;
        this.riskControl = riskControl;
        this.json = json;
    }

    public CommerceSuiteDtos.EstimateResponse estimate(CommerceSuiteDtos.EstimateRequest request) {
        CommerceSuiteDtos.PlatformPreset platform = catalog.platform(request.platform());
        List<String> slots = selectedSlots(platform, request.selectedSlots());
        String resolution = normalizeResolution(request.resolution(), platform.defaultResolution());
        List<Map<String, Object>> costs = new ArrayList<>();
        int total = 0;
        for (String slotCode : slots) {
            CommerceSuiteDtos.TemplatePreset template = catalog.template(platform.code(), slotCode);
            CommerceSuiteDtos.ImageSlot slot = catalog.slot(slotCode);
            String size = sizeForResolution(blankToDefault(template.defaultSize(), slot.defaultSize()), resolution);
            int cost = creditsForSize(size);
            total += cost;
            costs.add(Map.of(
                "slot", slot.code(),
                "name", slot.name(),
                "size", size,
                "credits_cost", cost
            ));
        }
        return new CommerceSuiteDtos.EstimateResponse(platform.code(), resolution, slots.size(), total, costs);
    }

    @Transactional
    public CommerceSuiteDtos.GenerateResponse generate(CurrentUser user, CommerceSuiteDtos.GenerateRequest request, List<MultipartFile> references, MultipartFile reference) throws Exception {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        CommerceSuiteDtos.PlatformPreset platform = catalog.platform(request.platform());
        if (!Boolean.TRUE.equals(platform.enabled())) {
            throw new IllegalArgumentException("该平台套图暂未启用");
        }
        CommerceSuiteDtos.ProductInfo productInfo = normalizeProductInfo(request.productInfo(), platform);
        if (productInfo.productName() == null || productInfo.productName().isBlank()) {
            throw new IllegalArgumentException("请填写商品名称");
        }
        riskControl.checkBeforeProvider(user.id(), "", "", "commerce_suite", json.writeValueAsString(productInfo), "user_input");
        List<String> slotCodes = selectedSlots(platform, request.selectedSlots());
        if (slotCodes.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个图种");
        }
        String resolution = normalizeResolution(request.resolution(), platform.defaultResolution());
        boolean mockMode = mockMode();
        if (!mockMode && !aiImage.enabled()) {
            throw new IllegalArgumentException("AI 作图服务已被管理员禁用或未配置密钥，请联系管理员开启后再使用");
        }

        String dedupeKey = commerceDedupeKey(user.id(), platform, productInfo, slotCodes, resolution, request.referenceUrl(), request.overlayOptions(), references, reference);
        CommerceSuiteDtos.GenerateResponse duplicate = recentDuplicateCommerceSubmit(user, dedupeKey);
        if (duplicate != null) {
            return duplicate;
        }
        boolean dedupeLocked = submitLocks.add(dedupeKey);
        if (!dedupeLocked) {
            return waitForDuplicateCommerceSubmit(user, dedupeKey);
        }
        boolean dedupeSuccess = false;
        String dedupeProjectId = "";
        int dedupeCreditsCost = 0;
        try {
        List<SlotPlan> plans = plans(user, platform, productInfo, slotCodes, resolution, request.overlayOptions());
        riskControl.checkBeforeProvider(user.id(), "", "", "commerce_suite_images", json.writeValueAsString(plans.stream().map(SlotPlan::prompt).toList()), "image_prompt");
        int totalCost = plans.stream().mapToInt(SlotPlan::creditsCost).sum();
        UserEntity account = users.lockById(user.id());
        if (account == null || account.getDeleted() != 0) {
            throw new IllegalArgumentException("User not found");
        }
        if (account.getCredits() < totalCost) {
            throw new IllegalArgumentException("积分不足，本次套图生成需要 " + totalCost + " 积分");
        }

        OffsetDateTime now = OffsetDateTime.now();
        ProductProjectEntity project = new ProductProjectEntity();
        project.setId("prod_" + id());
        project.setUserId(user.id());
        project.setBrand(blankToDefault(productInfo.brand(), platform.name()));
        project.setProductName(productInfo.productName().trim());
        project.setCountry(blankToDefault(productInfo.country(), platform.code()));
        project.setLanguage(blankToDefault(productInfo.language(), platform.language()));
        project.setStatus(mockMode ? "images_completed" : "images_queued");
        project.setOriginalInfo(json.writeValueAsString(originalInfo(platform, productInfo, slotCodes, resolution, request.referenceUrl())));
        project.setGeneratedCopy("{}");
        project.setConfirmedFacts(json.writeValueAsString(confirmedFacts(platform, productInfo, slotCodes, resolution, totalCost)));
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        project.setDeleted(0);
        projects.insert(project);

        storeReferenceImages(user.id(), project.getId(), references, reference);
        String referenceUrl = normalizeReferenceUrl(request.referenceUrl());
        if (!referenceUrl.isBlank()) {
            storeLinkedProductImage(user.id(), project.getId(), "reference_link", referenceUrl);
        }

        int balanceAfter = account.getCredits() - totalCost;
        account.setCredits(balanceAfter);
        account.setUpdatedAt(now);
        users.updateById(account);
        CreditLedgerEntity ledger = new CreditLedgerEntity();
        ledger.setId("cl_" + id());
        ledger.setUserId(user.id());
        ledger.setChangeAmount(-totalCost);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setBizType("commerce_suite_generate");
        ledger.setBizId(project.getId());
        ledger.setRemark(platform.name() + " 电商套图生成扣费：" + plans.size() + " 张");
        ledger.setCreatedAt(now);
        ledger.setDeleted(0);
        ledgers.insert(ledger);

        AiRuntimeConfigService.EffectiveImageConfig runtime = mockMode ? null : aiRuntimeConfig.effectiveImage();
        List<ImageTaskEntity> created = new ArrayList<>();
        for (SlotPlan plan : plans) {
            ImageTaskEntity task = new ImageTaskEntity();
            task.setId("img_" + id());
            task.setProjectId(project.getId());
            task.setUserId(user.id());
            task.setTaskSetId(project.getId());
            task.setImageType(plan.slot().code());
            task.setStatus(mockMode ? "success" : "queued");
            task.setResultUrl("");
            task.setCreditsCost(plan.creditsCost());
            task.setPromptSnapshot(promptSnapshot(project, platform, productInfo, plan, runtime));
            task.setProvider(mockMode ? "mock-ai" : runtime.provider());
            task.setModel(mockMode ? "mock-commerce-suite" : modelForGenerationSize(runtime.provider(), runtime.model(), plan.generationSize()));
            task.setSafeError("");
            task.setRawError("");
            task.setProviderAttempts("[]");
            task.setActualCost(mockMode ? BigDecimal.ZERO : null);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            task.setHiddenByUser(0);
            task.setDeleted(0);
            tasks.insert(task);
            if (mockMode) {
                writeMockResult(project, task, platform, plan);
            }
            created.add(task);
        }

        ProjectDtos.ProjectDetail detail = projectService.detail(user, project.getId());
        dedupeSuccess = true;
        dedupeProjectId = project.getId();
        dedupeCreditsCost = totalCost;
        return new CommerceSuiteDtos.GenerateResponse(
            project.getId(),
            detail,
            detail.imageTasks().stream().filter(task -> created.stream().anyMatch(row -> row.getId().equals(task.id()))).toList(),
            totalCost,
            mockMode
        );
        } finally {
            finishCommerceSubmit(dedupeKey, dedupeLocked, dedupeSuccess, dedupeProjectId, dedupeCreditsCost, mockMode);
        }
    }

    private String commerceDedupeKey(String userId, CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ProductInfo productInfo, List<String> slotCodes, String resolution, String referenceUrl, Map<String, Object> overlayOptions, List<MultipartFile> references, MultipartFile reference) throws Exception {
        return String.join("\n",
            "commerce-suite",
            blankToDefault(userId, ""),
            platform.code(),
            resolution,
            json.writeValueAsString(productInfo),
            json.writeValueAsString(slotCodes),
            normalizeReferenceUrl(referenceUrl),
            json.writeValueAsString(overlayOptions == null ? Map.of() : overlayOptions),
            fileSignatures(references, reference)
        );
    }

    private CommerceSuiteDtos.GenerateResponse waitForDuplicateCommerceSubmit(CurrentUser user, String dedupeKey) throws Exception {
        long deadline = System.currentTimeMillis() + SUBMIT_DEDUPE_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            CommerceSuiteDtos.GenerateResponse duplicate = recentDuplicateCommerceSubmit(user, dedupeKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (!submitLocks.contains(dedupeKey)) {
                break;
            }
            Thread.sleep(300);
        }
        throw new IllegalArgumentException("任务已提交，请稍后刷新历史记录查看结果");
    }

    private CommerceSuiteDtos.GenerateResponse recentDuplicateCommerceSubmit(CurrentUser user, String dedupeKey) throws Exception {
        DedupedCommerceSubmission submission = submitResults.get(dedupeKey);
        if (submission == null) {
            return null;
        }
        if (submission.expiresAtMillis() < System.currentTimeMillis()) {
            submitResults.remove(dedupeKey, submission);
            return null;
        }
        ProjectDtos.ProjectDetail detail = projectService.detail(user, submission.projectId());
        return new CommerceSuiteDtos.GenerateResponse(
            submission.projectId(),
            detail,
            detail.imageTasks(),
            submission.creditsCost(),
            submission.mockMode()
        );
    }

    private void finishCommerceSubmit(String dedupeKey, boolean dedupeLocked, boolean success, String projectId, int creditsCost, boolean mockMode) {
        if (!dedupeLocked) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_COMMITTED && success && projectId != null && !projectId.isBlank()) {
                        rememberCommerceSubmit(dedupeKey, projectId, creditsCost, mockMode);
                    }
                    submitLocks.remove(dedupeKey);
                }
            });
            return;
        }
        if (success && projectId != null && !projectId.isBlank()) {
            rememberCommerceSubmit(dedupeKey, projectId, creditsCost, mockMode);
        }
        submitLocks.remove(dedupeKey);
    }

    private void rememberCommerceSubmit(String dedupeKey, String projectId, int creditsCost, boolean mockMode) {
        // ponytail: in-memory guard covers accidental double-clicks on the current single-node deploy; use a DB idempotency key if this becomes multi-node.
        long now = System.currentTimeMillis();
        submitResults.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
        submitResults.put(dedupeKey, new DedupedCommerceSubmission(projectId, creditsCost, mockMode, now + SUBMIT_DEDUPE_SECONDS * 1000L));
    }

    private String fileSignatures(List<MultipartFile> references, MultipartFile reference) {
        List<String> values = new ArrayList<>();
        if (references != null) {
            for (MultipartFile file : references) {
                values.add(fileSignature(file));
            }
        }
        values.add(fileSignature(reference));
        return String.join("|", values);
    }

    private String fileSignature(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "";
        }
        return blankToDefault(file.getOriginalFilename(), "") + ":" + file.getSize() + ":" + blankToDefault(file.getContentType(), "");
    }

    public ProjectDtos.ProjectDetail project(CurrentUser user, String projectId) throws Exception {
        return projectService.detail(user, projectId);
    }

    public ProjectDtos.ImageTaskView task(CurrentUser user, String taskId) throws Exception {
        ImageTaskEntity task = tasks.selectById(taskId);
        if (task == null || task.getDeleted() != 0 || Integer.valueOf(1).equals(task.getHiddenByUser()) || !user.id().equals(task.getUserId())) {
            throw new IllegalArgumentException("Task not found");
        }
        ProjectDtos.ProjectDetail detail = projectService.detail(user, task.getProjectId());
        return detail.imageTasks().stream()
            .filter(item -> item.id().equals(taskId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Task not found"));
    }

    public ProjectDtos.ImageTaskView retry(CurrentUser user, String taskId) throws Exception {
        ImageTaskEntity task = tasks.selectById(taskId);
        if (task == null || task.getDeleted() != 0 || Integer.valueOf(1).equals(task.getHiddenByUser()) || !user.id().equals(task.getUserId())) {
            throw new IllegalArgumentException("Task not found");
        }
        return projectService.retryFailedTask(user, task.getProjectId(), taskId);
    }

    private List<SlotPlan> plans(CurrentUser user, CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ProductInfo productInfo, List<String> slotCodes, String resolution, Map<String, Object> overlayOptions) {
        List<SlotPlan> plans = new ArrayList<>();
        for (String slotCode : slotCodes) {
            CommerceSuiteDtos.TemplatePreset template = catalog.template(platform.code(), slotCode);
            CommerceSuiteDtos.ImageSlot slot = catalog.slot(slotCode);
            String size = sizeForResolution(blankToDefault(template.defaultSize(), slot.defaultSize()), resolution);
            String prompt = catalog.renderPromptTemplate(template, platform, slot, productInfo);
            riskControl.checkBeforeProvider(user.id(), "", "", "commerce_suite_prompt", prompt, "text_prompt");
            Map<String, Object> visibleTextPlan = catalog.renderOverlayTemplate(template, platform, slot, productInfo, overlayOptions);
            plans.add(new SlotPlan(slot, template, size, creditsForSize(size), prompt, visibleTextPlan));
        }
        return plans;
    }

    private String promptSnapshot(ProductProjectEntity project, CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ProductInfo productInfo, SlotPlan plan, AiRuntimeConfigService.EffectiveImageConfig runtime) throws Exception {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskKind", "commerce_suite");
        snapshot.put("projectId", project.getId());
        snapshot.put("productName", project.getProductName());
        snapshot.put("imageType", plan.slot().code());
        snapshot.put("imageName", plan.template().name());
        snapshot.put("platform", platform.code());
        snapshot.put("platformName", platform.name());
        snapshot.put("slot", plan.slot().code());
        snapshot.put("slotName", plan.slot().name());
        snapshot.put("generationSize", plan.generationSize());
        snapshot.put("outputSize", plan.generationSize());
        snapshot.put("negativePrompt", catalog.renderNegativePrompt(plan.template()));
        snapshot.put("visibleTextPlan", plan.visibleTextPlan());
        snapshot.put("aiTextPlan", plan.visibleTextPlan());
        snapshot.put("productInfo", productInfo);
        snapshot.put("templateId", plan.template().id());
        snapshot.put("prompt_template", plan.template().promptTemplate());
        snapshot.put("intent", "poster_layout");
        snapshot.put("text_mode", "ai_short_text");
        snapshot.put("reference_policy", "preserve");
        snapshot.put("reference_count", 1);
        snapshot.put("postProcess", Map.of(
            "type", "ai_visible_text",
            "overlay_saved", false,
            "background_image", "ai_generated",
            "final_image", "ai_generated"
        ));
        String modelPrompt = plan.prompt() + "\n\nVisible text plan to render directly inside the AI-generated image. Render only these confirmed words or concise translations requested by the prompt; do not leave a blank text panel for later editing:\n" + json.writeValueAsString(plan.visibleTextPlan());
        snapshot.put("modelPrompt", modelPrompt);
        snapshot.put("prompt", modelPrompt);
        if (runtime != null) {
            snapshot.put("aiProviderId", blankToDefault(runtime.id(), ""));
            snapshot.put("aiProvider", blankToDefault(runtime.provider(), ""));
            snapshot.put("aiModel", blankToDefault(runtime.model(), ""));
        }
        return json.writeValueAsString(snapshot);
    }

    private Map<String, Object> originalInfo(CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ProductInfo productInfo, List<String> slotCodes, String resolution, String referenceUrl) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_type", "commerce_suite");
        row.put("platform", platform.code());
        row.put("platform_name", platform.name());
        row.put("product_info", productInfo);
        row.put("selected_slots", slotCodes);
        row.put("resolution", resolution);
        row.put("reference_url", blankToDefault(referenceUrl, ""));
        row.put("description", blankToDefault(productInfo.scenario(), ""));
        row.put("category", blankToDefault(productInfo.category(), ""));
        row.put("material", blankToDefault(productInfo.material(), ""));
        row.put("dimensions", blankToDefault(productInfo.specs(), ""));
        row.put("keywords", cleanList(productInfo.sellingPoints()));
        return row;
    }

    private Map<String, Object> confirmedFacts(CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ProductInfo productInfo, List<String> slotCodes, String resolution, int totalCost) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mode", "commerce_suite");
        row.put("platform", platform.code());
        row.put("platform_name", platform.name());
        row.put("product_name", productInfo.productName());
        row.put("brand", blankToDefault(productInfo.brand(), ""));
        row.put("category", blankToDefault(productInfo.category(), ""));
        row.put("material", blankToDefault(productInfo.material(), ""));
        row.put("specs", blankToDefault(productInfo.specs(), ""));
        row.put("selling_points", cleanList(productInfo.sellingPoints()));
        row.put("selected_slots", slotCodes);
        row.put("resolution", resolution);
        row.put("credits_cost", totalCost);
        row.put("overlay_editable", false);
        row.put("visible_text_rendered_by", "ai_image_model");
        return row;
    }

    private CommerceSuiteDtos.ProductInfo normalizeProductInfo(CommerceSuiteDtos.ProductInfo info, CommerceSuiteDtos.PlatformPreset platform) {
        if (info == null) {
            return new CommerceSuiteDtos.ProductInfo("", "", "", List.of(), "", "", "", "", "", "", platform.code(), platform.language());
        }
        return new CommerceSuiteDtos.ProductInfo(
            blankToDefault(info.productName(), ""),
            blankToDefault(info.category(), ""),
            blankToDefault(info.brand(), ""),
            cleanList(info.sellingPoints()),
            blankToDefault(info.material(), ""),
            blankToDefault(info.specs(), ""),
            blankToDefault(info.audience(), ""),
            blankToDefault(info.scenario(), ""),
            blankToDefault(info.priceText(), ""),
            blankToDefault(info.promoText(), ""),
            blankToDefault(info.country(), platform.code()),
            blankToDefault(info.language(), platform.language())
        );
    }

    private List<String> selectedSlots(CommerceSuiteDtos.PlatformPreset platform, List<String> requested) {
        Set<String> allowed = catalog.templates(platform.code()).stream()
            .filter(template -> Boolean.TRUE.equals(template.enabled()))
            .map(CommerceSuiteDtos.TemplatePreset::slot)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> source = requested == null || requested.isEmpty() ? platform.defaultSlots() : requested;
        Set<String> result = new LinkedHashSet<>();
        for (String slot : source) {
            String normalized = blankToDefault(slot, "");
            if (allowed.contains(normalized)) {
                result.add(normalized);
            }
        }
        return new ArrayList<>(result);
    }

    private void storeReferenceImages(String userId, String projectId, List<MultipartFile> references, MultipartFile reference) throws Exception {
        if (references != null) {
            for (MultipartFile item : references) {
                if (item != null && !item.isEmpty()) {
                    storeProductImage(userId, projectId, "reference", item);
                }
            }
        }
        if (reference != null && !reference.isEmpty()) {
            storeProductImage(userId, projectId, "reference", reference);
        }
    }

    private void storeProductImage(String userId, String projectId, String imageType, MultipartFile file) throws Exception {
        String imageId = "pimg_" + id();
        LocalStorageService.StoredFile stored = storage.saveProjectImage(userId, projectId, imageId, file);
        ProductImageEntity entity = new ProductImageEntity();
        entity.setId(imageId);
        entity.setProjectId(projectId);
        entity.setUserId(userId);
        entity.setImageType(imageType);
        entity.setOriginalFilename(stored.filename());
        entity.setUrl(stored.url());
        entity.setStoragePath(stored.path());
        entity.setContentType(stored.contentType());
        entity.setImageFormat(stored.format());
        entity.setFileSize(stored.fileSize());
        entity.setWidth(stored.width());
        entity.setHeight(stored.height());
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        entity.setDeleted(0);
        productImages.insert(entity);
    }

    private void storeLinkedProductImage(String userId, String projectId, String imageType, String url) {
        ProductImageEntity entity = new ProductImageEntity();
        entity.setId("pimg_" + id());
        entity.setProjectId(projectId);
        entity.setUserId(userId);
        entity.setImageType(imageType);
        entity.setOriginalFilename("linked-image");
        entity.setUrl(url);
        entity.setStoragePath(url);
        entity.setContentType("image/url");
        entity.setFileSize(0L);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        entity.setDeleted(0);
        productImages.insert(entity);
    }

    private void writeMockResult(ProductProjectEntity project, ImageTaskEntity task, CommerceSuiteDtos.PlatformPreset platform, SlotPlan plan) throws Exception {
        byte[] mock = mockImage(project, platform, plan);
        LocalStorageService.StoredFile stored = storage.saveGeneratedProjectImage(project.getId(), task.getId() + ".png", mock, "image/png");
        task.setResultUrl(stored.url());
        task.setStatus("success");
        task.setProvider("mock-ai");
        task.setModel("mock-commerce-suite");
        task.setActualCost(BigDecimal.ZERO);
        task.setUpdatedAt(OffsetDateTime.now());
        tasks.updateById(task);
    }

    private byte[] mockImage(ProductProjectEntity project, CommerceSuiteDtos.PlatformPreset platform, SlotPlan plan) throws Exception {
        AiImageSizes.Size size = AiImageSizes.parse(plan.generationSize());
        int width = size == null ? 1024 : size.width();
        int height = size == null ? 1024 : size.height();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(background(platform.code()));
        g.fillRect(0, 0, width, height);
        int base = Math.min(width, height);
        int productW = Math.max(base / 4, width / 3);
        int productH = Math.max(base / 3, height / 2);
        int productX = Math.max(32, width / 8);
        int productY = Math.max(40, (height - productH) / 2);
        g.setColor(new Color(255, 255, 255, 230));
        g.fillRoundRect(productX, productY, productW, productH, base / 24, base / 24);
        g.setColor(new Color(48, 64, 84, 45));
        g.fillOval(productX + productW / 10, productY + productH - base / 16, productW * 4 / 5, base / 14);
        g.setColor(new Color(37, 99, 235, 180));
        g.setFont(new Font("SansSerif", Font.BOLD, Math.max(22, base / 28)));
        g.drawString(project.getProductName(), productX + base / 20, productY + base / 12);
        g.setFont(new Font("SansSerif", Font.PLAIN, Math.max(16, base / 44)));
        g.drawString(platform.name() + " / " + plan.slot().name(), productX + base / 20, productY + base / 7);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Color background(String platformCode) {
        return switch (platformCode) {
            case "pinduoduo" -> new Color(255, 246, 241);
            case "douyin", "tiktok_shop" -> new Color(238, 244, 255);
            case "xiaohongshu", "vipshop" -> new Color(255, 247, 250);
            case "alibaba_1688" -> new Color(255, 248, 237);
            default -> new Color(246, 249, 252);
        };
    }

    private String normalizeReferenceUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("/api/public/files/")) {
            return trimmed;
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("图片链接必须是 http 或 https 地址");
            }
            return trimmed;
        } catch (Exception exception) {
            throw new IllegalArgumentException("图片链接格式不正确");
        }
    }

    private String normalizeResolution(String value, String fallback) {
        String text = blankToDefault(value, fallback).toUpperCase();
        return List.of("1K", "2K", "4K").contains(text) ? text : "1K";
    }

    private String sizeForResolution(String defaultSize, String resolution) {
        String normalized = blankToDefault(defaultSize, "1024x1024").replace('×', 'x');
        Map<String, String> exact = SIZE_BY_RATIO.get(normalized);
        if (exact != null && exact.containsKey(resolution)) {
            return exact.get(resolution);
        }
        AiImageSizes.Size parsed = AiImageSizes.parse(normalized);
        if (parsed == null) {
            return "1K".equals(resolution) ? "1024x1024" : ("2K".equals(resolution) ? "2048x2048" : "2880x2880");
        }
        return AiImageSizes.normalizeGenerationSize(parsed.value(), "1024x1024");
    }

    private int creditsForSize(String size) {
        AiImageSizes.Size parsed = AiImageSizes.parse(size);
        if (parsed == null) {
            return 6;
        }
        int max = Math.max(parsed.width(), parsed.height());
        if (max >= 2800) {
            return 30;
        }
        if (max >= 1800) {
            return 24;
        }
        return 6;
    }

    private String modelForGenerationSize(String provider, String model, String generationSize) {
        if (provider != null && provider.toLowerCase().contains("rua")) {
            return "gpt-image-2";
        }
        String normalizedModel = model == null || model.isBlank() ? "gpt-image-2" : model.trim();
        String providerText = provider == null ? "" : provider.toLowerCase();
        if (!isGptImage2Family(normalizedModel)) {
            return normalizedModel;
        }
        if (!providerText.contains("hfsyapi")) {
            return "gpt-image-2";
        }
        AiImageSizes.Size parsed = AiImageSizes.parse(generationSize);
        return parsed != null && Math.max(parsed.width(), parsed.height()) >= 1800 ? "gpt-image-2pro" : "gpt-image-2";
    }

    private boolean isGptImage2Family(String value) {
        return "gpt-image-2".equalsIgnoreCase(value) || "gpt-image-2pro".equalsIgnoreCase(value);
    }

    private boolean mockMode() {
        return Boolean.parseBoolean(blankToDefault(System.getenv("MOCK_AI"), "false"))
            || Boolean.parseBoolean(blankToDefault(System.getenv("OPENIMAGE_MOCK_AI"), "false"));
    }

    private List<String> cleanList(List<String> values) {
        return CommerceTextNormalizer.sellingPoints(values);
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record SlotPlan(
        CommerceSuiteDtos.ImageSlot slot,
        CommerceSuiteDtos.TemplatePreset template,
        String generationSize,
        int creditsCost,
        String prompt,
        Map<String, Object> visibleTextPlan
    ) {}

    private record DedupedCommerceSubmission(String projectId, int creditsCost, boolean mockMode, long expiresAtMillis) {}
}
