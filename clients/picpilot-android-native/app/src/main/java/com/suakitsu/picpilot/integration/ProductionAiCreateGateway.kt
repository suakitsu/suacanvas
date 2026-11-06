package com.suakitsu.picpilot.integration

import com.suakitsu.picpilot.core.media.resolveMediaUrl
import com.suakitsu.picpilot.core.media.mediaReferenceForApi
import com.suakitsu.picpilot.core.model.CreativeChatGenerateRequestDto
import com.suakitsu.picpilot.core.model.CreativeChatGenerateResponseDto
import com.suakitsu.picpilot.core.model.CreativeChatViewDto
import com.suakitsu.picpilot.core.model.ImageTask
import com.suakitsu.picpilot.core.repository.PicPilotRepository
import com.suakitsu.picpilot.feature.aicreate.AI_CREATE_MAX_REFERENCES
import com.suakitsu.picpilot.feature.aicreate.AiCreateCatalog
import com.suakitsu.picpilot.feature.aicreate.AiCreateConversation
import com.suakitsu.picpilot.feature.aicreate.AiCreateGateway
import com.suakitsu.picpilot.feature.aicreate.AiCreateGenerateRequest
import com.suakitsu.picpilot.feature.aicreate.AiCreateMessage
import com.suakitsu.picpilot.feature.aicreate.AiCreateMessageRole
import com.suakitsu.picpilot.feature.aicreate.AiCreateQualityOption
import com.suakitsu.picpilot.feature.aicreate.AiCreateQuoteChangedException
import com.suakitsu.picpilot.feature.aicreate.AiCreateReference
import com.suakitsu.picpilot.feature.aicreate.AiCreateReferenceSource
import com.suakitsu.picpilot.feature.aicreate.AiCreateResultImage
import com.suakitsu.picpilot.feature.aicreate.AiCreateSubmitReceipt
import com.suakitsu.picpilot.feature.aicreate.AiCreateTaskPresentation
import com.suakitsu.picpilot.feature.aicreate.AiCreateTaskStatus
import com.suakitsu.picpilot.feature.aicreate.AiCreateTaskUpdate
import com.suakitsu.picpilot.feature.aicreate.defaultAiCreateStyles
import com.suakitsu.picpilot.integration.upload.OrderedMultipartFactory
import com.suakitsu.picpilot.integration.upload.UploadDescriptor
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

