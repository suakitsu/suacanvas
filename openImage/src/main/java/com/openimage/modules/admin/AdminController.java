package com.openimage.modules.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.openimage.ai.AiImageCostCalculator;
import com.openimage.ai.AiImageClient;
import com.openimage.ai.AiImageSizes;
import com.openimage.ai.AiRuntimeConfigService;
import com.openimage.common.ApiResponse;
import com.openimage.common.BusinessTime;
import com.openimage.domain.entity.AdminAuditLogEntity;
import com.openimage.domain.entity.CreditLedgerEntity;
import com.openimage.domain.entity.ImageTaskEntity;
import com.openimage.domain.entity.ImageTypeConfigEntity;
import com.openimage.domain.entity.PaymentChannelEntity;
import com.openimage.domain.entity.ProductImageEntity;
import com.openimage.domain.entity.ProductProjectEntity;
import com.openimage.domain.entity.PromptTemplateEntity;
import com.openimage.domain.entity.RedemptionCodeEntity;
import com.openimage.domain.entity.RedemptionRecordEntity;
import com.openimage.domain.entity.RechargeOrderEntity;
import com.openimage.domain.entity.RechargePlanEntity;
import com.openimage.domain.entity.RiskEventEntity;
import com.openimage.domain.entity.StationMessageEntity;
import com.openimage.domain.entity.SystemSettingEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.entity.VideoTaskEntity;
import com.openimage.domain.mapper.AdminAuditLogMapper;
import com.openimage.domain.mapper.CreditLedgerMapper;
import com.openimage.domain.mapper.ImageTaskMapper;
import com.openimage.domain.mapper.ImageTypeConfigMapper;
import com.openimage.domain.mapper.PaymentChannelMapper;
import com.openimage.domain.mapper.ProductImageMapper;
import com.openimage.domain.mapper.ProductProjectMapper;
import com.openimage.domain.mapper.PromptTemplateMapper;
import com.openimage.domain.mapper.RedemptionCodeMapper;
import com.openimage.domain.mapper.RedemptionRecordMapper;
import com.openimage.domain.mapper.RechargeOrderMapper;
import com.openimage.domain.mapper.RechargePlanMapper;
import com.openimage.domain.mapper.RiskEventMapper;
import com.openimage.domain.mapper.StationMessageMapper;
import com.openimage.domain.mapper.StationMessageReadMapper;
import com.openimage.domain.mapper.SystemSettingMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.domain.mapper.VideoTaskMapper;
import com.openimage.modules.billing.BillingService;
import com.openimage.security.CurrentUser;
import com.openimage.storage.LocalStorageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final UserMapper users;
    private final ProductProjectMapper projects;
    private final ImageTaskMapper tasks;
    private final ProductImageMapper productImages;
    private final VideoTaskMapper videoTasks;
    private final CreditLedgerMapper ledgers;
    private final RechargePlanMapper plans;
    private final PaymentChannelMapper channels;
    private final RechargeOrderMapper orders;
    private final ImageTypeConfigMapper imageConfigs;
    private final PromptTemplateMapper prompts;
    private final RedemptionCodeMapper redemptionCodes;
    private final RedemptionRecordMapper redemptionRecords;
    private final RiskEventMapper riskEvents;
    private final StationMessageMapper stationMessages;
    private final StationMessageReadMapper stationMessageReads;
    private final SystemSettingMapper settings;
    private final AdminAuditLogMapper auditLogs;
    private final BillingService billingService;
    private final LocalStorageService storage;
    private final AiImageClient aiImageClient;
    private final ObjectMapper json;
    private final PasswordEncoder passwordEncoder;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom CODE_RANDOM = new SecureRandom();

    public AdminController(
        UserMapper users,
        ProductProjectMapper projects,
        ImageTaskMapper tasks,
        ProductImageMapper productImages,
        VideoTaskMapper videoTasks,
        CreditLedgerMapper ledgers,
        RechargePlanMapper plans,
        PaymentChannelMapper channels,
        RechargeOrderMapper orders,
        ImageTypeConfigMapper imageConfigs,
        PromptTemplateMapper prompts,
        RedemptionCodeMapper redemptionCodes,
        RedemptionRecordMapper redemptionRecords,
        RiskEventMapper riskEvents,
        StationMessageMapper stationMessages,
        StationMessageReadMapper stationMessageReads,
        SystemSettingMapper settings,
        AdminAuditLogMapper auditLogs,
        BillingService billingService,
        LocalStorageService storage,
        AiImageClient aiImageClient,
        ObjectMapper json,
        PasswordEncoder passwordEncoder
    ) {
        this.users = users;
        this.projects = projects;
        this.tasks = tasks;
        this.productImages = productImages;
        this.videoTasks = videoTasks;
        this.ledgers = ledgers;
        this.plans = plans;
        this.channels = channels;
        this.orders = orders;
        this.imageConfigs = imageConfigs;
        this.prompts = prompts;
        this.redemptionCodes = redemptionCodes;
        this.redemptionRecords = redemptionRecords;
        this.riskEvents = riskEvents;
        this.stationMessages = stationMessages;
        this.stationMessageReads = stationMessageReads;
        this.settings = settings;
        this.auditLogs = auditLogs;
        this.billingService = billingService;
        this.storage = storage;
        this.aiImageClient = aiImageClient;
        this.json = json;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "provider", required = false) String provider,
        @RequestParam(value = "image_type", required = false) String imageType,
        @RequestParam(value = "media_type", required = false) String mediaType,
        @RequestParam(value = "user_keyword", required = false) String userKeyword,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to
    ) {
        AdminTaskFilters filters = taskFilters(keyword, status, provider, imageType, userKeyword, from, to, mediaType);
        List<ImageTaskEntity> filteredTasks = tasks.selectList(taskQuery(filters));
        List<VideoTaskEntity> filteredVideos = videoTasks.selectList(videoTaskQuery(filters));
        long userCount = users.selectCount(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getDeleted, 0));
        long projectCount = projects.selectCount(new LambdaQueryWrapper<ProductProjectEntity>().eq(ProductProjectEntity::getDeleted, 0));
        long imageTaskCount = filteredTasks.size();
        long videoTaskCount = filteredVideos.size();
        long queuedImages = filteredTasks.stream().filter(task -> "queued".equals(task.getStatus())).count();
        long queuedVideos = filteredVideos.stream().filter(task -> "queued".equals(task.getStatus())).count();
        long activeImages = filteredTasks.stream().filter(task -> Set.of("queued", "running").contains(task.getStatus())).count();
        long activeVideos = filteredVideos.stream().filter(task -> isActiveVideoStatus(task.getStatus())).count();
        long failedImages = filteredTasks.stream().filter(task -> "failed".equals(task.getStatus())).count();
        long failedVideos = filteredVideos.stream().filter(task -> isFailedVideoStatus(task.getStatus())).count();
        long pendingOrders = orders.selectCount(new LambdaQueryWrapper<RechargeOrderEntity>().eq(RechargeOrderEntity::getDeleted, 0).eq(RechargeOrderEntity::getStatus, "pending"));
        Integer credits = users.selectList(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getDeleted, 0)).stream().map(UserEntity::getCredits).reduce(0, Integer::sum);
        BigDecimal imageActualCost = filteredTasks.stream()
            .map(this::taskActualCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal videoActualCost = filteredVideos.stream()
            .map(this::videoActualCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        int videoCreditsIncome = filteredVideos.stream()
            .filter(task -> task.getRefundedAt() == null)
            .mapToInt(task -> task.getCreditsCost() == null ? 0 : task.getCreditsCost())
            .sum();
        int videoRefundedCredits = filteredVideos.stream()
            .filter(task -> task.getRefundedAt() != null)
            .mapToInt(task -> task.getCreditsCost() == null ? 0 : task.getCreditsCost())
            .sum();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("users", userCount);
        payload.put("projects", projectCount);
        payload.put("image_tasks", imageTaskCount);
        payload.put("video_tasks", videoTaskCount);
        payload.put("total_tasks", imageTaskCount + videoTaskCount);
        payload.put("queued_image_tasks", queuedImages);
        payload.put("queued_video_tasks", queuedVideos);
        payload.put("queued_tasks", queuedImages + queuedVideos);
        payload.put("active_image_tasks", activeImages);
        payload.put("active_video_tasks", activeVideos);
        payload.put("active_tasks", activeImages + activeVideos);
        payload.put("failed_image_tasks", failedImages);
        payload.put("failed_video_tasks", failedVideos);
        payload.put("failed_tasks", failedImages + failedVideos);
        payload.put("credits", credits);
        payload.put("video_credits_income", videoCreditsIncome);
        payload.put("video_refunded_credits", videoRefundedCredits);
        payload.put("pending_orders", pendingOrders);
        payload.put("image_actual_cost", imageActualCost);
        payload.put("video_actual_cost", videoActualCost);
        payload.put("actual_cost", imageActualCost.add(videoActualCost));
        payload.put("trends", dashboardTrend(filters));
        return ApiResponse.ok(payload);
    }

    @GetMapping("/dashboard/trends")
    public ApiResponse<List<Map<String, Object>>> dashboardTrends(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "provider", required = false) String provider,
        @RequestParam(value = "image_type", required = false) String imageType,
        @RequestParam(value = "media_type", required = false) String mediaType,
        @RequestParam(value = "user_keyword", required = false) String userKeyword,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to
    ) {
        return ApiResponse.ok(dashboardTrend(taskFilters(keyword, status, provider, imageType, userKeyword, from, to, mediaType)));
    }

    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> users(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "role", required = false) String role,
        @RequestParam(value = "min_credits", required = false) Integer minCredits,
        @RequestParam(value = "max_credits", required = false) Integer maxCredits,
        @RequestParam(value = "sort", required = false, defaultValue = "created_desc") String sort,
        @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", required = false, defaultValue = "20") Integer pageSize
    ) {
        LambdaQueryWrapper<UserEntity> query = new LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getDeleted, 0);
        String normalizedStatus = status == null ? "" : status.trim().toLowerCase();
        if (!normalizedStatus.isBlank()) {
            query.eq(UserEntity::getStatus, normalizedStatus);
        }
        String normalizedRole = role == null ? "" : role.trim().toLowerCase();
        if (!normalizedRole.isBlank()) {
            query.eq(UserEntity::getRole, normalizedRole);
        }
        Integer normalizedMin = minCredits;
        Integer normalizedMax = maxCredits;
        if (normalizedMin != null && normalizedMax != null && normalizedMin > normalizedMax) {
            int swap = normalizedMin;
            normalizedMin = normalizedMax;
            normalizedMax = swap;
        }
        if (normalizedMin != null) {
            query.ge(UserEntity::getCredits, normalizedMin);
        }
        if (normalizedMax != null) {
            query.le(UserEntity::getCredits, normalizedMax);
        }
        String search = keyword == null ? "" : keyword.trim().toLowerCase();
        if (!search.isBlank()) {
            String like = "%" + search + "%";
            String githubLike = "%" + search.replaceFirst("^@", "") + "%";
            query.and(wrapper -> wrapper
                .apply("lower(id) like {0}", like)
                .or()
                .apply("lower(email) like {0}", like)
                .or()
                .apply("lower(coalesce(nickname, '')) like {0}", like)
                .or()
                .apply("lower(coalesce(github_login, '')) like {0}", githubLike));
        }
        long total = users.selectCount(query);
        PageParams paging = pageParams(page, pageSize);
        switch (sort == null ? "created_desc" : sort.trim().toLowerCase()) {
            case "created_asc" -> query.orderByAsc(UserEntity::getCreatedAt);
            case "credits_desc" -> query.orderByDesc(UserEntity::getCredits).orderByDesc(UserEntity::getCreatedAt);
            case "credits_asc" -> query.orderByAsc(UserEntity::getCredits).orderByDesc(UserEntity::getCreatedAt);
            case "last_active_desc" -> query.orderByDesc(UserEntity::getLastRequestAt).orderByDesc(UserEntity::getCreatedAt);
            default -> query.orderByDesc(UserEntity::getCreatedAt);
        }
        List<UserEntity> pageRows = users.selectList(query.last(limitOffset(paging)));
        Set<String> pageUserIds = pageRows.stream().map(UserEntity::getId).collect(Collectors.toSet());
        Map<String, Long> projectCounts = pageUserIds.isEmpty() ? Map.of() : countByUser(projects.selectMaps(
            new QueryWrapper<ProductProjectEntity>()
                .select("user_id", "COUNT(*) AS item_count")
                .eq("deleted", 0)
                .in("user_id", pageUserIds)
                .groupBy("user_id")
        ));
        Map<String, Long> rechargeCounts = pageUserIds.isEmpty() ? Map.of() : countByUser(orders.selectMaps(
            new QueryWrapper<RechargeOrderEntity>()
                .select("user_id", "COUNT(*) AS item_count")
                .eq("deleted", 0)
                .eq("status", "paid")
                .in("user_id", pageUserIds)
                .groupBy("user_id")
        ));
        Map<String, Long> riskCounts = pageUserIds.isEmpty() ? Map.of() : countByUser(riskEvents.selectMaps(
            new QueryWrapper<RiskEventEntity>()
                .select("user_id", "COUNT(*) AS item_count")
                .eq("deleted", 0)
                .in("user_id", pageUserIds)
                .groupBy("user_id")
        ));
        return ApiResponse.ok(paged(pageRows
            .stream()
            .map(user -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", user.getId());
                row.put("email_masked", maskUserAccount(user));
                row.put("email_display", displayUserAccount(user));
                row.put("nickname", user.getNickname() == null ? "" : user.getNickname());
                row.put("auth_provider", user.getAuthProvider() == null ? "" : user.getAuthProvider());
                row.put("github_login", user.getGithubLogin() == null ? "" : user.getGithubLogin());
                row.put("register_ip", user.getRegisterIp() == null ? "" : user.getRegisterIp());
                row.put("last_request_ip", user.getLastRequestIp() == null ? "" : user.getLastRequestIp());
                row.put("last_request_at", user.getLastRequestAt());
                row.put("role", user.getRole());
                row.put("status", user.getStatus());
                row.put("credits", user.getCredits());
                row.put("project_count", projectCounts.getOrDefault(user.getId(), 0L));
                row.put("recharge_count", rechargeCounts.getOrDefault(user.getId(), 0L));
                row.put("risk_count", riskCounts.getOrDefault(user.getId(), 0L));
                row.put("created_at", user.getCreatedAt());
                row.put("updated_at", user.getUpdatedAt());
                return row;
            })
            .toList(), total, paging));
    }

    @PostMapping("/users/{id}/credits")
    @PreAuthorize("hasAnyRole('admin','super_admin')")
    @Transactional
    public ApiResponse<Map<String, Object>> adjustCredits(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable String id, @Valid @RequestBody CreditAdjustRequest request) {
        CreditLedgerEntity existing = ledgers.selectOne(new LambdaQueryWrapper<CreditLedgerEntity>()
            .eq(CreditLedgerEntity::getDeleted, 0)
            .eq(CreditLedgerEntity::getUserId, id)
            .eq(CreditLedgerEntity::getBizType, "manual_adjust")
            .eq(CreditLedgerEntity::getBizId, request.requestToken()));
        if (existing != null) {
            return ApiResponse.ok(Map.of("user_id", id, "balance_after", existing.getBalanceAfter(), "ledger_id", existing.getId(), "idempotent", true));
        }
        UserEntity user = users.lockById(id);
        if (user == null || user.getDeleted() != 0) {
            throw new IllegalArgumentException("User not found");
        }
        existing = ledgers.selectOne(new LambdaQueryWrapper<CreditLedgerEntity>()
            .eq(CreditLedgerEntity::getDeleted, 0)
            .eq(CreditLedgerEntity::getUserId, id)
            .eq(CreditLedgerEntity::getBizType, "manual_adjust")
            .eq(CreditLedgerEntity::getBizId, request.requestToken()));
        if (existing != null) {
            return ApiResponse.ok(Map.of("user_id", id, "balance_after", existing.getBalanceAfter(), "ledger_id", existing.getId(), "idempotent", true));
        }
        if (request.changeAmount() == 0) {
            throw new IllegalArgumentException("Change amount cannot be zero");
        }
        int balanceAfter = user.getCredits() + request.changeAmount();
        if (balanceAfter < 0) {
            throw new IllegalArgumentException("Insufficient user credits");
        }
        Map<String, Object> before = auditMap("credits", user.getCredits());
        user.setCredits(balanceAfter);
        user.setUpdatedAt(OffsetDateTime.now());
        users.updateById(user);
        CreditLedgerEntity ledger = new CreditLedgerEntity();
        ledger.setId("cl_" + uuid());
        ledger.setUserId(user.getId());
        ledger.setChangeAmount(request.changeAmount());
        ledger.setBalanceAfter(balanceAfter);
        ledger.setBizType("manual_adjust");
        ledger.setBizId(request.requestToken());
        ledger.setRemark(request.remark());
        ledger.setCreatedAt(OffsetDateTime.now());
        ledger.setDeleted(0);
        ledgers.insert(ledger);
        audit(currentUser, "manual_credit_adjust", "user", user.getId(), "手动调整用户积分 " + request.changeAmount(), before, auditMap("credits", balanceAfter, "ledger_id", ledger.getId(), "remark", request.remark()));
        return ApiResponse.ok(Map.of("user_id", user.getId(), "balance_after", balanceAfter, "ledger_id", ledger.getId()));
    }

    @PatchMapping("/users/{id}/status")
    @PreAuthorize("hasAnyRole('admin','super_admin')")
    public ApiResponse<Map<String, Object>> updateUserStatus(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable String id, @RequestBody UserStatusRequest request) {
        UserEntity user = users.selectById(id);
        if (user == null || user.getDeleted() != 0) {
            throw new IllegalArgumentException("User not found");
        }
        String status = request.status() == null ? "" : request.status().trim();
        if (!List.of("active", "disabled", "banned").contains(status)) {
            throw new IllegalArgumentException("Invalid user status");
        }
        String beforeStatus = user.getStatus();
        user.setStatus(status);
        user.setUpdatedAt(OffsetDateTime.now());
        users.updateById(user);
        audit(currentUser, "user_status_update", "user", user.getId(), "修改用户状态为 " + status, auditMap("status", beforeStatus), auditMap("status", status));
        return ApiResponse.ok(Map.of("user_id", user.getId(), "status", user.getStatus()));
    }

    @GetMapping("/risk-events")
    public ApiResponse<Map<String, Object>> riskEvents(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "risk_level", required = false) String riskLevel,
        @RequestParam(value = "user_keyword", required = false) String userKeyword,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to,
        @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", required = false, defaultValue = "20") Integer pageSize
    ) {
        LambdaQueryWrapper<RiskEventEntity> query = new LambdaQueryWrapper<RiskEventEntity>();
        String search = keyword == null ? "" : keyword.trim().toLowerCase();
        if (!search.isBlank()) {
            String like = "%" + search + "%";
            query.and(wrapper -> wrapper
                .apply("lower(keyword) like {0}", like)
                .or()
                .apply("lower(coalesce(matched_text, '')) like {0}", like)
                .or()
                .apply("lower(feature) like {0}", like)
                .or()
                .apply("lower(source) like {0}", like));
        }
        if (riskLevel != null && !riskLevel.isBlank()) {
            query.eq(RiskEventEntity::getRiskLevel, riskLevel.trim().toLowerCase());
        }
        if (userKeyword != null && !userKeyword.isBlank()) {
            Set<String> ids = matchingUserIds(userKeyword.trim().toLowerCase());
            if (ids.isEmpty()) {
                query.eq(RiskEventEntity::getUserId, "__no_user__");
            } else {
                query.in(RiskEventEntity::getUserId, ids);
            }
        }
        OffsetDateTime since = parseDateBoundary(from, false);
        OffsetDateTime until = parseDateBoundary(to, true);
        if (since != null) {
            query.ge(RiskEventEntity::getCreatedAt, since);
        }
        if (until != null) {
            query.le(RiskEventEntity::getCreatedAt, until);
        }
        long total = riskEvents.selectCount(query);
        PageParams paging = pageParams(page, pageSize);
        List<RiskEventEntity> rows = riskEvents.selectList(query.orderByDesc(RiskEventEntity::getCreatedAt).last(limitOffset(paging)));
        Map<String, UserEntity> userMap = userMap(rows.stream().map(RiskEventEntity::getUserId).collect(Collectors.toSet()));
        return ApiResponse.ok(paged(rows.stream().map(event -> {
            UserEntity user = userMap.get(event.getUserId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", event.getId());
            row.put("user_id", event.getUserId());
            row.put("user_email_masked", user == null ? "-" : maskUserAccount(user));
            row.put("user_account", user == null ? "-" : displayUserAccount(user));
            row.put("project_id", event.getProjectId());
            row.put("task_id", event.getTaskId());
            row.put("feature", event.getFeature());
            row.put("risk_level", event.getRiskLevel());
            row.put("keyword", event.getKeyword());
            row.put("matched_text", event.getMatchedText());
            row.put("source", event.getSource());
            row.put("action", event.getAction());
            row.put("cleared", event.getDeleted() != null && event.getDeleted() != 0);
            row.put("created_at", event.getCreatedAt());
            return row;
        }).toList(), total, paging));
    }

    @GetMapping("/risk-keywords")
    public ApiResponse<Map<String, Object>> riskKeywords() {
        return ApiResponse.ok(riskControlConfigValue());
    }

    @PatchMapping("/risk-config")
    @PreAuthorize("hasAnyRole('admin','super_admin')")
    public ApiResponse<Map<String, Object>> updateRiskConfig(@AuthenticationPrincipal CurrentUser currentUser, @RequestBody RiskConfigUpdateRequest request) {
        Map<String, Object> before = riskControlConfigValue();
        Map<String, Object> next = deepCopyMap(before);
        if (request.enabled() != null) {
            next.put("enabled", request.enabled());
        }
        if (request.banThreshold() != null) {
            int threshold = request.banThreshold();
            if (threshold < 1 || threshold > 100) {
                throw new IllegalArgumentException("封禁阈值必须在 1-100 次之间");
            }
            next.put("ban_threshold", threshold);
        }
        if (request.blockLevels() != null) {
            next.put("block_levels", normalizeRiskLevelList(request.blockLevels(), "拦截等级"));
        }
        if (request.warnLevels() != null) {
            next.put("warn_levels", normalizeRiskLevelList(request.warnLevels(), "提醒等级"));
        }
        ensureNoRiskLevelOverlap(next);
        saveRiskControlConfig(currentUser, next, before, "更新风控配置", "risk_config_update");
        return ApiResponse.ok(next);
    }

    @PostMapping("/risk-keywords")
    @PreAuthorize("hasAnyRole('admin','super_admin')")
    public ApiResponse<Map<String, Object>> addRiskKeyword(@AuthenticationPrincipal CurrentUser currentUser, @Valid @RequestBody RiskKeywordRequest request) {
        String level = normalizeRiskLevel(request.level());
        String keyword = cleanKeyword(request.keyword());
        Map<String, Object> before = riskControlConfigValue();
        Map<String, Object> next = deepCopyMap(before);
        Map<String, List<String>> keywords = riskKeywordMap(next.get("keywords"));
        List<String> rows = new ArrayList<>(keywords.getOrDefault(level, List.of()));
        boolean exists = rows.stream().anyMatch(item -> item.equalsIgnoreCase(keyword));
        if (!exists) {
            rows.add(keyword);
        }
        keywords.put(level, rows);
        next.put("keywords", keywords);
        saveRiskControlConfig(currentUser, next, before, exists ? "风控词已存在：" + keyword : "新增风控词：" + keyword);
        return ApiResponse.ok(next);
    }

    @PostMapping("/risk-keywords/delete")
    @PreAuthorize("hasAnyRole('admin','super_admin')")
    public ApiResponse<Map<String, Object>> deleteRiskKeyword(@AuthenticationPrincipal CurrentUser currentUser, @Valid @RequestBody RiskKeywordRequest request) {
        String level = normalizeRiskLevel(request.level());
        String keyword = cleanKeyword(request.keyword());
        Map<String, Object> before = riskControlConfigValue();
        Map<String, Object> next = deepCopyMap(before);
        Map<String, List<String>> keywords = riskKeywordMap(next.get("keywords"));
        List<String> rows = keywords.getOrDefault(level, List.of()).stream()
            .filter(item -> !item.equalsIgnoreCase(keyword))
            .toList();
        keywords.put(level, rows);
        next.put("keywords", keywords);
        saveRiskControlConfig(currentUser, next, before, "删除风控词：" + keyword);
        return ApiResponse.ok(next);
    }

    @PostMapping("/users/{id}/risk-events/clear")
    @PreAuthorize("hasAnyRole('admin','super_admin')")
    @Transactional
    public ApiResponse<Map<String, Object>> clearUserRiskEvents(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable String id) {
        UserEntity user = users.lockById(id);
        if (user == null || user.getDeleted() != 0) {
            throw new IllegalArgumentException("User not found");
        }
        String beforeStatus = user.getStatus();
        int cleared = riskEvents.softDeleteByUserId(id);
        user.setStatus("active");
        user.setUpdatedAt(OffsetDateTime.now());
        users.updateById(user);
        audit(currentUser, "risk_events_clear", "user", user.getId(), "清空用户风控次数并解禁", auditMap("status", beforeStatus), auditMap("status", user.getStatus(), "cleared", cleared));
        return ApiResponse.ok(Map.of("user_id", user.getId(), "status", user.getStatus(), "cleared", cleared));
    }

    @PostMapping("/users/{id}/password")
    @PreAuthorize("hasAnyRole('admin','super_admin')")
    public ApiResponse<Map<String, Object>> resetUserPassword(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable String id, @RequestBody PasswordResetRequest request) {
        UserEntity user = users.selectById(id);
        if (user == null || user.getDeleted() != 0) {
            throw new IllegalArgumentException("User not found");
        }
        String password = request.password() == null ? "" : request.password().trim();
        if (!password.matches("^(?=.*[A-Za-z])(?=.*\\d)\\S{8,16}$")) {
            throw new IllegalArgumentException("密码必须为 8-16 位，且至少包含字母和数字");
        }
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setAuthProvider("password");
        user.setUpdatedAt(OffsetDateTime.now());
        users.updateById(user);
        audit(currentUser, "user_password_reset", "user", user.getId(), "管理员重置用户临时密码", auditMap("email", mask(user.getEmail())), auditMap("password_reset", true));
        return ApiResponse.ok(Map.of("user_id", user.getId(), "updated", true));
    }

    @GetMapping("/image-tasks")
    public ApiResponse<Map<String, Object>> tasks(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "provider", required = false) String provider,
        @RequestParam(value = "image_type", required = false) String imageType,
        @RequestParam(value = "user_keyword", required = false) String userKeyword,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to,
        @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", required = false, defaultValue = "20") Integer pageSize
    ) {
        AdminTaskFilters filters = taskFilters(keyword, status, provider, imageType, userKeyword, from, to);
        long total = tasks.selectCount(taskQuery(filters));
        PageParams paging = pageParams(page, pageSize);
        List<ImageTaskEntity> rows = tasks.selectList(taskQuery(filters).orderByDesc(ImageTaskEntity::getCreatedAt).last(limitOffset(paging)));
        Map<String, UserEntity> userMap = userMap(rows.stream()
            .map(ImageTaskEntity::getUserId)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toSet()));
        return ApiResponse.ok(paged(rows.stream()
            .map(task -> {
                UserEntity user = userMap.get(task.getUserId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("task_id", task.getId());
                row.put("user_id", task.getUserId());
                row.put("user_email", user == null || user.getEmail() == null ? "" : user.getEmail());
                row.put("user_email_masked", user == null ? "-" : maskUserAccount(user));
                row.put("user_account", user == null ? task.getUserId() : displayUserAccount(user));
                row.put("user_nickname", user == null ? "" : (user.getNickname() == null ? "" : user.getNickname()));
                row.put("project_id", task.getProjectId());
                row.put("image_type", task.getImageType());
                row.put("status", task.getStatus());
                row.put("credits_cost", task.getCreditsCost());
                row.put("actual_cost", taskActualCost(task));
                row.put("cost_details", taskCostDetails(task));
                row.put("provider", task.getProvider() == null ? "" : task.getProvider());
                row.put("model", task.getModel() == null ? "" : task.getModel());
                row.put("safe_error", task.getSafeError() == null ? "" : task.getSafeError());
                row.put("result_url", task.getResultUrl() == null ? "" : task.getResultUrl());
                row.put("provider_attempts", readList(task.getProviderAttempts()));
                row.put("hidden_by_user", Integer.valueOf(1).equals(task.getHiddenByUser()));
                row.put("created_at", task.getCreatedAt());
                row.put("updated_at", task.getUpdatedAt());
                return row;
            })
            .toList(), total, paging));
    }

    @GetMapping("/image-tasks/{id}")
    public ApiResponse<Map<String, Object>> imageTaskDetail(@PathVariable String id) {
        ImageTaskEntity task = tasks.selectById(id);
        if (task == null || Integer.valueOf(1).equals(task.getDeleted())) {
            throw new IllegalArgumentException("Task not found");
        }
        UserEntity user = users.selectById(task.getUserId());
        ProductProjectEntity project = projects.selectById(task.getProjectId());
        Object promptDetails = readJsonValue(task.getPromptSnapshot());
        List<ProductImageEntity> references = taskReferenceImages(task, promptDetails);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("task_id", task.getId());
        row.put("task_set_id", task.getTaskSetId() == null ? "" : task.getTaskSetId());
        row.put("user_id", task.getUserId());
        row.put("user_email", user == null || user.getEmail() == null ? "" : user.getEmail());
        row.put("user_email_masked", user == null ? "-" : maskUserAccount(user));
        row.put("user_account", user == null ? task.getUserId() : displayUserAccount(user));
        row.put("user_nickname", user == null || user.getNickname() == null ? "" : user.getNickname());
        row.put("project_id", task.getProjectId());
        row.put("image_type", task.getImageType());
        row.put("status", task.getStatus());
        row.put("credits_cost", task.getCreditsCost());
        row.put("actual_cost", taskActualCost(task));
        row.put("cost_details", taskCostDetails(task));
        row.put("provider", task.getProvider() == null ? "" : task.getProvider());
        row.put("model", task.getModel() == null ? "" : task.getModel());
        row.put("safe_error", task.getSafeError() == null ? "" : task.getSafeError());
        row.put("result_url", task.getResultUrl() == null ? "" : task.getResultUrl());
        row.put("prompt_snapshot", task.getPromptSnapshot() == null ? "" : task.getPromptSnapshot());
        row.put("prompt_details", promptDetails);
        row.put("provider_attempts", readList(task.getProviderAttempts()));
        row.put("reference_images", references.stream().map(this::adminImageReference).toList());
        row.put("project", adminProjectDetail(project));
        row.put("refunded_at", task.getRefundedAt());
        row.put("hidden_by_user", Integer.valueOf(1).equals(task.getHiddenByUser()));
        row.put("created_at", task.getCreatedAt());
        row.put("updated_at", task.getUpdatedAt());
        return ApiResponse.ok(row);
    }

    @PatchMapping("/image-tasks/{id}/retry")
    public ApiResponse<Map<String, Object>> retryTask(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable String id) {
        ImageTaskEntity task = tasks.selectById(id);
        if (task == null || task.getDeleted() != 0) {
            throw new IllegalArgumentException("Task not found");
        }
        if (!"failed".equals(task.getStatus())) {
            throw new IllegalArgumentException("Only failed tasks can be retried from admin");
        }
        if (!aiImageClient.enabled()) {
            throw new IllegalArgumentException("AI 作图服务已被管理员禁用或未配置密钥，请先在 AI 配置中启用后再重试");
        }
        String beforeStatus = task.getStatus();
        task.setStatus("queued");
        task.setSafeError("");
        task.setRawError("");
        task.setProviderAttempts("[]");
        task.setUpdatedAt(OffsetDateTime.now());
        tasks.updateById(task);
        ProductProjectEntity project = projects.selectById(task.getProjectId());
        if (project != null && project.getDeleted() == 0) {
            project.setStatus("images_queued");
            project.setUpdatedAt(OffsetDateTime.now());
            projects.updateById(project);
        }
        audit(currentUser, "image_task_retry", "image_task", task.getId(), "后台重试图片任务", auditMap("status", beforeStatus), auditMap("status", task.getStatus(), "project_id", task.getProjectId()));
        return ApiResponse.ok(Map.of("task_id", task.getId(), "status", task.getStatus()));
    }

    @PatchMapping("/image-tasks/{id}/delete")
    public ApiResponse<Map<String, Object>> deleteTask(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable String id) {
        ImageTaskEntity task = tasks.selectById(id);
        if (task == null || task.getDeleted() != 0) {
            throw new IllegalArgumentException("Task not found");
        }
        if ("queued".equals(task.getStatus()) || "running".equals(task.getStatus())) {
            throw new IllegalArgumentException("生成中的图片任务不能删除，请等待任务结束");
        }
        int deleted = tasks.softDeleteTerminalByAdmin(task.getId(), OffsetDateTime.now());
        if (deleted != 1) {
            throw new IllegalArgumentException("任务状态已变化，请刷新后重试");
        }
        audit(currentUser, "image_task_delete", "image_task", task.getId(), "后台删除图片任务", auditMap("deleted", 0, "status", task.getStatus()), auditMap("deleted", 1, "project_id", task.getProjectId()));
        return ApiResponse.ok(Map.of("task_id", task.getId(), "deleted", true));
    }

    @GetMapping("/billing/plans")
    public ApiResponse<List<RechargePlanEntity>> rechargePlans() {
        return ApiResponse.ok(plans.selectList(new LambdaQueryWrapper<RechargePlanEntity>().eq(RechargePlanEntity::getDeleted, 0).orderByAsc(RechargePlanEntity::getSortOrder)));
    }

    @GetMapping("/billing/channels")
    public ApiResponse<List<PaymentChannelEntity>> paymentChannels() {
        return ApiResponse.ok(channels.selectList(new LambdaQueryWrapper<PaymentChannelEntity>().eq(PaymentChannelEntity::getDeleted, 0).orderByAsc(PaymentChannelEntity::getSortOrder)));
    }

    @GetMapping("/billing/orders")
    public ApiResponse<Map<String, Object>> rechargeOrders(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "channel", required = false) String channel,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to,
        @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", required = false, defaultValue = "20") Integer pageSize
    ) {
        LambdaQueryWrapper<RechargeOrderEntity> query = new LambdaQueryWrapper<RechargeOrderEntity>()
            .eq(RechargeOrderEntity::getDeleted, 0);
        String normalizedStatus = status == null ? "" : status.trim();
        if (!normalizedStatus.isBlank()) {
            query.eq(RechargeOrderEntity::getStatus, normalizedStatus);
        }
        String normalizedChannel = channel == null ? "" : channel.trim().toLowerCase();
        if (!normalizedChannel.isBlank()) {
            query.eq(RechargeOrderEntity::getChannelCode, normalizedChannel);
        }
        OffsetDateTime since = parseDateBoundary(from, false);
        OffsetDateTime until = parseDateBoundary(to, true);
        if (since != null) {
            query.ge(RechargeOrderEntity::getCreatedAt, since);
        }
        if (until != null) {
            query.le(RechargeOrderEntity::getCreatedAt, until);
        }
        String search = keyword == null ? "" : keyword.trim().toLowerCase();
        if (!search.isBlank()) {
            String like = "%" + search + "%";
            Set<String> matchingUsers = matchingUserIds(search);
            query.and(wrapper -> {
                wrapper.apply("lower(id) like {0}", like)
                    .or()
                    .apply("lower(user_id) like {0}", like)
                    .or()
                    .apply("lower(channel_code) like {0}", like)
                    .or()
                    .apply("lower(coalesce(external_trade_no, '')) like {0}", like)
                    .or()
                    .apply("lower(coalesce(remark, '')) like {0}", like);
                if (!matchingUsers.isEmpty()) {
                    wrapper.or().in(RechargeOrderEntity::getUserId, matchingUsers);
                }
            });
        }
        long total = orders.selectCount(query);
        PageParams paging = pageParams(page, pageSize);
        List<RechargeOrderEntity> pageRows = orders.selectList(query.orderByDesc(RechargeOrderEntity::getCreatedAt).last(limitOffset(paging)));
        Map<String, UserEntity> orderUsers = userMap(pageRows.stream().map(RechargeOrderEntity::getUserId).collect(Collectors.toSet()));
        return ApiResponse.ok(paged(pageRows
            .stream()
            .map(order -> {
                UserEntity user = orderUsers.get(order.getUserId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", order.getId());
                row.put("user_id", order.getUserId());
                row.put("user_email_masked", user == null ? "-" : mask(user.getEmail()));
                row.put("user_email_display", user == null ? "-" : displayUserAccount(user));
                row.put("user_nickname", user == null || user.getNickname() == null ? "" : user.getNickname());
                row.put("plan_id", order.getPlanId());
                row.put("channel_code", order.getChannelCode());
                row.put("amount_cents", order.getAmountCents());
                row.put("credits", order.getCredits());
                row.put("currency", order.getCurrency());
                row.put("status", order.getStatus());
                row.put("remark", order.getRemark());
                row.put("external_trade_no", order.getExternalTradeNo());
                row.put("paid_at", order.getPaidAt());
                row.put("created_at", order.getCreatedAt());
                row.put("updated_at", order.getUpdatedAt());
                return row;
            })
            .toList(), total, paging));
    }

    @PostMapping("/billing/plans/{id}")
    public ApiResponse<RechargePlanEntity> updateRechargePlan(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable String id, @RequestBody RechargePlanUpdateRequest request) {
        RechargePlanEntity plan = plans.selectById(id);
        if (plan == null || plan.getDeleted() != 0) {
            throw new IllegalArgumentException("Recharge plan not found");
        }
        Map<String, Object> before = auditMap(
            "name", plan.getName(),
            "credits", plan.getCredits(),
            "price_cents", plan.getPriceCents(),
            "enabled", plan.getEnabled()
        );
        if (request.name() != null && !request.name().isBlank()) {
            plan.setName(request.name());
        }
        if (request.credits() != null && request.credits() > 0) {
            plan.setCredits(request.credits());
        }
        if (request.priceCents() != null && request.priceCents() >= 0) {
            plan.setPriceCents(request.priceCents());
        }
        if (request.currency() != null && !request.currency().isBlank()) {
            plan.setCurrency(request.currency());
        }
        if (request.enabled() != null) {
            plan.setEnabled(request.enabled());
        }
        if (request.sortOrder() != null) {
            plan.setSortOrder(request.sortOrder());
        }
        plan.setUpdatedAt(OffsetDateTime.now());
        plans.updateById(plan);
        audit(currentUser, "recharge_plan_update", "recharge_plan", plan.getId(), "修改积分套餐 " + plan.getName(), before, auditMap("name", plan.getName(), "credits", plan.getCredits(), "price_cents", plan.getPriceCents(), "enabled", plan.getEnabled()));
        return ApiResponse.ok(plan);
    }

    @PostMapping("/billing/channels/{id}")
    public ApiResponse<PaymentChannelEntity> updatePaymentChannel(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable String id, @RequestBody PaymentChannelUpdateRequest request) {
        PaymentChannelEntity channel = channels.selectById(id);
        if (channel == null || channel.getDeleted() != 0) {
            throw new IllegalArgumentException("Payment channel not found");
        }
        Map<String, Object> before = auditMap(
            "name", channel.getName(),
            "visible", channel.getVisible(),
            "enabled", channel.getEnabled(),
            "sort_order", channel.getSortOrder()
        );
        if (request.name() != null && !request.name().isBlank()) {
            channel.setName(request.name());
        }
        if (request.visible() != null) {
            channel.setVisible(request.visible());
        }
        if (request.enabled() != null) {
            channel.setEnabled(request.enabled());
        }
        if (request.sortOrder() != null) {
            channel.setSortOrder(request.sortOrder());
        }
        if (request.config() != null && !request.config().isBlank()) {
            try {
                json.readTree(request.config());
            } catch (Exception ignored) {
                throw new IllegalArgumentException("支付渠道配置必须是合法 JSON");
            }
            channel.setConfig(request.config());
        }
        channel.setUpdatedAt(OffsetDateTime.now());
        channels.updateById(channel);
        audit(currentUser, "payment_channel_update", "payment_channel", channel.getId(), "修改支付渠道 " + channel.getName(), before, auditMap("name", channel.getName(), "visible", channel.getVisible(), "enabled", channel.getEnabled(), "sort_order", channel.getSortOrder()));
        return ApiResponse.ok(channel);
    }

    @PatchMapping("/billing/orders/{id}/confirm")
    @Transactional
    public ApiResponse<Map<String, Object>> confirmRechargeOrder(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable String id) {
        RechargeOrderEntity order = orders.selectById(id);
        if (order == null || order.getDeleted() != 0) {
            throw new IllegalArgumentException("Recharge order not found");
        }
        String beforeStatus = order.getStatus();
        BillingService.PaymentResult result = billingService.confirmRechargeOrder(id, "管理员确认到账", order.getExternalTradeNo(), order.getPaidAt());
        audit(currentUser, "recharge_order_confirm", "recharge_order", order.getId(), "确认充值订单到账", auditMap("status", beforeStatus), auditMap("status", result.status(), "user_id", order.getUserId(), "credits", order.getCredits(), "ledger_id", result.ledgerId(), "idempotent", result.idempotent()));
        return ApiResponse.ok(result.toMap());
    }

    @PatchMapping("/billing/orders/{id}/cancel")
    @Transactional
    public ApiResponse<Map<String, Object>> cancelRechargeOrder(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable String id) {
        RechargeOrderEntity order = orders.lockById(id);
        if (order == null || order.getDeleted() != 0) {
            throw new IllegalArgumentException("Recharge order not found");
        }
        if ("paid".equals(order.getStatus())) {
            throw new IllegalArgumentException("Paid orders cannot be cancelled");
        }
        String beforeStatus = order.getStatus();
        order.setStatus("cancelled");
        order.setRemark("管理员取消订单");
        order.setUpdatedAt(OffsetDateTime.now());
        orders.updateById(order);
        audit(currentUser, "recharge_order_cancel", "recharge_order", order.getId(), "取消充值订单", auditMap("status", beforeStatus), auditMap("status", "cancelled"));
        return ApiResponse.ok(Map.of("order_id", order.getId(), "status", order.getStatus()));
    }

    @GetMapping("/billing/redemption-codes")
    public ApiResponse<Map<String, Object>> redemptionCodes(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", required = false, defaultValue = "20") Integer pageSize
    ) {
        redemptionCodes.expireOverdueCodes();
        LambdaQueryWrapper<RedemptionCodeEntity> query = new LambdaQueryWrapper<RedemptionCodeEntity>()
            .eq(RedemptionCodeEntity::getDeleted, 0);
        String normalizedStatus = status == null ? "" : status.trim();
        if (!normalizedStatus.isBlank()) {
            if ("expired".equals(normalizedStatus)) {
                query.lt(RedemptionCodeEntity::getExpiresAt, OffsetDateTime.now());
            } else if ("used_up".equals(normalizedStatus)) {
                query.apply("used_count >= max_uses");
            } else if ("disabled".equals(normalizedStatus)) {
                query.eq(RedemptionCodeEntity::getEnabled, false);
            } else if ("active".equals(normalizedStatus)) {
                query.eq(RedemptionCodeEntity::getEnabled, true)
                    .apply("(expires_at is null or expires_at >= now())")
                    .apply("used_count < max_uses");
            } else {
                query.eq(RedemptionCodeEntity::getStatus, normalizedStatus);
            }
        }
        String search = keyword == null ? "" : keyword.trim().toLowerCase();
        if (!search.isBlank()) {
            String like = "%" + search + "%";
            query.and(wrapper -> wrapper
                .apply("lower(code) like {0}", like)
                .or()
                .apply("lower(id) like {0}", like)
                .or()
                .apply("lower(coalesce(benefit_type, '')) like {0}", like)
                .or()
                .apply("lower(coalesce(batch_id, '')) like {0}", like)
                .or()
                .apply("lower(coalesce(batch_name, '')) like {0}", like)
                .or()
                .apply("lower(coalesce(note, '')) like {0}", like));
        }
        long total = redemptionCodes.selectCount(query);
        PageParams paging = pageParams(page, pageSize);
        return ApiResponse.ok(paged(redemptionCodes.selectList(query.orderByDesc(RedemptionCodeEntity::getCreatedAt).last(limitOffset(paging)))
            .stream()
            .map(code -> {
                long recordCount = redemptionRecords.selectCount(new LambdaQueryWrapper<RedemptionRecordEntity>()
                    .eq(RedemptionRecordEntity::getDeleted, 0)
                    .eq(RedemptionRecordEntity::getCodeId, code.getId()));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", code.getId());
                row.put("code", code.getCode());
                row.put("credits", code.getCredits());
                row.put("max_uses", code.getMaxUses());
                row.put("used_count", code.getUsedCount());
                row.put("record_count", recordCount);
                row.put("expires_at", code.getExpiresAt());
                row.put("enabled", code.getEnabled());
                row.put("benefit_type", code.getBenefitType() == null ? "兑换码积分" : code.getBenefitType());
                row.put("batch_id", code.getBatchId() == null ? "" : code.getBatchId());
                row.put("batch_name", code.getBatchName() == null ? "" : code.getBatchName());
                row.put("batch_exclusive", Boolean.TRUE.equals(code.getBatchExclusive()));
                row.put("status", redemptionStatus(code));
                row.put("note", code.getNote() == null ? "" : code.getNote());
                row.put("created_at", code.getCreatedAt());
                return row;
            })
            .toList(), total, paging));
    }

    @PostMapping("/billing/redemption-codes")
    public ApiResponse<List<RedemptionCodeEntity>> createRedemptionCodes(@AuthenticationPrincipal CurrentUser currentUser, @Valid @RequestBody RedemptionCodeCreateRequest request) {
        redemptionCodes.expireOverdueCodes();
        int quantity = request.quantity() == null ? 1 : request.quantity();
        int maxUses = request.maxUses() == null ? 1 : request.maxUses();
        if (request.credits() == null || request.credits() <= 0) {
            throw new IllegalArgumentException("Credits must be positive");
        }
        if (quantity <= 0 || quantity > 200) {
            throw new IllegalArgumentException("Quantity must be between 1 and 200");
        }
        if (maxUses <= 0 || maxUses > 10000) {
            throw new IllegalArgumentException("Max uses must be between 1 and 10000");
        }
        OffsetDateTime expiresAt = request.expiresAt();
        if (expiresAt == null && request.validDays() != null && request.validDays() > 0) {
            expiresAt = OffsetDateTime.now().plusDays(request.validDays());
        }
        if (expiresAt != null && expiresAt.isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("Expire time must be in the future");
        }
        List<RedemptionCodeEntity> created = new ArrayList<>();
        for (int i = 0; i < quantity; i++) {
            RedemptionCodeEntity code = new RedemptionCodeEntity();
            code.setId("rc_" + uuid());
            code.setCode(nextRedemptionCode(request.prefix()));
            code.setCredits(request.credits());
            code.setMaxUses(maxUses);
            code.setUsedCount(0);
            code.setExpiresAt(expiresAt);
            code.setEnabled(true);
            code.setBenefitType(blank(request.benefitType()) ? "兑换码积分" : request.benefitType().trim());
            code.setBatchId(blank(request.batchId()) ? null : request.batchId().trim());
            code.setBatchName(blank(request.batchName()) ? null : request.batchName().trim());
            code.setBatchExclusive(Boolean.TRUE.equals(request.batchExclusive()));
            code.setStatus("active");
            code.setNote(request.note());
            code.setCreatedBy(currentUser == null ? null : currentUser.id());
            code.setCreatedAt(OffsetDateTime.now());
            code.setUpdatedAt(OffsetDateTime.now());
            code.setDeleted(0);
            redemptionCodes.insert(code);
            created.add(code);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("quantity", created.size());
        after.put("credits", request.credits());
        after.put("max_uses", maxUses);
        after.put("expires_at", expiresAt);
        after.put("benefit_type", blank(request.benefitType()) ? "兑换码积分" : request.benefitType().trim());
        after.put("batch_id", blank(request.batchId()) ? "" : request.batchId().trim());
        after.put("batch_name", blank(request.batchName()) ? "" : request.batchName().trim());
        after.put("batch_exclusive", Boolean.TRUE.equals(request.batchExclusive()));
        after.put("code_ids", created.stream().map(RedemptionCodeEntity::getId).toList());
        audit(currentUser, "redemption_codes_create", "redemption_code", created.isEmpty() ? "" : created.get(0).getId(), "批量生成兑换码 " + created.size() + " 个", null, after);
        return ApiResponse.ok(created);
    }

    @PostMapping("/benefits/card-waves")
    public ApiResponse<Map<String, Object>> createBenefitCardWave(@AuthenticationPrincipal CurrentUser currentUser, @Valid @RequestBody BenefitCardWaveCreateRequest request) {
        redemptionCodes.expireOverdueCodes();
        List<BenefitCardTierRequest> tiers = request.tiers() == null ? List.of() : request.tiers();
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("请至少配置一个发卡档位");
        }
        int totalQuantity = tiers.stream().mapToInt(tier -> tier.quantity() == null ? 0 : tier.quantity()).sum();
        if (totalQuantity <= 0 || totalQuantity > 500) {
            throw new IllegalArgumentException("单波发卡数量必须在 1 到 500 之间");
        }
        OffsetDateTime expiresAt = request.expiresAt();
        if (expiresAt == null && request.validDays() != null && request.validDays() > 0) {
            expiresAt = OffsetDateTime.now().plusDays(request.validDays());
        }
        if (expiresAt != null && expiresAt.isBefore(OffsetDateTime.now())) {
            throw new IllegalArgumentException("Expire time must be in the future");
        }
        String batchId = blank(request.batchId()) ? "wave_" + uuid() : request.batchId().trim();
        String batchName = blank(request.name()) ? "福利发卡 " + OffsetDateTime.now().toLocalDate() : request.name().trim();
        String benefitType = blank(request.benefitType()) ? "定时福利卡" : request.benefitType().trim();
        String prefix = blank(request.prefix()) ? "FL" : request.prefix().trim();
        String note = blank(request.note()) ? batchName : request.note().trim();
        List<RedemptionCodeEntity> created = new ArrayList<>();
        for (BenefitCardTierRequest tier : tiers) {
            int credits = tier.credits() == null ? 0 : tier.credits();
            int quantity = tier.quantity() == null ? 0 : tier.quantity();
            int maxUses = tier.maxUses() == null ? 1 : tier.maxUses();
            if (credits <= 0) {
                throw new IllegalArgumentException("每个档位积分必须大于 0");
            }
            if (quantity <= 0 || quantity > 500) {
                throw new IllegalArgumentException("每个档位数量必须在 1 到 500 之间");
            }
            if (maxUses <= 0 || maxUses > 10000) {
                throw new IllegalArgumentException("Max uses must be between 1 and 10000");
            }
            for (int i = 0; i < quantity; i++) {
                RedemptionCodeEntity code = new RedemptionCodeEntity();
                code.setId("rc_" + uuid());
                code.setCode(nextRedemptionCode(prefix));
                code.setCredits(credits);
                code.setMaxUses(maxUses);
                code.setUsedCount(0);
                code.setExpiresAt(expiresAt);
                code.setEnabled(true);
                code.setBenefitType(benefitType);
                code.setBatchId(batchId);
                code.setBatchName(batchName);
                code.setBatchExclusive(true);
                code.setStatus("active");
                code.setNote(note);
                code.setCreatedBy(currentUser == null ? null : currentUser.id());
                code.setCreatedAt(OffsetDateTime.now());
                code.setUpdatedAt(OffsetDateTime.now());
                code.setDeleted(0);
                redemptionCodes.insert(code);
                created.add(code);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batch_id", batchId);
        payload.put("batch_name", batchName);
        payload.put("quantity", created.size());
        payload.put("code_ids", created.stream().map(RedemptionCodeEntity::getId).toList());
        payload.put("codes", created);
        audit(currentUser, "benefit_card_wave_create", "redemption_code_batch", batchId, "生成福利发卡波次 " + created.size() + " 张", null, payload);
        return ApiResponse.ok(payload);
    }

    @GetMapping("/benefits/redemption-codes")
    public ApiResponse<Map<String, Object>> benefitRedemptionCodes(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", required = false, defaultValue = "20") Integer pageSize
    ) {
        return redemptionCodes(keyword, status, page, pageSize);
    }

    @GetMapping("/benefits/redemption-records")
    public ApiResponse<Map<String, Object>> redemptionRecords(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", required = false, defaultValue = "20") Integer pageSize
    ) {
        LambdaQueryWrapper<RedemptionRecordEntity> query = new LambdaQueryWrapper<RedemptionRecordEntity>()
            .eq(RedemptionRecordEntity::getDeleted, 0);
        String search = keyword == null ? "" : keyword.trim().toLowerCase();
        if (!search.isBlank()) {
            String like = "%" + search + "%";
            query.and(wrapper -> wrapper
                .apply("lower(id) like {0}", like)
                .or()
                .apply("lower(code) like {0}", like)
                .or()
                .apply("lower(code_id) like {0}", like)
                .or()
                .apply("lower(coalesce(batch_id, '')) like {0}", like)
                .or()
                .apply("lower(user_id) like {0}", like)
                .or()
                .apply("lower(coalesce(user_email_masked, '')) like {0}", like)
                .or()
                .apply("lower(coalesce(user_nickname, '')) like {0}", like));
        }
        long total = redemptionRecords.selectCount(query);
        PageParams paging = pageParams(page, pageSize);
        return ApiResponse.ok(paged(redemptionRecords.selectList(query.orderByDesc(RedemptionRecordEntity::getRedeemedAt).last(limitOffset(paging)))
            .stream()
            .map(record -> {
                UserEntity user = users.selectById(record.getUserId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", record.getId());
                row.put("code", record.getCode());
                row.put("code_id", record.getCodeId());
                row.put("batch_id", record.getBatchId() == null ? "" : record.getBatchId());
                row.put("user_id", record.getUserId());
                row.put("user_email_masked", record.getUserEmailMasked() == null || record.getUserEmailMasked().isBlank() ? (user == null ? "-" : mask(user.getEmail())) : record.getUserEmailMasked());
                row.put("user_nickname", record.getUserNickname() == null ? "" : record.getUserNickname());
                row.put("credits", record.getCredits());
                row.put("redeemed_at", record.getRedeemedAt());
                return row;
            })
            .toList(), total, paging));
    }

    @GetMapping("/benefits/config")
    public ApiResponse<Map<String, Object>> benefitConfig() {
        return ApiResponse.ok(benefitConfigValue());
    }

    @PostMapping("/benefits/config")
    public ApiResponse<Map<String, Object>> updateBenefitConfig(@AuthenticationPrincipal CurrentUser currentUser, @RequestBody Map<String, Object> request) {
        Map<String, Object> before = benefitConfigValue();
        Map<String, Object> next = benefitConfigValue();
        next.putAll(request == null ? Map.of() : request);
        saveBenefitConfig(currentUser, next, before, "更新积分兑换码引导配置");
        return ApiResponse.ok(next);
    }

    @PostMapping("/benefits/qr")
    public ApiResponse<Map<String, Object>> uploadBenefitQr(
        @AuthenticationPrincipal CurrentUser currentUser,
        @RequestParam(defaultValue = "qq") String kind,
        @RequestParam("file") MultipartFile file
    ) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择二维码图片");
        }
        if (file.getSize() > 3L * 1024L * 1024L) {
            throw new IllegalArgumentException("二维码图片不能超过 3MB");
        }
        String normalizedKind = "wechat".equalsIgnoreCase(kind) ? "wechat" : "qq";
        LocalStorageService.StoredFile stored = storage.saveBenefitImage(normalizedKind, "qr_" + uuid(), file);
        Map<String, Object> before = benefitConfigValue();
        Map<String, Object> next = new LinkedHashMap<>(before);
        next.put(("wechat".equals(normalizedKind) ? "wechat" : "qq") + "_qr_url", stored.url());
        saveBenefitConfig(currentUser, next, before, "上传" + ("wechat".equals(normalizedKind) ? "微信群" : "QQ群") + "二维码");
        return ApiResponse.ok(Map.of("kind", normalizedKind, "url", stored.url(), "config", next));
    }

    @GetMapping("/messages")
    public ApiResponse<Map<String, Object>> stationMessages(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", required = false, defaultValue = "20") Integer pageSize
    ) {
        LambdaQueryWrapper<StationMessageEntity> query = new LambdaQueryWrapper<StationMessageEntity>()
            .eq(StationMessageEntity::getDeleted, 0);
        String search = keyword == null ? "" : keyword.trim().toLowerCase();
        if (!search.isBlank()) {
            String like = "%" + search + "%";
            query.and(wrapper -> wrapper
                .apply("lower(title) like {0}", like)
                .or()
                .apply("lower(content) like {0}", like)
                .or()
                .apply("lower(coalesce(audience, '')) like {0}", like));
        }
        long total = stationMessages.selectCount(query);
        PageParams paging = pageParams(page, pageSize);
        return ApiResponse.ok(paged(stationMessages.selectList(query.orderByDesc(StationMessageEntity::getPublishedAt).last(limitOffset(paging)))
            .stream()
            .map(message -> {
                long readCount = stationMessageReads.selectCount(new LambdaQueryWrapper<com.openimage.domain.entity.StationMessageReadEntity>()
                    .eq(com.openimage.domain.entity.StationMessageReadEntity::getDeleted, 0)
                    .eq(com.openimage.domain.entity.StationMessageReadEntity::getMessageId, message.getId()));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", message.getId());
                row.put("title", message.getTitle());
                row.put("content", message.getContent());
                row.put("audience", message.getAudience());
                row.put("enabled", message.getEnabled());
                row.put("published_by", message.getPublishedBy());
                row.put("published_at", message.getPublishedAt());
                row.put("expires_at", message.getExpiresAt());
                row.put("read_count", readCount);
                return row;
            })
            .toList(), total, paging));
    }

    @PostMapping("/messages")
    public ApiResponse<StationMessageEntity> publishStationMessage(@AuthenticationPrincipal CurrentUser currentUser, @Valid @RequestBody StationMessageCreateRequest request) {
        StationMessageEntity message = new StationMessageEntity();
        message.setId("sm_" + uuid());
        message.setTitle(request.title());
        message.setContent(request.content());
        message.setAudience(blank(request.audience()) ? "all" : request.audience());
        message.setPublishedBy(currentUser == null ? null : currentUser.id());
        message.setPublishedAt(OffsetDateTime.now());
        message.setExpiresAt(request.expiresAt());
        message.setEnabled(request.enabled() == null || request.enabled());
        message.setDeleted(0);
        stationMessages.insert(message);
        audit(currentUser, "station_message_publish", "station_message", message.getId(), "发布站内信：" + message.getTitle(), null, auditMap("title", message.getTitle(), "audience", message.getAudience(), "enabled", message.getEnabled()));
        return ApiResponse.ok(message);
    }

    @GetMapping("/image-configs")
    public ApiResponse<List<ImageTypeConfigEntity>> imageConfigs() {
        return ApiResponse.ok(imageConfigs.selectList(new LambdaQueryWrapper<ImageTypeConfigEntity>().eq(ImageTypeConfigEntity::getDeleted, 0).orderByAsc(ImageTypeConfigEntity::getSortOrder)));
    }

    @PostMapping("/image-configs/{id}")
    public ApiResponse<ImageTypeConfigEntity> updateImageConfig(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable String id, @RequestBody ImageConfigUpdateRequest request) {
        ImageTypeConfigEntity config = imageConfigs.selectById(id);
        if (config == null || config.getDeleted() != 0) {
            throw new IllegalArgumentException("Image config not found");
        }
        Map<String, Object> before = auditMap(
            "enabled", config.getEnabled(),
            "credits_cost", config.getCreditsCost(),
            "default_model", config.getDefaultModel(),
            "default_size", config.getDefaultSize()
        );
        if (request.enabled() != null) {
            config.setEnabled(request.enabled());
        }
        if (request.creditsCost() != null) {
            config.setCreditsCost(request.creditsCost());
        }
        if (request.defaultModel() != null) {
            config.setDefaultModel(request.defaultModel());
        }
        if (request.fallbackModel() != null) {
            config.setFallbackModel(request.fallbackModel());
        }
        if (request.defaultSize() != null && !request.defaultSize().isBlank()) {
            config.setDefaultSize(AiImageSizes.normalizeListingOutputSize(request.defaultSize()));
        }
        if (request.requiredFields() != null) {
            config.setRequiredFields(request.requiredFields());
        }
        config.setUpdatedAt(OffsetDateTime.now());
        imageConfigs.updateById(config);
        audit(currentUser, "image_config_update", "image_config", config.getId(), "修改图片类型配置 " + config.getImageName(), before, auditMap("enabled", config.getEnabled(), "credits_cost", config.getCreditsCost(), "default_model", config.getDefaultModel(), "default_size", config.getDefaultSize()));
        return ApiResponse.ok(config);
    }

    @GetMapping("/prompts")
    public ApiResponse<List<PromptTemplateEntity>> prompts() {
        return ApiResponse.ok(prompts.selectList(new LambdaQueryWrapper<PromptTemplateEntity>().eq(PromptTemplateEntity::getDeleted, 0).orderByDesc(PromptTemplateEntity::getUpdatedAt)));
    }

    @PostMapping("/prompts")
    public ApiResponse<PromptTemplateEntity> createPrompt(@AuthenticationPrincipal CurrentUser currentUser, @RequestBody PromptCreateRequest request) {
        PromptTemplateEntity prompt = new PromptTemplateEntity();
        prompt.setId("prompt_" + uuid());
        prompt.setName(request.name());
        prompt.setScene(request.scene());
        prompt.setVersion(1);
        prompt.setContent(request.content());
        prompt.setEnabled(true);
        prompt.setCreatedAt(OffsetDateTime.now());
        prompt.setUpdatedAt(OffsetDateTime.now());
        prompt.setDeleted(0);
        prompts.insert(prompt);
        audit(currentUser, "prompt_template_create", "prompt_template", prompt.getId(), "创建 Prompt 模板 " + prompt.getName(), null, auditMap("scene", prompt.getScene(), "version", prompt.getVersion(), "enabled", prompt.getEnabled()));
        return ApiResponse.ok(prompt);
    }

    @PostMapping("/prompts/{id}")
    public ApiResponse<PromptTemplateEntity> updatePrompt(@AuthenticationPrincipal CurrentUser currentUser, @PathVariable String id, @RequestBody PromptUpdateRequest request) {
        PromptTemplateEntity prompt = prompts.selectById(id);
        if (prompt == null || prompt.getDeleted() != 0) {
            throw new IllegalArgumentException("Prompt not found");
        }
        Map<String, Object> before = auditMap("name", prompt.getName(), "scene", prompt.getScene(), "version", prompt.getVersion(), "enabled", prompt.getEnabled());
        if (request.name() != null && !request.name().isBlank()) {
            prompt.setName(request.name());
        }
        if (request.scene() != null && !request.scene().isBlank()) {
            prompt.setScene(request.scene());
        }
        if (request.content() != null && !request.content().isBlank() && !request.content().equals(prompt.getContent())) {
            prompt.setContent(request.content());
            prompt.setVersion((prompt.getVersion() == null ? 1 : prompt.getVersion()) + 1);
        }
        if (request.enabled() != null) {
            prompt.setEnabled(request.enabled());
        }
        prompt.setUpdatedAt(OffsetDateTime.now());
        prompts.updateById(prompt);
        audit(currentUser, "prompt_template_update", "prompt_template", prompt.getId(), "修改 Prompt 模板 " + prompt.getName(), before, auditMap("name", prompt.getName(), "scene", prompt.getScene(), "version", prompt.getVersion(), "enabled", prompt.getEnabled()));
        return ApiResponse.ok(prompt);
    }

    @GetMapping("/audit-logs")
    public ApiResponse<Map<String, Object>> auditLogs(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", required = false, defaultValue = "20") Integer pageSize
    ) {
        LambdaQueryWrapper<AdminAuditLogEntity> query = new LambdaQueryWrapper<AdminAuditLogEntity>()
            .eq(AdminAuditLogEntity::getDeleted, 0);
        String search = keyword == null ? "" : keyword.trim().toLowerCase();
        if (!search.isBlank()) {
            String like = "%" + search + "%";
            query.and(wrapper -> wrapper
                .apply("lower(action) like {0}", like)
                .or()
                .apply("lower(target_type) like {0}", like)
                .or()
                .apply("lower(target_id) like {0}", like)
                .or()
                .apply("lower(coalesce(summary, '')) like {0}", like)
                .or()
                .apply("lower(coalesce(admin_id, '')) like {0}", like));
        }
        long total = auditLogs.selectCount(query);
        PageParams paging = pageParams(page, pageSize);
        return ApiResponse.ok(paged(auditLogs.selectList(query
                .eq(AdminAuditLogEntity::getDeleted, 0)
                .orderByDesc(AdminAuditLogEntity::getCreatedAt)
                .last(limitOffset(paging)))
            .stream()
            .map(log -> {
                UserEntity admin = log.getAdminId() == null ? null : users.selectById(log.getAdminId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", log.getId());
                row.put("admin_id", log.getAdminId());
                row.put("admin_email_masked", admin == null ? "-" : mask(admin.getEmail()));
                row.put("action", log.getAction());
                row.put("target_type", log.getTargetType());
                row.put("target_id", log.getTargetId());
                row.put("summary", log.getSummary());
                row.put("before_json", log.getBeforeJson());
                row.put("after_json", log.getAfterJson());
                row.put("created_at", log.getCreatedAt());
                return row;
            })
            .toList(), total, paging));
    }

    @GetMapping("/costs")
    public ApiResponse<Map<String, Object>> costs(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "provider", required = false) String provider,
        @RequestParam(value = "image_type", required = false) String imageType,
        @RequestParam(value = "media_type", required = false) String mediaType,
        @RequestParam(value = "user_keyword", required = false) String userKeyword,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to
    ) {
        AdminTaskFilters filters = taskFilters(keyword, status, provider, imageType, userKeyword, from, to, mediaType);
        List<ImageTaskEntity> imageRows = tasks.selectList(taskQuery(filters));
        List<VideoTaskEntity> videoRows = videoTasks.selectList(videoTaskQuery(filters));
        BigDecimal imageCost = imageRows.stream().map(this::taskActualCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal videoCost = videoRows.stream().map(this::videoActualCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCost = imageCost.add(videoCost);
        LocalDate today = BusinessTime.today();
        BigDecimal todayImageCost = imageRows.stream()
            .filter(task -> today.equals(BusinessTime.date(task.getCreatedAt())))
            .map(this::taskActualCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal todayVideoCost = videoRows.stream()
            .filter(task -> today.equals(BusinessTime.date(task.getCreatedAt())))
            .map(this::videoActualCost)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        int imageCredits = imageRows.stream().mapToInt(task -> task.getCreditsCost() == null ? 0 : task.getCreditsCost()).sum();
        int videoCredits = videoRows.stream().mapToInt(task -> task.getCreditsCost() == null ? 0 : task.getCreditsCost()).sum();
        int netVideoCredits = videoRows.stream()
            .filter(task -> task.getRefundedAt() == null)
            .mapToInt(task -> task.getCreditsCost() == null ? 0 : task.getCreditsCost())
            .sum();
        long failedImages = imageRows.stream().filter(task -> "failed".equals(task.getStatus())).count();
        long failedVideos = videoRows.stream().filter(task -> isFailedVideoStatus(task.getStatus())).count();
        int taskCount = imageRows.size() + videoRows.size();
        BigDecimal averageImageCost = averageCost(imageCost, imageRows.size());
        BigDecimal averageVideoCost = averageCost(videoCost, videoRows.size());
        BigDecimal averageTaskCost = averageCost(totalCost, taskCount);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(imageRows.stream().collect(Collectors.groupingBy(task -> (blank(task.getProvider()) ? "unknown" : task.getProvider()) + "|" + task.getImageType(), LinkedHashMap::new, Collectors.toList()))
            .entrySet()
            .stream()
            .map(entry -> {
                List<ImageTaskEntity> groupTasks = entry.getValue();
                BigDecimal groupCost = groupTasks.stream().map(this::taskActualCost).reduce(BigDecimal.ZERO, BigDecimal::add);
                long groupFailed = groupTasks.stream().filter(task -> "failed".equals(task.getStatus())).count();
                String[] keys = entry.getKey().split("\\|", 2);
                return Map.<String, Object>of(
                    "media_type", "image",
                    "provider", keys[0],
                    "task_type", keys.length > 1 ? keys[1] : "unknown",
                    "calls", groupTasks.size(),
                    "cost", groupCost,
                    "failure_rate", groupTasks.isEmpty() ? 0 : BigDecimal.valueOf(groupFailed * 100.0 / groupTasks.size()).setScale(2, RoundingMode.HALF_UP)
                );
            })
            .toList());
        rows.addAll(videoRows.stream().collect(Collectors.groupingBy(task -> (blank(task.getProvider()) ? "unknown" : task.getProvider()) + "|" + videoTaskType(task), LinkedHashMap::new, Collectors.toList()))
            .entrySet()
            .stream()
            .map(entry -> {
                List<VideoTaskEntity> groupTasks = entry.getValue();
                BigDecimal groupCost = groupTasks.stream().map(this::videoActualCost).reduce(BigDecimal.ZERO, BigDecimal::add);
                long groupFailed = groupTasks.stream().filter(task -> isFailedVideoStatus(task.getStatus())).count();
                String[] keys = entry.getKey().split("\\|", 2);
                return Map.<String, Object>of(
                    "media_type", "video",
                    "provider", keys[0],
                    "task_type", keys.length > 1 ? keys[1] : "unknown",
                    "calls", groupTasks.size(),
                    "cost", groupCost,
                    "failure_rate", groupTasks.isEmpty() ? 0 : BigDecimal.valueOf(groupFailed * 100.0 / groupTasks.size()).setScale(2, RoundingMode.HALF_UP)
                );
            })
            .toList());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("today_cost", todayImageCost.add(todayVideoCost));
        payload.put("today_image_cost", todayImageCost);
        payload.put("today_video_cost", todayVideoCost);
        payload.put("total_cost", totalCost);
        payload.put("image_cost", imageCost);
        payload.put("video_cost", videoCost);
        payload.put("average_image_cost", averageImageCost);
        payload.put("average_video_cost", averageVideoCost);
        payload.put("average_task_cost", averageTaskCost);
        payload.put("failure_rate", taskCount == 0 ? 0 : BigDecimal.valueOf((failedImages + failedVideos) * 100.0 / taskCount).setScale(2, RoundingMode.HALF_UP));
        payload.put("image_failure_rate", imageRows.isEmpty() ? 0 : BigDecimal.valueOf(failedImages * 100.0 / imageRows.size()).setScale(2, RoundingMode.HALF_UP));
        payload.put("video_failure_rate", videoRows.isEmpty() ? 0 : BigDecimal.valueOf(failedVideos * 100.0 / videoRows.size()).setScale(2, RoundingMode.HALF_UP));
        payload.put("credits_cost", imageCredits + videoCredits);
        payload.put("net_video_credits", netVideoCredits);
        payload.put("task_count", taskCount);
        payload.put("image_task_count", imageRows.size());
        payload.put("video_task_count", videoRows.size());
        payload.put("cost_policy", imageCostPolicy());
        payload.put("rows", rows);
        return ApiResponse.ok(payload);
    }

    private BigDecimal averageCost(BigDecimal cost, int count) {
        return count == 0 ? BigDecimal.ZERO : cost.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal videoActualCost(VideoTaskEntity task) {
        return task == null || task.getActualCost() == null ? BigDecimal.ZERO : task.getActualCost();
    }

    private String videoTaskType(VideoTaskEntity task) {
        if (task == null) {
            return "unknown";
        }
        if (!blank(task.getModel())) {
            return task.getModel();
        }
        return blank(task.getMode()) ? "video" : task.getMode();
    }

    private BigDecimal taskActualCost(ImageTaskEntity task) {
        if (task == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal attemptCost = taskAttemptActualCost(task.getProviderAttempts());
        if (attemptCost != null) {
            return attemptCost;
        }
        if (!"success".equalsIgnoreCase(task.getStatus())) {
            return BigDecimal.ZERO;
        }
        String provider = task.getProvider() == null ? "" : task.getProvider().toLowerCase();
        boolean apimart = provider.contains("aishuch") || provider.contains("apimart");
        if (!apimart && task.getActualCost() != null && task.getActualCost().signum() > 0) {
            return task.getActualCost();
        }
        return AiImageCostCalculator.cost(
            task.getProvider(),
            "",
            task.getModel(),
            taskCostSize(task),
            task.getActualCost()
        );
    }

    private Map<String, Object> taskCostDetails(ImageTaskEntity task) {
        Map<String, Object> details = taskAttemptCostDetails(task == null ? null : task.getProviderAttempts());
        if (!details.isEmpty()) {
            return details;
        }
        if (task == null || !"success".equalsIgnoreCase(task.getStatus())) {
            return costDetails("not_charged", BigDecimal.ZERO, null, "", null);
        }
        String provider = task == null || task.getProvider() == null ? "" : task.getProvider().toLowerCase();
        if (provider.contains("aishuch") || provider.contains("apimart")) {
            BigDecimal credits = AiImageCostCalculator.apimartTierCredits(taskCostSize(task));
            return costDetails(
                "historical_tier_fallback",
                AiImageCostCalculator.apimartCreditsToCny(credits),
                credits,
                "APIMART_CREDIT",
                AiImageCostCalculator.apimartCreditCnyRate()
            );
        }
        if (provider.contains("rua")) {
            BigDecimal storedCost = task.getActualCost();
            if (storedCost != null && storedCost.signum() > 0) {
                return costDetails("stored_actual_cost", storedCost, storedCost, "CNY", BigDecimal.ONE);
            }
            return costDetails("fixed_price", new BigDecimal("0.020000"), new BigDecimal("0.020000"), "CNY", BigDecimal.ONE);
        }
        return costDetails("provider_price_table", taskActualCost(task), null, "", null);
    }

    private Map<String, Object> imageCostPolicy() {
        Map<String, Object> apimartTiers = new LinkedHashMap<>();
        apimartTiers.put("1k", AiImageCostCalculator.apimartTierCredits("1k"));
        apimartTiers.put("2k", AiImageCostCalculator.apimartTierCredits("2k"));
        apimartTiers.put("4k", AiImageCostCalculator.apimartTierCredits("4k"));
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("currency", "CNY");
        policy.put("local_worker_per_image_cny", AiRuntimeConfigService.LOCAL_IMAGE_COST_CNY);
        policy.put("rua_chat_per_image_cny", new BigDecimal("0.020000"));
        policy.put("apimart_credit_cny_rate", AiImageCostCalculator.apimartCreditCnyRate());
        policy.put("apimart_usd_cny_rate", AiImageCostCalculator.apimartUsdCnyRate());
        policy.put("apimart_tier_credits", apimartTiers);
        policy.put("apimart_source_priority", List.of("upstream_credits_cost", "upstream_cost", "tier_fallback"));
        return policy;
    }

    private BigDecimal taskAttemptActualCost(String providerAttempts) {
        Object value = taskAttemptCostDetails(providerAttempts).get("actual_cost_cny");
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return null;
    }

    private Map<String, Object> taskAttemptCostDetails(String providerAttempts) {
        if (providerAttempts == null || providerAttempts.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode nodes = json.readTree(providerAttempts);
            if (!nodes.isArray()) {
                return Map.of();
            }
            for (JsonNode item : nodes) {
                JsonNode success = item.get("success");
                if (success == null || !success.asBoolean(false)) {
                    continue;
                }
                BigDecimal actualCost = decimalNode(item, "actualCostCny", "actual_cost_cny");
                if (actualCost == null) {
                    continue;
                }
                return costDetails(
                    textNode(item, "costSource", "cost_source"),
                    actualCost,
                    decimalNode(item, "upstreamCost", "upstream_cost"),
                    textNode(item, "upstreamCostUnit", "upstream_cost_unit"),
                    decimalNode(item, "conversionRate", "conversion_rate")
                );
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return Map.of();
    }

    private Map<String, Object> costDetails(
        String source,
        BigDecimal actualCostCny,
        BigDecimal upstreamCost,
        String upstreamCostUnit,
        BigDecimal conversionRate
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currency", "CNY");
        details.put("source", blank(source) ? "provider_price_table" : source);
        details.put("actual_cost_cny", actualCostCny == null ? BigDecimal.ZERO : actualCostCny);
        if (upstreamCost != null) {
            details.put("upstream_cost", upstreamCost);
        }
        if (!blank(upstreamCostUnit)) {
            details.put("upstream_cost_unit", upstreamCostUnit);
        }
        if (conversionRate != null) {
            details.put("conversion_rate", conversionRate);
        }
        return details;
    }

    private BigDecimal decimalNode(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull() || value.isMissingNode()) {
                continue;
            }
            String text = value.isNumber() || value.isTextual() ? value.asText("").trim() : "";
            if (text.isBlank()) {
                continue;
            }
            try {
                BigDecimal decimal = new BigDecimal(text);
                if (decimal.signum() >= 0) {
                    return decimal;
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed provider metadata.
            }
        }
        return null;
    }

    private String taskCostSize(ImageTaskEntity task) {
        String fromAttempts = taskCostSizeFromAttempts(task.getProviderAttempts());
        if (!fromAttempts.isBlank()) {
            return fromAttempts;
        }
        String fromPrompt = taskCostSizeFromPrompt(task.getPromptSnapshot());
        return fromPrompt.isBlank() ? "1024x1024" : fromPrompt;
    }

    private String taskCostSizeFromPrompt(String promptSnapshot) {
        if (promptSnapshot == null || promptSnapshot.isBlank()) {
            return "";
        }
        try {
            JsonNode node = json.readTree(promptSnapshot);
            for (String field : List.of("generationSize", "generation_size", "size", "target_size")) {
                JsonNode value = node.get(field);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private String taskCostSizeFromAttempts(String providerAttempts) {
        if (providerAttempts == null || providerAttempts.isBlank()) {
            return "";
        }
        try {
            JsonNode nodes = json.readTree(providerAttempts);
            if (!nodes.isArray()) {
                return "";
            }
            String fallback = "";
            for (JsonNode item : nodes) {
                String returned = textNode(item, "returnedSize", "returned_size");
                String requested = textNode(item, "requestSize", "request_size");
                if (fallback.isBlank()) {
                    fallback = !returned.isBlank() ? returned : requested;
                }
                JsonNode success = item.get("success");
                if (success != null && success.asBoolean(false)) {
                    return !returned.isBlank() ? returned : requested;
                }
            }
            return fallback;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String textNode(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private void audit(CurrentUser admin, String action, String targetType, String targetId, String summary, Object before, Object after) {
        AdminAuditLogEntity log = new AdminAuditLogEntity();
        log.setId("audit_" + uuid());
        log.setAdminId(admin == null ? null : admin.id());
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setSummary(summary == null ? "" : (summary.length() > 500 ? summary.substring(0, 500) : summary));
        log.setBeforeJson(toJson(before));
        log.setAfterJson(toJson(after));
        log.setCreatedAt(OffsetDateTime.now());
        log.setDeleted(0);
        auditLogs.insert(log);
    }

    private Map<String, Object> riskControlConfigValue() {
        SystemSettingEntity setting = settings.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
            .eq(SystemSettingEntity::getDeleted, 0)
            .eq(SystemSettingEntity::getSettingKey, "risk_control"));
        Map<String, Object> merged = defaultRiskControlConfig();
        if (setting == null || setting.getSettingValue() == null || setting.getSettingValue().isBlank()) {
            return merged;
        }
        try {
            Object value = json.readValue(setting.getSettingValue(), Map.class);
            if (value instanceof Map<?, ?> map) {
                map.forEach((key, item) -> merged.put(String.valueOf(key), item));
            }
        } catch (Exception ignored) {
            return merged;
        }
        merged.put("keywords", riskKeywordMap(merged.get("keywords")));
        return merged;
    }

    private void saveRiskControlConfig(CurrentUser currentUser, Map<String, Object> value, Map<String, Object> before, String summary) {
        saveRiskControlConfig(currentUser, value, before, summary, "risk_keywords_update");
    }

    private void saveRiskControlConfig(CurrentUser currentUser, Map<String, Object> value, Map<String, Object> before, String summary, String action) {
        SystemSettingEntity setting = settings.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
            .eq(SystemSettingEntity::getDeleted, 0)
            .eq(SystemSettingEntity::getSettingKey, "risk_control"));
        OffsetDateTime now = OffsetDateTime.now();
        try {
            boolean isNewSetting = setting == null;
            if (isNewSetting) {
                setting = new SystemSettingEntity();
                setting.setId("risk-control");
                setting.setSettingKey("risk_control");
                setting.setDescription("风控关键词与封禁阈值");
                setting.setCreatedAt(now);
                setting.setDeleted(0);
            }
            setting.setSettingValue(json.writeValueAsString(value));
            setting.setUpdatedAt(now);
            if (isNewSetting) {
                settings.insert(setting);
            } else {
                settings.updateById(setting);
            }
            audit(currentUser, action, "system_setting", "risk_control", summary, before, value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("风控词库保存失败");
        }
    }

    private Map<String, Object> defaultRiskControlConfig() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("enabled", true);
        row.put("ban_threshold", 5);
        row.put("block_levels", List.of("high", "medium"));
        row.put("warn_levels", List.of("low"));
        Map<String, List<String>> keywords = new LinkedHashMap<>();
        keywords.put("high", List.of("色情", "成人内容", "裸露", "全裸", "裸体", "露点", "性爱", "性交", "性行为", "援交", "卖淫", "未成年裸露", "儿童色情", "幼女裸", "萝莉裸", "血腥", "肢解", "尸体", "自残", "自杀", "虐待", "毒品", "吸毒", "贩毒", "大麻", "冰毒", "海洛因", "可卡因", "枪支", "手枪", "步枪", "炸弹", "爆炸物", "制爆", "杀人", "身份证", "护照", "银行卡", "信用卡", "验证码", "密码", "私钥", "纳粹", "恐怖主义", "极端组织"));
        keywords.put("medium", List.of("仿牌", "高仿", "复刻", "原单", "尾单", "莆田", "山寨", "假冒", "侵权", "logo复刻", "商标复刻", "大牌同款", "明星真人", "名人脸", "网红真人", "换脸", "AI换脸", "deepfake", "迪士尼", "漫威", "宝可梦", "皮卡丘", "Hello Kitty", "奥特曼", "Nike", "Adidas", "Gucci", "LV", "Chanel", "Dior", "Prada", "Hermes", "政治敏感", "领导人", "宗教攻击", "医疗功效", "治愈", "根治", "减肥神药", "壮阳"));
        keywords.put("low", List.of("性感", "泳装", "内衣", "比基尼", "儿童", "小孩", "宝宝", "刀", "军事", "战争", "品牌同款", "大牌风", "奢侈品风"));
        row.put("keywords", keywords);
        return row;
    }

    private Map<String, List<String>> riskKeywordMap(Object value) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Map<?, ?> source = value instanceof Map<?, ?> map ? map : Map.of();
        for (String level : List.of("high", "medium", "low")) {
            Object item = source.get(level);
            List<String> rows = new ArrayList<>();
            if (item instanceof Iterable<?> iterable) {
                for (Object entry : iterable) {
                    String keyword = entry == null ? "" : String.valueOf(entry).trim();
                    if (!keyword.isBlank() && rows.stream().noneMatch(existing -> existing.equalsIgnoreCase(keyword))) {
                        rows.add(keyword);
                    }
                }
            }
            result.put(level, rows);
        }
        return result;
    }

    private Map<String, Object> deepCopyMap(Map<String, Object> value) {
        try {
            return json.readValue(json.writeValueAsString(value), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return new LinkedHashMap<>(value);
        }
    }

    private String normalizeRiskLevel(String value) {
        String level = value == null ? "" : value.trim().toLowerCase();
        if (!List.of("high", "medium", "low").contains(level)) {
            throw new IllegalArgumentException("风控等级必须是 high / medium / low");
        }
        return level;
    }

    private List<String> normalizeRiskLevelList(List<String> values, String label) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String level = normalizeRiskLevel(value);
            if (!result.contains(level)) {
                result.add(level);
            }
        }
        return result;
    }

    private void ensureNoRiskLevelOverlap(Map<String, Object> config) {
        Set<String> blockLevels = new HashSet<>(normalizeRiskLevelList(castStringList(config.get("block_levels")), "拦截等级"));
        Set<String> warnLevels = new HashSet<>(normalizeRiskLevelList(castStringList(config.get("warn_levels")), "提醒等级"));
        blockLevels.retainAll(warnLevels);
        if (!blockLevels.isEmpty()) {
            throw new IllegalArgumentException("同一风控等级不能同时设置为拦截和提醒：" + String.join(", ", blockLevels));
        }
    }

    private List<String> castStringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> rows = new ArrayList<>();
        for (Object item : iterable) {
            if (item != null) {
                rows.add(String.valueOf(item));
            }
        }
        return rows;
    }

    private String cleanKeyword(String value) {
        String keyword = value == null ? "" : value.trim();
        if (keyword.isBlank()) {
            throw new IllegalArgumentException("请输入风控关键词");
        }
        if (keyword.length() > 120) {
            throw new IllegalArgumentException("风控关键词不能超过 120 个字符");
        }
        return keyword;
    }

    private Map<String, Object> benefitConfigValue() {
        SystemSettingEntity setting = settings.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
            .eq(SystemSettingEntity::getDeleted, 0)
            .eq(SystemSettingEntity::getSettingKey, "benefit_onboarding"));
        Map<String, Object> merged = defaultBenefitConfig();
        if (setting == null || setting.getSettingValue() == null || setting.getSettingValue().isBlank()) {
            return merged;
        }
        try {
            Object value = json.readValue(setting.getSettingValue(), Map.class);
            if (value instanceof Map<?, ?> map) {
                map.forEach((key, item) -> merged.put(String.valueOf(key), item));
            }
        } catch (Exception ignored) {
            return merged;
        }
        return merged;
    }

    private void saveBenefitConfig(CurrentUser currentUser, Map<String, Object> value, Map<String, Object> before, String summary) {
        SystemSettingEntity setting = settings.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
            .eq(SystemSettingEntity::getDeleted, 0)
            .eq(SystemSettingEntity::getSettingKey, "benefit_onboarding"));
        OffsetDateTime now = OffsetDateTime.now();
        try {
            boolean isNewSetting = setting == null;
            if (isNewSetting) {
                setting = new SystemSettingEntity();
                setting.setId("setting_" + uuid());
                setting.setSettingKey("benefit_onboarding");
                setting.setDescription("社群二维码和积分兑换码引导配置");
                setting.setCreatedAt(now);
                setting.setDeleted(0);
            }
            setting.setSettingValue(json.writeValueAsString(value));
            setting.setUpdatedAt(now);
            if (isNewSetting) {
                settings.insert(setting);
            } else {
                settings.updateById(setting);
            }
            audit(currentUser, "benefit_config_update", "system_setting", "benefit_onboarding", summary, before, value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("兑换码配置保存失败");
        }
    }

    private Map<String, Object> defaultBenefitConfig() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("enabled", true);
        row.put("title", "新人积分兑换码");
        row.put("description", "加入 QQ 群或微信群，联系管理员领取兑换码；到积分页兑换后即可用积分体验生成流程。");
        row.put("qq_group", "619616946");
        row.put("qq_link", "https://qm.qq.com/q/Hks6HegucA");
        row.put("wechat_group", "");
        row.put("qq_qr_url", "");
        row.put("wechat_qr_url", "");
        row.put("redeem_tutorial", "1. 加入社群 2. 联系管理员领取兑换码 3. 到积分充值页兑换积分 4. 回到工作台生成图片");
        row.put("free_highlight_label", "兑换码会直接兑换成积分，生成仍按积分扣费");
        return row;
    }

    private String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return trimmed;
            }
            return "{\"value\":\"" + trimmed.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        }
        try {
            return json.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private Map<String, Object> auditMap(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (pairs == null) {
            return row;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }

    private List<Map<String, Object>> dashboardTrend(AdminTaskFilters filters) {
        LocalDate today = BusinessTime.today();
        LocalDate end = filters.to() == null ? today : BusinessTime.date(filters.to());
        LocalDate start = filters.from() == null ? end.minusDays(6) : BusinessTime.date(filters.from());
        if (end.isBefore(start)) {
            end = start;
        }
        if (ChronoUnit.DAYS.between(start, end) > 60) {
            start = end.minusDays(60);
        }
        Map<LocalDate, TrendAccumulator> days = new LinkedHashMap<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            days.put(date, new TrendAccumulator(date));
        }
        OffsetDateTime since = BusinessTime.startOfDay(start);
        OffsetDateTime until = BusinessTime.startOfDay(end.plusDays(1));

        LambdaQueryWrapper<RechargeOrderEntity> orderQuery = new LambdaQueryWrapper<RechargeOrderEntity>()
            .eq(RechargeOrderEntity::getDeleted, 0)
            .eq(RechargeOrderEntity::getStatus, "paid")
            .ge(RechargeOrderEntity::getUpdatedAt, since)
            .lt(RechargeOrderEntity::getUpdatedAt, until);
        if (filters.userFilterActive()) {
            if (filters.userIds().isEmpty()) {
                orderQuery.eq(RechargeOrderEntity::getUserId, "__no_user__");
            } else {
                orderQuery.in(RechargeOrderEntity::getUserId, filters.userIds());
            }
        }
        orders.selectList(orderQuery)
            .forEach(order -> {
                OffsetDateTime time = order.getUpdatedAt() == null ? order.getCreatedAt() : order.getUpdatedAt();
                TrendAccumulator item = days.get(BusinessTime.date(time));
                if (item != null) {
                    item.rechargeCents += order.getAmountCents() == null ? 0 : order.getAmountCents();
                    item.revenueCents += order.getAmountCents() == null ? 0 : order.getAmountCents();
                }
            });

        LambdaQueryWrapper<ImageTaskEntity> trendTaskQuery = taskQuery(filters);
        if (filters.from() == null) {
            trendTaskQuery.ge(ImageTaskEntity::getCreatedAt, since);
        }
        if (filters.to() == null) {
            trendTaskQuery.lt(ImageTaskEntity::getCreatedAt, until);
        }
        tasks.selectList(trendTaskQuery)
            .forEach(task -> {
                TrendAccumulator item = days.get(BusinessTime.date(task.getCreatedAt()));
                if (item != null) {
                    BigDecimal taskCost = taskActualCost(task);
                    item.usage += 1;
                    item.imageUsage += 1;
                    item.actualCost = item.actualCost.add(taskCost);
                    item.imageCost = item.imageCost.add(taskCost);
                    if ("failed".equals(task.getStatus())) {
                        item.failures += 1;
                        item.imageFailures += 1;
                    }
                }
            });

        LambdaQueryWrapper<VideoTaskEntity> trendVideoQuery = videoTaskQuery(filters);
        if (filters.from() == null) {
            trendVideoQuery.ge(VideoTaskEntity::getCreatedAt, since);
        }
        if (filters.to() == null) {
            trendVideoQuery.lt(VideoTaskEntity::getCreatedAt, until);
        }
        videoTasks.selectList(trendVideoQuery)
            .forEach(task -> {
                TrendAccumulator item = days.get(BusinessTime.date(task.getCreatedAt()));
                if (item != null) {
                    BigDecimal taskCost = videoActualCost(task);
                    item.usage += 1;
                    item.videoUsage += 1;
                    item.actualCost = item.actualCost.add(taskCost);
                    item.videoCost = item.videoCost.add(taskCost);
                    if (isFailedVideoStatus(task.getStatus())) {
                        item.failures += 1;
                        item.videoFailures += 1;
                    }
                }
            });

        LambdaQueryWrapper<UserEntity> userQuery = new LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getDeleted, 0)
            .ge(UserEntity::getCreatedAt, since)
            .lt(UserEntity::getCreatedAt, until);
        if (filters.userFilterActive()) {
            if (filters.userIds().isEmpty()) {
                userQuery.eq(UserEntity::getId, "__no_user__");
            } else {
                userQuery.in(UserEntity::getId, filters.userIds());
            }
        }
        users.selectList(userQuery)
            .forEach(user -> {
                TrendAccumulator item = days.get(BusinessTime.date(user.getCreatedAt()));
                if (item != null) {
                    item.users += 1;
                }
            });

        LambdaQueryWrapper<CreditLedgerEntity> ledgerQuery = new LambdaQueryWrapper<CreditLedgerEntity>()
            .eq(CreditLedgerEntity::getDeleted, 0)
            .ge(CreditLedgerEntity::getCreatedAt, since)
            .lt(CreditLedgerEntity::getCreatedAt, until);
        if (filters.userFilterActive()) {
            if (filters.userIds().isEmpty()) {
                ledgerQuery.eq(CreditLedgerEntity::getUserId, "__no_user__");
            } else {
                ledgerQuery.in(CreditLedgerEntity::getUserId, filters.userIds());
            }
        }
        ledgers.selectList(ledgerQuery)
            .forEach(ledger -> {
                TrendAccumulator item = days.get(BusinessTime.date(ledger.getCreatedAt()));
                if (item != null && ledger.getChangeAmount() != null && ledger.getChangeAmount() < 0) {
                    item.credits += Math.abs(ledger.getChangeAmount());
                }
            });

        return days.values().stream().map(TrendAccumulator::toMap).toList();
    }

    private AdminTaskFilters taskFilters(String keyword, String status, String provider, String imageType, String userKeyword, String from, String to) {
        return taskFilters(keyword, status, provider, imageType, userKeyword, from, to, null);
    }

    private AdminTaskFilters taskFilters(
        String keyword,
        String status,
        String provider,
        String imageType,
        String userKeyword,
        String from,
        String to,
        String mediaType
    ) {
        String normalizedUserKeyword = userKeyword == null ? "" : userKeyword.trim().toLowerCase();
        boolean userFilterActive = !normalizedUserKeyword.isBlank();
        return new AdminTaskFilters(
            keyword == null ? "" : keyword.trim().toLowerCase(),
            splitCsv(status),
            splitCsv(provider),
            splitCsv(imageType),
            splitCsv(mediaType),
            userFilterActive ? matchingUserIds(normalizedUserKeyword) : Set.of(),
            userFilterActive,
            parseDateBoundary(from, false),
            parseDateBoundary(to, true)
        );
    }

    private LambdaQueryWrapper<ImageTaskEntity> taskQuery(AdminTaskFilters filters) {
        LambdaQueryWrapper<ImageTaskEntity> query = new LambdaQueryWrapper<ImageTaskEntity>()
            .eq(ImageTaskEntity::getDeleted, 0);
        if (!includesMedia(filters, "image")) {
            query.eq(ImageTaskEntity::getId, "__no_image_task__");
        }
        if (!filters.statuses().isEmpty()) {
            query.in(ImageTaskEntity::getStatus, imageStatuses(filters.statuses()));
        }
        if (!filters.providers().isEmpty()) {
            query.in(ImageTaskEntity::getProvider, filters.providers());
        }
        if (!filters.imageTypes().isEmpty()) {
            query.in(ImageTaskEntity::getImageType, filters.imageTypes());
        }
        if (filters.from() != null) {
            query.ge(ImageTaskEntity::getCreatedAt, filters.from());
        }
        if (filters.to() != null) {
            query.le(ImageTaskEntity::getCreatedAt, filters.to());
        }
        if (filters.userFilterActive()) {
            if (filters.userIds().isEmpty()) {
                query.eq(ImageTaskEntity::getUserId, "__no_user__");
            } else {
                query.in(ImageTaskEntity::getUserId, filters.userIds());
            }
        }
        if (!filters.keyword().isBlank()) {
            String like = "%" + filters.keyword() + "%";
            query.and(wrapper -> wrapper
                .apply("lower(id) like {0}", like)
                .or()
                .apply("lower(user_id) like {0}", like)
                .or()
                .apply("lower(project_id) like {0}", like)
                .or()
                .apply("lower(image_type) like {0}", like)
                .or()
                .apply("lower(status) like {0}", like)
                .or()
                .apply("lower(coalesce(provider, '')) like {0}", like)
                .or()
                .apply("lower(coalesce(model, '')) like {0}", like));
        }
        return query;
    }

    private LambdaQueryWrapper<VideoTaskEntity> videoTaskQuery(AdminTaskFilters filters) {
        LambdaQueryWrapper<VideoTaskEntity> query = new LambdaQueryWrapper<VideoTaskEntity>()
            .eq(VideoTaskEntity::getDeleted, 0);
        if (!includesMedia(filters, "video") || !filters.imageTypes().isEmpty()) {
            query.eq(VideoTaskEntity::getId, "__no_video_task__");
        }
        if (!filters.statuses().isEmpty()) {
            query.in(VideoTaskEntity::getStatus, videoStatuses(filters.statuses()));
        }
        if (!filters.providers().isEmpty()) {
            query.in(VideoTaskEntity::getProvider, filters.providers());
        }
        if (filters.from() != null) {
            query.ge(VideoTaskEntity::getCreatedAt, filters.from());
        }
        if (filters.to() != null) {
            query.le(VideoTaskEntity::getCreatedAt, filters.to());
        }
        if (filters.userFilterActive()) {
            if (filters.userIds().isEmpty()) {
                query.eq(VideoTaskEntity::getUserId, "__no_user__");
            } else {
                query.in(VideoTaskEntity::getUserId, filters.userIds());
            }
        }
        if (!filters.keyword().isBlank()) {
            String like = "%" + filters.keyword() + "%";
            query.and(wrapper -> wrapper
                .apply("lower(id) like {0}", like)
                .or()
                .apply("lower(user_id) like {0}", like)
                .or()
                .apply("lower(coalesce(title, '')) like {0}", like)
                .or()
                .apply("lower(prompt) like {0}", like)
                .or()
                .apply("lower(status) like {0}", like)
                .or()
                .apply("lower(coalesce(provider, '')) like {0}", like)
                .or()
                .apply("lower(coalesce(model, '')) like {0}", like));
        }
        return query;
    }

    private boolean includesMedia(AdminTaskFilters filters, String mediaType) {
        return filters.mediaTypes().isEmpty()
            || filters.mediaTypes().stream().anyMatch(value -> mediaType.equalsIgnoreCase(value));
    }

    private Set<String> imageStatuses(Set<String> statuses) {
        Set<String> mapped = new LinkedHashSet<>();
        for (String status : statuses) {
            switch (status.toLowerCase()) {
                case "completed" -> mapped.add("success");
                case "submitting", "submitted", "generating", "upscaling", "quality_check" -> mapped.add("running");
                case "timeout", "cancelled" -> mapped.add("failed");
                default -> mapped.add(status);
            }
        }
        return mapped;
    }

    private Set<String> videoStatuses(Set<String> statuses) {
        Set<String> mapped = new LinkedHashSet<>();
        for (String status : statuses) {
            switch (status.toLowerCase()) {
                case "success" -> mapped.add("completed");
                case "running" -> mapped.addAll(Set.of("submitting", "submitted", "generating", "upscaling", "quality_check"));
                case "failed" -> mapped.addAll(Set.of("failed", "timeout", "cancelled"));
                default -> mapped.add(status);
            }
        }
        return mapped;
    }

    private boolean isFailedVideoStatus(String status) {
        return status != null && Set.of("failed", "timeout", "cancelled").contains(status);
    }

    private boolean isActiveVideoStatus(String status) {
        return status != null && Set.of("queued", "submitting", "submitted", "generating", "upscaling", "quality_check").contains(status);
    }

    private Set<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private OffsetDateTime parseDateBoundary(String value, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            if (text.contains("T")) {
                return OffsetDateTime.parse(text);
            }
            LocalDate date = LocalDate.parse(text);
            return endOfDay ? BusinessTime.endOfDay(date) : BusinessTime.startOfDay(date);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Set<String> matchingUserIds(String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return Set.of();
        }
        String like = "%" + normalizedKeyword + "%";
        String githubLike = "%" + normalizedKeyword.replaceFirst("^@", "") + "%";
        return users.selectList(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getDeleted, 0)
                .and(wrapper -> wrapper
                    .apply("lower(id) like {0}", like)
                    .or()
                    .apply("lower(email) like {0}", like)
                    .or()
                    .apply("lower(coalesce(nickname, '')) like {0}", like)
                    .or()
                    .apply("lower(coalesce(github_login, '')) like {0}", githubLike)))
            .stream()
            .map(UserEntity::getId)
            .collect(Collectors.toCollection(HashSet::new));
    }

    private Map<String, UserEntity> userMap(Set<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return users.selectList(new LambdaQueryWrapper<UserEntity>().in(UserEntity::getId, userIds))
            .stream()
            .collect(Collectors.toMap(UserEntity::getId, user -> user, (left, right) -> left));
    }

    private Map<String, Long> countByUser(List<Map<String, Object>> rows) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object userId = row.get("user_id");
            Object itemCount = row.get("item_count");
            if (userId != null && itemCount instanceof Number number) {
                counts.put(String.valueOf(userId), number.longValue());
            }
        }
        return counts;
    }

    private static class TrendAccumulator {
        private final LocalDate date;
        private int rechargeCents;
        private int revenueCents;
        private int usage;
        private int imageUsage;
        private int videoUsage;
        private int users;
        private int credits;
        private int failures;
        private int imageFailures;
        private int videoFailures;
        private BigDecimal actualCost = BigDecimal.ZERO;
        private BigDecimal imageCost = BigDecimal.ZERO;
        private BigDecimal videoCost = BigDecimal.ZERO;

        TrendAccumulator(LocalDate date) {
            this.date = date;
        }

        Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date.toString());
            row.put("label", date.getMonthValue() + "/" + date.getDayOfMonth());
            row.put("recharge", BigDecimal.valueOf(rechargeCents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            row.put("revenue", BigDecimal.valueOf(revenueCents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            row.put("usage", usage);
            row.put("image_usage", imageUsage);
            row.put("video_usage", videoUsage);
            row.put("users", users);
            row.put("credits", credits);
            row.put("failures", failures);
            row.put("image_failures", imageFailures);
            row.put("video_failures", videoFailures);
            row.put("cost", actualCost);
            row.put("image_cost", imageCost);
            row.put("video_cost", videoCost);
            return row;
        }
    }

    private String mask(String email) {
        if (email == null || email.isBlank()) {
            return "-";
        }
        int at = email.indexOf('@');
        if (at < 0) {
            return email.length() <= 2 ? "***" : email.substring(0, 2) + "***";
        }
        if (at <= 2) {
            return "***" + email.substring(at);
        }
        return email.substring(0, 2) + "***" + email.substring(at);
    }

    private String maskUserAccount(UserEntity user) {
        if (isGithubPlaceholderEmail(user.getEmail()) && user.getGithubLogin() != null && !user.getGithubLogin().isBlank()) {
            return "@" + user.getGithubLogin();
        }
        return mask(user.getEmail());
    }

    private String displayUserAccount(UserEntity user) {
        if (isGithubPlaceholderEmail(user.getEmail()) && user.getGithubLogin() != null && !user.getGithubLogin().isBlank()) {
            return "@" + user.getGithubLogin();
        }
        return user.getEmail() == null || user.getEmail().isBlank() ? "-" : user.getEmail();
    }

    private boolean isGithubPlaceholderEmail(String email) {
        return email != null && email.endsWith("@users.noreply.github.openimage.local");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private PageParams pageParams(Integer page, Integer pageSize) {
        int normalizedPage = page == null || page < 1 ? 1 : page;
        int normalizedSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        return new PageParams(normalizedPage, normalizedSize);
    }

    private String limitOffset(PageParams paging) {
        return "limit " + paging.pageSize() + " offset " + ((paging.page() - 1) * paging.pageSize());
    }

    private Map<String, Object> paged(List<Map<String, Object>> rows, long total, PageParams paging) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rows", rows);
        payload.put("total", total);
        payload.put("page", paging.page());
        payload.put("page_size", paging.pageSize());
        payload.put("total_pages", total == 0 ? 0 : (long) Math.ceil(total / (double) paging.pageSize()));
        return payload;
    }

    private List<Object> readList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {});
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Object readJsonValue(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(value, Object.class);
        } catch (Exception ignored) {
            return value;
        }
    }

    private List<ProductImageEntity> taskReferenceImages(ImageTaskEntity task, Object promptDetails) {
        if (task.getProjectId() == null || task.getProjectId().isBlank()) {
            return List.of();
        }
        List<ProductImageEntity> images = productImages.selectList(
            new LambdaQueryWrapper<ProductImageEntity>()
                .eq(ProductImageEntity::getDeleted, 0)
                .eq(ProductImageEntity::getProjectId, task.getProjectId())
                .eq(ProductImageEntity::getUserId, task.getUserId())
                .orderByAsc(ProductImageEntity::getCreatedAt)
        );
        if (!(promptDetails instanceof Map<?, ?> details)) {
            return images;
        }
        Object sourceId = details.get("translationSourceImageId");
        if (sourceId == null || sourceId.toString().isBlank()) {
            return images;
        }
        return images.stream().filter(image -> sourceId.toString().equals(image.getId())).toList();
    }

    private Map<String, Object> adminImageReference(ProductImageEntity image) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", image.getId());
        row.put("image_type", image.getImageType() == null ? "" : image.getImageType());
        row.put("original_filename", image.getOriginalFilename() == null ? "" : image.getOriginalFilename());
        row.put("url", image.getUrl() == null ? "" : image.getUrl());
        row.put("content_type", image.getContentType() == null ? "" : image.getContentType());
        row.put("image_format", image.getImageFormat() == null ? "" : image.getImageFormat());
        row.put("file_size", image.getFileSize());
        row.put("width", image.getWidth());
        row.put("height", image.getHeight());
        row.put("created_at", image.getCreatedAt());
        return row;
    }

    private Map<String, Object> adminProjectDetail(ProductProjectEntity project) {
        if (project == null || Integer.valueOf(1).equals(project.getDeleted())) {
            return Map.of();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", project.getId());
        row.put("product_name", project.getProductName() == null ? "" : project.getProductName());
        row.put("brand", project.getBrand() == null ? "" : project.getBrand());
        row.put("country", project.getCountry() == null ? "" : project.getCountry());
        row.put("language", project.getLanguage() == null ? "" : project.getLanguage());
        row.put("original_info", readJsonValue(project.getOriginalInfo()));
        row.put("confirmed_facts", readJsonValue(project.getConfirmedFacts()));
        return row;
    }

    private String redemptionStatus(RedemptionCodeEntity code) {
        if (code.getDeleted() != null && code.getDeleted() != 0) {
            return "deleted";
        }
        if (!Boolean.TRUE.equals(code.getEnabled())) {
            return "disabled";
        }
        if (code.getExpiresAt() != null && code.getExpiresAt().isBefore(OffsetDateTime.now())) {
            return "expired";
        }
        if (code.getUsedCount() != null && code.getMaxUses() != null && code.getUsedCount() >= code.getMaxUses()) {
            return "used_up";
        }
        return "active";
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String nextRedemptionCode(String prefix) {
        String normalizedPrefix = prefix == null ? "PP" : prefix.trim().replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (normalizedPrefix.isBlank()) {
            normalizedPrefix = "PP";
        }
        if (normalizedPrefix.length() > 12) {
            normalizedPrefix = normalizedPrefix.substring(0, 12);
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = normalizedPrefix + "-" + randomCodePart(4) + "-" + randomCodePart(4);
            long exists = redemptionCodes.selectCount(new LambdaQueryWrapper<RedemptionCodeEntity>().eq(RedemptionCodeEntity::getCode, candidate));
            if (exists == 0) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Failed to generate unique code");
    }

    private String randomCodePart(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(CODE_ALPHABET.charAt(CODE_RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return builder.toString();
    }

    public record CreditAdjustRequest(
        @NotNull @com.fasterxml.jackson.annotation.JsonProperty("change_amount") Integer changeAmount,
        @NotBlank String remark,
        @NotBlank @com.fasterxml.jackson.annotation.JsonProperty("request_token") String requestToken
    ) {}
    public record UserStatusRequest(String status) {}
    public record PasswordResetRequest(String password) {}
    public record RechargePlanUpdateRequest(
        String name,
        Integer credits,
        @com.fasterxml.jackson.annotation.JsonProperty("price_cents") Integer priceCents,
        String currency,
        Boolean enabled,
        @com.fasterxml.jackson.annotation.JsonProperty("sort_order") Integer sortOrder
    ) {}
    public record PaymentChannelUpdateRequest(
        String name,
        Boolean visible,
        Boolean enabled,
        @com.fasterxml.jackson.annotation.JsonProperty("sort_order") Integer sortOrder,
        String config
    ) {}
    public record RedemptionCodeCreateRequest(
        Integer credits,
        @com.fasterxml.jackson.annotation.JsonProperty("max_uses") Integer maxUses,
        Integer quantity,
        @com.fasterxml.jackson.annotation.JsonProperty("expires_at") OffsetDateTime expiresAt,
        @com.fasterxml.jackson.annotation.JsonProperty("valid_days") Integer validDays,
        @com.fasterxml.jackson.annotation.JsonProperty("benefit_type") String benefitType,
        @com.fasterxml.jackson.annotation.JsonProperty("batch_id") String batchId,
        @com.fasterxml.jackson.annotation.JsonProperty("batch_name") String batchName,
        @com.fasterxml.jackson.annotation.JsonProperty("batch_exclusive") Boolean batchExclusive,
        String prefix,
        String note
    ) {}
    public record BenefitCardWaveCreateRequest(
        String name,
        @com.fasterxml.jackson.annotation.JsonProperty("batch_id") String batchId,
        String prefix,
        @com.fasterxml.jackson.annotation.JsonProperty("valid_days") Integer validDays,
        @com.fasterxml.jackson.annotation.JsonProperty("expires_at") OffsetDateTime expiresAt,
        @com.fasterxml.jackson.annotation.JsonProperty("benefit_type") String benefitType,
        String note,
        List<BenefitCardTierRequest> tiers
    ) {}
    public record BenefitCardTierRequest(
        Integer credits,
        Integer quantity,
        @com.fasterxml.jackson.annotation.JsonProperty("max_uses") Integer maxUses
    ) {}
    public record RiskKeywordRequest(
        @NotBlank String level,
        @NotBlank String keyword
    ) {}
    public record RiskConfigUpdateRequest(
        Boolean enabled,
        @com.fasterxml.jackson.annotation.JsonProperty("ban_threshold") Integer banThreshold,
        @com.fasterxml.jackson.annotation.JsonProperty("block_levels") List<String> blockLevels,
        @com.fasterxml.jackson.annotation.JsonProperty("warn_levels") List<String> warnLevels
    ) {}
    public record StationMessageCreateRequest(
        @NotBlank String title,
        @NotBlank String content,
        String audience,
        @com.fasterxml.jackson.annotation.JsonProperty("expires_at") OffsetDateTime expiresAt,
        Boolean enabled
    ) {}
    public record ImageConfigUpdateRequest(
        Boolean enabled,
        @com.fasterxml.jackson.annotation.JsonProperty("credits_cost") Integer creditsCost,
        @com.fasterxml.jackson.annotation.JsonProperty("default_model") String defaultModel,
        @com.fasterxml.jackson.annotation.JsonProperty("fallback_model") String fallbackModel,
        @com.fasterxml.jackson.annotation.JsonProperty("default_size") String defaultSize,
        @com.fasterxml.jackson.annotation.JsonProperty("required_fields") String requiredFields
    ) {}
    public record PromptCreateRequest(String name, String scene, String content) {}
    public record PromptUpdateRequest(String name, String scene, String content, Boolean enabled) {}
    private record AdminTaskFilters(
        String keyword,
        Set<String> statuses,
        Set<String> providers,
        Set<String> imageTypes,
        Set<String> mediaTypes,
        Set<String> userIds,
        boolean userFilterActive,
        OffsetDateTime from,
        OffsetDateTime to
    ) {}
    private record PageParams(int page, int pageSize) {}
}
