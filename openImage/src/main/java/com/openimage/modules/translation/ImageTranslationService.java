package com.openimage.modules.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.AiImageClient;
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
import com.openimage.security.CurrentUser;
import com.openimage.storage.ImageUploadNormalizer;
import com.openimage.storage.LocalStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ImageTranslationService {
    static final int MAX_IMAGES = 10;
    static final long MAX_FILE_BYTES = 25L * 1024L * 1024L;
    static final long MAX_TOTAL_BYTES = 28L * 1024L * 1024L;

    private static final Map<String, Integer> CREDITS_BY_RESOLUTION = Map.of("1K", 6, "2K", 24, "4K", 30);
    private static final List<ImageTranslationDtos.LanguageOption> LANGUAGES = List.of(
        language("ms-MY", "马来语（马来西亚）", "Bahasa Melayu", "Malay for Malaysia"),
        language("en-US", "英语", "English", "English"),
        language("id-ID", "印度尼西亚语", "Bahasa Indonesia", "Indonesian"),
        language("th-TH", "泰语", "ไทย", "Thai"),
        language("vi-VN", "越南语", "Tiếng Việt", "Vietnamese"),
        language("fil-PH", "菲律宾语", "Filipino", "Filipino"),
        language("es-ES", "西班牙语", "Español", "Spanish"),
        language("pt-BR", "葡萄牙语（巴西）", "Português", "Brazilian Portuguese"),
        language("fr-FR", "法语", "Français", "French"),
        language("de-DE", "德语", "Deutsch", "German"),
        language("ja-JP", "日语", "日本語", "Japanese"),
        language("ko-KR", "韩语", "한국어", "Korean"),
        language("ar-SA", "阿拉伯语", "العربية", "Arabic"),
        language("zh-TW", "繁体中文", "繁體中文", "Traditional Chinese")
    );
    private static final List<AspectPreset> ASPECT_PRESETS = List.of(
        preset(1.0, "1024x1024", "2048x2048", "2880x2880"),
        preset(5.0 / 4.0, "1040x832", "2080x1664", "3200x2560"),
        preset(9.0 / 16.0, "720x1280", "1152x2048", "2160x3840"),
        preset(16.0 / 9.0, "1280x720", "2048x1152", "3840x2160"),
        preset(4.0 / 3.0, "1024x768", "2048x1536", "3264x2448"),
        preset(3.0 / 2.0, "1008x672", "2016x1344", "3504x2336"),
        preset(4.0 / 5.0, "832x1040", "1664x2080", "2560x3200"),
        preset(3.0 / 4.0, "768x1024", "1536x2048", "2448x3264"),
        preset(2.0 / 3.0, "672x1008", "1344x2016", "2336x3504"),
        preset(21.0 / 9.0, "1344x576", "2016x864", "3696x1584")
    );

    private final ProductProjectMapper projects;
    private final ImageTaskMapper tasks;
    private final ProductImageMapper productImages;
    private final UserMapper users;
    private final CreditLedgerMapper ledgers;
    private final LocalStorageService storage;
    private final ImageUploadNormalizer imageNormalizer;
    private final AiImageClient aiImage;
    private final AiRuntimeConfigService aiRuntimeConfig;
    private final ProjectService projectService;
    private final ObjectMapper json;

    public ImageTranslationService(
        ProductProjectMapper projects,
        ImageTaskMapper tasks,
        ProductImageMapper productImages,
        UserMapper users,
        CreditLedgerMapper ledgers,
        LocalStorageService storage,
        ImageUploadNormalizer imageNormalizer,
        AiImageClient aiImage,
        AiRuntimeConfigService aiRuntimeConfig,
        ProjectService projectService,
        ObjectMapper json
    ) {
        this.projects = projects;
        this.tasks = tasks;
        this.productImages = productImages;
        this.users = users;
        this.ledgers = ledgers;
        this.storage = storage;
        this.imageNormalizer = imageNormalizer;
        this.aiImage = aiImage;
        this.aiRuntimeConfig = aiRuntimeConfig;
        this.projectService = projectService;
        this.json = json;
    }

    public List<ImageTranslationDtos.LanguageOption> languages() {
        return LANGUAGES;
    }

    @Transactional
    public ImageTranslationDtos.GenerateResponse generate(
        CurrentUser user,
        String targetLanguageCode,
        String requestedResolution,
        List<MultipartFile> uploads
    ) throws Exception {
        ImageTranslationDtos.LanguageOption targetLanguage = targetLanguage(targetLanguageCode);
        String resolution = normalizeResolution(requestedResolution);
        List<SourcePlan> sourcePlans = validateUploads(uploads, resolution);
        if (!aiImage.enabled()) {
            throw new IllegalArgumentException("AI 作图服务已被管理员禁用或未配置密钥，请联系管理员开启后再使用");
        }
        List<AiRuntimeConfigService.EffectiveImageConfig> runtimes = sourcePlans.stream()
            .map(plan -> aiRuntimeConfig.effectiveImageForTask("", "", "", plan.generationSize()))
            .toList();
        if (runtimes.stream().anyMatch(runtime -> !runtime.enabledAndConfigured())) {
            throw new IllegalArgumentException("当前分辨率没有可用的 AI 作图供应商，请联系管理员");
        }

        int creditsPerImage = CREDITS_BY_RESOLUTION.get(resolution);
        int totalCost = Math.multiplyExact(creditsPerImage, sourcePlans.size());
        UserEntity account = users.lockById(user.id());
        if (account == null || account.getDeleted() != 0) {
            throw new IllegalArgumentException("User not found");
        }
        if (account.getCredits() < totalCost) {
            throw new IllegalArgumentException("积分不足，本次图片翻译需要 " + totalCost + " 积分");
        }

        OffsetDateTime now = OffsetDateTime.now();
        ProductProjectEntity project = new ProductProjectEntity();
        project.setId("prod_" + id());
        project.setUserId(user.id());
        project.setBrand("PicPilot 图片翻译");
        project.setProductName("图片翻译 · " + targetLanguage.name());
        project.setCountry(countryFromLanguage(targetLanguage.code()));
        project.setLanguage(targetLanguage.code());
        project.setStatus("images_queued");
        project.setOriginalInfo(json.writeValueAsString(originalInfo(targetLanguage, resolution, sourcePlans.size())));
        project.setGeneratedCopy("{}");
        project.setConfirmedFacts(json.writeValueAsString(confirmedFacts(targetLanguage, resolution, sourcePlans.size(), totalCost)));
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        project.setDeleted(0);
        projects.insert(project);

        List<StoredSource> sources = new ArrayList<>();
        for (int index = 0; index < sourcePlans.size(); index++) {
            sources.add(storeSource(user.id(), project.getId(), index, sourcePlans.get(index)));
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
        ledger.setBizType("image_translation_generate");
        ledger.setBizId(project.getId());
        ledger.setRemark("批量图片翻译：" + sourcePlans.size() + " 张，目标语言 " + targetLanguage.name());
        ledger.setCreatedAt(now);
        ledger.setDeleted(0);
        ledgers.insert(ledger);

        List<ImageTaskEntity> created = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            StoredSource source = sources.get(index);
            AiRuntimeConfigService.EffectiveImageConfig runtime = runtimes.get(index);
            ImageTaskEntity task = new ImageTaskEntity();
            task.setId("img_" + id());
            task.setProjectId(project.getId());
            task.setUserId(user.id());
            task.setTaskSetId(project.getId());
            task.setImageType(taskImageType(index));
            task.setStatus("queued");
            task.setResultUrl("");
            task.setCreditsCost(creditsPerImage);
            task.setPromptSnapshot(promptSnapshot(project, source, index, targetLanguage, resolution, runtime));
            task.setProvider(runtime.provider());
            task.setModel(runtime.model());
            task.setSafeError("");
            task.setRawError("");
            task.setProviderAttempts("[]");
            task.setActualCost((BigDecimal) null);
            OffsetDateTime taskTime = now.plusNanos(index * 1_000_000L);
            task.setCreatedAt(taskTime);
            task.setUpdatedAt(taskTime);
            task.setHiddenByUser(0);
            task.setDeleted(0);
            tasks.insert(task);
            created.add(task);
        }

        ProjectDtos.ProjectDetail detail = projectService.detail(user, project.getId());
        return new ImageTranslationDtos.GenerateResponse(
            project.getId(),
            detail,
            detail.imageTasks().stream().filter(item -> created.stream().anyMatch(task -> task.getId().equals(item.id()))).toList(),
            targetLanguage,
            resolution,
            totalCost
        );
    }

    public ProjectDtos.ProjectDetail project(CurrentUser user, String projectId) throws Exception {
        return projectService.detail(user, projectId);
    }

    public ProjectDtos.ImageTaskView task(CurrentUser user, String taskId) throws Exception {
        ImageTaskEntity task = ownTask(user, taskId);
        return projectService.detail(user, task.getProjectId()).imageTasks().stream()
            .filter(item -> item.id().equals(taskId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Task not found"));
    }

    public ProjectDtos.ImageTaskView retry(CurrentUser user, String taskId) throws Exception {
        ImageTaskEntity task = ownTask(user, taskId);
        return projectService.retryFailedTask(user, task.getProjectId(), taskId);
    }

    private ImageTaskEntity ownTask(CurrentUser user, String taskId) {
        ImageTaskEntity task = tasks.selectById(taskId);
        if (task == null || task.getDeleted() != 0 || Integer.valueOf(1).equals(task.getHiddenByUser()) || !user.id().equals(task.getUserId()) || task.getImageType() == null || !task.getImageType().startsWith("image_translation_")) {
            throw new IllegalArgumentException("Task not found");
        }
        return task;
    }

    private List<SourcePlan> validateUploads(List<MultipartFile> uploads, String resolution) throws Exception {
        if (uploads == null || uploads.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一张图片");
        }
        if (uploads.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("一次最多翻译 " + MAX_IMAGES + " 张图片");
        }
        long totalBytes = 0L;
        List<SourcePlan> plans = new ArrayList<>();
        for (MultipartFile upload : uploads) {
            if (upload == null || upload.isEmpty()) {
                throw new IllegalArgumentException("上传图片不能为空");
            }
            if (upload.getSize() > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("单张图片不能超过 25MB");
            }
            totalBytes += upload.getSize();
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("全部图片合计不能超过 28MB");
            }
            ImageUploadNormalizer.NormalizedImage normalized = imageNormalizer.normalize(upload, MAX_FILE_BYTES, ImageUploadNormalizer.DEFAULT_MAX_PIXELS);
            plans.add(new SourcePlan(normalized, normalized.width(), normalized.height(), generationSize(normalized.width(), normalized.height(), resolution)));
        }
        return plans;
    }

    private StoredSource storeSource(String userId, String projectId, int index, SourcePlan plan) throws Exception {
        String imageId = "pimg_" + id();
        LocalStorageService.StoredFile stored = storage.saveProjectImage(userId, projectId, imageId, plan.image());
        ProductImageEntity entity = new ProductImageEntity();
        entity.setId(imageId);
        entity.setProjectId(projectId);
        entity.setUserId(userId);
        entity.setImageType("image_translation_source");
        entity.setOriginalFilename(stored.filename());
        entity.setUrl(stored.url());
        entity.setStoragePath(stored.path());
        entity.setContentType(stored.contentType());
        entity.setImageFormat(stored.format());
        entity.setFileSize(stored.fileSize());
        entity.setWidth(stored.width());
        entity.setHeight(stored.height());
        entity.setCreatedAt(OffsetDateTime.now().plusNanos(index));
        entity.setUpdatedAt(entity.getCreatedAt());
        entity.setDeleted(0);
        productImages.insert(entity);
        return new StoredSource(entity, plan);
    }

    private String promptSnapshot(
        ProductProjectEntity project,
        StoredSource source,
        int index,
        ImageTranslationDtos.LanguageOption targetLanguage,
        String resolution,
        AiRuntimeConfigService.EffectiveImageConfig runtime
    ) throws Exception {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("taskKind", "image_translation");
        snapshot.put("imageName", "第 " + (index + 1) + " 张 · " + safeFilename(source.entity().getOriginalFilename()));
        snapshot.put("translationSourceImageId", source.entity().getId());
        snapshot.put("sourceImageIndex", index);
        snapshot.put("sourceFilename", safeFilename(source.entity().getOriginalFilename()));
        snapshot.put("sourceWidth", source.plan().width());
        snapshot.put("sourceHeight", source.plan().height());
        snapshot.put("sourceLanguage", "auto");
        snapshot.put("targetLanguageCode", targetLanguage.code());
        snapshot.put("targetLanguageName", targetLanguage.name());
        snapshot.put("resolution", resolution);
        snapshot.put("generationSize", source.plan().generationSize());
        snapshot.put("outputSize", source.plan().generationSize());
        snapshot.put("intent", "translate_image_text");
        snapshot.put("text_mode", "replace_existing_text_only");
        snapshot.put("reference_policy", "preserve_non_text_exactly");
        snapshot.put("modelPrompt", translationPrompt(targetLanguage));
        snapshot.put("aiProviderId", safe(runtime.id()));
        snapshot.put("aiProvider", safe(runtime.provider()));
        snapshot.put("aiModel", safe(runtime.model()));
        snapshot.put("projectName", project.getProductName());
        return json.writeValueAsString(snapshot);
    }

    static String translationPrompt(ImageTranslationDtos.LanguageOption targetLanguage) {
        return """
            EDIT THE PROVIDED SOURCE IMAGE. This is a text-translation edit, not a redesign.

            Translate every visible source-language text block into natural %s (%s). Auto-detect the source language.

            NON-NEGOTIABLE CONSTRAINTS:
            1. Treat the provided image as the exact visual base. Preserve the canvas, crop, composition, product, people, hands, background, lighting, shadows, colors, textures, icons, decorations, borders, and spacing.
            2. Change pixels only inside existing translatable text regions. Remove the original text cleanly and place the corresponding translated text in the same region.
            3. Keep a one-to-one semantic mapping for every text block. Do not summarize, omit, combine, expand, or invent marketing claims.
            4. Preserve all numbers, prices, units, dimensions, quantities, SKU/model codes, punctuation, and symbols exactly unless only their surrounding label needs translation.
            5. Preserve brand names, trademarks, logos, model names, proper nouns, and marketplace/platform names exactly. Never replace Taobao, Tmall, 1688, JD, Xiaohongshu, PicPilot, or another source platform with Shopee, Lazada, TikTok, or any target-market platform.
            6. %s
            7. Match the original typography hierarchy, alignment, color, weight, and visual density. Adjust font size and line breaks only as needed to fit within the original text region.
            8. Do not add new text, badges, watermarks, QR codes, platform UI, objects, or decorative elements. Do not remove any non-text content.
            9. Return one final edited image only, at the requested aspect ratio. If a region is unreadable, preserve it instead of guessing.
            10. If there is no translatable text, return an image visually identical to the source.

            Highest priority: the only intended visual difference between input and output is the language of existing text.
            """.formatted(targetLanguage.promptName(), targetLanguage.code(), languageQualityInstruction(targetLanguage)).trim();
    }

    private static String languageQualityInstruction(ImageTranslationDtos.LanguageOption targetLanguage) {
        if ("ms-MY".equalsIgnoreCase(targetLanguage.code())) {
            return "Use standard Malaysian Bahasa Melayu only. Do not mix in Indonesian, German, or substitute English words unless an original proper noun must be preserved.";
        }
        return "Use only the requested target language for translated text. Do not mix in another language unless an original proper noun must be preserved.";
    }

    static String generationSize(int width, int height, String resolution) {
        String normalizedResolution = normalizeResolution(resolution);
        double ratio = (double) width / (double) height;
        AspectPreset nearest = ASPECT_PRESETS.stream()
            .min((left, right) -> Double.compare(Math.abs(ratio - left.ratio()), Math.abs(ratio - right.ratio())))
            .orElse(ASPECT_PRESETS.get(0));
        return nearest.sizes().get(normalizedResolution);
    }

    static String normalizeResolution(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return CREDITS_BY_RESOLUTION.containsKey(normalized) ? normalized : "1K";
    }

    static String taskImageType(int zeroBasedIndex) {
        return "image_translation_" + String.format(Locale.ROOT, "%02d", zeroBasedIndex + 1);
    }

    private ImageTranslationDtos.LanguageOption targetLanguage(String code) {
        String normalized = code == null ? "" : code.trim();
        return LANGUAGES.stream()
            .filter(item -> item.code().equalsIgnoreCase(normalized))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("不支持的目标语言"));
    }

    private Map<String, Object> originalInfo(ImageTranslationDtos.LanguageOption language, String resolution, int count) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_type", "image_translation");
        row.put("source_language", "auto");
        row.put("target_language", language.code());
        row.put("target_language_name", language.name());
        row.put("resolution", resolution);
        row.put("source_image_count", count);
        row.put("translation_engine", "direct_ai_image_edit");
        row.put("description", "批量图片文字翻译");
        return row;
    }

    private Map<String, Object> confirmedFacts(ImageTranslationDtos.LanguageOption language, String resolution, int count, int totalCost) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mode", "image_translation");
        row.put("source_language", "auto");
        row.put("target_language", language.code());
        row.put("target_language_name", language.name());
        row.put("resolution", resolution);
        row.put("source_image_count", count);
        row.put("credits_cost", totalCost);
        row.put("edit_scope", "existing_text_only");
        return row;
    }

    private String countryFromLanguage(String language) {
        int separator = language.indexOf('-');
        return separator >= 0 && separator + 1 < language.length() ? language.substring(separator + 1).toUpperCase(Locale.ROOT) : language;
    }

    private String safeFilename(String value) {
        return value == null || value.isBlank() ? "source-image" : value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static ImageTranslationDtos.LanguageOption language(String code, String name, String nativeName, String promptName) {
        return new ImageTranslationDtos.LanguageOption(code, name, nativeName, promptName);
    }

    private static AspectPreset preset(double ratio, String oneK, String twoK, String fourK) {
        return new AspectPreset(ratio, Map.of("1K", oneK, "2K", twoK, "4K", fourK));
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record AspectPreset(double ratio, Map<String, String> sizes) {}
    private record SourcePlan(ImageUploadNormalizer.NormalizedImage image, int width, int height, String generationSize) {}
    private record StoredSource(ProductImageEntity entity, SourcePlan plan) {}
}