@Singleton
class ProductionAiCreateGateway @Inject constructor(
    private val repository: PicPilotRepository,
    private val multipart: OrderedMultipartFactory,
) : AiCreateGateway {
    private val taskBindings = ConcurrentHashMap<String, TaskBinding>()
    private val submitLocks = ConcurrentHashMap<String, Mutex>()
    private val recentReceipts = ConcurrentHashMap<String, CachedReceipt>()
    private val uncertainSubmissions = ConcurrentHashMap<String, Long>()

    override suspend fun loadConversations(): Result<List<AiCreateConversation>> = integrationResult {
        repository.creativeChats().valueOrThrow().map { chat ->
            AiCreateConversation(
                id = chat.id,
                title = chat.title.ifBlank { "AI 生图对话" },
                preview = chat.latestStatus.ifBlank { chat.style },
                updatedAtLabel = chat.updatedAt,
            )
        }
    }

    override suspend fun loadMessages(conversationId: String): Result<List<AiCreateMessage>> = integrationResult {
        val view = repository.creativeChat(conversationId).valueOrThrow()
        mapMessages(view)
    }

    override suspend fun estimate(request: AiCreateGenerateRequest): Result<Int> = integrationResult {
        estimateCredits(request.prompt, request.quality.id)
    }

    override suspend fun loadCatalog(): Result<AiCreateCatalog> = integrationResult {
        val qualities = QUALITY_SIZES.map { (id, size) ->
            val estimate = repository.estimateCreativeChat(
                CreativeChatGenerateRequestDto(
                    message = CATALOG_ESTIMATE_PROMPT,
                    size = size,
                    language = "zh-CN",
                ),
            ).valueOrThrow()
            require(estimate.creditsCost > 0) { "服务端返回了无效的 AI 创作价格" }
            AiCreateQualityOption(
                id = id,
                label = estimate.qualityLabel.ifBlank { id.uppercase() },
                pixels = size,
                credits = estimate.creditsCost,
            )
        }
        AiCreateCatalog(
            qualities = qualities,
            styles = defaultAiCreateStyles,
            authoritative = true,
        )
    }

    override suspend fun loadBalance(): Result<Int> = integrationResult {
        repository.me().valueOrThrow().credits
    }

    override suspend fun submit(request: AiCreateGenerateRequest): Result<AiCreateSubmitReceipt> = integrationResult {
        require(request.referenceImages.size <= AI_CREATE_MAX_REFERENCES) {
            "最多支持 $AI_CREATE_MAX_REFERENCES 张参考图"
        }
        val operationKey = request.fingerprint()
        val lock = submitLocks.computeIfAbsent(operationKey) { Mutex() }
        try {
            lock.withLock {
                uncertainSubmissions[operationKey]
                    ?.takeIf { System.currentTimeMillis() - it <= UNCERTAIN_SUBMISSION_WINDOW_MS }
                    ?.let { throw SubmissionUnknownException() }
                recentReceipts[operationKey]
                    ?.takeIf { System.currentTimeMillis() - it.createdAtMillis <= RAPID_DUPLICATE_WINDOW_MS }
                    ?.receipt
                    ?: submitOnce(request).also { receipt ->
                        recentReceipts[operationKey] = CachedReceipt(receipt, System.currentTimeMillis())
                    }
            }
        } catch (changed: ServerQuoteChangedException) {
            throw AiCreateQuoteChangedException(changed.authoritativeCredits)
        } catch (unknown: SubmissionUnknownException) {
            rememberUncertainSubmission(operationKey)
            throw unknown
        } finally {
            pruneReceiptCache()
        }
    }

    override fun observeTask(taskId: String): Flow<AiCreateTaskUpdate> = flow {
        val binding = taskBindings[taskId]
            ?: throw IllegalStateException("任务上下文已失效，请刷新对话后继续查看")
        while (currentCoroutineContext().isActive) {
            val view = repository.creativeChat(binding.chatId).valueOrThrow()
            val task = view.messageTask(taskId)
            val version = view.versions.firstOrNull { it.taskId == taskId }
            val resultUrl = task?.resultUrl?.takeIf(String::isNotBlank)
                ?: version?.resultUrl?.takeIf(String::isNotBlank)
            val status = if (resultUrl != null) {
                AiCreateTaskStatus.Completed
            } else {
                mapStatus(task?.status ?: version?.status)
            }
            emit(
                AiCreateTaskUpdate(
                    taskId = taskId,
                    status = status,
                    progress = status.progress(),
                    safeError = version?.safeError,
                    results = resultUrl?.let {
                        listOf(AiCreateResultImage(id = "result_$taskId", url = resolveMediaUrl(it)))
                    }.orEmpty(),
                ),
            )
            if (status.isTerminal()) break
            delay(TASK_POLL_INTERVAL_MS)
        }
    }

    private suspend fun submitOnce(request: AiCreateGenerateRequest): AiCreateSubmitReceipt {
        val authoritativeCredits = estimateCredits(request.prompt, request.quality.id)
        if (authoritativeCredits != request.expectedCredits) {
            throw ServerQuoteChangedException(authoritativeCredits)
        }

        val snapshot = captureSnapshot(request)
        val localUploads = request.referenceImages.filter(AiCreateReference::isLocalContent)
        val remoteUrls = request.referenceImages.filterNot(AiCreateReference::isLocalContent)
            .map { mediaReferenceForApi(it.uri) }
            .filter(::isAllowedReferenceUrl)
            .distinct()
            .take(AI_CREATE_MAX_REFERENCES - localUploads.size)
        val payload = request.toDto(remoteUrls)
        val result = try {
            withTimeout(SUBMISSION_TIMEOUT_MS) {
                if (localUploads.isEmpty()) {
                    repository.generateCreativeChat(payload)
                } else {
                    repository.generateCreativeChat(buildMultipart(payload, localUploads))
                }
            }
        } catch (_: TimeoutCancellationException) {
            return reconcileWithRetry(request, snapshot) ?: throw SubmissionUnknownException()
        }

        return when (result) {
            is com.suakitsu.picpilot.core.network.ApiResult.Success -> receipt(result.value)
            is com.suakitsu.picpilot.core.network.ApiResult.Failure -> {
                if (result.isAmbiguousSubmission()) {
                    reconcileWithRetry(request, snapshot) ?: throw SubmissionUnknownException()
                } else {
                    throw IntegrationApiException(result.message, result.code)
                }
            }
        }
    }

    private suspend fun estimateCredits(prompt: String, qualityId: String): Int =
        repository.estimateCreativeChat(
            CreativeChatGenerateRequestDto(
                message = prompt,
                size = qualitySize(qualityId),
                language = "zh-CN",
            ),
        ).valueOrThrow().creditsCost

    private fun buildMultipart(
        payload: CreativeChatGenerateRequestDto,
        uploads: List<AiCreateReference>,
    ) = buildList {
        if (payload.chatId.isNotBlank()) add(multipart.text("chatId", payload.chatId))
        add(multipart.text("message", payload.message))
        add(multipart.text("style", payload.style))
        add(multipart.text("size", payload.size))
        add(multipart.text("language", payload.language))
        addAll(multipart.repeatedText("referenceUrls", payload.referenceUrls))
        addAll(
            multipart.imageParts(
                fieldName = "references",
                uploads = uploads.mapIndexed { index, reference ->
                    UploadDescriptor(
                        uri = reference.uri,
                        displayName = reference.displayName,
                        mimeType = reference.mimeType,
                        order = index,
                    )
                },
                maxFiles = AI_CREATE_MAX_REFERENCES,
            ),
        )
    }

    private suspend fun captureSnapshot(request: AiCreateGenerateRequest): SubmissionSnapshot {
        val serverChatId = request.conversationId.takeUnless(String::isLocalConversation)
        return if (serverChatId != null) {
            val view = (repository.creativeChat(serverChatId) as? com.suakitsu.picpilot.core.network.ApiResult.Success)?.value
            SubmissionSnapshot(
                chatId = serverChatId,
                existingChatIds = emptySet(),
                existingTaskIds = view?.versions?.mapNotNull { it.taskId }?.toSet().orEmpty(),
            )
        } else {
            val chats = (repository.creativeChats() as? com.suakitsu.picpilot.core.network.ApiResult.Success)?.value.orEmpty()
            SubmissionSnapshot(null, chats.map { it.id }.toSet(), emptySet())
        }
    }

    private suspend fun reconcile(
        request: AiCreateGenerateRequest,
        snapshot: SubmissionSnapshot,
    ): AiCreateSubmitReceipt? {
        if (snapshot.chatId != null) {
            val view = (repository.creativeChat(snapshot.chatId) as? com.suakitsu.picpilot.core.network.ApiResult.Success)?.value
                ?: return null
            return reconcileFromView(view, request.prompt, snapshot.existingTaskIds)
        }
        val chats = (repository.creativeChats() as? com.suakitsu.picpilot.core.network.ApiResult.Success)?.value.orEmpty()
            .filterNot { it.id in snapshot.existingChatIds }
            .take(RECONCILIATION_CHAT_LIMIT)
        val matches = chats.mapNotNull { chat ->
            val view = (repository.creativeChat(chat.id) as? com.suakitsu.picpilot.core.network.ApiResult.Success)?.value
                ?: return@mapNotNull null
            reconcileFromView(view, request.prompt, emptySet())
        }
        return matches.singleOrNull()
    }

    private suspend fun reconcileWithRetry(
        request: AiCreateGenerateRequest,
        snapshot: SubmissionSnapshot,
    ): AiCreateSubmitReceipt? {
        repeat(RECONCILIATION_ATTEMPTS) { attempt ->
            reconcile(request, snapshot)?.let { return it }
            if (attempt < RECONCILIATION_ATTEMPTS - 1) delay(RECONCILIATION_DELAY_MS)
        }
        return null
    }

    private suspend fun reconcileFromView(
        view: CreativeChatViewDto,
        prompt: String,
        previousTaskIds: Set<String>,
    ): AiCreateSubmitReceipt? {
        val version = view.versions.filter {
            it.taskId != null && it.taskId !in previousTaskIds && it.userRequest.trim() == prompt.trim()
        }.singleOrNull() ?: return null
        val taskId = version.taskId ?: return null
        val projectId = version.projectId ?: return null
        val detail = (repository.project(projectId) as? com.suakitsu.picpilot.core.network.ApiResult.Success)?.value
            ?: return null
        val task = detail.imageTasks.firstOrNull { it.id == taskId } ?: return null
        taskBindings[taskId] = TaskBinding(view.chat.id, projectId)
        val balance = (repository.me() as? com.suakitsu.picpilot.core.network.ApiResult.Success)?.value?.credits
        return AiCreateSubmitReceipt(taskId, mapStatus(task.status), task.creditsCost, balance)
    }

    private suspend fun receipt(response: CreativeChatGenerateResponseDto): AiCreateSubmitReceipt {
        val project = response.project
            ?: throw IllegalStateException("本次请求未创建图片任务，请补充明确的生图要求")
        val responseTaskId = response.chat.versions.firstOrNull { it.versionNo == response.versionNo }?.taskId
        val task = responseTaskId?.let { taskId -> project.imageTasks.firstOrNull { it.id == taskId } }
            ?: project.imageTasks.singleOrNull()
            ?: throw IllegalStateException("服务端未返回图片任务，请刷新历史记录核对")
        taskBindings[task.id] = TaskBinding(response.chat.chat.id, project.project.id)
        val balance = (repository.me() as? com.suakitsu.picpilot.core.network.ApiResult.Success)?.value?.credits
        return AiCreateSubmitReceipt(
            taskId = task.id,
            status = mapStatus(task.status),
            chargedCredits = task.creditsCost,
            balanceAfter = balance,
        )
    }

    private fun mapMessages(view: CreativeChatViewDto): List<AiCreateMessage> {
        val versionsByUser = view.versions.mapNotNull { version -> version.userMessageId?.let { it to version } }.toMap()
        val versionsByAssistant = view.versions.mapNotNull { version ->
            version.assistantMessageId?.let { it to version }
        }.toMap()
        view.versions.forEach { version ->
            version.taskId?.let { taskId -> taskBindings[taskId] = TaskBinding(view.chat.id, version.projectId) }
        }
        return view.messages.map { message ->
            val version = versionsByUser[message.id] ?: versionsByAssistant[message.id]
            val references = version?.let { item ->
                (listOfNotNull(item.referenceUrl?.takeIf(String::isNotBlank)) + item.referenceUrls)
                    .distinct()
                    .take(AI_CREATE_MAX_REFERENCES)
                    .mapIndexed { index, url ->
                        AiCreateReference(
                            id = "${message.id}_reference_$index",
                            uri = resolveMediaUrl(url),
                            source = AiCreateReferenceSource.Conversation,
                            sourceMessageId = message.id,
                        )
                    }
            }.orEmpty()
            val isAssistant = message.role.equals("assistant", true)
            val taskId = message.taskId ?: version?.taskId?.takeIf { isAssistant }
            val resultUrl = message.resultUrl?.takeIf(String::isNotBlank)
                ?: version?.resultUrl?.takeIf(String::isNotBlank)
            val status = if (resultUrl != null) {
                AiCreateTaskStatus.Completed
            } else {
                mapStatus(message.status ?: version?.status)
            }
            AiCreateMessage(
                id = message.id,
                role = if (isAssistant) AiCreateMessageRole.Assistant else AiCreateMessageRole.User,
                text = message.content,
                references = references,
                task = taskId?.let {
                    AiCreateTaskPresentation(
                        localId = "server_$it",
                        taskId = it,
                        status = status,
                        progress = status.progress(),
                        results = resultUrl?.let { url ->
                            listOf(AiCreateResultImage("result_$it", resolveMediaUrl(url)))
                        }.orEmpty(),
                        originalPrompt = version?.userRequest.orEmpty(),
                        originalReferences = references,
                    )
                },
                createdAtLabel = message.createdAt,
            )
        }
    }

    private fun CreativeChatViewDto.messageTask(taskId: String) = messages.firstOrNull { it.taskId == taskId }

    private fun AiCreateGenerateRequest.toDto(remoteUrls: List<String>) = CreativeChatGenerateRequestDto(
        chatId = conversationId.takeUnless(String::isLocalConversation).orEmpty(),
        message = prompt.trim().take(3000),
        style = style.label.ifBlank { "自由创作" },
        size = qualitySize(quality.id),
        language = "zh-CN",
        referenceUrls = remoteUrls,
    )

    private fun AiCreateGenerateRequest.fingerprint(): String {
        val source = buildString {
            append(conversationId).append('\n')
            append(prompt.trim()).append('\n')
            append(quality.id).append('\n').append(style.id)
            referenceImages.forEach { append('\n').append(it.uri) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun pruneReceiptCache() {
        val now = System.currentTimeMillis()
        recentReceipts.entries.removeIf { now - it.value.createdAtMillis > RAPID_DUPLICATE_WINDOW_MS }
        uncertainSubmissions.entries.removeIf { now - it.value > UNCERTAIN_SUBMISSION_WINDOW_MS }
    }

    private fun rememberUncertainSubmission(operationKey: String) {
        val now = System.currentTimeMillis()
        uncertainSubmissions.compute(operationKey) { _, previous ->
            previous?.takeIf { now - it <= UNCERTAIN_SUBMISSION_WINDOW_MS } ?: now
        }
    }

    private data class TaskBinding(val chatId: String, val projectId: String?)
    private data class SubmissionSnapshot(
        val chatId: String?,
        val existingChatIds: Set<String>,
        val existingTaskIds: Set<String>,
    )
    private data class CachedReceipt(val receipt: AiCreateSubmitReceipt, val createdAtMillis: Long)

    companion object {
        private const val SUBMISSION_TIMEOUT_MS = 90_000L
        private const val TASK_POLL_INTERVAL_MS = 2_500L
        private const val RAPID_DUPLICATE_WINDOW_MS = 10_000L
        private const val UNCERTAIN_SUBMISSION_WINDOW_MS = 120_000L
        private const val RECONCILIATION_CHAT_LIMIT = 5
        private const val RECONCILIATION_ATTEMPTS = 3
        private const val RECONCILIATION_DELAY_MS = 750L
        private const val CATALOG_ESTIMATE_PROMPT = "生成一张商品图片"
        private val QUALITY_SIZES = linkedMapOf(
            "1k" to "1024x1024",
            "2k" to "2048x2048",
            "4k" to "2880x2880",
        )
    }
}

private fun qualitySize(id: String): String = when (id.lowercase()) {
    "2k" -> "2048x2048"
    "4k" -> "2880x2880"
    else -> "1024x1024"
}

private fun AiCreateReference.isLocalContent(): Boolean = uri.startsWith("content://", ignoreCase = true)

private fun isAllowedReferenceUrl(value: String): Boolean =
    value.startsWith("https://", true) || value.startsWith("http://", true) || value.startsWith("/")

private fun String.isLocalConversation(): Boolean = startsWith("local_chat_")

private fun mapStatus(value: String?): AiCreateTaskStatus = when (value?.lowercase()) {
    "success", "completed" -> AiCreateTaskStatus.Completed
    "failed", "timeout" -> AiCreateTaskStatus.Failed
    "cancelled", "canceled" -> AiCreateTaskStatus.Cancelled
    "running", "generating", "upscaling", "quality_check" -> AiCreateTaskStatus.Generating
    "submitted", "submitting" -> AiCreateTaskStatus.Submitting
    else -> AiCreateTaskStatus.Queued
}

private fun AiCreateTaskStatus.progress(): Int = when (this) {
    AiCreateTaskStatus.Queued -> 5
    AiCreateTaskStatus.Submitting -> 10
    AiCreateTaskStatus.Generating -> 55
    AiCreateTaskStatus.Completed, AiCreateTaskStatus.Failed, AiCreateTaskStatus.Cancelled -> 100
}

private fun AiCreateTaskStatus.isTerminal(): Boolean =
    this == AiCreateTaskStatus.Completed || this == AiCreateTaskStatus.Failed || this == AiCreateTaskStatus.Cancelled
