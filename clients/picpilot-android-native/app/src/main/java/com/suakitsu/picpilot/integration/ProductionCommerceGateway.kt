package com.suakitsu.picpilot.integration

import com.suakitsu.picpilot.core.model.CommerceCatalogDto
import com.suakitsu.picpilot.core.model.CommerceEstimateDto
import com.suakitsu.picpilot.core.model.CommerceEstimateRequestDto
import com.suakitsu.picpilot.core.model.CommerceGenerateResponseDto
import com.suakitsu.picpilot.core.model.CommerceProductInfoDto
import com.suakitsu.picpilot.core.model.ImageTask
import com.suakitsu.picpilot.core.model.ImageTranslationGenerateResponseDto
import com.suakitsu.picpilot.core.model.ImageTranslationLanguageDto
import com.suakitsu.picpilot.core.model.ProjectDetailDto
import com.suakitsu.picpilot.core.network.ApiResult
import com.suakitsu.picpilot.core.repository.PicPilotRepository
import com.suakitsu.picpilot.feature.commerce.CommerceCreationKind
import com.suakitsu.picpilot.feature.commerce.CommerceInputImage
import com.suakitsu.picpilot.feature.commerce.CommerceQuote
import com.suakitsu.picpilot.feature.commerce.CommerceSubmissionResult
import com.suakitsu.picpilot.feature.commerce.CommerceSubmitRequest
import com.suakitsu.picpilot.feature.commerce.CommerceSubmitter
import com.suakitsu.picpilot.integration.upload.OrderedMultipartFactory
import com.suakitsu.picpilot.integration.upload.UploadDescriptor
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CommerceProjectUpdate(
    val projectId: String,
    val status: String,
    val tasks: List<ImageTask>,
    val totalCredits: Int,
    val terminal: Boolean,
)

