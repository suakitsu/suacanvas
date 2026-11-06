package com.openimage.modules.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.AiImageClient;
import com.openimage.ai.AiImageSizes;
import com.openimage.ai.AiRuntimeConfigService;
import com.openimage.ai.AiTextCallAuditService;
import com.openimage.ai.AiTextService;
import com.openimage.ai.CreativePromptPolicy;
import com.openimage.domain.entity.CreditLedgerEntity;
import com.openimage.domain.entity.CreativeChatEntity;
import com.openimage.domain.entity.CreativeChatMessageEntity;
import com.openimage.domain.entity.CreativeChatVersionEntity;
import com.openimage.domain.entity.ImageTaskEntity;
import com.openimage.domain.entity.ImageTypeConfigEntity;
import com.openimage.domain.entity.ProductImageEntity;
import com.openimage.domain.entity.ProductProjectEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.mapper.CreditLedgerMapper;
import com.openimage.domain.mapper.CreativeChatMapper;
import com.openimage.domain.mapper.CreativeChatMessageMapper;
import com.openimage.domain.mapper.CreativeChatVersionMapper;
import com.openimage.domain.mapper.ImageTaskMapper;
import com.openimage.domain.mapper.ImageTypeConfigMapper;
import com.openimage.domain.mapper.ProductImageMapper;
import com.openimage.domain.mapper.ProductProjectMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.modules.project.dto.ProjectDtos;
import com.openimage.risk.RiskControlService;
import com.openimage.security.CurrentUser;
import com.openimage.storage.LocalStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProjectService {
    private static final int CREATIVE_CHAT_VERSION_LIMIT = 10;
    private static final int CREATIVE_CHAT_CONTEXT_MESSAGE_LIMIT = 8;
    private static final int CREATIVE_CHAT_MESSAGE_LIMIT = 3000;
    private static final int CREATIVE_CHAT_REFERENCE_LIMIT = 10;
    private static final int CREATIVE_CHAT_DEDUPE_SECONDS = 120;
    private static final int CREATIVE_CHAT_DEDUPE_WAIT_MILLIS = 30000;
    private static final int IMAGE_SUBMIT_DEDUPE_SECONDS = 120;
    private static final int IMAGE_SUBMIT_DEDUPE_WAIT_MILLIS = 30000;

    private static final List<ImageSpec> IMAGE_SPECS = List.of(
        new ImageSpec("main", "1 白底主图", ImageCreditPricing.ONE_K_CREDITS, "gpt-image-2", AiImageSizes.DEFAULT_LISTING_OUTPUT_SIZE),
        new ImageSpec("scenario", "2 场景图", ImageCreditPricing.ONE_K_CREDITS, "gpt-image-2", AiImageSizes.DEFAULT_LISTING_OUTPUT_SIZE),
        new ImageSpec("features", "3 卖点图", ImageCreditPricing.ONE_K_CREDITS, "gpt-image-2", AiImageSizes.DEFAULT_LISTING_OUTPUT_SIZE),
        new ImageSpec("dimensions", "4 尺寸图", ImageCreditPricing.ONE_K_CREDITS, "gpt-image-2", AiImageSizes.DEFAULT_LISTING_OUTPUT_SIZE),
        new ImageSpec("steps", "5 步骤图", ImageCreditPricing.ONE_K_CREDITS, "gpt-image-2", AiImageSizes.DEFAULT_LISTING_OUTPUT_SIZE),
        new ImageSpec("comparison", "6 对比图", ImageCreditPricing.ONE_K_CREDITS, "gpt-image-2", AiImageSizes.DEFAULT_LISTING_OUTPUT_SIZE),
        new ImageSpec("package", "7 包装/变体图", ImageCreditPricing.ONE_K_CREDITS, "gpt-image-2", AiImageSizes.DEFAULT_LISTING_OUTPUT_SIZE)
    );

    private final ProductProjectMapper projects;
    private final CreativeChatMapper creativeChats;
    private final CreativeChatMessageMapper creativeChatMessages;
    private final CreativeChatVersionMapper creativeChatVersions;
    private final ImageTaskMapper tasks;
    private final ImageTypeConfigMapper imageConfigs;
    private final ProductImageMapper productImages;
    private final UserMapper users;
    private final CreditLedgerMapper ledgers;
    private final ObjectMapper json;
    private final LocalStorageService storage;
    private final AiTextService aiText;
    private final AiTextCallAuditService textAudit;
    private final AiImageClient aiImage;
    private final RiskControlService riskControl;
    private final Set<String> creativeChatSubmitLocks = ConcurrentHashMap.newKeySet();
    private final Set<String> singleImageSubmitLocks = ConcurrentHashMap.newKeySet();
    private final Map<String, DedupedProjectSubmission> singleImageSubmitResults = new ConcurrentHashMap<>();
    private final AiRuntimeConfigService aiRuntimeConfig;
    private final TransactionTemplate transactionTemplate;

    public ProjectService(
        ProductProjectMapper projects,
        CreativeChatMapper creativeChats,
        CreativeChatMessageMapper creativeChatMessages,
        CreativeChatVersionMapper creativeChatVersions,
        ImageTaskMapper tasks,
        ImageTypeConfigMapper imageConfigs,
        ProductImageMapper productImages,
        UserMapper users,
        CreditLedgerMapper ledgers,
        ObjectMapper json,
        LocalStorageService storage,
        AiTextService aiText,
        AiTextCallAuditService textAudit,
        AiImageClient aiImage,
        RiskControlService riskControl,
        AiRuntimeConfigService aiRuntimeConfig,
        TransactionTemplate transactionTemplate
    ) {
        this.projects = projects;
        this.creativeChats = creativeChats;
        this.creativeChatMessages = creativeChatMessages;
        this.creativeChatVersions = creativeChatVersions;
        this.tasks = tasks;
        this.imageConfigs = imageConfigs;
        this.productImages = productImages;
        this.users = users;
        this.ledgers = ledgers;
        this.json = json;
        this.storage = storage;
        this.aiText = aiText;
        this.textAudit = textAudit;
        this.aiImage = aiImage;
        this.riskControl = riskControl;
        this.aiRuntimeConfig = aiRuntimeConfig;
        this.transactionTemplate = transactionTemplate;
    }

    public List<ProjectDtos.ProjectSummary> listMine(CurrentUser user) {
        List<ProductProjectEntity> ownedProjects = projects.selectList(new LambdaQueryWrapper<ProductProjectEntity>()
                .eq(ProductProjectEntity::getUserId, user.id())
                .eq(ProductProjectEntity::getDeleted, 0)
                .orderByDesc(ProductProjectEntity::getUpdatedAt));
        List<CreativeChatVersionEntity> chatVersionRows = creativeChatVersions.selectList(new LambdaQueryWrapper<CreativeChatVersionEntity>()
            .eq(CreativeChatVersionEntity::getUserId, user.id())
            .eq(CreativeChatVersionEntity::getDeleted, 0));
        if (chatVersionRows.isEmpty()) {
            return ownedProjects.stream().map(this::summary).toList();
        }

        Map<String, ProductProjectEntity> projectById = new LinkedHashMap<>();
        for (ProductProjectEntity project : ownedProjects) {
            projectById.put(project.getId(), project);
        }
        Map<String, List<CreativeChatVersionEntity>> versionsByChat = new LinkedHashMap<>();
        Set<String> creativeChatProjectIds = new HashSet<>();
        for (CreativeChatVersionEntity version : chatVersionRows) {
            if (version.getChatId() == null || version.getChatId().isBlank()) {
                continue;
            }
            if (version.getProjectId() != null && !version.getProjectId().isBlank()) {
                creativeChatProjectIds.add(version.getProjectId());
            }
            versionsByChat.computeIfAbsent(version.getChatId(), key -> new ArrayList<>()).add(version);
        }

        List<ProjectDtos.ProjectSummary> result = new ArrayList<>();
        for (ProductProjectEntity project : ownedProjects) {
            if (!creativeChatProjectIds.contains(project.getId())) {
                result.add(summary(project));
            }
        }
        for (Map.Entry<String, List<CreativeChatVersionEntity>> entry : versionsByChat.entrySet()) {
            CreativeChatEntity chat = creativeChats.selectById(entry.getKey());
            if (chat == null || chat.getDeleted() != 0 || !user.id().equals(chat.getUserId())) {
                continue;
            }
            ProjectDtos.ProjectSummary chatSummary = creativeChatHistorySummary(chat, entry.getValue(), projectById);
            if (chatSummary != null) {
                result.add(chatSummary);
            }
        }
        result.sort((left, right) -> compareOffsetDesc(left.updatedAt(), right.updatedAt()));
        return result;
    }

    public ProjectDtos.ProjectDetail detail(CurrentUser user, String projectId) throws Exception {
        ProductProjectEntity project = own(user, projectId);
        return detail(project);
    }

    public ProjectDtos.ProjectSummary create(CurrentUser user, ProjectDtos.CreateProjectRequest request) throws Exception {
        ProductProjectEntity project = new ProductProjectEntity();
        project.setId("prod_" + id());
        project.setUserId(user.id());
        project.setBrand(blankToDefault(request.brand(), "未填写品牌"));
        project.setProductName(request.productName());
        project.setCountry(blankToDefault(request.country(), "US"));
        project.setLanguage(blankToDefault(request.language(), "en-US"));
        project.setStatus("draft");
        Map<String, Object> originalInfo = new LinkedHashMap<>();
        originalInfo.put("description", blankToDefault(request.description(), ""));
        originalInfo.put("category", blankToDefault(request.category(), ""));
        originalInfo.put("material", blankToDefault(request.material(), ""));
        originalInfo.put("dimensions", blankToDefault(request.dimensions(), ""));
        originalInfo.put("visual_prompt", blankToDefault(request.visualPrompt(), ""));
        originalInfo.put("keywords", request.keywords() == null ? List.of() : request.keywords());
        originalInfo.put("title_keywords", request.titleKeywords() == null ? (request.keywords() == null ? List.of() : request.keywords()) : request.titleKeywords());
        originalInfo.put("selling_point_keywords", request.sellingPointKeywords() == null ? List.of() : request.sellingPointKeywords());
        originalInfo.put("description_keywords", request.descriptionKeywords() == null ? List.of() : request.descriptionKeywords());
        originalInfo.put("include_aplus", Boolean.TRUE.equals(request.includeAplus()));
        originalInfo.put("aplus_module", blankToDefault(request.aplusModule(), "核心卖点模块"));
        originalInfo.put("aplus_size", normalizeAplusOutputSize(request.aplusSize(), AiImageSizes.DEFAULT_APLUS_OUTPUT_SIZE));
        originalInfo.put("aplus_prompt", blankToDefault(request.aplusPrompt(), ""));
        project.setOriginalInfo(json.writeValueAsString(originalInfo));
        project.setGeneratedCopy("{}");
        project.setConfirmedFacts("{}");
        project.setCreatedAt(OffsetDateTime.now());
        project.setUpdatedAt(OffsetDateTime.now());
        project.setDeleted(0);
        projects.insert(project);
        return summary(project);
    }

    @Transactional
    public Map<String, Object> deleteProject(CurrentUser user, String projectId) {
        own(user, projectId);
        OffsetDateTime now = OffsetDateTime.now();
        List<CreativeChatVersionEntity> sourceVersions = creativeChatVersions.selectList(new LambdaQueryWrapper<CreativeChatVersionEntity>()
            .eq(CreativeChatVersionEntity::getUserId, user.id())
            .eq(CreativeChatVersionEntity::getProjectId, projectId)
            .eq(CreativeChatVersionEntity::getDeleted, 0));
        if (!sourceVersions.isEmpty()) {
            Set<String> chatIds = new HashSet<>();
            for (CreativeChatVersionEntity version : sourceVersions) {
                if (version.getChatId() != null && !version.getChatId().isBlank()) {
                    chatIds.add(version.getChatId());
                }
            }
            Set<String> projectIds = new HashSet<>();
            for (String chatId : chatIds) {
                CreativeChatEntity chat = creativeChats.selectById(chatId);
                if (chat != null && chat.getDeleted() == 0 && user.id().equals(chat.getUserId())) {
                    creativeChats.softDeleteById(chat.getId(), user.id(), now);
                }
                creativeChatMessages.softDeleteByChat(chatId, user.id(), now);
                creativeChatVersions.selectList(new LambdaQueryWrapper<CreativeChatVersionEntity>()
                        .eq(CreativeChatVersionEntity::getUserId, user.id())
                        .eq(CreativeChatVersionEntity::getChatId, chatId)
                        .eq(CreativeChatVersionEntity::getDeleted, 0))
                    .forEach(version -> {
                        if (version.getProjectId() != null && !version.getProjectId().isBlank()) {
                            projectIds.add(version.getProjectId());
                        }
                        creativeChatVersions.softDeleteById(version.getId(), user.id(), now);
                    });
            }
            projectIds.add(projectId);
            for (String id : projectIds) {
                softDeleteProjectWithTasks(user.id(), id, now);
            }
            return Map.of("project_id", projectId, "deleted", true, "source_type", "creative_chat");
        }
        softDeleteProjectWithTasks(user.id(), projectId, now);
        return Map.of("project_id", projectId, "deleted", true);
    }

    @Transactional
    public Map<String, Object> deleteFailedTask(CurrentUser user, String projectId, String taskId) {
        own(user, projectId);
        ImageTaskEntity task = tasks.lockById(taskId);
        if (task == null || task.getDeleted() != 0 || isHiddenByUser(task) || !user.id().equals(task.getUserId()) || !projectId.equals(task.getProjectId())) {
            throw new IllegalArgumentException("Task not found");
        }
        if (!"failed".equals(task.getStatus())) {
            throw new IllegalArgumentException("只能删除失败的图片任务，生成中或已完成任务请保留结果");
        }
        OffsetDateTime now = OffsetDateTime.now();
        int hidden = tasks.hideFailedForUser(taskId, user.id(), projectId, now);
        if (hidden == 0) {
            throw new IllegalArgumentException("Task not found");
        }

        creativeChatVersions.selectList(new LambdaQueryWrapper<CreativeChatVersionEntity>()
                .eq(CreativeChatVersionEntity::getUserId, user.id())
                .eq(CreativeChatVersionEntity::getProjectId, projectId)
                .eq(CreativeChatVersionEntity::getTaskId, taskId)
                .eq(CreativeChatVersionEntity::getDeleted, 0))
            .forEach(version -> {
                creativeChatVersions.softDeleteById(version.getId(), user.id(), now);
                softDeleteCreativeChatMessage(version.getUserMessageId(), user.id(), now);
                softDeleteCreativeChatMessage(version.getAssistantMessageId(), user.id(), now);
                refreshCreativeChatAfterVersionDelete(version.getChatId(), user.id(), now);
            });

        refreshProjectAfterTaskDelete(user.id(), projectId, now);
        return Map.of("project_id", projectId, "task_id", taskId, "hidden", true);
    }

    private void softDeleteCreativeChatMessage(String messageId, String userId, OffsetDateTime now) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        CreativeChatMessageEntity message = creativeChatMessages.selectById(messageId);
        if (message == null || message.getDeleted() != 0 || !userId.equals(message.getUserId())) {
            return;
        }
        creativeChatMessages.softDeleteById(messageId, userId, now);
    }

    private void refreshCreativeChatAfterVersionDelete(String chatId, String userId, OffsetDateTime now) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        CreativeChatEntity chat = creativeChats.selectById(chatId);
        if (chat == null || chat.getDeleted() != 0 || !userId.equals(chat.getUserId())) {
            return;
        }
        Long remaining = creativeChatVersions.selectCount(new LambdaQueryWrapper<CreativeChatVersionEntity>()
            .eq(CreativeChatVersionEntity::getChatId, chatId)
            .eq(CreativeChatVersionEntity::getUserId, userId)
            .eq(CreativeChatVersionEntity::getDeleted, 0));
        if (remaining == null || remaining == 0) {
            creativeChats.softDeleteById(chatId, userId, now);
            return;
        }
        chat.setUpdatedAt(now);
        creativeChats.updateById(chat);
    }

    private void refreshProjectAfterTaskDelete(String userId, String projectId, OffsetDateTime now) {
        ProductProjectEntity project = projects.selectById(projectId);
        if (project == null || project.getDeleted() != 0 || !userId.equals(project.getUserId())) {
            return;
        }
        List<ImageTaskEntity> remaining = tasks.selectList(new LambdaQueryWrapper<ImageTaskEntity>()
            .eq(ImageTaskEntity::getUserId, userId)
            .eq(ImageTaskEntity::getProjectId, projectId)
            .eq(ImageTaskEntity::getDeleted, 0)
            .eq(ImageTaskEntity::getHiddenByUser, 0)
            .orderByAsc(ImageTaskEntity::getCreatedAt));
        if (remaining.isEmpty()) {
            projects.softDeleteById(projectId, userId, now);
            return;
        } else {
            project.setStatus(aggregateStatus(project.getStatus(), remaining));
        }
        project.setUpdatedAt(now);
        projects.updateById(project);
    }

    private void softDeleteProjectWithTasks(String userId, String projectId, OffsetDateTime now) {
        ProductProjectEntity project = projects.selectById(projectId);
        if (project == null || project.getDeleted() != 0 || !userId.equals(project.getUserId())) {
            return;
        }
        projects.softDeleteById(projectId, userId, now);
        tasks.hideByProjectForUser(userId, projectId, now);
    }

    public Map<String, Object> optimizePrompt(CurrentUser user, ProjectDtos.OptimizePromptRequest request) {
        checkRisk(user, "", "", "prompt_optimize", String.join("\n",
            request == null ? "" : blankToDefault(request.prompt(), ""),
            request == null ? "" : blankToDefault(request.productName(), ""),
            request == null ? "" : blankToDefault(request.marketplace(), "")
        ), "user_input");
        try (AiTextCallAuditService.Scope ignored = textAudit.open(user.id(), "prompt_optimize", "project", "")) {
            return Map.of("optimized_prompt", aiText.optimizePrompt(request.prompt(), request.productName(), request.marketplace(), request.language()));
        }
    }

    public Map<String, Object> suggestDomesticSellingPoints(CurrentUser user, ProjectDtos.DomesticSellingPointSuggestionRequest request) {
        if (request == null || request.productName() == null || request.productName().isBlank()) {
            throw new IllegalArgumentException("请先填写商品名称");
        }
        checkRisk(user, "", "", "domestic_selling_points", String.join("\n",
            blankToDefault(request.productName(), ""),
            blankToDefault(request.platform(), ""),
            blankToDefault(request.usage(), ""),
            blankToDefault(request.specs(), ""),
            blankToDefault(request.audience(), "")
        ), "user_input");
        List<String> suggestions;
        try (AiTextCallAuditService.Scope ignored = textAudit.open(user.id(), "domestic_selling_points", "domestic", request.productName())) {
            suggestions = aiText.suggestDomesticSellingPoints(
                request.productName(),
                request.platform(),
                request.usage(),
                request.specs(),
                request.audience(),
                request.language()
            );
        }
        return Map.of(
            "selling_points", suggestions,
            "text", String.join("、", suggestions),
            "source", "ai_assisted_category_common"
        );
    }

    @Transactional
    public ProjectDtos.ProjectDetail createLinkedAplusProject(CurrentUser user, String sourceProjectId, ProjectDtos.LinkedAplusRequest request) throws Exception {
        ProductProjectEntity source = projects.lockById(sourceProjectId);
        if (source == null || source.getDeleted() != 0 || !source.getUserId().equals(user.id())) {
            throw new IllegalArgumentException("Project not found");
        }
        Map<String, Object> sourceOriginal = new LinkedHashMap<>(readMap(source.getOriginalInfo()));
        String fallbackPrompt = blankToDefault(String.valueOf(sourceOriginal.getOrDefault("aplus_prompt", "")),
            blankToDefault(String.valueOf(sourceOriginal.getOrDefault("description", "")),
                source.getProductName() + " Amazon A+ content module. Highlight key selling points, brand trust, usage scenario, and render only confirmed short text when requested."));
        String prompt = blankToDefault(request == null ? null : request.prompt(), fallbackPrompt);
        String size = normalizeAplusOutputSize(request == null ? null : request.size(), normalizeAplusOutputSize(String.valueOf(sourceOriginal.getOrDefault("aplus_size", "")), AiImageSizes.DEFAULT_APLUS_OUTPUT_SIZE));
        String module = blankToDefault(request == null ? null : request.module(), blankToDefault(String.valueOf(sourceOriginal.getOrDefault("aplus_module", "")), "核心卖点模块"));
        String language = blankToDefault(request == null ? null : request.language(), source.getLanguage());

        sourceOriginal.put("include_aplus", true);
        sourceOriginal.put("aplus_module", module);
        sourceOriginal.put("aplus_size", size);
        sourceOriginal.put("aplus_prompt", prompt);
        ProductProjectEntity existing = linkedAplusProject(user.id(), sourceOriginal, source.getId());
        if (existing != null) {
            sourceOriginal.put("linked_aplus_project_id", existing.getId());
            sourceOriginal.put("linked_aplus_product_name", existing.getProductName());
            source.setOriginalInfo(json.writeValueAsString(sourceOriginal));
            source.setUpdatedAt(OffsetDateTime.now());
            projects.updateById(source);
            return detail(existing);
        }

        ProjectDtos.ProjectDetail created = createSingleImageProject(
            user,
            new ProjectDtos.SingleImageRequest(source.getProductName() + " A+ 模块", source.getBrand(), prompt, size, language, module, null),
            "aplus",
            null
        );
        cloneProductImages(user.id(), source.getId(), created.project().id());

        ProductProjectEntity linked = projects.selectById(created.project().id());
        Map<String, Object> original = new LinkedHashMap<>(readMap(linked.getOriginalInfo()));
        original.put("linked_from_project_id", source.getId());
        original.put("linked_from_product_name", source.getProductName());
        original.put("reference_images_reused", productImageEntities(user.id(), source.getId()).size());
        linked.setOriginalInfo(json.writeValueAsString(original));
        linked.setUpdatedAt(OffsetDateTime.now());
        projects.updateById(linked);
        sourceOriginal.put("linked_aplus_project_id", linked.getId());
        sourceOriginal.put("linked_aplus_product_name", linked.getProductName());
        source.setOriginalInfo(json.writeValueAsString(sourceOriginal));
        source.setUpdatedAt(OffsetDateTime.now());
        projects.updateById(source);
        return detail(linked);
    }

    private ProductProjectEntity linkedAplusProject(String userId, Map<String, Object> sourceOriginal, String sourceProjectId) {
        Object linkedId = sourceOriginal.get("linked_aplus_project_id");
        if (linkedId instanceof String id && !id.isBlank()) {
            ProductProjectEntity existing = projects.selectById(id);
            if (existing != null && existing.getDeleted() == 0 && userId.equals(existing.getUserId())) {
                return existing;
            }
        }
        return projects.findLinkedAplusBySource(userId, sourceProjectId);
    }

    public List<ProjectDtos.CreativeChatSummary> listCreativeChats(CurrentUser user) {
        return creativeChats.selectList(new LambdaQueryWrapper<CreativeChatEntity>()
                .eq(CreativeChatEntity::getUserId, user.id())
                .eq(CreativeChatEntity::getDeleted, 0)
                .orderByDesc(CreativeChatEntity::getUpdatedAt)
                .last("limit 30"))
            .stream()
            .map(chat -> {
                hydrateCreativeChat(chat);
                return creativeChatSummary(chat);
            })
            .toList();
    }

    public ProjectDtos.CreativeChatView creativeChat(CurrentUser user, String chatId) {
        CreativeChatEntity chat = ownCreativeChat(user, chatId);
        hydrateCreativeChat(chat);
        return creativeChatView(chat);
    }

    public ProjectDtos.CreativeChatEstimateResponse estimateCreativeChat(CurrentUser user, ProjectDtos.CreativeChatGenerateRequest request) {
        String message = request == null ? "" : blankToDefault(request.message(), "");
        String size = request == null ? "" : blankToDefault(request.size(), "");
        boolean hasReference = request != null && !normalizeImageUrl(request.referenceUrl()).isBlank();
        boolean hasImageContext = request != null && request.chatId() != null && !request.chatId().isBlank();
        AgentImagePolicy.Decision decision = AgentImagePolicy.decide(message, size, hasReference, hasImageContext);
        AgentImagePolicy.ImagePlan plan = decision.shouldGenerateImage() ? AgentImagePolicy.imagePlan(message, size) : AgentImagePolicy.chatPlan();
        return new ProjectDtos.CreativeChatEstimateResponse(plan.size(), plan.qualityLabel(), plan.creditsCost());
    }

    public ProjectDtos.CreativeChatGenerateResponse generateCreativeChat(CurrentUser user, ProjectDtos.CreativeChatGenerateRequest request) throws Exception {
        return generateCreativeChat(user, request, List.of(), null);
    }

    public ProjectDtos.CreativeChatGenerateResponse generateCreativeChat(CurrentUser user, ProjectDtos.CreativeChatGenerateRequest request, MultipartFile reference) throws Exception {
        return generateCreativeChat(user, request, List.of(), reference);
    }

    public ProjectDtos.CreativeChatGenerateResponse generateCreativeChat(CurrentUser user, ProjectDtos.CreativeChatGenerateRequest request, List<MultipartFile> references, MultipartFile reference) throws Exception {
        if (request == null) {
            request = new ProjectDtos.CreativeChatGenerateRequest("", "", "", "", "", "");
        }
        ProjectDtos.CreativeChatGenerateRequest normalizedRequest = request;
        String message = truncate(blankToDefault(normalizedRequest.message(), "").trim(), CREATIVE_CHAT_MESSAGE_LIMIT);
        if (message.isBlank()) {
            throw new IllegalArgumentException("请输入生图需求或修改意见");
        }
        CreativeChatEntity existing = normalizedRequest.chatId() == null || normalizedRequest.chatId().isBlank() ? null : ownCreativeChat(user, normalizedRequest.chatId());
        List<OwnedCreativeReference> quotedReferences = resolveOwnedCreativeReferences(
            user.id(),
            existing == null ? "" : existing.getId(),
            normalizedRequest.referenceUrls()
        );
        String directReferenceUrl = normalizeImageUrl(normalizedRequest.referenceUrl());
        if (!directReferenceUrl.isBlank()) {
            quotedReferences = quotedReferences.stream()
                .filter(item -> !directReferenceUrl.equals(item.url()))
                .toList();
        }
        int availableUploadSlots = CREATIVE_CHAT_REFERENCE_LIMIT
            - quotedReferences.size()
            - (directReferenceUrl.isBlank() ? 0 : 1);
        if (availableUploadSlots < 0) {
            throw new IllegalArgumentException("最多支持 " + CREATIVE_CHAT_REFERENCE_LIMIT + " 张参考图");
        }
        List<MultipartFile> normalizedReferences = normalizeReferenceFiles(references, reference, availableUploadSlots);
        checkRisk(user, "", "", "creative_chat", message + "\n" + blankToDefault(normalizedRequest.style(), ""), "user_input");
        String dedupeKey = existing == null ? creativeChatDedupeKey(user.id(), message) : "";
        boolean dedupeLocked = false;
        if (existing == null) {
            ProjectDtos.CreativeChatGenerateResponse duplicate = recentDuplicateCreativeChatGeneration(user, message);
            if (duplicate != null) {
                return duplicate;
            }
            if (!creativeChatSubmitLocks.add(dedupeKey)) {
                return waitForDuplicateCreativeChatGeneration(user, message);
            }
            dedupeLocked = true;
        }
        try {
        boolean hasReference = !normalizedReferences.isEmpty() || !quotedReferences.isEmpty() || !directReferenceUrl.isBlank();
        normalizedRequest = withDomesticCommerceDefaults(normalizedRequest, message, hasReference);
        ProjectDtos.CreativeChatGenerateRequest requestForGeneration = normalizedRequest;
        List<OwnedCreativeReference> referencesForGeneration = quotedReferences;
        boolean hasImageContext = existing != null && creativeChatVersionCount(existing.getId(), user.id()) > 0;
        List<AiTextService.CreativeChatTurn> context = creativeChatContext(user, existing);
        AgentImagePolicy.Decision decision;
        try (AiTextCallAuditService.Scope ignored = textAudit.open(user.id(), "image_agent_decision", "creative_chat", existing == null ? "" : existing.getId())) {
            decision = agentDecision(context, message, requestForGeneration.size(), requestForGeneration.language(), hasReference, hasImageContext);
        }
        if (!decision.shouldGenerateImage()) {
            return transactionTemplate.execute(status -> persistCreativeChatReply(user, existing, requestForGeneration, message, decision.assistantReply()));
        }
        if (existing != null && creativeChatVersionCount(existing.getId(), user.id()) >= CREATIVE_CHAT_VERSION_LIMIT) {
            throw new IllegalArgumentException("当前对话已达到 10 轮上限，请新开一个对话继续生成，避免上下文过长影响速度和效果");
        }
        ensureImageEnabled();
        UserEntity account = users.selectById(user.id());
        if (account == null || account.getDeleted() != 0) {
            throw new IllegalArgumentException("User not found");
        }
        AgentImagePolicy.ImagePlan plan = AgentImagePolicy.imagePlan(message, requestForGeneration.size());
        if (account.getCredits() < plan.creditsCost()) {
            throw new IllegalArgumentException("积分不足，本次生成需要 " + plan.creditsCost() + " 积分");
        }
        String plannedSize = plan.size();
        String finalPrompt;
        try (AiTextCallAuditService.Scope ignored = textAudit.open(user.id(), "image_agent_prompt", "creative_chat", existing == null ? "" : existing.getId())) {
            Map<String, Object> intentFacts = imageIntentFacts(
                requestForGeneration,
                message,
                requestForGeneration.style(),
                hasReference,
                referencesForGeneration.size() + referenceCount(normalizedReferences, requestForGeneration.referenceUrl())
            );
            finalPrompt = aiText.buildCreativeChatPrompt(
                context,
                message,
                requestForGeneration.style(),
                plannedSize,
                blankToDefault(requestForGeneration.language(), "zh-CN"),
                !normalizedReferences.isEmpty() || !referencesForGeneration.isEmpty() ? "uploaded-reference-images" : requestForGeneration.referenceUrl(),
                intentFacts
            );
        }
        try {
            ProjectDtos.CreativeChatGenerateResponse response = transactionTemplate.execute(status -> {
                try {
                    return persistCreativeChatGeneration(user, existing, requestForGeneration, message, finalPrompt, plan, normalizedReferences, referencesForGeneration);
                } catch (Exception exception) {
                    throw new CreativeChatPersistenceException(exception);
                }
            });
            if (response == null) {
                throw new IllegalArgumentException("对话生图提交失败，请重试");
            }
            return response;
        } catch (CreativeChatPersistenceException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw exception;
        }
        } finally {
            if (dedupeLocked) {
                creativeChatSubmitLocks.remove(dedupeKey);
            }
        }
    }

    private String creativeChatDedupeKey(String userId, String message) {
        return blankToDefault(userId, "") + "\n" + blankToDefault(message, "");
    }

    private ProjectDtos.CreativeChatGenerateRequest withDomesticCommerceDefaults(ProjectDtos.CreativeChatGenerateRequest request, String message, boolean hasReference) {
        if (!isDomesticCommerceRequest(message + " " + blankToDefault(request.style(), ""))) {
            return request;
        }
        return new ProjectDtos.CreativeChatGenerateRequest(
            request.chatId(),
            request.message(),
            resolveDomesticModule(message),
            request.size(),
            request.language(),
            request.referenceUrl(),
            request.referenceUrls(),
            normalizeIntent(request.intent()).isBlank() ? "poster_layout" : request.intent(),
            normalizeTextMode(request.textMode()).isBlank() ? "ai_short_text" : request.textMode(),
            normalizeReferencePolicy(request.referencePolicy()).isBlank() && hasReference ? "preserve" : request.referencePolicy(),
            request.templateId()
        );
    }

    private boolean isDomesticCommerceRequest(String value) {
        return containsAny(value, "国内", "小红书", "淘宝", "天猫", "京东", "拼多多", "1688", "抖音", "快手", "主图", "详情页", "卖点图", "种草", "封面", "电商图", "商品图");
    }

    private String resolveDomesticModule(String value) {
        String text = blankToDefault(value, "");
        if (containsAny(text, "小红书", "种草")) return "国内电商 / 小红书 / 小红书种草图";
        if (containsAny(text, "淘宝", "天猫")) return "国内电商 / 淘宝/天猫 / 主图";
        if (containsAny(text, "1688")) return "国内电商 / 1688 / 详情页模块";
        if (containsAny(text, "京东")) return "国内电商 / 京东 / 商品图";
        if (containsAny(text, "拼多多")) return "国内电商 / 拼多多 / 主图";
        if (containsAny(text, "快手")) return "国内电商 / 快手电商 / 商品图";
        if (containsAny(text, "抖音")) return "国内电商 / 抖音电商 / 商品图";
        return "国内电商 / 通用 / 商品图";
    }

    private ProjectDtos.CreativeChatGenerateResponse waitForDuplicateCreativeChatGeneration(CurrentUser user, String message) throws Exception {
        long deadline = System.currentTimeMillis() + CREATIVE_CHAT_DEDUPE_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ProjectDtos.CreativeChatGenerateResponse duplicate = recentDuplicateCreativeChatGeneration(user, message);
            if (duplicate != null) {
                return duplicate;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalArgumentException("任务已提交，请稍后刷新历史记录查看结果");
    }

    private ProjectDtos.CreativeChatGenerateResponse recentDuplicateCreativeChatGeneration(CurrentUser user, String message) throws Exception {
        CreativeChatVersionEntity version = creativeChatVersions.selectList(new LambdaQueryWrapper<CreativeChatVersionEntity>()
                .eq(CreativeChatVersionEntity::getUserId, user.id())
                .eq(CreativeChatVersionEntity::getUserRequest, message)
                .eq(CreativeChatVersionEntity::getDeleted, 0)
                .ge(CreativeChatVersionEntity::getCreatedAt, OffsetDateTime.now().minusSeconds(CREATIVE_CHAT_DEDUPE_SECONDS))
                .orderByDesc(CreativeChatVersionEntity::getCreatedAt)
                .last("limit 1"))
            .stream()
            .findFirst()
            .orElse(null);
        if (version == null || version.getChatId() == null || version.getProjectId() == null) {
            return null;
        }
        CreativeChatEntity chat = creativeChats.selectById(version.getChatId());
        ProductProjectEntity project = projects.selectById(version.getProjectId());
        if (chat == null || chat.getDeleted() != 0 || project == null || project.getDeleted() != 0) {
            return null;
        }
        hydrateCreativeChat(chat);
        return new ProjectDtos.CreativeChatGenerateResponse(creativeChatView(chat), detail(project), version.getFinalPrompt(), versionNumber(version));
    }

    private ProjectDtos.CreativeChatGenerateResponse persistCreativeChatGeneration(
        CurrentUser user,
        CreativeChatEntity existing,
        ProjectDtos.CreativeChatGenerateRequest request,
        String userMessage,
        String finalPrompt,
        AgentImagePolicy.ImagePlan plan,
        List<MultipartFile> references,
        List<OwnedCreativeReference> quotedReferences
    ) throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        CreativeChatEntity chat = existing == null ? new CreativeChatEntity() : creativeChats.selectById(existing.getId());
        if (existing != null && (chat == null || chat.getDeleted() != 0 || !chat.getUserId().equals(user.id()))) {
            throw new IllegalArgumentException("Chat not found");
        }
        String style = blankToDefault(request.style(), "自由创作");
        String size = normalizeCreativeSize(plan.size(), AiImageSizes.DEFAULT_CREATIVE_SIZE);
        String language = blankToDefault(request.language(), "zh-CN");
        String referenceUrl = normalizeImageUrl(request.referenceUrl());
        if (existing == null) {
            chat.setId("cchat_" + id());
            chat.setUserId(user.id());
            chat.setTitle(chatTitle(userMessage));
            chat.setCreatedAt(now);
            chat.setDeleted(0);
        } else if ((chat.getTitle() == null || chat.getTitle().isBlank()) && !userMessage.isBlank()) {
            chat.setTitle(chatTitle(userMessage));
        }
        chat.setStyle(style);
        chat.setSize(size);
        chat.setLanguage(language);
        chat.setReferenceUrl(referenceUrl);
        chat.setUpdatedAt(now);
        if (existing == null) {
            creativeChats.insert(chat);
        } else {
            creativeChats.updateById(chat);
        }

        CreativeChatMessageEntity userRow = new CreativeChatMessageEntity();
        userRow.setId("cmsg_" + id());
        userRow.setChatId(chat.getId());
        userRow.setUserId(user.id());
        userRow.setRole("user");
        userRow.setContent(userMessage);
        userRow.setStatus("submitted");
        userRow.setCreatedAt(now);
        userRow.setUpdatedAt(now);
        userRow.setDeleted(0);
        creativeChatMessages.insert(userRow);

        ProjectDtos.ProjectDetail project = createSingleImageProjectInternal(
            user,
            new ProjectDtos.SingleImageRequest("对话生图", style, finalPrompt, size, language, style, referenceUrl, request.intent(), request.textMode(), request.referencePolicy(), request.templateId()),
            "creative",
            plan.creditsCost(),
            references,
            null,
            "ai_output",
            quotedReferences
        );
        String persistedReferenceUrl = firstReferenceUrl(project, referenceUrl);
        ProjectDtos.ImageTaskView task = project.imageTasks().isEmpty() ? null : project.imageTasks().get(0);
        int versionNo = nextCreativeChatVersion(chat.getId());

        OffsetDateTime assistantMessageTime = now.plusNanos(1_000_000);
        CreativeChatMessageEntity assistantRow = new CreativeChatMessageEntity();
        assistantRow.setId("cmsg_" + id());
        assistantRow.setChatId(chat.getId());
        assistantRow.setUserId(user.id());
        assistantRow.setRole("assistant");
        assistantRow.setContent(finalPrompt);
        assistantRow.setProjectId(project.project().id());
        assistantRow.setTaskId(task == null ? null : task.id());
        assistantRow.setResultUrl(task == null ? "" : blankToDefault(task.resultUrl(), ""));
        assistantRow.setStatus(task == null ? "queued" : task.status());
        assistantRow.setVersionNo(versionNo);
        assistantRow.setCreatedAt(assistantMessageTime);
        assistantRow.setUpdatedAt(assistantMessageTime);
        assistantRow.setDeleted(0);
        creativeChatMessages.insert(assistantRow);

        userRow.setVersionNo(versionNo);
        userRow.setProjectId(project.project().id());
        userRow.setTaskId(task == null ? null : task.id());
        userRow.setUpdatedAt(now);
        creativeChatMessages.updateById(userRow);

        CreativeChatVersionEntity version = new CreativeChatVersionEntity();
        version.setId("cver_" + id());
        version.setChatId(chat.getId());
        version.setUserId(user.id());
        version.setVersionNo(versionNo);
        version.setUserMessageId(userRow.getId());
        version.setAssistantMessageId(assistantRow.getId());
        version.setProjectId(project.project().id());
        version.setTaskId(task == null ? null : task.id());
        version.setUserRequest(userMessage);
        version.setFinalPrompt(finalPrompt);
        version.setStyle(style);
        version.setSize(size);
        version.setReferenceUrl(persistedReferenceUrl);
        version.setStatus(task == null ? "queued" : task.status());
        version.setResultUrl(task == null ? "" : blankToDefault(task.resultUrl(), ""));
        version.setSafeError(task == null ? "" : blankToDefault(task.safeError(), ""));
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        version.setDeleted(0);
        creativeChatVersions.insert(version);
        if (!persistedReferenceUrl.equals(referenceUrl)) {
            chat.setReferenceUrl(persistedReferenceUrl);
            chat.setUpdatedAt(now);
            creativeChats.updateById(chat);
        }

        hydrateCreativeChat(chat);
        return new ProjectDtos.CreativeChatGenerateResponse(creativeChatView(chat), project, finalPrompt, versionNo);
    }

    private List<AiTextService.CreativeChatTurn> creativeChatContext(CurrentUser user, CreativeChatEntity existing) {
        if (existing == null) {
            return List.of();
        }
        return creativeChatMessages.selectList(new LambdaQueryWrapper<CreativeChatMessageEntity>()
            .eq(CreativeChatMessageEntity::getChatId, existing.getId())
            .eq(CreativeChatMessageEntity::getUserId, user.id())
            .eq(CreativeChatMessageEntity::getDeleted, 0)
            .orderByDesc(CreativeChatMessageEntity::getCreatedAt)
            .last("limit " + CREATIVE_CHAT_CONTEXT_MESSAGE_LIMIT))
            .stream()
            .sorted((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()))
            .map(row -> new AiTextService.CreativeChatTurn(row.getRole(), row.getContent()))
            .toList();
    }

    private AgentImagePolicy.Decision agentDecision(
        List<AiTextService.CreativeChatTurn> context,
        String message,
        String requestedSize,
        String language,
        boolean hasReference,
        boolean hasImageContext
    ) {
        if (AgentImagePolicy.isGenerationFollowUp(
            message,
            latestCreativeChatTurn(context, "user"),
            latestCreativeChatTurn(context, "assistant")
        )) {
            return new AgentImagePolicy.Decision(true, "");
        }
        AgentImagePolicy.Decision local = AgentImagePolicy.decide(message, requestedSize, hasReference, hasImageContext);
        AiTextService.CreativeChatPlan aiPlan = aiText.planCreativeChat(context, message, requestedSize, blankToDefault(language, "zh-CN"), hasReference, hasImageContext);
        String intent = aiPlan.intent() == null ? "" : aiPlan.intent();
        if (aiPlan.confidence() >= 0.7) {
            if ("generate_image".equals(intent) || "edit_image".equals(intent)) {
                return local.shouldGenerateImage()
                    ? new AgentImagePolicy.Decision(true, "")
                    : new AgentImagePolicy.Decision(false, blankToDefault(aiPlan.reply(), local.assistantReply()));
            }
            if ("chat".equals(intent) || "clarify".equals(intent)) {
                return local.shouldGenerateImage() && ("chat".equals(intent) && aiPlan.confidence() < 0.85)
                    ? local
                    : new AgentImagePolicy.Decision(false, blankToDefault(aiPlan.reply(), local.assistantReply()));
            }
        }
        return local;
    }

    private String latestCreativeChatTurn(List<AiTextService.CreativeChatTurn> context, String role) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        for (int index = context.size() - 1; index >= 0; index--) {
            AiTextService.CreativeChatTurn turn = context.get(index);
            if (turn != null && role.equalsIgnoreCase(blankToDefault(turn.role(), ""))) {
                return blankToDefault(turn.content(), "");
            }
        }
        return "";
    }

    private ProjectDtos.CreativeChatGenerateResponse persistCreativeChatReply(
        CurrentUser user,
        CreativeChatEntity existing,
        ProjectDtos.CreativeChatGenerateRequest request,
        String userMessage,
        String assistantReply
    ) {
        OffsetDateTime now = OffsetDateTime.now();
        CreativeChatEntity chat = existing == null ? new CreativeChatEntity() : creativeChats.selectById(existing.getId());
        if (existing != null && (chat == null || chat.getDeleted() != 0 || !chat.getUserId().equals(user.id()))) {
            throw new IllegalArgumentException("Chat not found");
        }
        String style = blankToDefault(request.style(), "Agent 对话");
        String language = blankToDefault(request.language(), "zh-CN");
        String referenceUrl = normalizeImageUrl(request.referenceUrl());
        if (existing == null) {
            chat.setId("cchat_" + id());
            chat.setUserId(user.id());
            chat.setTitle(chatTitle(userMessage));
            chat.setCreatedAt(now);
            chat.setDeleted(0);
        } else if ((chat.getTitle() == null || chat.getTitle().isBlank()) && !userMessage.isBlank()) {
            chat.setTitle(chatTitle(userMessage));
        }
        chat.setStyle(style);
        chat.setSize(blankToDefault(chat.getSize(), ""));
        chat.setLanguage(language);
        if (!referenceUrl.isBlank()) {
            chat.setReferenceUrl(referenceUrl);
        }
        chat.setUpdatedAt(now);
        if (existing == null) {
            creativeChats.insert(chat);
        } else {
            creativeChats.updateById(chat);
        }

        CreativeChatMessageEntity userRow = new CreativeChatMessageEntity();
        userRow.setId("cmsg_" + id());
        userRow.setChatId(chat.getId());
        userRow.setUserId(user.id());
        userRow.setRole("user");
        userRow.setContent(userMessage);
        userRow.setStatus("chat");
        userRow.setCreatedAt(now);
        userRow.setUpdatedAt(now);
        userRow.setDeleted(0);
        creativeChatMessages.insert(userRow);

        OffsetDateTime assistantMessageTime = now.plusNanos(1_000_000);
        CreativeChatMessageEntity assistantRow = new CreativeChatMessageEntity();
        assistantRow.setId("cmsg_" + id());
        assistantRow.setChatId(chat.getId());
        assistantRow.setUserId(user.id());
        assistantRow.setRole("assistant");
        assistantRow.setContent(blankToDefault(assistantReply, "可以聊。你告诉我想要什么画面，我会在真正提交生图时再扣积分。"));
        assistantRow.setStatus("chat");
        assistantRow.setCreatedAt(assistantMessageTime);
        assistantRow.setUpdatedAt(assistantMessageTime);
        assistantRow.setDeleted(0);
        creativeChatMessages.insert(assistantRow);

        return new ProjectDtos.CreativeChatGenerateResponse(creativeChatView(chat), null, "", 0);
    }

    private String firstReferenceUrl(ProjectDtos.ProjectDetail project, String fallback) {
        if (project == null || project.productImages() == null) {
            return blankToDefault(fallback, "");
        }
        for (ProjectDtos.ProductImageView image : project.productImages()) {
            String imageType = normalizeImageType(blankToDefault(image.imageType(), ""));
            if (imageType.startsWith("reference")) {
                return blankToDefault(image.url(), fallback);
            }
        }
        return blankToDefault(fallback, "");
    }

    @Transactional
    public ProjectDtos.ProjectDetail createSingleImageProject(CurrentUser user, ProjectDtos.SingleImageRequest request, String mode) throws Exception {
        return createSingleImageProject(user, request, mode, null);
    }

    @Transactional
    public ProjectDtos.ProjectDetail createSingleImageProject(CurrentUser user, ProjectDtos.SingleImageRequest request, String mode, MultipartFile reference) throws Exception {
        return createSingleImageProject(user, request, mode, List.of(), reference);
    }

    @Transactional
    public ProjectDtos.ProjectDetail createSingleImageProject(CurrentUser user, ProjectDtos.SingleImageRequest request, String mode, List<MultipartFile> references, MultipartFile reference) throws Exception {
        return createSingleImageProject(user, request, mode, 0, references, reference);
    }

    @Transactional
    public ProjectDtos.ProjectDetail createSingleImageProject(CurrentUser user, ProjectDtos.SingleImageRequest request, String mode, int overrideCost, MultipartFile reference) throws Exception {
        return createSingleImageProject(user, request, mode, overrideCost, List.of(), reference);
    }

    @Transactional
    public ProjectDtos.ProjectDetail createSingleImageProject(CurrentUser user, ProjectDtos.SingleImageRequest request, String mode, int overrideCost, List<MultipartFile> references, MultipartFile reference) throws Exception {
        return createSingleImageProjectInternal(user, request, mode, overrideCost, references, reference, "user_input", List.of());
    }

    private ProjectDtos.ProjectDetail createSingleImageProjectInternal(
        CurrentUser user,
        ProjectDtos.SingleImageRequest request,
        String mode,
        int overrideCost,
        List<MultipartFile> references,
        MultipartFile reference,
        String promptSource,
        List<OwnedCreativeReference> quotedReferences
    ) throws Exception {
        String imageType = "aplus".equals(mode) ? "aplus" : "creative";
        String imageName = "aplus".equals(mode) ? "A+ 模块图" : "自由创作图";
        String prompt = blankToDefault(request.prompt(), "").trim();
        String outputSize = "aplus".equals(mode)
            ? normalizeAplusOutputSize(request.size(), AiImageSizes.DEFAULT_APLUS_OUTPUT_SIZE)
            : normalizeCreativeSize(request.size(), AiImageSizes.DEFAULT_CREATIVE_SIZE);
        String generationSize = "aplus".equals(mode)
            ? generationSizeForOutput(outputSize)
            : outputSize;
        int baseCost = overrideCost > 0 ? overrideCost : ImageCreditPricing.creditsForSize(generationSize);
        int cost = "creative".equals(imageType)
            ? Math.max(baseCost, AgentImagePolicy.creditsForCreativeSize(generationSize))
            : baseCost;
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("请输入生成提示词");
        }
        String dedupeKey = singleImageDedupeKey(user.id(), mode, request, outputSize, generationSize, cost, references, reference, quotedReferences);
        ProjectDtos.ProjectDetail duplicate = recentDuplicateSingleImageProject(user, dedupeKey);
        if (duplicate != null) {
            return duplicate;
        }
        boolean dedupeLocked = singleImageSubmitLocks.add(dedupeKey);
        if (!dedupeLocked) {
            return waitForDuplicateSingleImageProject(user, dedupeKey);
        }
        boolean dedupeSuccess = false;
        String dedupeProjectId = "";
        try {
        ensureImageEnabled();
        checkRisk(user, "", "", imageType + "_generate", String.join("\n",
            prompt,
            blankToDefault(request.productName(), ""),
            blankToDefault(request.brand(), ""),
            blankToDefault(request.module(), "")
        ), promptSource);

        UserEntity accountPreview = users.selectById(user.id());
        if (accountPreview == null || accountPreview.getDeleted() != 0) {
            throw new IllegalArgumentException("User not found");
        }
        if (accountPreview.getCredits() < cost) {
            throw new IllegalArgumentException("积分不足，本次生成需要 " + cost + " 积分");
        }

        OffsetDateTime now = OffsetDateTime.now();
        ProductProjectEntity project = new ProductProjectEntity();
        project.setId("prod_" + id());
        project.setUserId(user.id());
        project.setBrand(blankToDefault(request.brand(), "自由创作"));
        project.setProductName(blankToDefault(request.productName(), imageName));
        project.setCountry("CUSTOM");
        project.setLanguage(blankToDefault(request.language(), "zh-CN"));
        project.setStatus("images_queued");
        project.setOriginalInfo(json.writeValueAsString(Map.of(
            "description", prompt,
            "category", imageName,
            "material", "",
            "dimensions", outputSize,
            "generation_size", generationSize,
            "keywords", List.of(mode),
            "template_id", blankToDefault(request.templateId(), "")
        )));
        project.setGeneratedCopy("{}");
        Map<String, Object> confirmedFacts = new LinkedHashMap<>();
        confirmedFacts.put("mode", mode);
        confirmedFacts.put("prompt", prompt);
        confirmedFacts.put("module", blankToDefault(request.module(), imageName));
        confirmedFacts.put("size", outputSize);
        confirmedFacts.put("generation_size", generationSize);
        int quotedReferenceCount = quotedReferences == null ? 0 : quotedReferences.size();
        confirmedFacts.putAll(imageIntentFacts(
            request,
            prompt,
            request.module(),
            quotedReferenceCount > 0 || hasReferenceInput(references, reference, request.referenceUrl()),
            quotedReferenceCount + referenceCount(references, request.referenceUrl()) + (reference != null && !reference.isEmpty() ? 1 : 0)
        ));
        project.setConfirmedFacts(json.writeValueAsString(confirmedFacts));
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        project.setDeleted(0);
        projects.insert(project);

        storeOwnedCreativeReferences(user.id(), project.getId(), quotedReferences);
        storeReferenceImages(user.id(), project.getId(), references, reference);
        String referenceUrl = normalizeImageUrl(request.referenceUrl());
        if (!referenceUrl.isBlank()) {
            storeLinkedProductImage(user.id(), project.getId(), "reference_link", referenceUrl);
        }

        AiImageClientRuntime runtime = imageRuntime();
        String promptSnapshot;
        try (AiTextCallAuditService.Scope ignored = textAudit.open(user.id(), "single_image_prompt", "project", project.getId())) {
            promptSnapshot = withAiRuntime(aiText.buildSingleImagePrompt(project, prompt, imageType, imageName, generationSize, outputSize, blankToDefault(request.module(), imageName)), runtime);
        }

        UserEntity account = users.lockById(user.id());
        if (account == null || account.getDeleted() != 0) {
            throw new IllegalArgumentException("User not found");
        }
        if (account.getCredits() < cost) {
            throw new IllegalArgumentException("积分不足，本次生成需要 " + cost + " 积分");
        }

        int balanceAfter = account.getCredits() - cost;
        account.setCredits(balanceAfter);
        account.setUpdatedAt(now);
        users.updateById(account);

        CreditLedgerEntity ledger = new CreditLedgerEntity();
        ledger.setId("cl_" + id());
        ledger.setUserId(user.id());
        ledger.setChangeAmount(-cost);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setBizType("aplus".equals(mode) ? "aplus_generate" : "creative_generate");
        ledger.setBizId(project.getId());
        ledger.setRemark(imageName + "生成扣费");
        ledger.setCreatedAt(now);
        ledger.setDeleted(0);
        ledgers.insert(ledger);

        ImageTaskEntity task = new ImageTaskEntity();
        task.setId("img_" + id());
        task.setProjectId(project.getId());
        task.setUserId(user.id());
        task.setTaskSetId(project.getId());
        task.setImageType(imageType);
        task.setStatus("queued");
        task.setResultUrl("");
        task.setCreditsCost(cost);
        task.setPromptSnapshot(promptSnapshot);
        task.setProvider(runtime.provider());
        task.setModel(modelForGenerationSize(runtime.provider(), runtime.model(), generationSize));
        task.setSafeError("");
        task.setProviderAttempts("[]");
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        task.setHiddenByUser(0);
        task.setDeleted(0);
        tasks.insert(task);
        ProjectDtos.ProjectDetail created = detail(project);
        dedupeSuccess = true;
        dedupeProjectId = project.getId();
        return created;
        } finally {
            finishSingleImageSubmit(dedupeKey, dedupeLocked, dedupeSuccess, dedupeProjectId);
        }
    }

    private String singleImageDedupeKey(String userId, String mode, ProjectDtos.SingleImageRequest request, String outputSize, String generationSize, int cost, List<MultipartFile> references, MultipartFile reference, List<OwnedCreativeReference> quotedReferences) {
        return String.join("\n",
            "single-image",
            blankToDefault(userId, ""),
            blankToDefault(mode, ""),
            blankToDefault(request.productName(), "").trim(),
            blankToDefault(request.brand(), "").trim(),
            blankToDefault(request.prompt(), "").trim(),
            outputSize,
            generationSize,
            String.valueOf(cost),
            blankToDefault(request.language(), "").trim(),
            blankToDefault(request.module(), "").trim(),
            normalizeImageUrl(request.referenceUrl()),
            blankToDefault(request.intent(), "").trim(),
            blankToDefault(request.textMode(), "").trim(),
            blankToDefault(request.referencePolicy(), "").trim(),
            blankToDefault(request.templateId(), "").trim(),
            fileSignatures(references, reference),
            quotedReferenceSignatures(quotedReferences)
        );
    }

    private ProjectDtos.ProjectDetail waitForDuplicateSingleImageProject(CurrentUser user, String dedupeKey) throws Exception {
        long deadline = System.currentTimeMillis() + IMAGE_SUBMIT_DEDUPE_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ProjectDtos.ProjectDetail duplicate = recentDuplicateSingleImageProject(user, dedupeKey);
            if (duplicate != null) {
                return duplicate;
            }
            if (!singleImageSubmitLocks.contains(dedupeKey)) {
                break;
            }
            Thread.sleep(300);
        }
        throw new IllegalArgumentException("任务已提交，请稍后刷新历史记录查看结果");
    }

    private ProjectDtos.ProjectDetail recentDuplicateSingleImageProject(CurrentUser user, String dedupeKey) throws Exception {
        DedupedProjectSubmission submission = singleImageSubmitResults.get(dedupeKey);
        if (submission == null) {
            return null;
        }
        if (submission.expiresAtMillis() < System.currentTimeMillis()) {
            singleImageSubmitResults.remove(dedupeKey, submission);
            return null;
        }
        ProductProjectEntity project = projects.selectById(submission.projectId());
        if (project == null || project.getDeleted() != 0 || !user.id().equals(project.getUserId())) {
            return null;
        }
        return detail(project);
    }

    private void finishSingleImageSubmit(String dedupeKey, boolean dedupeLocked, boolean success, String projectId) {
        if (!dedupeLocked) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_COMMITTED && success && projectId != null && !projectId.isBlank()) {
                        rememberSingleImageSubmit(dedupeKey, projectId);
                    }
                    singleImageSubmitLocks.remove(dedupeKey);
                }
            });
            return;
        }
        if (success && projectId != null && !projectId.isBlank()) {
            rememberSingleImageSubmit(dedupeKey, projectId);
        }
        singleImageSubmitLocks.remove(dedupeKey);
    }

    private void rememberSingleImageSubmit(String dedupeKey, String projectId) {
        // ponytail: in-memory guard covers accidental double-clicks on the current single-node deploy; use a DB idempotency key if this becomes multi-node.
        long now = System.currentTimeMillis();
        singleImageSubmitResults.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
        singleImageSubmitResults.put(dedupeKey, new DedupedProjectSubmission(projectId, now + IMAGE_SUBMIT_DEDUPE_SECONDS * 1000L));
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

    private String quotedReferenceSignatures(List<OwnedCreativeReference> references) {
        if (references == null || references.isEmpty()) {
            return "";
        }
        return references.stream()
            .map(OwnedCreativeReference::url)
            .filter(url -> url != null && !url.isBlank())
            .toList()
            .toString();
    }

    private String fileSignature(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "";
        }
        return blankToDefault(file.getOriginalFilename(), "") + ":" + file.getSize() + ":" + blankToDefault(file.getContentType(), "");
    }

    List<OwnedCreativeReference> resolveOwnedCreativeReferences(String userId, String chatId, List<String> requestedUrls) {
        LinkedHashSet<String> uniqueUrls = new LinkedHashSet<>();
        if (requestedUrls != null) {
            for (String requestedUrl : requestedUrls) {
                String url = blankToDefault(requestedUrl, "").trim();
                if (url.isBlank()) {
                    continue;
                }
                if (url.length() > 2048) {
                    throw new IllegalArgumentException("引用图片地址过长");
                }
                uniqueUrls.add(url);
            }
        }
        if (uniqueUrls.isEmpty()) {
            return List.of();
        }
        if (uniqueUrls.size() > CREATIVE_CHAT_REFERENCE_LIMIT) {
            throw new IllegalArgumentException("最多支持 " + CREATIVE_CHAT_REFERENCE_LIMIT + " 张参考图");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("引用图片不存在或无权访问");
        }

        List<String> projectIds = creativeChatVersions.selectList(new LambdaQueryWrapper<CreativeChatVersionEntity>()
                .eq(CreativeChatVersionEntity::getChatId, chatId)
                .eq(CreativeChatVersionEntity::getUserId, userId)
                .eq(CreativeChatVersionEntity::getDeleted, 0))
            .stream()
            .map(CreativeChatVersionEntity::getProjectId)
            .filter(projectId -> projectId != null && !projectId.isBlank())
            .distinct()
            .toList();
        if (projectIds.isEmpty()) {
            throw new IllegalArgumentException("引用图片不存在或无权访问");
        }

        List<String> orderedUrls = new ArrayList<>(uniqueUrls);
        LinkedHashSet<String> lookupUrls = new LinkedHashSet<>();
        for (String url : orderedUrls) {
            lookupUrls.add(url);
            lookupUrls.add(canonicalOwnedReferenceUrl(url));
        }
        Map<String, OwnedCreativeReference> ownedByUrl = new LinkedHashMap<>();
        productImages.selectList(new LambdaQueryWrapper<ProductImageEntity>()
                .eq(ProductImageEntity::getUserId, userId)
                .eq(ProductImageEntity::getDeleted, 0)
                .in(ProductImageEntity::getProjectId, projectIds)
                .in(ProductImageEntity::getUrl, lookupUrls))
            .forEach(image -> {
                OwnedCreativeReference reference = new OwnedCreativeReference(
                    image.getUrl(),
                    image.getStoragePath(),
                    image.getOriginalFilename(),
                    image.getContentType(),
                    image.getImageFormat(),
                    image.getFileSize(),
                    image.getWidth(),
                    image.getHeight()
                );
                ownedByUrl.putIfAbsent(image.getUrl(), reference);
                ownedByUrl.putIfAbsent(canonicalOwnedReferenceUrl(image.getUrl()), reference);
            });
        tasks.selectList(new LambdaQueryWrapper<ImageTaskEntity>()
                .eq(ImageTaskEntity::getUserId, userId)
                .eq(ImageTaskEntity::getDeleted, 0)
                .eq(ImageTaskEntity::getStatus, "success")
                .in(ImageTaskEntity::getProjectId, projectIds)
                .in(ImageTaskEntity::getResultUrl, lookupUrls))
            .forEach(task -> {
                OwnedCreativeReference reference = new OwnedCreativeReference(
                    task.getResultUrl(),
                    task.getResultUrl(),
                    "generated-image",
                    "image/url",
                    "",
                    0L,
                    null,
                    null
                );
                ownedByUrl.putIfAbsent(task.getResultUrl(), reference);
                ownedByUrl.putIfAbsent(canonicalOwnedReferenceUrl(task.getResultUrl()), reference);
            });

        List<OwnedCreativeReference> resolved = new ArrayList<>();
        for (String url : orderedUrls) {
            OwnedCreativeReference owned = ownedByUrl.get(url);
            if (owned == null) {
                owned = ownedByUrl.get(canonicalOwnedReferenceUrl(url));
            }
            if (owned == null) {
                throw new IllegalArgumentException("引用图片不存在或无权访问");
            }
            resolved.add(owned);
        }
        return resolved;
    }

    private String canonicalOwnedReferenceUrl(String value) {
        String trimmed = blankToDefault(value, "").trim();
        if (trimmed.startsWith("/api/public/files/")) {
            return trimmed;
        }
        try {
            URI uri = URI.create(trimmed);
            String path = uri.getRawPath();
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && path != null && path.startsWith("/api/public/files/")) {
                return path;
            }
        } catch (Exception ignored) {
            // The regular ownership lookup below will reject malformed or unknown values.
        }
        return trimmed;
    }

    void storeOwnedCreativeReferences(String userId, String projectId, List<OwnedCreativeReference> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (int index = 0; index < references.size(); index++) {
            OwnedCreativeReference source = references.get(index);
            ProductImageEntity entity = new ProductImageEntity();
            entity.setId("pimg_" + id());
            entity.setProjectId(projectId);
            entity.setUserId(userId);
            entity.setImageType("reference_history");
            entity.setOriginalFilename(blankToDefault(source.originalFilename(), "history-reference"));
            entity.setUrl(source.url());
            entity.setStoragePath(blankToDefault(source.storagePath(), source.url()));
            entity.setContentType(blankToDefault(source.contentType(), "image/url"));
            entity.setImageFormat(blankToDefault(source.imageFormat(), ""));
            entity.setFileSize(source.fileSize() == null ? 0L : source.fileSize());
            entity.setWidth(source.width());
            entity.setHeight(source.height());
            entity.setCreatedAt(now.plusNanos(index));
            entity.setUpdatedAt(now.plusNanos(index));
            entity.setDeleted(0);
            productImages.insert(entity);
        }
    }

    public Map<String, Object> canvasText(CurrentUser user, ProjectDtos.CanvasTextRequest request) {
        if (request == null || request.requestBody() == null || request.requestBody().isEmpty()) {
            throw new IllegalArgumentException("画布文案请求不能为空");
        }
        String feature = switch (blankToDefault(request.feature(), "canvas_text")) {
            case "canvas_agent" -> "canvas_agent";
            case "canvas_reverse_prompt" -> "canvas_reverse_prompt";
            case "canvas_prompt_optimize" -> "canvas_prompt_optimize";
            default -> "canvas_text";
        };
        String riskText = canvasTextForRisk(request.requestBody());
        checkRisk(user, "", "", feature, riskText, "user_input");
        String businessTaskId = "canvas_text_" + UUID.randomUUID().toString().replace("-", "");
        try (AiTextCallAuditService.Scope ignored = textAudit.open(user.id(), feature, "canvas", businessTaskId)) {
            return Map.of("response", aiText.generateCanvasText(request.requestBody(), businessTaskId));
        }
    }

    private String canvasTextForRisk(Map<String, Object> requestBody) {
        StringBuilder text = new StringBuilder();
        collectCanvasText(requestBody == null ? null : requestBody.get("messages"), text, 0);
        return text.length() > 20_000 ? text.substring(0, 20_000) : text.toString();
    }

    private void collectCanvasText(Object value, StringBuilder out, int depth) {
        if (value == null || out.length() >= 20_000 || depth > 8) return;
        if (value instanceof String text) {
            if (!text.regionMatches(true, 0, "data:image/", 0, 11)) {
                out.append(text, 0, Math.min(text.length(), 20_000 - out.length())).append('\n');
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (!"image_url".equals(String.valueOf(key)) && !"image".equals(String.valueOf(key))) {
                    collectCanvasText(item, out, depth + 1);
                }
            });
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(item -> collectCanvasText(item, out, depth + 1));
        }
    }

    private void storeReferenceImages(String userId, String projectId, List<MultipartFile> references, MultipartFile reference) throws Exception {
        if (references != null) {
            for (MultipartFile item : references) {
                if (item == null || item.isEmpty()) {
                    continue;
                }
                storeProductImage(userId, projectId, "reference", item);
            }
        }
        if (reference != null && !reference.isEmpty()) {
            storeProductImage(userId, projectId, "reference", reference);
        }
    }

    private Map<String, Object> imageIntentFacts(ProjectDtos.SingleImageRequest request, String prompt, String module, boolean hasReference, int referenceCount) {
        String intent = normalizeIntent(request == null ? "" : request.intent());
        String textMode = normalizeTextMode(request == null ? "" : request.textMode());
        String referencePolicy = normalizeReferencePolicy(request == null ? "" : request.referencePolicy());
        String source = (blankToDefault(prompt, "") + " " + blankToDefault(module, "")).toLowerCase();
        boolean ignoreReferences = CreativePromptPolicy.ignoresAllReferences(source);
        if (ignoreReferences) {
            intent = "free_create";
        } else if (hasReference && CreativePromptPolicy.requestsReferenceReplacement(source)) {
            intent = "reference_replace";
        } else if (intent.isBlank()) {
            intent = inferIntent(source, hasReference);
        }
        if (textMode.isBlank()) {
            textMode = inferTextMode(source, "poster_layout".equals(intent));
        }
        if (ignoreReferences) {
            referencePolicy = "none";
        } else if (referencePolicy.isBlank()) {
            referencePolicy = source.contains("风格参考") || source.contains("style reference") ? "style_reference" : (hasReference ? "preserve" : "none");
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("intent", intent);
        facts.put("text_mode", textMode);
        facts.put("reference_policy", referencePolicy);
        facts.put("template_id", blankToDefault(request == null ? "" : request.templateId(), ""));
        facts.put("reference_count", Math.max(0, referenceCount));
        return facts;
    }

    private Map<String, Object> imageIntentFacts(ProjectDtos.CreativeChatGenerateRequest request, String prompt, String module, boolean hasReference, int referenceCount) {
        return imageIntentFacts(new ProjectDtos.SingleImageRequest("", "", prompt, "", "", module, request == null ? "" : request.referenceUrl(), request == null ? "" : request.intent(), request == null ? "" : request.textMode(), request == null ? "" : request.referencePolicy(), request == null ? "" : request.templateId()), prompt, module, hasReference, referenceCount);
    }

    private String inferIntent(String source, boolean hasReference) {
        if (hasReference && CreativePromptPolicy.requestsReferenceReplacement(source)) {
            return "reference_replace";
        }
        if (isDomesticCommerceRequest(source) || containsAny(source, "poster", "ecommerce")) {
            return "poster_layout";
        }
        if (hasReference && containsAny(source, "换背景", "背景", "环境", "场景", "lighting", "background")) {
            return "background_edit";
        }
        if (hasReference && containsAny(source, "保持", "保留", "不要变", "原图", "参考图", "修图", "局部", "preserve", "keep")) {
            return "reference_preserve";
        }
        if (containsAny(source, "海报", "卖点", "模块")) {
            return "poster_layout";
        }
        return hasReference ? "reference_preserve" : "free_create";
    }

    private String inferTextMode(String source, boolean poster) {
        if (CreativePromptPolicy.visibleTextIntent(source) == CreativePromptPolicy.VisibleTextIntent.ADD_EXACT_TEXT) {
            return "ai_short_text";
        }
        return "no_text";
    }

    private String normalizeIntent(String value) {
        String text = blankToDefault(value, "").trim();
        return List.of("reference_preserve", "reference_replace", "background_edit", "poster_layout", "free_create").contains(text) ? text : "";
    }

    private String normalizeTextMode(String value) {
        String text = blankToDefault(value, "").trim();
        if ("system_overlay".equals(text)) {
            return "ai_short_text";
        }
        return List.of("ai_short_text", "no_text").contains(text) ? text : "";
    }

    private String normalizeReferencePolicy(String value) {
        String text = blankToDefault(value, "").trim();
        return List.of("preserve", "style_reference", "none").contains(text) ? text : "";
    }

    private boolean containsAny(String value, String... needles) {
        String text = blankToDefault(value, "");
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase())) return true;
        }
        return false;
    }

    private boolean hasReferenceInput(List<MultipartFile> references, MultipartFile reference, String referenceUrl) {
        return referenceCount(references, referenceUrl) > 0 || (reference != null && !reference.isEmpty());
    }

    private int referenceCount(List<MultipartFile> references, String referenceUrl) {
        int count = normalizeImageUrl(referenceUrl).isBlank() ? 0 : 1;
        if (references != null) {
            for (MultipartFile item : references) {
                if (item != null && !item.isEmpty()) {
                    count += 1;
                }
            }
        }
        return count;
    }

    private List<MultipartFile> normalizeReferenceFiles(List<MultipartFile> references, MultipartFile reference, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<MultipartFile> files = new ArrayList<>();
        if (references != null) {
            for (MultipartFile item : references) {
                if (item == null || item.isEmpty()) {
                    continue;
                }
                files.add(item);
                if (files.size() >= limit) {
                    return files;
                }
            }
        }
        if (reference != null && !reference.isEmpty()) {
            boolean alreadyIncluded = files.stream().anyMatch(item -> sameUpload(item, reference));
            if (!alreadyIncluded && files.size() < limit) {
                files.add(reference);
            }
        }
        return files;
    }

    private boolean sameUpload(MultipartFile left, MultipartFile right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.getSize() == right.getSize()
            && blankToDefault(left.getOriginalFilename(), "").equals(blankToDefault(right.getOriginalFilename(), ""));
    }

    @Transactional
    public ProjectDtos.GenerateCopyResponse generateCopy(CurrentUser user, String projectId) throws Exception {
        ProductProjectEntity project = own(user, projectId);
        Map<String, Object> originalInfo = readMap(project.getOriginalInfo());
        checkRisk(user, projectId, "", "listing_copy", json.writeValueAsString(originalInfo), "user_input");
        Map<String, Object> copy;
        try (AiTextCallAuditService.Scope ignored = textAudit.open(user.id(), "listing_copy", "project", projectId)) {
            copy = aiText.generateListingCopy(project, originalInfo, productImageMaps(user, projectId), imageBriefSeeds());
        }
        project.setGeneratedCopy(json.writeValueAsString(copy));
        project.setStatus("copy_generated");
        project.setUpdatedAt(OffsetDateTime.now());
        projects.updateById(project);
        return new ProjectDtos.GenerateCopyResponse(project.getId(), project.getStatus(), copy);
    }

    @Transactional
    public ProjectDtos.ProjectSummary confirmFacts(CurrentUser user, String projectId, ProjectDtos.ConfirmFactsRequest request) throws Exception {
        ProductProjectEntity project = own(user, projectId);
        Map<String, Object> facts = request.confirmedFacts() == null ? Map.of() : request.confirmedFacts();
        project.setConfirmedFacts(json.writeValueAsString(facts));
        project.setStatus("facts_confirmed");
        project.setUpdatedAt(OffsetDateTime.now());
        projects.updateById(project);
        return summary(project);
    }

    @Transactional
    public ProjectDtos.ProductImageView uploadProductImage(CurrentUser user, String projectId, String imageType, MultipartFile file) throws Exception {
        own(user, projectId);
        return storeProductImage(user.id(), projectId, imageType, file);
    }

    @Transactional
    public ProjectDtos.ProductImageView linkProductImage(CurrentUser user, String projectId, ProjectDtos.LinkProductImageRequest request) {
        own(user, projectId);
        String imageType = blankToDefault(request.imageType(), "reference_link");
        return storeLinkedProductImage(user.id(), projectId, imageType, request.url());
    }

    private ProjectDtos.ProductImageView storeProductImage(String userId, String projectId, String imageType, MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片");
        }
        String id = "pimg_" + id();
        LocalStorageService.StoredFile stored = storage.saveProjectImage(userId, projectId, id, file);
        ProductImageEntity entity = new ProductImageEntity();
        entity.setId(id);
        entity.setProjectId(projectId);
        entity.setUserId(userId);
        entity.setImageType(normalizeImageType(blankToDefault(imageType, "main")));
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
        return productImageView(entity);
    }

    private String storeRetryReferenceFile(String userId, String projectId, String taskId, MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            return "";
        }
        LocalStorageService.StoredFile stored = storage.saveProjectImage(userId, projectId, "retry_" + taskId + "_" + id(), file);
        return stored.url();
    }

    private ProjectDtos.ProductImageView storeLinkedProductImage(String userId, String projectId, String imageType, String url) {
        String normalizedUrl = normalizeImageUrl(url);
        if (normalizedUrl.isBlank()) {
            throw new IllegalArgumentException("请输入有效图片链接");
        }
        String id = "pimg_" + id();
        ProductImageEntity entity = new ProductImageEntity();
        entity.setId(id);
        entity.setProjectId(projectId);
        entity.setUserId(userId);
        entity.setImageType(normalizeImageType(blankToDefault(imageType, "reference_link")));
        entity.setOriginalFilename("linked-image");
        entity.setUrl(normalizedUrl);
        entity.setStoragePath(normalizedUrl);
        entity.setContentType("image/url");
        entity.setFileSize(0L);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        entity.setDeleted(0);
        productImages.insert(entity);
        return productImageView(entity);
    }

    private void cloneProductImages(String userId, String sourceProjectId, String targetProjectId) {
        productImageEntities(userId, sourceProjectId).forEach(source -> {
            ProductImageEntity entity = new ProductImageEntity();
            entity.setId("pimg_" + id());
            entity.setProjectId(targetProjectId);
            entity.setUserId(userId);
            entity.setImageType(source.getImageType());
            entity.setOriginalFilename(source.getOriginalFilename());
            entity.setUrl(source.getUrl());
            entity.setStoragePath(source.getStoragePath());
            entity.setContentType(source.getContentType());
            entity.setImageFormat(source.getImageFormat());
            entity.setFileSize(source.getFileSize());
            entity.setWidth(source.getWidth());
            entity.setHeight(source.getHeight());
            entity.setCreatedAt(OffsetDateTime.now());
            entity.setUpdatedAt(OffsetDateTime.now());
            entity.setDeleted(0);
            productImages.insert(entity);
        });
    }

    public List<ProjectDtos.ProductImageView> listProductImages(CurrentUser user, String projectId) {
        own(user, projectId);
        return productImageEntities(user, projectId).stream().map(this::productImageView).toList();
    }

    @Transactional
    public ProjectDtos.ListingPackageResponse enqueueListingPackage(
        CurrentUser user,
        String projectId,
        ProjectDtos.ListingPackageRequest request
    ) throws Exception {
        ProductProjectEntity project = projects.lockById(projectId);
        if (project == null || project.getDeleted() != 0 || !project.getUserId().equals(user.id())) {
            throw new IllegalArgumentException("Project not found");
        }

        Map<String, Object> originalInfo = new LinkedHashMap<>(readMap(project.getOriginalInfo()));
        boolean includeAplus = request != null && Boolean.TRUE.equals(request.includeAplus());
        String module = blankToDefault(
            request == null ? null : request.aplusModule(),
            blankToDefault(String.valueOf(originalInfo.getOrDefault("aplus_module", "")), "核心卖点模块")
        );
        String size = normalizeAplusOutputSize(
            request == null ? null : request.aplusSize(),
            normalizeAplusOutputSize(String.valueOf(originalInfo.getOrDefault("aplus_size", "")), AiImageSizes.DEFAULT_APLUS_OUTPUT_SIZE)
        );
        String prompt = blankToDefault(
            request == null ? null : request.aplusPrompt(),
            blankToDefault(String.valueOf(originalInfo.getOrDefault("aplus_prompt", "")), "")
        );
        String language = blankToDefault(request == null ? null : request.language(), project.getLanguage());

        originalInfo.put("include_aplus", includeAplus);
        originalInfo.put("aplus_module", module);
        originalInfo.put("aplus_size", size);
        originalInfo.put("aplus_prompt", prompt);
        project.setOriginalInfo(json.writeValueAsString(originalInfo));
        project.setUpdatedAt(OffsetDateTime.now());
        projects.updateById(project);

        List<ProjectDtos.ImageTaskView> imageTasks = enqueueSevenImages(user, projectId);
        ProjectDtos.ProjectDetail aplusProject = includeAplus
            ? createLinkedAplusProject(user, projectId, new ProjectDtos.LinkedAplusRequest(prompt, size, language, module))
            : null;
        return new ProjectDtos.ListingPackageResponse(
            imageTasks,
            aplusProject,
            includeAplus,
            includeAplus ? "queued" : "not_requested"
        );
    }

    @Transactional
    public List<ProjectDtos.ImageTaskView> enqueueSevenImages(CurrentUser user, String projectId) throws Exception {
        ProductProjectEntity project = projects.lockById(projectId);
        if (project == null || project.getDeleted() != 0 || !project.getUserId().equals(user.id())) {
            throw new IllegalArgumentException("Project not found");
        }
        if (!"copy_generated".equals(project.getStatus()) && !"facts_confirmed".equals(project.getStatus()) && !"images_queued".equals(project.getStatus()) && !"images_completed".equals(project.getStatus())) {
            throw new IllegalArgumentException("请先生成 Listing 文案，再创建图片任务");
        }
        boolean hasMainImage = productImageEntities(user, projectId).stream().anyMatch(image -> normalizeImageType(image.getImageType()).equals("main"));
        if (!hasMainImage) {
            throw new IllegalArgumentException("请先上传主商品图");
        }
        ensureImageEnabled();
        List<ImageTaskEntity> existingTasks = taskEntities(user, projectId);
        if (!existingTasks.isEmpty()) {
            return existingTasks.stream().map(this::taskView).toList();
        }

        Map<String, Object> copy = readMap(project.getGeneratedCopy());
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("请先生成 Listing 文案，再创建图片任务");
        }
        Map<String, Object> originalInfo = readMap(project.getOriginalInfo());
        Map<String, Object> facts = aiText.rebuildConfirmedFacts(project, originalInfo, productImageMaps(user, projectId));
        project.setConfirmedFacts(json.writeValueAsString(facts));
        project.setUpdatedAt(OffsetDateTime.now());
        projects.updateById(project);
        List<AiTextService.ImageBriefSeed> lockedSeeds = lockedImageBriefSeeds(copy);
        int totalCost = lockedSeeds.stream().mapToInt(AiTextService.ImageBriefSeed::cost).sum();
        checkRisk(user, projectId, "", "listing_images", json.writeValueAsString(originalInfo), "user_input");
        UserEntity accountPreview = users.selectById(user.id());
        if (accountPreview == null || accountPreview.getDeleted() != 0) {
            throw new IllegalArgumentException("User not found");
        }
        if (accountPreview.getCredits() < totalCost) {
            throw new IllegalArgumentException("积分不足，预计需要 " + totalCost + " 积分");
        }
        List<ImageTaskEntity> prepared = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (AiTextService.ImageBriefSeed seed : lockedSeeds) {
            ImageTaskEntity task = new ImageTaskEntity();
            task.setId("img_" + id());
            task.setProjectId(project.getId());
            task.setUserId(user.id());
            task.setTaskSetId(project.getId());
            task.setImageType(seed.type());
            task.setStatus("queued");
            task.setResultUrl("");
            task.setCreditsCost(seed.cost());
            AiImageClientRuntime runtime = imageRuntime();
            String promptSnapshot;
            try (AiTextCallAuditService.Scope ignored = textAudit.open(user.id(), "listing_image_prompt", "project", projectId)) {
                promptSnapshot = withAiRuntime(aiText.buildImagePrompt(project, copy, seed), runtime);
            }
            task.setPromptSnapshot(promptSnapshot);
            task.setProvider(runtime.provider());
            task.setModel(modelForGenerationSize(runtime.provider(), runtime.model(), seed.generationSize()));
            task.setSafeError("");
            task.setProviderAttempts("[]");
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            task.setHiddenByUser(0);
            task.setDeleted(0);
            prepared.add(task);
        }

        UserEntity account = users.lockById(user.id());
        if (account == null || account.getDeleted() != 0) {
            throw new IllegalArgumentException("User not found");
        }
        if (account.getCredits() < totalCost) {
            throw new IllegalArgumentException("积分不足，预计需要 " + totalCost + " 积分");
        }
        int balanceAfter = account.getCredits() - totalCost;
        account.setCredits(balanceAfter);
        account.setUpdatedAt(OffsetDateTime.now());
        users.updateById(account);

        CreditLedgerEntity ledger = new CreditLedgerEntity();
        ledger.setId("cl_" + id());
        ledger.setUserId(user.id());
        ledger.setChangeAmount(-totalCost);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setBizType("image_generate");
        ledger.setBizId(projectId);
        ledger.setRemark("创建 Listing 七图任务");
        ledger.setCreatedAt(OffsetDateTime.now());
        ledger.setDeleted(0);
        ledgers.insert(ledger);

        List<ImageTaskEntity> created = new ArrayList<>();
        for (ImageTaskEntity task : prepared) {
            tasks.insert(task);
            created.add(task);
        }

        project.setStatus("images_queued");
        project.setUpdatedAt(now);
        projects.updateById(project);
        return created.stream().map(this::taskView).toList();
    }

    public List<ProjectDtos.ImageTaskView> listTasks(CurrentUser user, String projectId) {
        return taskEntities(user, projectId).stream().map(this::taskView).toList();
    }

    @Transactional
    public ProjectDtos.ImageTaskView retryFailedTask(CurrentUser user, String projectId, String taskId) throws Exception {
        return retryFailedTask(user, projectId, taskId, null, null);
    }

    @Transactional
    public ProjectDtos.ImageTaskView retryFailedTask(CurrentUser user, String projectId, String taskId, ProjectDtos.RetryImageRequest request) throws Exception {
        return retryFailedTask(user, projectId, taskId, request, null);
    }

    @Transactional
    public ProjectDtos.ImageTaskView retryFailedTask(CurrentUser user, String projectId, String taskId, ProjectDtos.RetryImageRequest request, MultipartFile reference) throws Exception {
        own(user, projectId);
        ImageTaskEntity task = tasks.lockById(taskId);
        if (task == null || task.getDeleted() != 0 || isHiddenByUser(task) || !task.getUserId().equals(user.id()) || !task.getProjectId().equals(projectId)) {
            throw new IllegalArgumentException("Task not found");
        }
        if ("queued".equals(task.getStatus()) || "running".equals(task.getStatus())) {
            throw new IllegalArgumentException("图片正在生成中，请完成后再重新生成");
        }
        ensureImageEnabled();
        checkRisk(user, projectId, taskId, "image_retry", request == null ? "" : blankToDefault(request.referenceText(), ""), "user_input");
        int cost = task.getCreditsCost() == null ? 0 : task.getCreditsCost();
        UserEntity account = users.lockById(user.id());
        if (account == null || account.getDeleted() != 0) {
            throw new IllegalArgumentException("User not found");
        }
        if (cost > 0 && account.getCredits() < cost) {
            throw new IllegalArgumentException("积分不足，本张图片重新生成需要 " + cost + " 积分");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (cost > 0) {
            int balanceAfter = account.getCredits() - cost;
            account.setCredits(balanceAfter);
            account.setUpdatedAt(now);
            users.updateById(account);

            CreditLedgerEntity ledger = new CreditLedgerEntity();
            ledger.setId("cl_" + id());
            ledger.setUserId(user.id());
            ledger.setChangeAmount(-cost);
            ledger.setBalanceAfter(balanceAfter);
            ledger.setBizType("image_retry");
            ledger.setBizId(task.getId() + ":" + now.toInstant().toEpochMilli());
            ledger.setRemark("重新生成图片：" + task.getImageType());
            ledger.setCreatedAt(now);
            ledger.setDeleted(0);
            ledgers.insert(ledger);
        }
        String referenceUrl = request == null ? "" : normalizeRetryReferenceUrl(request.referenceUrl());
        String referenceText = request == null ? "" : blankToDefault(request.referenceText(), "").trim();
        if (reference != null && !reference.isEmpty()) {
            referenceUrl = storeRetryReferenceFile(user.id(), projectId, task.getId(), reference);
        }
        task.setPromptSnapshot(withRetryReference(task.getPromptSnapshot(), referenceUrl, referenceText));
        task.setStatus("queued");
        task.setResultUrl("");
        task.setSafeError("");
        task.setRawError("");
        task.setProviderAttempts("[]");
        task.setRefundedAt(null);
        task.setUpdatedAt(now);
        tasks.updateById(task);
        ProductProjectEntity project = projects.selectById(projectId);
        if (project != null) {
            project.setStatus("images_queued");
            project.setUpdatedAt(now);
            projects.updateById(project);
        }
        return taskView(task);
    }

    private ProjectDtos.ProjectDetail detail(ProductProjectEntity project) throws Exception {
        return new ProjectDtos.ProjectDetail(
            summary(project),
            readMap(project.getOriginalInfo()),
            readMap(project.getGeneratedCopy()),
            readMap(project.getConfirmedFacts()),
            productImageEntities(project.getUserId(), project.getId()).stream().map(this::productImageView).toList(),
            tasks.selectList(new LambdaQueryWrapper<ImageTaskEntity>()
                    .eq(ImageTaskEntity::getUserId, project.getUserId())
                    .eq(ImageTaskEntity::getProjectId, project.getId())
                    .eq(ImageTaskEntity::getDeleted, 0)
                    .eq(ImageTaskEntity::getHiddenByUser, 0)
            .orderByAsc(ImageTaskEntity::getCreatedAt))
                .stream()
                .map(this::taskView)
                .toList()
        );
    }

    private CreativeChatEntity ownCreativeChat(CurrentUser user, String chatId) {
        CreativeChatEntity chat = creativeChats.selectById(chatId);
        if (chat == null || chat.getDeleted() != 0 || !chat.getUserId().equals(user.id())) {
            throw new IllegalArgumentException("Chat not found");
        }
        return chat;
    }

    private ProjectDtos.CreativeChatView creativeChatView(CreativeChatEntity chat) {
        List<ProjectDtos.CreativeChatMessageView> messages = creativeChatMessages.selectList(new LambdaQueryWrapper<CreativeChatMessageEntity>()
                .eq(CreativeChatMessageEntity::getChatId, chat.getId())
                .eq(CreativeChatMessageEntity::getUserId, chat.getUserId())
                .eq(CreativeChatMessageEntity::getDeleted, 0)
                .orderByAsc(CreativeChatMessageEntity::getCreatedAt))
            .stream()
            .map(this::creativeChatMessageView)
            .toList();
        List<ProjectDtos.CreativeChatVersionView> versions = creativeChatVersions.selectList(new LambdaQueryWrapper<CreativeChatVersionEntity>()
                .eq(CreativeChatVersionEntity::getChatId, chat.getId())
                .eq(CreativeChatVersionEntity::getUserId, chat.getUserId())
                .eq(CreativeChatVersionEntity::getDeleted, 0)
                .orderByDesc(CreativeChatVersionEntity::getVersionNo))
            .stream()
            .map(this::creativeChatVersionView)
            .toList();
        return new ProjectDtos.CreativeChatView(creativeChatSummary(chat), messages, versions);
    }

    private ProjectDtos.CreativeChatSummary creativeChatSummary(CreativeChatEntity chat) {
        CreativeChatVersionEntity latest = creativeChatVersions.selectList(new LambdaQueryWrapper<CreativeChatVersionEntity>()
                .eq(CreativeChatVersionEntity::getChatId, chat.getId())
                .eq(CreativeChatVersionEntity::getUserId, chat.getUserId())
                .eq(CreativeChatVersionEntity::getDeleted, 0)
                .orderByDesc(CreativeChatVersionEntity::getVersionNo)
                .last("limit 1"))
            .stream()
            .findFirst()
            .orElse(null);
        Long versionCount = creativeChatVersions.selectCount(new LambdaQueryWrapper<CreativeChatVersionEntity>()
            .eq(CreativeChatVersionEntity::getChatId, chat.getId())
            .eq(CreativeChatVersionEntity::getUserId, chat.getUserId())
            .eq(CreativeChatVersionEntity::getDeleted, 0));
        return new ProjectDtos.CreativeChatSummary(
            chat.getId(),
            chat.getTitle(),
            chat.getStyle(),
            chat.getSize(),
            chat.getLanguage(),
            chat.getReferenceUrl(),
            latest == null ? "" : latest.getStatus(),
            latest == null ? "" : latest.getResultUrl(),
            versionCount == null ? 0 : versionCount.intValue(),
            chat.getCreatedAt(),
            chat.getUpdatedAt()
        );
    }

    private ProjectDtos.CreativeChatMessageView creativeChatMessageView(CreativeChatMessageEntity row) {
        return new ProjectDtos.CreativeChatMessageView(
            row.getId(),
            row.getChatId(),
            row.getRole(),
            row.getContent(),
            row.getProjectId(),
            row.getTaskId(),
            row.getResultUrl(),
            row.getStatus(),
            row.getVersionNo(),
            row.getCreatedAt()
        );
    }

    private ProjectDtos.CreativeChatVersionView creativeChatVersionView(CreativeChatVersionEntity row) {
        return new ProjectDtos.CreativeChatVersionView(
            row.getId(),
            row.getChatId(),
            row.getVersionNo(),
            row.getUserMessageId(),
            row.getAssistantMessageId(),
            row.getProjectId(),
            row.getTaskId(),
            row.getUserRequest(),
            row.getFinalPrompt(),
            row.getStyle(),
            row.getSize(),
            row.getReferenceUrl(),
            referenceUrlsForProject(row.getProjectId(), row.getReferenceUrl()),
            row.getStatus(),
            row.getResultUrl(),
            row.getSafeError(),
            row.getCreatedAt(),
            row.getUpdatedAt()
        );
    }

    private List<String> referenceUrlsForProject(String projectId, String fallback) {
        List<String> urls = new ArrayList<>();
        if (projectId != null && !projectId.isBlank()) {
            List<ProductImageEntity> projectReferences = productImages.selectList(new LambdaQueryWrapper<ProductImageEntity>()
                .eq(ProductImageEntity::getProjectId, projectId)
                .eq(ProductImageEntity::getDeleted, 0)
                .orderByAsc(ProductImageEntity::getCreatedAt));
            for (ProductImageEntity image : projectReferences) {
                String type = blankToDefault(image.getImageType(), "");
                if (!type.startsWith("reference")) {
                    continue;
                }
                String url = blankToDefault(image.getUrl(), "");
                if (!url.isBlank() && !urls.contains(url)) {
                    urls.add(url);
                }
            }
        }
        String fallbackUrl = blankToDefault(fallback, "");
        if (!fallbackUrl.isBlank() && !urls.contains(fallbackUrl)) {
            urls.add(fallbackUrl);
        }
        return urls;
    }

    private void hydrateCreativeChat(CreativeChatEntity chat) {
        List<CreativeChatVersionEntity> versions = creativeChatVersions.selectList(new LambdaQueryWrapper<CreativeChatVersionEntity>()
            .eq(CreativeChatVersionEntity::getChatId, chat.getId())
            .eq(CreativeChatVersionEntity::getUserId, chat.getUserId())
            .eq(CreativeChatVersionEntity::getDeleted, 0));
        OffsetDateTime now = OffsetDateTime.now();
        boolean chatChanged = false;
        for (CreativeChatVersionEntity version : versions) {
            if (version.getTaskId() == null || version.getTaskId().isBlank()) {
                continue;
            }
            ImageTaskEntity task = tasks.selectById(version.getTaskId());
            if (task == null || task.getDeleted() != 0) {
                continue;
            }
            String status = blankToDefault(task.getStatus(), "queued");
            String resultUrl = blankToDefault(task.getResultUrl(), "");
            String safeError = blankToDefault(task.getSafeError(), "");
            boolean changed = !status.equals(blankToDefault(version.getStatus(), ""))
                || !resultUrl.equals(blankToDefault(version.getResultUrl(), ""))
                || !safeError.equals(blankToDefault(version.getSafeError(), ""));
            if (!changed) {
                continue;
            }
            version.setStatus(status);
            version.setResultUrl(resultUrl);
            version.setSafeError(safeError);
            version.setUpdatedAt(now);
            creativeChatVersions.updateById(version);
            if (version.getAssistantMessageId() != null && !version.getAssistantMessageId().isBlank()) {
                CreativeChatMessageEntity message = creativeChatMessages.selectById(version.getAssistantMessageId());
                if (message != null && message.getDeleted() == 0) {
                    message.setStatus(status);
                    message.setResultUrl(resultUrl);
                    message.setUpdatedAt(now);
                    creativeChatMessages.updateById(message);
                }
            }
            chatChanged = true;
        }
        if (chatChanged) {
            chat.setUpdatedAt(now);
            creativeChats.updateById(chat);
        }
    }

    private int nextCreativeChatVersion(String chatId) {
        CreativeChatVersionEntity latest = creativeChatVersions.selectList(new LambdaQueryWrapper<CreativeChatVersionEntity>()
                .eq(CreativeChatVersionEntity::getChatId, chatId)
                .eq(CreativeChatVersionEntity::getDeleted, 0)
                .orderByDesc(CreativeChatVersionEntity::getVersionNo)
                .last("limit 1"))
            .stream()
            .findFirst()
            .orElse(null);
        return latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;
    }

    private int creativeChatVersionCount(String chatId, String userId) {
        Long count = creativeChatVersions.selectCount(new LambdaQueryWrapper<CreativeChatVersionEntity>()
            .eq(CreativeChatVersionEntity::getChatId, chatId)
            .eq(CreativeChatVersionEntity::getUserId, userId)
            .eq(CreativeChatVersionEntity::getDeleted, 0));
        return count == null ? 0 : count.intValue();
    }

    private String chatTitle(String message) {
        String cleaned = blankToDefault(message, "对话生图").replaceAll("\\s+", " ").trim();
        return cleaned.length() <= 32 ? cleaned : cleaned.substring(0, 32);
    }

    private ProductProjectEntity own(CurrentUser user, String projectId) {
        ProductProjectEntity project = projects.selectById(projectId);
        if (project == null || project.getDeleted() != 0 || !project.getUserId().equals(user.id())) {
            throw new IllegalArgumentException("Project not found");
        }
        return project;
    }

    private void ensureImageEnabled() {
        if (!aiImage.enabled()) {
            throw new IllegalArgumentException("AI 作图服务已被管理员禁用或未配置密钥，请联系管理员开启后再使用");
        }
    }

    private void checkRisk(CurrentUser user, String projectId, String taskId, String feature, String text, String source) {
        riskControl.checkBeforeProvider(user == null ? "" : user.id(), projectId, taskId, feature, text, source);
    }

    private AiImageClientRuntime imageRuntime() {
        AiRuntimeConfigService.EffectiveImageConfig config = aiRuntimeConfig.effectiveImage();
        return new AiImageClientRuntime(config.id(), config.provider(), config.model());
    }

    private String withAiRuntime(String promptSnapshot, AiImageClientRuntime runtime) throws Exception {
        Map<String, Object> prompt = new LinkedHashMap<>(readMap(promptSnapshot));
        prompt.put("aiProviderId", blankToDefault(runtime.id(), ""));
        prompt.put("aiProvider", blankToDefault(runtime.provider(), ""));
        prompt.put("aiModel", blankToDefault(runtime.model(), ""));
        return json.writeValueAsString(prompt);
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
        if (!supportsGptImage2Pro(providerText)) {
            return "gpt-image-2";
        }
        AiImageSizes.Size parsed = AiImageSizes.parse(generationSize);
        if (parsed == null) {
            return normalizedModel;
        }
        return Math.max(parsed.width(), parsed.height()) >= 1800 ? "gpt-image-2pro" : "gpt-image-2";
    }

    private boolean supportsGptImage2Pro(String providerText) {
        String text = providerText == null ? "" : providerText.toLowerCase();
        return text.contains("hfsyapi");
    }

    private boolean isGptImage2Family(String value) {
        return "gpt-image-2".equalsIgnoreCase(value) || "gpt-image-2pro".equalsIgnoreCase(value);
    }

    private record AiImageClientRuntime(String id, String provider, String model) {}

    private List<ImageTaskEntity> taskEntities(CurrentUser user, String projectId) {
        return tasks.selectList(new LambdaQueryWrapper<ImageTaskEntity>()
            .eq(ImageTaskEntity::getUserId, user.id())
            .eq(ImageTaskEntity::getProjectId, projectId)
            .eq(ImageTaskEntity::getDeleted, 0)
            .eq(ImageTaskEntity::getHiddenByUser, 0)
            .orderByAsc(ImageTaskEntity::getCreatedAt));
    }

    private boolean isHiddenByUser(ImageTaskEntity task) {
        return task != null && Integer.valueOf(1).equals(task.getHiddenByUser());
    }

    private ProjectDtos.ProjectSummary creativeChatHistorySummary(
        CreativeChatEntity chat,
        List<CreativeChatVersionEntity> versions,
        Map<String, ProductProjectEntity> projectById
    ) {
        List<CreativeChatVersionEntity> activeVersions = new ArrayList<>();
        for (CreativeChatVersionEntity version : versions) {
            if (version.getProjectId() != null && projectById.containsKey(version.getProjectId())) {
                activeVersions.add(version);
            }
        }
        if (activeVersions.isEmpty()) {
            return null;
        }
        activeVersions.sort((left, right) -> Integer.compare(versionNumber(left), versionNumber(right)));

        List<ProjectDtos.ProjectSummary> summaries = new ArrayList<>();
        for (CreativeChatVersionEntity version : activeVersions) {
            ProductProjectEntity project = projectById.get(version.getProjectId());
            if (project != null) {
                summaries.add(summary(project));
            }
        }
        if (summaries.isEmpty()) {
            return null;
        }

        ProjectDtos.ProjectSummary latest = summaries.get(summaries.size() - 1);
        int successImages = summaries.stream().mapToInt(item -> item.successImages() == null ? 0 : item.successImages()).sum();
        int failedImages = summaries.stream().mapToInt(item -> item.failedImages() == null ? 0 : item.failedImages()).sum();
        int runningImages = summaries.stream().mapToInt(item -> item.runningImages() == null ? 0 : item.runningImages()).sum();
        int totalImages = summaries.stream().mapToInt(item -> item.totalImages() == null ? 0 : item.totalImages()).sum();
        int creditsCost = summaries.stream().mapToInt(item -> item.creditsCost() == null ? 0 : item.creditsCost()).sum();

        String latestFailureReason = "";
        String latestFailedTaskId = "";
        List<String> previewUrls = new ArrayList<>();
        OffsetDateTime updatedAt = chat.getUpdatedAt();
        for (int index = summaries.size() - 1; index >= 0; index--) {
            ProjectDtos.ProjectSummary item = summaries.get(index);
            updatedAt = maxOffset(updatedAt, item.updatedAt());
            if (latestFailureReason.isBlank() && item.latestFailureReason() != null && !item.latestFailureReason().isBlank()) {
                latestFailureReason = item.latestFailureReason();
                latestFailedTaskId = blankToDefault(item.latestFailedTaskId(), "");
            }
            if (previewUrls.size() < 4) {
                for (String url : item.previewUrls() == null ? List.<String>of() : item.previewUrls()) {
                    if (url != null && !url.isBlank() && !previewUrls.contains(url)) {
                        previewUrls.add(url);
                    }
                    if (previewUrls.size() >= 4) {
                        break;
                    }
                }
            }
        }
        String coverUrl = previewUrls.isEmpty() ? "" : previewUrls.get(0);
        OffsetDateTime createdAt = chat.getCreatedAt() == null ? latest.createdAt() : chat.getCreatedAt();

        return new ProjectDtos.ProjectSummary(
            latest.id(),
            latest.brand(),
            blankToDefault(chat.getTitle(), latest.productName()),
            latest.country(),
            blankToDefault(chat.getLanguage(), latest.language()),
            aggregateStatus(latest.status(), successImages, failedImages, runningImages, totalImages),
            successImages,
            failedImages,
            runningImages,
            totalImages,
            creditsCost,
            latestFailureReason,
            latestFailedTaskId,
            coverUrl,
            previewUrls,
            createdAt,
            updatedAt,
            "creative_chat",
            chat.getId(),
            activeVersions.size()
        );
    }

    private int versionNumber(CreativeChatVersionEntity version) {
        return version.getVersionNo() == null ? 0 : version.getVersionNo();
    }

    private OffsetDateTime maxOffset(OffsetDateTime left, OffsetDateTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private int compareOffsetDesc(OffsetDateTime left, OffsetDateTime right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return right.compareTo(left);
    }

    private ProjectDtos.ProjectSummary summary(ProductProjectEntity project) {
        List<ImageTaskEntity> projectTasks = tasks.selectList(new LambdaQueryWrapper<ImageTaskEntity>()
            .eq(ImageTaskEntity::getProjectId, project.getId())
            .eq(ImageTaskEntity::getDeleted, 0)
            .eq(ImageTaskEntity::getHiddenByUser, 0)
            .orderByAsc(ImageTaskEntity::getCreatedAt));
        int successImages = (int) projectTasks.stream().filter(task -> "success".equals(task.getStatus())).count();
        int failedImages = (int) projectTasks.stream().filter(task -> "failed".equals(task.getStatus())).count();
        int runningImages = (int) projectTasks.stream().filter(task -> "queued".equals(task.getStatus()) || "running".equals(task.getStatus())).count();
        int creditsCost = projectTasks.stream().mapToInt(task -> task.getCreditsCost() == null ? 0 : task.getCreditsCost()).sum();
        ImageTaskEntity latestFailed = projectTasks.stream()
            .filter(task -> "failed".equals(task.getStatus()))
            .max((left, right) -> {
                OffsetDateTime leftTime = left.getUpdatedAt() == null ? left.getCreatedAt() : left.getUpdatedAt();
                OffsetDateTime rightTime = right.getUpdatedAt() == null ? right.getCreatedAt() : right.getUpdatedAt();
                if (leftTime == null && rightTime == null) return 0;
                if (leftTime == null) return -1;
                if (rightTime == null) return 1;
                return leftTime.compareTo(rightTime);
            })
            .orElse(null);
        List<String> generatedPreviews = projectTasks.stream()
            .filter(task -> "success".equals(task.getStatus()))
            .map(ImageTaskEntity::getResultUrl)
            .filter(url -> url != null && !url.isBlank())
            .limit(4)
            .toList();
        List<String> referencePreviews = generatedPreviews.isEmpty()
            ? productImageEntitiesByProject(project.getId()).stream()
                .map(ProductImageEntity::getUrl)
                .filter(url -> url != null && !url.isBlank())
                .limit(4)
                .toList()
            : List.of();
        List<String> previewUrls = generatedPreviews.isEmpty() ? referencePreviews : generatedPreviews;
        String coverUrl = previewUrls.isEmpty() ? "" : previewUrls.get(0);
        return new ProjectDtos.ProjectSummary(
            project.getId(),
            project.getBrand(),
            project.getProductName(),
            project.getCountry(),
            project.getLanguage(),
            aggregateStatus(project.getStatus(), projectTasks),
            successImages,
            failedImages,
            runningImages,
            projectTasks.size(),
            creditsCost,
            latestFailed == null ? "" : blankToDefault(latestFailed.getSafeError(), "生成失败，已退回本张图片积分"),
            latestFailed == null ? "" : latestFailed.getId(),
            coverUrl,
            previewUrls,
            project.getCreatedAt(),
            project.getUpdatedAt(),
            "",
            "",
            0
        );
    }

    private String aggregateStatus(String currentStatus, List<ImageTaskEntity> projectTasks) {
        if (projectTasks == null || projectTasks.isEmpty()) {
            return currentStatus;
        }
        long success = projectTasks.stream().filter(task -> "success".equals(task.getStatus())).count();
        long failed = projectTasks.stream().filter(task -> "failed".equals(task.getStatus())).count();
        long running = projectTasks.stream().filter(task -> "queued".equals(task.getStatus()) || "running".equals(task.getStatus())).count();
        if (failed > 0 && success > 0) {
            return "images_partial_failed";
        }
        if (failed > 0 && running == 0) {
            return "images_failed";
        }
        if (failed > 0) {
            return "images_partial_failed";
        }
        if (running > 0) {
            return "images_queued";
        }
        if (success == projectTasks.size()) {
            return "images_completed";
        }
        return currentStatus;
    }

    private String aggregateStatus(String currentStatus, int success, int failed, int running, int total) {
        if (total <= 0) {
            return currentStatus;
        }
        if (failed > 0 && success > 0) {
            return "images_partial_failed";
        }
        if (failed > 0 && running == 0) {
            return "images_failed";
        }
        if (failed > 0) {
            return "images_partial_failed";
        }
        if (running > 0) {
            return "images_queued";
        }
        if (success == total) {
            return "images_completed";
        }
        return currentStatus;
    }

    private ProjectDtos.ImageTaskView taskView(ImageTaskEntity task) {
        ImageSpec spec = imageSpecs().stream()
            .filter(item -> item.type().equals(task.getImageType()))
            .findFirst()
            .orElse(new ImageSpec(task.getImageType(), taskSnapshotText(task, "imageName", task.getImageType()), task.getCreditsCost() == null ? 0 : task.getCreditsCost(), task.getModel(), "1024x1024"));
        return new ProjectDtos.ImageTaskView(
            task.getId(),
            task.getImageType(),
            spec.name(),
            task.getStatus(),
            userSafePromptSnapshot(task.getPromptSnapshot()),
            task.getResultUrl(),
            task.getCreditsCost(),
            task.getProvider(),
            task.getModel(),
            task.getSafeError(),
            task.getCreatedAt()
        );
    }

    private String taskSnapshotText(ImageTaskEntity task, String key, String fallback) {
        if (task == null || task.getPromptSnapshot() == null || task.getPromptSnapshot().isBlank()) {
            return fallback;
        }
        try {
            Map<String, Object> snapshot = readMap(task.getPromptSnapshot());
            Object value = snapshot.get(key);
            return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String userSafePromptSnapshot(String promptSnapshot) {
        if (promptSnapshot == null || promptSnapshot.isBlank()) {
            return promptSnapshot;
        }
        try {
            Map<String, Object> prompt = new LinkedHashMap<>(readMap(promptSnapshot));
            prompt.remove("aiProvider");
            prompt.remove("aiProviderId");
            prompt.remove("aiModel");
            prompt.remove("provider");
            prompt.remove("providerId");
            prompt.remove("modelProvider");
            prompt.remove("upstreamProvider");
            prompt.remove("upstream");
            return json.writeValueAsString(prompt);
        } catch (Exception ignored) {
            return promptSnapshot;
        }
    }

    private List<ImageSpec> imageSpecs() {
        List<ImageSpec> configured = imageConfigs.selectList(new LambdaQueryWrapper<ImageTypeConfigEntity>()
                .eq(ImageTypeConfigEntity::getDeleted, 0)
            .eq(ImageTypeConfigEntity::getEnabled, true)
            .orderByAsc(ImageTypeConfigEntity::getSortOrder))
            .stream()
            .map(config -> new ImageSpec(
                config.getImageType(),
                config.getImageName(),
                config.getCreditsCost(),
                config.getDefaultModel(),
                normalizeListingOutputSize(config.getDefaultSize(), AiImageSizes.DEFAULT_LISTING_OUTPUT_SIZE)
            ))
            .toList();
        return configured.isEmpty() ? IMAGE_SPECS : configured;
    }

    private Map<String, Object> readMap(String value) throws Exception {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        return json.readValue(value, new TypeReference<>() {});
    }

    private List<ProductImageEntity> productImageEntities(CurrentUser user, String projectId) {
        return productImageEntities(user.id(), projectId);
    }

    private List<ProductImageEntity> productImageEntities(String userId, String projectId) {
        return productImages.selectList(new LambdaQueryWrapper<ProductImageEntity>()
            .eq(ProductImageEntity::getUserId, userId)
            .eq(ProductImageEntity::getProjectId, projectId)
            .eq(ProductImageEntity::getDeleted, 0)
            .orderByDesc(ProductImageEntity::getCreatedAt));
    }

    private List<ProductImageEntity> productImageEntitiesByProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return List.of();
        }
        return productImages.selectList(new LambdaQueryWrapper<ProductImageEntity>()
            .eq(ProductImageEntity::getProjectId, projectId)
            .eq(ProductImageEntity::getDeleted, 0)
            .orderByDesc(ProductImageEntity::getCreatedAt));
    }

    private List<Map<String, Object>> productImageMaps(CurrentUser user, String projectId) {
        return productImageEntities(user, projectId).stream()
            .map(image -> Map.<String, Object>of(
                "id", image.getId(),
                "imageType", image.getImageType(),
                "url", image.getUrl(),
                "width", image.getWidth() == null ? 0 : image.getWidth(),
                "height", image.getHeight() == null ? 0 : image.getHeight()
            ))
            .toList();
    }

    private ProjectDtos.ProductImageView productImageView(ProductImageEntity image) {
        return new ProjectDtos.ProductImageView(
            image.getId(),
            image.getImageType(),
            image.getOriginalFilename(),
            image.getUrl(),
            image.getContentType(),
            image.getImageFormat(),
            image.getFileSize(),
            image.getWidth(),
            image.getHeight(),
            image.getCreatedAt()
        );
    }

    private List<AiTextService.ImageBriefSeed> imageBriefSeeds() {
        return imageSpecs().stream()
            .map(spec -> new AiTextService.ImageBriefSeed(spec.type(), spec.name(), spec.cost(), requiredFacts(spec.type()), generationSizeForOutput(spec.size()), spec.size()))
            .toList();
    }

    private List<AiTextService.ImageBriefSeed> lockedImageBriefSeeds(Map<String, Object> copy) {
        Object rawBriefs = copy == null ? null : copy.get("image_briefs");
        if (!(rawBriefs instanceof List<?> briefs) || briefs.isEmpty()) {
            return imageBriefSeeds();
        }
        List<AiTextService.ImageBriefSeed> seeds = new ArrayList<>();
        for (Object item : briefs) {
            if (!(item instanceof Map<?, ?> brief)) {
                continue;
            }
            String type = textValue(brief.get("image_type"));
            if (type.isBlank()) {
                continue;
            }
            String outputSize = normalizeListingOutputSize(textValue(brief.get("output_size")), AiImageSizes.DEFAULT_LISTING_OUTPUT_SIZE);
            String generationSize = AiImageSizes.normalizeGenerationSize(textValue(brief.get("generation_size")), generationSizeForOutput(outputSize));
            seeds.add(new AiTextService.ImageBriefSeed(
                type,
                blankToDefault(textValue(brief.get("image_name")), type),
                ImageCreditPricing.creditsForSize(generationSize),
                blankToDefault(textValue(brief.get("required_facts")), requiredFacts(type)),
                generationSize,
                outputSize
            ));
        }
        return seeds.isEmpty() ? imageBriefSeeds() : seeds;
    }

    private String withRetryReference(String promptSnapshot, String referenceUrl, String referenceText) throws Exception {
        Map<String, Object> prompt = new LinkedHashMap<>(readMap(promptSnapshot));
        prompt.remove("retryReference");
        if ((referenceUrl == null || referenceUrl.isBlank()) && (referenceText == null || referenceText.isBlank())) {
            return json.writeValueAsString(prompt);
        }
        Map<String, Object> retryReference = new LinkedHashMap<>();
        if (referenceUrl != null && !referenceUrl.isBlank()) {
            retryReference.put("imageUrl", referenceUrl);
        }
        if (referenceText != null && !referenceText.isBlank()) {
            retryReference.put("text", referenceText);
        }
        retryReference.put("instruction", "Use this as an additional user-provided reference for regenerating this same image slot. Keep the original image type and confirmed facts unchanged.");
        prompt.put("retryReference", retryReference);
        return json.writeValueAsString(prompt);
    }

    private String textValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return "null".equalsIgnoreCase(text) ? "" : text;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String requiredFacts(String type) {
        return switch (type) {
            case "main" -> "主商品图、品牌、商品名称";
            case "scenario" -> "使用场景、目标人群";
            case "features" -> "核心卖点、材质、功能边界";
            case "dimensions" -> "准确尺寸、单位、测量位置";
            case "steps" -> "使用步骤、安装步骤、包装清单";
            case "comparison" -> "对比维度、痛点、可验证优势";
            case "package" -> "包装清单、颜色、规格、变体";
            case "aplus" -> "A+ 模块尺寸、商品卖点、品牌调性";
            case "creative" -> "自由创作提示词、画面主体、风格";
            default -> "已确认商品事实";
        };
    }

    private String providerFor(String model) {
        return model != null && model.startsWith("gpt-image") ? "hfsyapi" : "template-worker";
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String normalizeCreativeSize(String value, String fallback) {
        return AiImageSizes.normalizeCreativeSize(value, fallback);
    }

    private String normalizeAplusOutputSize(String value, String fallback) {
        return AiImageSizes.normalizeAplusOutputSize(value, fallback);
    }

    private String normalizeListingOutputSize(String value, String fallback) {
        return AiImageSizes.normalizeListingOutputSize(value, fallback);
    }

    private String generationSizeForOutput(String outputSize) {
        return AiImageSizes.generationSizeForOutput(outputSize);
    }

    private String normalizeImageType(String value) {
        if (value == null) {
            return "main";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "main";
        }
        int comma = trimmed.indexOf(',');
        return comma >= 0 ? trimmed.substring(0, comma).trim() : trimmed;
    }

    private String normalizeImageUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() > 2048) {
            throw new IllegalArgumentException("图片链接过长");
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (Exception exception) {
            throw new IllegalArgumentException("图片链接格式不正确");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("图片链接必须是 http 或 https 地址");
        }
        String lowerHost = host.toLowerCase();
        if ("localhost".equals(lowerHost) || lowerHost.startsWith("127.") || lowerHost.startsWith("10.") || lowerHost.startsWith("192.168.") || lowerHost.startsWith("169.254.")) {
            throw new IllegalArgumentException("不支持内网图片链接");
        }
        return trimmed;
    }

    private String normalizeRetryReferenceUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("/api/public/files/")) {
            return trimmed;
        }
        return normalizeImageUrl(trimmed);
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record ImageSpec(String type, String name, int cost, String model, String size) {}

    private record DedupedProjectSubmission(String projectId, long expiresAtMillis) {}

    record OwnedCreativeReference(
        String url,
        String storagePath,
        String originalFilename,
        String contentType,
        String imageFormat,
        Long fileSize,
        Integer width,
        Integer height
    ) {}

    private static class CreativeChatPersistenceException extends RuntimeException {
        CreativeChatPersistenceException(Throwable cause) {
            super(cause);
        }
    }
}
