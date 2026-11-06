package com.openimage.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.AiImageSizes;
import com.openimage.ai.AiImageClient;
import com.openimage.ai.worker.AiWorkerJobService;
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
import com.openimage.risk.RiskControlException;
import com.openimage.storage.LocalStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Component
public class ImageTaskWorker {
    private static final Logger log = LoggerFactory.getLogger(ImageTaskWorker.class);
    private static final String TIMEOUT_SAFE_ERROR = "任务超时，已退回本张图片积分，可点击重新生成";
    private static final String TIMEOUT_RAW_ERROR = "任务超过 1 小时仍处于 running 状态，系统判定为超时失败并自动退款";
    private static final int TIMEOUT_SCAN_LIMIT = 50;

    private final ImageTaskMapper tasks;
    private final ProductProjectMapper projects;
    private final ProductImageMapper productImages;
    private final UserMapper users;
    private final CreditLedgerMapper ledgers;
    private final LocalStorageService storage;
    private final AiImageClient aiImageClient;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper json;
    private final DomesticCommerceOverlayRenderer overlayRenderer;
    private final AiWorkerJobService workerJobs;
    private final int concurrency;
    private final ExecutorService executor;
    private final Semaphore slots;

    public ImageTaskWorker(
        ImageTaskMapper tasks,
        ProductProjectMapper projects,
        ProductImageMapper productImages,
        UserMapper users,
        CreditLedgerMapper ledgers,
        LocalStorageService storage,
        AiImageClient aiImageClient,
        TransactionTemplate transactionTemplate,
        ObjectMapper json,
        DomesticCommerceOverlayRenderer overlayRenderer,
        @Value("${openimage.worker.image-concurrency:1}") int configuredConcurrency
    ) {
        this(
            tasks,
            projects,
            productImages,
            users,
            ledgers,
            storage,
            aiImageClient,
            transactionTemplate,
            json,
            overlayRenderer,
            configuredConcurrency,
            null
        );
    }

    @Autowired
    public ImageTaskWorker(
        ImageTaskMapper tasks,
        ProductProjectMapper projects,
        ProductImageMapper productImages,
        UserMapper users,
        CreditLedgerMapper ledgers,
        LocalStorageService storage,
        AiImageClient aiImageClient,
        TransactionTemplate transactionTemplate,
        ObjectMapper json,
        DomesticCommerceOverlayRenderer overlayRenderer,
        @Value("${openimage.worker.image-concurrency:1}") int configuredConcurrency,
        AiWorkerJobService workerJobs
    ) {
        this.tasks = tasks;
        this.projects = projects;
        this.productImages = productImages;
        this.users = users;
        this.ledgers = ledgers;
        this.storage = storage;
        this.aiImageClient = aiImageClient;
        this.transactionTemplate = transactionTemplate;
        this.json = json;
        this.overlayRenderer = overlayRenderer;
        this.workerJobs = workerJobs;
        this.concurrency = Math.max(1, Math.min(configuredConcurrency, 10));
        this.executor = Executors.newFixedThreadPool(this.concurrency);
        this.slots = new Semaphore(this.concurrency);
    }

    @Scheduled(fixedDelay = 5000)
    public void processQueuedTasks() {
        int available = slots.availablePermits();
        if (available <= 0) {
            return;
        }
        List<ImageTaskEntity> queued = tasks.selectList(new LambdaQueryWrapper<ImageTaskEntity>()
            .eq(ImageTaskEntity::getDeleted, 0)
            .eq(ImageTaskEntity::getStatus, "queued")
            .orderByAsc(ImageTaskEntity::getCreatedAt)
            .last("limit " + Math.min(Math.max(available * 20, 20), 100)));
        int dispatched = 0;
        for (ImageTaskEntity task : queued) {
            if (dispatched >= available) {
                break;
            }
            if (workerJobs != null && workerJobs.hasBlockingJob(task.getId())) {
                continue;
            }
            if (tasks.claimQueued(task.getId()) == 0) {
                continue;
            }
            ImageTaskEntity claimed = tasks.selectById(task.getId());
            if (claimed != null) {
                dispatch(claimed);
                dispatched++;
            }
        }
    }