@Singleton
class ProductionCommerceGateway @Inject constructor(
    private val repository: PicPilotRepository,
    private val multipart: OrderedMultipartFactory,
    private val json: Json,
) {
    private val submitLocks = ConcurrentHashMap<String, Mutex>()
    private val recentAccepted = ConcurrentHashMap<String, CachedAccepted>()
    private val uncertainSubmissions = ConcurrentHashMap<String, Long>()

    val submitter: CommerceSubmitter = ::submit

    suspend fun loadCatalog(): Result<CommerceCatalogDto> = integrationResult {
        repository.commerceCatalog().valueOrThrow()
    }

    suspend fun loadTranslationLanguages(): Result<List<ImageTranslationLanguageDto>> = integrationResult {
        repository.imageTranslationLanguages().valueOrThrow()
    }

    suspend fun loadBalance(): Result<Int> = integrationResult {
        repository.billingSummary().valueOrThrow().credits
    }

    suspend fun estimate(request: CommerceSubmitRequest): Result<CommerceQuote> = integrationResult {
        authoritativeQuote(request)
    }

    suspend fun submit(request: CommerceSubmitRequest): CommerceSubmissionResult {
        val operationKey = request.fingerprint()
        val lock = submitLocks.computeIfAbsent(operationKey) { Mutex() }
        return try {
            lock.withLock {
                uncertainSubmissions[operationKey]
                    ?.takeIf { System.currentTimeMillis() - it <= UNCERTAIN_SUBMISSION_WINDOW_MS }
                    ?.let { throw SubmissionUnknownException() }
                recentAccepted[operationKey]
                    ?.takeIf { System.currentTimeMillis() - it.createdAtMillis <= RAPID_DUPLICATE_WINDOW_MS }
                    ?.result
                    ?: submitOnce(request).also { result ->
                        if (result is CommerceSubmissionResult.Accepted) {
                            recentAccepted[operationKey] = CachedAccepted(result, System.currentTimeMillis())
                        }
                    }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (changed: ServerQuoteChangedException) {
            CommerceSubmissionResult.Rejected(changed.message.orEmpty(), retryable = true)
        } catch (unknown: SubmissionUnknownException) {
            rememberUncertainSubmission(operationKey)
            CommerceSubmissionResult.Rejected(unknown.message.orEmpty(), retryable = false)
        } catch (error: IntegrationApiException) {
            CommerceSubmissionResult.Rejected(error.message.orEmpty(), retryable = false)
        } catch (error: IllegalArgumentException) {
            CommerceSubmissionResult.Rejected(error.message ?: "提交参数不正确", retryable = true)
        } catch (_: Throwable) {
            CommerceSubmissionResult.Rejected("服务暂时不可用，请稍后重试", retryable = false)
        } finally {
            pruneCache()
        }
    }

    fun observeProject(projectId: String): Flow<CommerceProjectUpdate> = flow {
        while (currentCoroutineContext().isActive) {
            val detail = repository.project(projectId).valueOrThrow()
            val terminal = detail.imageTasks.isNotEmpty() && detail.imageTasks.all { it.status.isTerminalImageStatus() }
            emit(
                CommerceProjectUpdate(
                    projectId = projectId,
                    status = detail.project.status,
                    tasks = detail.imageTasks,
                    totalCredits = detail.imageTasks.sumOf(ImageTask::creditsCost),
                    terminal = terminal,
                ),
            )
            if (terminal) break
            delay(TASK_POLL_INTERVAL_MS)
        }
    }

    private suspend fun submitOnce(request: CommerceSubmitRequest): CommerceSubmissionResult {
        require(request.inputImages.isNotEmpty()) { "请至少选择一张图片" }
        require(request.inputImages.size <= MAX_IMAGES) { "最多支持 $MAX_IMAGES 张图片" }
        val quote = authoritativeQuote(request)
        if (quote.totalCredits != request.quotedCredits || quote.outputCount != request.expectedOutputCount) {
            throw ServerQuoteChangedException(quote.totalCredits)
        }

        val before = captureProjectSnapshot()
        return if (request.kind == CommerceCreationKind.IMAGE_TRANSLATION.code) {
            submitTranslation(request, before)
        } else {
            submitSuite(request, before)
        }
    }

    private suspend fun submitSuite(
        request: CommerceSubmitRequest,
        before: ProjectSnapshot,
    ): CommerceSubmissionResult {
        val parts = buildSuiteParts(request)
        val response = try {
            withTimeout(SUBMISSION_TIMEOUT_MS) { repository.generateCommerce(parts) }
        } catch (_: TimeoutCancellationException) {
            return reconcileWithRetry(request, before, SOURCE_COMMERCE) ?: throw SubmissionUnknownException()
        }
        return when (response) {
            is ApiResult.Success -> response.value.accepted()
            is ApiResult.Failure -> if (response.isAmbiguousSubmission()) {
                reconcileWithRetry(request, before, SOURCE_COMMERCE) ?: throw SubmissionUnknownException()
            } else {
                throw IntegrationApiException(response.message, response.code)
            }
        }
    }

    private suspend fun submitTranslation(
        request: CommerceSubmitRequest,
        before: ProjectSnapshot,
    ): CommerceSubmissionResult {
        val targetLanguage = normalizeTargetLanguage(request.targetLanguage)
        val parts = buildList {
            add(multipart.text("target_language", targetLanguage))
            add(multipart.text("resolution", request.resolution.uppercase()))
            addAll(
                multipart.imageParts(
                    fieldName = "images",
                    uploads = request.inputImages.toUploadDescriptors(),
                    maxFiles = MAX_IMAGES,
                    maxTotalBytes = TRANSLATION_MAX_TOTAL_BYTES,
                ),
            )
        }
        val response = try {
            withTimeout(SUBMISSION_TIMEOUT_MS) { repository.generateImageTranslation(parts) }
        } catch (_: TimeoutCancellationException) {
            return reconcileWithRetry(request, before, SOURCE_TRANSLATION) ?: throw SubmissionUnknownException()
        }
        return when (response) {
            is ApiResult.Success -> response.value.accepted()
            is ApiResult.Failure -> if (response.isAmbiguousSubmission()) {
                reconcileWithRetry(request, before, SOURCE_TRANSLATION) ?: throw SubmissionUnknownException()
            } else {
                throw IntegrationApiException(response.message, response.code)
            }
        }
    }

    private suspend fun authoritativeQuote(request: CommerceSubmitRequest): CommerceQuote {
        if (request.kind == CommerceCreationKind.IMAGE_TRANSLATION.code) {
            throw IntegrationApiException(
                "服务端暂未提供图片翻译报价，当前不能提交收费任务",
                "TRANSLATION_ESTIMATE_UNAVAILABLE",
            )
        }
        val estimate = repository.estimateCommerce(
            CommerceEstimateRequestDto(
                platform = request.platform,
                selectedSlots = request.deliverables,
                resolution = request.resolution.uppercase(),
            ),
        ).valueOrThrow()
        return estimate.toQuote()
    }

    private fun buildSuiteParts(request: CommerceSubmitRequest) = buildList {
        val productInfo = CommerceProductInfoDto(
            productName = request.productName,
            category = request.category,
            brand = request.brand,
            sellingPoints = request.coreSellingPoints.toSellingPoints(),
            material = request.productFacts,
            specs = request.customRequirements,
            country = request.platform,
            language = "zh-CN",
        )
        val overlayOptions = buildJsonObject {
            put("preserve_layout", request.preserveLayout)
            if (request.customRequirements.isNotBlank()) {
                put("custom_requirements", request.customRequirements)
            }
        }
        add(multipart.text("platform", request.platform))
        add(multipart.text("product_info", json.encodeToString(productInfo)))
        add(multipart.text("selected_slots", json.encodeToString(request.deliverables)))
        add(multipart.text("resolution", request.resolution.uppercase()))
        add(multipart.text("overlay_options", json.encodeToString(overlayOptions)))
        addAll(
            multipart.imageParts(
                fieldName = "references",
                uploads = request.inputImages.toUploadDescriptors(),
                maxFiles = MAX_IMAGES,
                maxTotalBytes = COMMERCE_MAX_TOTAL_BYTES,
            ),
        )
    }

    private suspend fun captureProjectSnapshot(): ProjectSnapshot = when (val result = repository.projects()) {
        is ApiResult.Success -> ProjectSnapshot(result.value.map { it.id }.toSet(), known = true)
        is ApiResult.Failure -> ProjectSnapshot(emptySet(), known = false)
    }

    private suspend fun reconcile(
        request: CommerceSubmitRequest,
        before: ProjectSnapshot,
        sourceType: String,
    ): CommerceSubmissionResult.Accepted? {
        if (!before.known) return null
        val projects = (repository.projects() as? ApiResult.Success)?.value.orEmpty()
        val candidates = projects.filter { project ->
            project.id !in before.ids &&
                project.sourceType == sourceType &&
                project.totalImages == request.expectedOutputCount &&
                (sourceType == SOURCE_TRANSLATION || project.productName == request.productName)
        }
        if (candidates.size != 1) return null
        val project = candidates.single()
        val detail = (repository.project(project.id) as? ApiResult.Success)?.value
        val serverCredits = detail?.imageTasks?.sumOf(ImageTask::creditsCost)
            ?.takeIf { it > 0 }
            ?: project.creditsCost
        return CommerceSubmissionResult.Accepted(project.id, serverCredits)
    }

    private suspend fun reconcileWithRetry(
        request: CommerceSubmitRequest,
        before: ProjectSnapshot,
        sourceType: String,
    ): CommerceSubmissionResult.Accepted? {
        repeat(RECONCILIATION_ATTEMPTS) { attempt ->
            reconcile(request, before, sourceType)?.let { return it }
            if (attempt < RECONCILIATION_ATTEMPTS - 1) delay(RECONCILIATION_DELAY_MS)
        }
        return null
    }

    private fun CommerceGenerateResponseDto.accepted() =
        CommerceSubmissionResult.Accepted(taskId = projectId, chargedCredits = creditsCost)

    private fun ImageTranslationGenerateResponseDto.accepted() =
        CommerceSubmissionResult.Accepted(taskId = projectId, chargedCredits = creditsCost)

    private fun List<CommerceInputImage>.toUploadDescriptors(): List<UploadDescriptor> =
        sortedBy(CommerceInputImage::order).map { image ->
            UploadDescriptor(
                uri = image.contentUri,
                displayName = image.displayName,
                mimeType = image.mimeType,
                declaredSize = image.sizeBytes,
                order = image.order,
            )
        }

    private fun CommerceEstimateDto.toQuote(): CommerceQuote {
        val perImage = if (imageCount > 0 && creditsCost % imageCount == 0) creditsCost / imageCount else 0
        return CommerceQuote(imageCount, perImage, creditsCost, isServerQuote = true)
    }

    private fun CommerceSubmitRequest.fingerprint(): String {
        val source = buildString {
            append(kind).append('\n').append(productName).append('\n').append(platform)
            append('\n').append(resolution).append('\n').append(deliverables.joinToString(","))
            append('\n').append(targetLanguage.orEmpty())
            inputImages.sortedBy(CommerceInputImage::order).forEach { append('\n').append(it.contentUri) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun pruneCache() {
        val now = System.currentTimeMillis()
        recentAccepted.entries.removeIf { now - it.value.createdAtMillis > RAPID_DUPLICATE_WINDOW_MS }
        uncertainSubmissions.entries.removeIf { now - it.value > UNCERTAIN_SUBMISSION_WINDOW_MS }
    }

    private fun rememberUncertainSubmission(operationKey: String) {
        val now = System.currentTimeMillis()
        uncertainSubmissions.compute(operationKey) { _, previous ->
            previous?.takeIf { now - it <= UNCERTAIN_SUBMISSION_WINDOW_MS } ?: now
        }
    }

    private data class ProjectSnapshot(val ids: Set<String>, val known: Boolean)
    private data class CachedAccepted(
        val result: CommerceSubmissionResult.Accepted,
        val createdAtMillis: Long,
    )

    companion object {

        private const val SUBMISSION_TIMEOUT_MS = 90_000L
        private const val TASK_POLL_INTERVAL_MS = 2_500L
        private const val RAPID_DUPLICATE_WINDOW_MS = 10_000L
        private const val UNCERTAIN_SUBMISSION_WINDOW_MS = 120_000L
        private const val RECONCILIATION_ATTEMPTS = 3
        private const val RECONCILIATION_DELAY_MS = 750L
        private const val MAX_IMAGES = 10
        private const val COMMERCE_MAX_TOTAL_BYTES = 30L * 1024L * 1024L
        private const val TRANSLATION_MAX_TOTAL_BYTES = 28L * 1024L * 1024L
        private const val SOURCE_COMMERCE = "commerce_suite"
        private const val SOURCE_TRANSLATION = "image_translation"
    }
}

private fun String.toSellingPoints(): List<String> = split(Regex("[\\r\\n;；]+"))
    .map(String::trim)
    .filter(String::isNotBlank)
    .take(20)

private fun normalizeTargetLanguage(value: String?): String = when (value.orEmpty()) {
    "ms" -> "ms-MY"
    "en" -> "en-US"
    "id" -> "id-ID"
    "th" -> "th-TH"
    "vi" -> "vi-VN"
    "ja" -> "ja-JP"
    "ko" -> "ko-KR"
    "es" -> "es-ES"
    "fr" -> "fr-FR"
    "de" -> "de-DE"
    else -> value.orEmpty()
}

private fun String.isTerminalImageStatus(): Boolean =
    equals("success", true) || equals("completed", true) || equals("failed", true) ||
        equals("cancelled", true) || equals("canceled", true) || equals("timeout", true)