    private void dispatch(ImageTaskEntity task) {
        if (!slots.tryAcquire()) {
            tasks.requeueRunning(task.getId());
            return;
        }
        executor.submit(() -> {
            try {
                process(task);
            } finally {
                slots.release();
            }
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failTimedOutRunningTasksOnStartup() {
        failTimedOutRunningTasks();
    }

    @Scheduled(fixedDelay = 300000)
    public void failTimedOutRunningTasksOnSchedule() {
        failTimedOutRunningTasks();
    }

    int failTimedOutRunningTasks() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(1);
        List<ImageTaskEntity> staleTasks = tasks.selectList(new LambdaQueryWrapper<ImageTaskEntity>()
            .eq(ImageTaskEntity::getDeleted, 0)
            .eq(ImageTaskEntity::getStatus, "running")
            .lt(ImageTaskEntity::getUpdatedAt, cutoff)
            .orderByAsc(ImageTaskEntity::getUpdatedAt)
            .last("limit " + TIMEOUT_SCAN_LIMIT));
        Set<String> projectIds = new LinkedHashSet<>();
        int failed = 0;
        for (ImageTaskEntity staleTask : staleTasks) {
            String projectId = transactionTemplate.execute(status -> failTimedOutTaskInTransaction(staleTask.getId(), cutoff));
            if (projectId != null) {
                projectIds.add(projectId);
                failed++;
            }
        }
        for (String projectId : projectIds) {
            refreshProjectStatus(projectId);
        }
        return failed;
    }

    private void process(ImageTaskEntity task) {
        ProductProjectEntity project = projects.selectById(task.getProjectId());
        if (project == null || project.getDeleted() != 0) {
            fail(task, "项目不存在，任务已失败", "Project not found");
            return;
        }
        try {
            task.setStatus("running");
            task.setUpdatedAt(OffsetDateTime.now());
            tasks.updateById(task);

            String filename = task.getId() + ".png";
            LocalStorageService.StoredFile stored;
            AiImageClient.GeneratedImage generated = aiImageClient.generate(project, task, referenceImages(task));
            if (!isTaskStillRunning(task.getId())) {
                updateProjectStatus(project);
                return;
            }
            byte[] outputBytes = postProcessImage(generated.bytes(), task);
            stored = storage.saveGeneratedProjectImage(project.getId(), filename, outputBytes, "image/png");
            tasks.markSuccessIfRunning(task.getId(), stored.url(), generated.provider(), generated.model(), generated.actualCost(), attemptsJson(generated.attempts()));
            updateProjectStatus(project);
        } catch (AiImageClient.AiImageDeferredException deferred) {
            tasks.requeueRunningWithAttempts(task.getId(), attemptsJson(deferred.attempts()));
            refreshProjectStatus(task.getProjectId());
        } catch (Exception ex) {
            String rawMessage = rawFailureMessage(ex);
            String safeMessage = ex instanceof RiskControlException
                ? rawMessage
                : rawMessage.contains("AI 作图服务已被管理员禁用") || rawMessage.contains("AI 生图功能已由管理员禁用")
                ? "AI 作图服务已被管理员禁用或未配置密钥，已退回本张图片积分"
                : "生成服务繁忙，请稍后重试，已退回本张图片积分，可点击重新生成";
            fail(task, safeMessage, rawMessage, attemptsJson(ex));
        }
    }

    private List<ProductImageEntity> referenceImages(ImageTaskEntity task) {
        List<ProductImageEntity> images = new ArrayList<>(productImages.selectList(new LambdaQueryWrapper<ProductImageEntity>()
            .eq(ProductImageEntity::getDeleted, 0)
            .eq(ProductImageEntity::getUserId, task.getUserId())
            .eq(ProductImageEntity::getProjectId, task.getProjectId())
            .orderByAsc(ProductImageEntity::getCreatedAt)));
        String translationSourceImageId = textFromSnapshot(task, "translationSourceImageId");
        images = translationReferences(images, translationSourceImageId);
        String retryUrl = textFromSnapshot(task, "retryReference.imageUrl");
        if (retryUrl != null && !retryUrl.isBlank()) {
            ProductImageEntity retryReference = new ProductImageEntity();
            retryReference.setId("retry-reference");
            retryReference.setProjectId(task.getProjectId());
            retryReference.setUserId(task.getUserId());
            retryReference.setImageType("retry_reference");
            retryReference.setOriginalFilename("retry-reference");
            retryReference.setUrl(retryUrl);
            retryReference.setStoragePath(retryUrl);
            retryReference.setContentType("image/url");
            retryReference.setFileSize(0L);
            images.add(0, retryReference);
        }
        return images;
    }

    static List<ProductImageEntity> translationReferences(List<ProductImageEntity> images, String sourceImageId) {
        if (sourceImageId == null || sourceImageId.isBlank()) {
            return images;
        }
        List<ProductImageEntity> selected = images.stream()
            .filter(image -> sourceImageId.equals(image.getId()))
            .toList();
        if (selected.isEmpty()) {
            throw new IllegalStateException("翻译源图不存在，任务无法继续");
        }
        return new ArrayList<>(selected);
    }

    private byte[] renderPlaceholder(ProductProjectEntity project, ImageTaskEntity task) throws Exception {
        AiImageSizes.Size canvas = outputSize(task);
        int width = canvas.width();
        int height = canvas.height();
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color bg = switch (task.getImageType()) {
            case "main" -> Color.WHITE;
            case "scenario" -> new Color(241, 247, 255);
            case "features" -> new Color(246, 248, 252);
            case "dimensions" -> new Color(255, 250, 240);
            case "comparison" -> new Color(245, 252, 248);
            default -> new Color(248, 248, 248);
        };
        g.setColor(bg);
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(40, 48, 64));
        int base = Math.max(16, Math.min(width, height));
        g.setFont(new Font("SansSerif", Font.BOLD, Math.max(18, base / 22)));
        drawCentered(g, title(task.getImageType()), Math.max(42, height / 8), width);
        g.setFont(new Font("SansSerif", Font.BOLD, Math.max(16, base / 28)));
        drawCentered(g, project.getProductName(), Math.max(76, height / 5), width);
        g.setFont(new Font("SansSerif", Font.PLAIN, Math.max(12, base / 42)));
        drawCentered(g, "System-rendered placeholder for MVP workflow", Math.max(108, height / 4), width);

        g.setColor(new Color(88, 118, 255));
        g.setStroke(new BasicStroke(Math.max(2, base / 160)));
        int boxX = Math.max(20, width / 8);
        int boxY = Math.max(120, height / 3);
        int boxW = Math.max(80, width - boxX * 2);
        int boxH = Math.max(80, height / 3);
        g.drawRoundRect(boxX, boxY, boxW, boxH, Math.max(12, base / 36), Math.max(12, base / 36));
        g.setFont(new Font("SansSerif", Font.PLAIN, Math.max(11, base / 38)));
        int line = Math.max(18, base / 24);
        int textX = boxX + Math.max(18, boxW / 14);
        int textY = boxY + line * 2;
        g.drawString("Image Type: " + task.getImageType(), textX, textY);
        g.drawString("Provider: template-worker", textX, textY + line);
        g.drawString("Overlays are controlled by system", textX, textY + line * 2);
        g.drawString("Canvas: " + width + " x " + height, textX, textY + line * 3);

        if ("dimensions".equals(task.getImageType()) && height > 500) {
            g.setColor(new Color(255, 128, 64));
            int y = Math.min(height - 90, boxY + boxH + Math.max(40, height / 12));
            int left = Math.max(40, width / 5);
            int right = Math.min(width - 40, width - width / 5);
            g.drawLine(left, y, right, y);
            g.drawLine(left, y - 25, left, y + 25);
            g.drawLine(right, y - 25, right, y + 25);
            g.setFont(new Font("SansSerif", Font.BOLD, Math.max(12, base / 40)));
            drawCentered(g, "尺寸由用户确认后叠加，不自动编造", Math.min(height - 30, y + 70), width);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private byte[] postProcessImage(byte[] bytes, ImageTaskEntity task) throws Exception {
        AiImageSizes.Size target = outputSize(task);
        if (target == null) {
            return bytes;
        }
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) {
            throw new IllegalArgumentException("上游返回图片解码失败，无法按套餐尺寸交付");
        }
        if (source.getWidth() == target.width() && source.getHeight() == target.height()) {
            return overlayRenderer.renderIfNeeded(encodePng(source), task);
        }
        log.warn(
            "Image task {} returned {}x{} for target {}x{}; preserving the complete image with contain fit",
            task.getId(),
            source.getWidth(),
            source.getHeight(),
            target.width(),
            target.height()
        );
        BufferedImage output = containResize(source, target.width(), target.height());
        return overlayRenderer.renderIfNeeded(encodePng(output), task);
    }

    private byte[] encodePng(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    static BufferedImage containResize(BufferedImage source, int targetWidth, int targetHeight) {
        boolean alpha = source.getColorModel().hasAlpha();
        BufferedImage output = new BufferedImage(targetWidth, targetHeight, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (!alpha) {
            g.setColor(estimatedCanvasColor(source));
            g.fillRect(0, 0, targetWidth, targetHeight);
        }
        double scale = Math.min((double) targetWidth / source.getWidth(), (double) targetHeight / source.getHeight());
        int scaledWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int scaledHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        int x = (targetWidth - scaledWidth) / 2;
        int y = (targetHeight - scaledHeight) / 2;
        g.drawImage(source, x, y, scaledWidth, scaledHeight, null);
        g.dispose();
        return output;
    }

    private static Color estimatedCanvasColor(BufferedImage source) {
        int sampleSize = Math.max(1, Math.min(24, Math.min(source.getWidth(), source.getHeight()) / 32));
        long red = 0;
        long green = 0;
        long blue = 0;
        long count = 0;
        int[] startXs = {0, Math.max(0, source.getWidth() - sampleSize)};
        int[] startYs = {0, Math.max(0, source.getHeight() - sampleSize)};
        for (int startX : startXs) {
            for (int startY : startYs) {
                for (int x = startX; x < Math.min(source.getWidth(), startX + sampleSize); x++) {
                    for (int y = startY; y < Math.min(source.getHeight(), startY + sampleSize); y++) {
                        Color color = new Color(source.getRGB(x, y), true);
                        red += color.getRed();
                        green += color.getGreen();
                        blue += color.getBlue();
                        count++;
                    }
                }
            }
        }
        if (count == 0) {
            return Color.WHITE;
        }
        return new Color((int) (red / count), (int) (green / count), (int) (blue / count));
    }

    private AiImageSizes.Size outputSize(ImageTaskEntity task) {
        AiImageSizes.Size parsed = sizeFromSnapshot(task, "outputSize");
        if (parsed != null) {
            return parsed;
        }
        String generationValue = textFromSnapshot(task, "generationSize");
        if ("aplus".equals(task.getImageType())) {
            return AiImageSizes.parse(AiImageSizes.normalizeAplusOutputSize(generationValue, AiImageSizes.DEFAULT_APLUS_OUTPUT_SIZE));
        }
        if ("creative".equals(task.getImageType())) {
            return AiImageSizes.parse(AiImageSizes.normalizeCreativeSize(generationValue, AiImageSizes.DEFAULT_CREATIVE_SIZE));
        }
        if (generationValue != null && !generationValue.isBlank()) {
            return AiImageSizes.parse(AiImageSizes.normalizeListingOutputSize(generationValue, generationValue));
        }
        return AiImageSizes.parse(AiImageSizes.DEFAULT_LISTING_OUTPUT_SIZE);
    }

    private AiImageSizes.Size sizeFromSnapshot(ImageTaskEntity task, String field) {
        return AiImageSizes.parse(textFromSnapshot(task, field));
    }

    private String textFromSnapshot(ImageTaskEntity task, String field) {
        try {
            JsonNode node = json.readTree(task.getPromptSnapshot());
            JsonNode value = field.contains(".") ? nestedValue(node, field) : node.get(field);
            if (value != null && value.isTextual()) {
                return value.asText();
            }
        } catch (Exception ignored) {
            // Older tasks may not contain structured sizing metadata.
        }
        return null;
    }

    private JsonNode nestedValue(JsonNode node, String path) {
        JsonNode current = node;
        for (String part : path.split("\\.")) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private void updateProjectStatus(ProductProjectEntity project) {
        List<ImageTaskEntity> all = tasks.selectList(new LambdaQueryWrapper<ImageTaskEntity>()
            .eq(ImageTaskEntity::getDeleted, 0)
            .eq(ImageTaskEntity::getProjectId, project.getId()));
        if (all.isEmpty()) {
            return;
        }
        long success = all.stream().filter(task -> "success".equals(task.getStatus())).count();
        long failed = all.stream().filter(task -> "failed".equals(task.getStatus())).count();
        long running = all.stream().filter(task -> "queued".equals(task.getStatus()) || "running".equals(task.getStatus())).count();
        String nextStatus = project.getStatus();
        if (failed > 0 && success > 0) {
            nextStatus = "images_partial_failed";
        } else if (failed > 0 && running == 0) {
            nextStatus = "images_failed";
        } else if (failed > 0) {
            nextStatus = "images_partial_failed";
        } else if (running > 0) {
            nextStatus = "images_queued";
        } else if (success == all.size()) {
            nextStatus = "images_completed";
        }
        if (!nextStatus.equals(project.getStatus())) {
            project.setStatus(nextStatus);
            project.setUpdatedAt(OffsetDateTime.now());
            projects.updateById(project);
        }
    }

    private void fail(ImageTaskEntity task, String message, String rawMessage) {
        fail(task, message, rawMessage, "[]");
    }

    private void fail(ImageTaskEntity task, String message, String rawMessage, String providerAttempts) {
        int marked = tasks.markFailedIfRunning(task.getId(), message, rawMessage, providerAttempts);
        if (marked > 0) {
            ImageTaskEntity failedTask = tasks.selectById(task.getId());
            if (failedTask != null) {
                refundFailedTask(failedTask);
            }
        }
        refreshProjectStatus(task.getProjectId());
    }

    private boolean isTaskStillRunning(String taskId) {
        ImageTaskEntity current = tasks.selectById(taskId);
        return current != null && current.getDeleted() == 0 && "running".equals(current.getStatus());
    }

    private void refreshProjectStatus(String projectId) {
        ProductProjectEntity project = projects.selectById(projectId);
        if (project != null && project.getDeleted() == 0) {
            updateProjectStatus(project);
        }
    }

    private String failTimedOutTaskInTransaction(String taskId, OffsetDateTime cutoff) {
        ImageTaskEntity task = tasks.lockById(taskId);
        if (task == null || task.getDeleted() != 0 || !"running".equals(task.getStatus())) {
            return null;
        }
        OffsetDateTime lastTouched = task.getUpdatedAt();
        if (lastTouched == null || !lastTouched.isBefore(cutoff)) {
            return null;
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (task.getRefundedAt() == null) {
            if (task.getCreditsCost() == null || task.getCreditsCost() <= 0) {
                task.setRefundedAt(now);
            } else if (refundTimedOutTaskInTransaction(task, lastTouched, now)) {
                task.setRefundedAt(now);
            } else {
                return null;
            }
        }
        task.setStatus("failed");
        task.setSafeError(TIMEOUT_SAFE_ERROR);
        task.setRawError(TIMEOUT_RAW_ERROR);
        task.setUpdatedAt(now);
        tasks.updateById(task);
        return task.getProjectId();
    }

    private boolean refundTimedOutTaskInTransaction(ImageTaskEntity task, OffsetDateTime timeoutReference, OffsetDateTime refundTime) {
        String refundBizId = task.getId() + ":timeout:" + timeoutReference.toInstant().toEpochMilli();
        CreditLedgerEntity existing = ledgers.selectOne(new LambdaQueryWrapper<CreditLedgerEntity>()
            .eq(CreditLedgerEntity::getDeleted, 0)
            .eq(CreditLedgerEntity::getUserId, task.getUserId())
            .eq(CreditLedgerEntity::getBizType, "image_refund")
            .eq(CreditLedgerEntity::getBizId, refundBizId));
        if (existing != null) {
            return true;
        }
        UserEntity user = users.lockById(task.getUserId());
        if (user == null || user.getDeleted() != 0) {
            return false;
        }
        int balanceAfter = user.getCredits() + task.getCreditsCost();
        user.setCredits(balanceAfter);
        user.setUpdatedAt(refundTime);
        users.updateById(user);
        CreditLedgerEntity ledger = new CreditLedgerEntity();
        ledger.setId("cl_" + java.util.UUID.randomUUID().toString().replace("-", ""));
        ledger.setUserId(task.getUserId());
        ledger.setChangeAmount(task.getCreditsCost());
        ledger.setBalanceAfter(balanceAfter);
        ledger.setBizType("image_refund");
        ledger.setBizId(refundBizId);
        ledger.setRemark("图片生成超时自动退回积分：" + task.getImageType());
        ledger.setCreatedAt(refundTime);
        ledger.setDeleted(0);
        ledgers.insert(ledger);
        return true;
    }

    private void refundFailedTask(ImageTaskEntity task) {
        transactionTemplate.executeWithoutResult(status -> refundFailedTaskInTransaction(task));
    }

    private void refundFailedTaskInTransaction(ImageTaskEntity task) {
        ImageTaskEntity lockedTask = tasks.lockById(task.getId());
        if (lockedTask == null || lockedTask.getDeleted() != 0) {
            return;
        }
        task = lockedTask;
        if (task.getRefundedAt() != null || task.getCreditsCost() == null || task.getCreditsCost() <= 0) {
            return;
        }
        OffsetDateTime refundTime = OffsetDateTime.now();
        String refundBizId = task.getId() + ":" + (task.getUpdatedAt() == null ? refundTime : task.getUpdatedAt()).toInstant().toEpochMilli();
        CreditLedgerEntity existing = ledgers.selectOne(new LambdaQueryWrapper<CreditLedgerEntity>()
            .eq(CreditLedgerEntity::getDeleted, 0)
            .eq(CreditLedgerEntity::getUserId, task.getUserId())
            .eq(CreditLedgerEntity::getBizType, "image_refund")
            .eq(CreditLedgerEntity::getBizId, refundBizId));
        if (existing != null) {
            task.setRefundedAt(refundTime);
            tasks.updateById(task);
            return;
        }
        UserEntity user = users.lockById(task.getUserId());
        if (user == null || user.getDeleted() != 0) {
            return;
        }
        int balanceAfter = user.getCredits() + task.getCreditsCost();
        user.setCredits(balanceAfter);
        user.setUpdatedAt(OffsetDateTime.now());
        users.updateById(user);
        CreditLedgerEntity ledger = new CreditLedgerEntity();
        ledger.setId("cl_" + java.util.UUID.randomUUID().toString().replace("-", ""));
        ledger.setUserId(task.getUserId());
        ledger.setChangeAmount(task.getCreditsCost());
        ledger.setBalanceAfter(balanceAfter);
        ledger.setBizType("image_refund");
        ledger.setBizId(refundBizId);
        ledger.setRemark("图片生成失败自动退回积分：" + task.getImageType());
        ledger.setCreatedAt(refundTime);
        ledger.setDeleted(0);
        ledgers.insert(ledger);
        task.setRefundedAt(refundTime);
        tasks.updateById(task);
    }

    private String rawFailureMessage(Exception ex) {
        String detail = ex.getMessage() == null ? "" : ex.getMessage().replaceAll("\\s+", " ").trim();
        if (detail.length() > 500) {
            detail = detail.substring(0, 500);
        }
        return detail.isBlank() ? ex.getClass().getSimpleName() : detail;
    }

    private String attemptsJson(Exception ex) {
        if (ex instanceof AiImageClient.AiImageGenerationException imageException) {
            return attemptsJson(imageException.attempts());
        }
        Throwable cause = ex.getCause();
        if (cause instanceof AiImageClient.AiImageGenerationException imageException) {
            return attemptsJson(imageException.attempts());
        }
        return "[]";
    }

    private String attemptsJson(List<AiImageClient.ProviderAttempt> attempts) {
        try {
            return json.writeValueAsString(attempts == null ? List.of() : attempts);
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private String title(String type) {
        return switch (type) {
            case "main" -> "1 白底主图";
            case "scenario" -> "2 场景图";
            case "features" -> "3 卖点图";
            case "dimensions" -> "4 尺寸图";
            case "steps" -> "5 步骤图";
            case "comparison" -> "6 对比图";
            case "package" -> "7 包装/变体图";
            default -> "Listing Image";
        };
    }

    private void drawCentered(Graphics2D g, String text, int y, int width) {
        int textWidth = g.getFontMetrics().stringWidth(text == null ? "" : text);
        g.drawString(text == null ? "" : text, Math.max(40, (width - textWidth) / 2), y);
    }
}
