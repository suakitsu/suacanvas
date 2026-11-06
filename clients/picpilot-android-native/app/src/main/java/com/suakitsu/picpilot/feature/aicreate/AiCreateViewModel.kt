package com.suakitsu.picpilot.feature.aicreate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiCreateViewModel(
    private val gateway: AiCreateGateway = NoOpAiCreateGateway,
    initialCatalog: AiCreateCatalog = AiCreateCatalog(),
    initialCredits: Int? = null,
) : ViewModel() {
    private val messagesByConversation = linkedMapOf<String, List<AiCreateMessage>>()
    private val taskObservers = mutableMapOf<String, Job>()
    private val _uiState = MutableStateFlow(
        AiCreateUiState(
            catalog = initialCatalog,
            availableCredits = initialCredits,
            isCatalogLoading = !initialCatalog.authoritative,
        ),
    )
    val uiState: StateFlow<AiCreateUiState> = _uiState.asStateFlow()

    init {
        refresh(initial = true)
    }

    fun refresh(initial: Boolean = false) {
        if (_uiState.value.isRefreshing || (!initial && _uiState.value.isLoading)) return
        _uiState.update {
            it.copy(
                isLoading = initial && it.conversations.isEmpty(),
                isRefreshing = !initial,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            val (catalogResult, balanceResult, conversationsResult) = coroutineScope {
                val catalogRequest = async { gateway.loadCatalog() }
                val balanceRequest = async { gateway.loadBalance() }
                val conversationsRequest = async { gateway.loadConversations() }
                Triple(catalogRequest.await(), balanceRequest.await(), conversationsRequest.await())
            }
            catalogResult.onSuccess(::updateCatalog)
            balanceResult.onSuccess(::updateBalance)
            conversationsResult
                .onSuccess { remote ->
                    val conversations = remote.ifEmpty { _uiState.value.conversations }
                    if (conversations.isEmpty()) {
                        createLocalConversation(select = true)
                    } else {
                        val selected = _uiState.value.selectedConversationId
                            ?.takeIf { id -> conversations.any { it.id == id } }
                            ?: conversations.first().id
                        _uiState.update {
                            it.copy(
                                conversations = conversations,
                                selectedConversationId = selected,
                            )
                        }
                        loadMessages(selected)
                    }
                }
                .onFailure { error ->
                    if (_uiState.value.conversations.isEmpty()) createLocalConversation(select = true)
                    _uiState.update { it.copy(errorMessage = safeMessage(error)) }
                }
            _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
        }
    }

    fun updateCatalog(value: AiCreateCatalog) {
        if (
            !value.authoritative ||
            value.qualities.isEmpty() ||
            value.styles.isEmpty() ||
            value.qualities.any { it.credits <= 0 }
        ) return
        _uiState.update { state ->
            val qualityId = state.selectedQualityId.takeIf { selected -> value.qualities.any { it.id == selected } }
                ?: value.qualities.first().id
            val styleId = state.selectedStyleId.takeIf { selected -> value.styles.any { it.id == selected } }
                ?: value.styles.first().id
            val pendingInvalidated = state.pendingRequest != null && state.catalog != value
            state.copy(
                catalog = value,
                selectedQualityId = qualityId,
                selectedStyleId = styleId,
                isCatalogLoading = false,
                pendingRequest = if (pendingInvalidated) null else state.pendingRequest,
                noticeMessage = if (pendingInvalidated) "生成配置已更新，请重新确认" else state.noticeMessage,
            )
        }
    }

    fun updateBalance(value: Int?) {
        _uiState.update { it.copy(availableCredits = value) }
    }

    fun selectConversation(conversationId: String) {
        if (conversationId == _uiState.value.selectedConversationId) return
        _uiState.update {
            it.copy(
                selectedConversationId = conversationId,
                messages = messagesByConversation[conversationId].orEmpty(),
                errorMessage = null,
                noticeMessage = null,
            )
        }
        if (!messagesByConversation.containsKey(conversationId)) loadMessages(conversationId)
    }

    fun newConversation() {
        createLocalConversation(select = true)
    }

    fun updatePrompt(value: String) {
        _uiState.update { it.copy(prompt = value.take(3000), errorMessage = null) }
    }

    fun selectQuality(id: String) {
        if (_uiState.value.catalog.qualities.none { it.id == id }) return
        _uiState.update { it.copy(selectedQualityId = id, errorMessage = null) }
    }

    fun selectStyle(id: String) {
        if (_uiState.value.catalog.styles.none { it.id == id }) return
        _uiState.update { it.copy(selectedStyleId = id, errorMessage = null) }
    }

    fun addReferences(references: List<AiCreateReference>) {
        if (references.isEmpty()) return
        val current = _uiState.value.draftReferences
        val merged = (current + references)
            .distinctBy { it.uri }
            .take(AI_CREATE_MAX_REFERENCES)
        val omitted = current.size + references.size - merged.size
        _uiState.update {
            it.copy(
                draftReferences = merged,
                errorMessage = null,
                noticeMessage = if (omitted > 0) "最多引用 $AI_CREATE_MAX_REFERENCES 张图片" else null,
            )
        }
    }

    fun referenceResult(messageId: String, image: AiCreateResultImage) {
        addReferences(
            listOf(
                AiCreateReference(
                    id = "ref_${UUID.randomUUID()}",
                    uri = image.url,
                    displayName = image.displayName,
                    mimeType = image.mimeType,
                    source = AiCreateReferenceSource.Conversation,
                    sourceMessageId = messageId,
                ),
            ),
        )
    }

    fun referenceMessageImage(messageId: String, reference: AiCreateReference) {
        addReferences(
            listOf(
                reference.copy(
                    id = "ref_${UUID.randomUUID()}",
                    source = AiCreateReferenceSource.Conversation,
                    sourceMessageId = messageId,
                ),
            ),
        )
    }

    fun removeReference(id: String) {
        _uiState.update {
            it.copy(draftReferences = it.draftReferences.filterNot { reference -> reference.id == id })
        }
    }

    fun requestGeneration() {
        val state = _uiState.value
        if (!state.catalog.authoritative || state.isCatalogLoading) {
            _uiState.update { it.copy(errorMessage = "生成配置和价格仍在加载，请稍后再试") }
            return
        }
        if (state.isEstimating || state.isSubmitting) return
        val conversationId = state.selectedConversationId ?: createLocalConversation(select = true)
        val prompt = state.prompt.trim()
        if (prompt.length < AI_CREATE_MIN_PROMPT_LENGTH) {
            _uiState.update { it.copy(errorMessage = "请至少输入 $AI_CREATE_MIN_PROMPT_LENGTH 个字符的提示词") }
            return
        }
        val quality = state.catalog.quality(state.selectedQualityId)
        val draft = AiCreateGenerateRequest(
            conversationId = conversationId,
            prompt = prompt,
            referenceImages = state.draftReferences,
            quality = quality,
            style = state.catalog.style(state.selectedStyleId),
            expectedCredits = quality.credits,
        )
        _uiState.update { it.copy(isEstimating = true, errorMessage = null, noticeMessage = null) }
        viewModelScope.launch {
            gateway.estimate(draft)
                .onSuccess { authoritativeCredits ->
                    val current = _uiState.value
                    val draftStillCurrent = current.prompt.trim() == draft.prompt &&
                        current.draftReferences == draft.referenceImages &&
                        current.selectedQualityId == draft.quality.id &&
                        current.selectedStyleId == draft.style.id
                    if (!draftStillCurrent) {
                        _uiState.update { it.copy(noticeMessage = "内容已更新，请重新点击生成并确认") }
                        return@onSuccess
                    }
                    val latestBalance = current.availableCredits
                    if (latestBalance != null && latestBalance < authoritativeCredits) {
                        _uiState.update { it.copy(errorMessage = "当前积分不足，请调整清晰度或先充值") }
                    } else {
                        _uiState.update {
                            it.copy(
                                pendingRequest = draft.copy(
                                    quality = draft.quality.copy(credits = authoritativeCredits),
                                    expectedCredits = authoritativeCredits,
                                ),
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(errorMessage = safeMessage(error)) }
                }
            _uiState.update { it.copy(isEstimating = false) }
        }
    }

    fun dismissConfirmation() {
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(pendingRequest = null) }
    }

    /** This is the only method in the feature that may invoke [AiCreateGateway.submit]. */
    fun confirmGeneration() {
        val request = _uiState.value.pendingRequest ?: return
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(isSubmitting = true, errorMessage = null, noticeMessage = null) }
        viewModelScope.launch {
            val latestCredits = gateway.estimate(request).getOrElse { error ->
                _uiState.update { it.copy(isSubmitting = false, errorMessage = safeMessage(error)) }
                return@launch
            }
            if (latestCredits != request.expectedCredits) {
                _uiState.update {
                    it.copy(
                        pendingRequest = request.copy(
                            quality = request.quality.copy(credits = latestCredits),
                            expectedCredits = latestCredits,
                        ),
                        isSubmitting = false,
                        noticeMessage = "服务端价格已更新为 $latestCredits 积分，请重新确认",
                    )
                }
                return@launch
            }
            val latestBalance = _uiState.value.availableCredits
            if (latestBalance != null && latestBalance < latestCredits) {
                _uiState.update {
                    it.copy(isSubmitting = false, errorMessage = "当前积分不足，请调整清晰度或先充值")
                }
                return@launch
            }

            val receipt = gateway.submit(request.copy(expectedCredits = latestCredits)).getOrElse { error ->
                if (error is AiCreateQuoteChangedException) {
                    val changedCredits = error.authoritativeCredits
                    _uiState.update {
                        it.copy(
                            pendingRequest = request.copy(
                                quality = request.quality.copy(credits = changedCredits),
                                expectedCredits = changedCredits,
                            ),
                            isSubmitting = false,
                            noticeMessage = "服务端价格已更新为 $changedCredits 积分，请重新确认",
                        )
                    }
                } else {
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = safeMessage(error)) }
                }
                return@launch
            }
            val acceptedRequest = request.copy(expectedCredits = receipt.chargedCredits)
            val localTaskId = appendAcceptedMessages(acceptedRequest, receipt)
            receipt.balanceAfter?.let(::updateBalance)
            _uiState.update {
                it.copy(
                    pendingRequest = null,
                    prompt = "",
                    draftReferences = emptyList(),
                )
            }
            observeTask(localTaskId, receipt.taskId)
            _uiState.update { it.copy(isSubmitting = false) }
        }
    }

    private fun appendAcceptedMessages(
        request: AiCreateGenerateRequest,
        receipt: AiCreateSubmitReceipt,
    ): String {
        val localTaskId = "local_${UUID.randomUUID()}"
        appendMessages(
            request.conversationId,
            listOf(
                AiCreateMessage(
                    id = "msg_${UUID.randomUUID()}",
                    role = AiCreateMessageRole.User,
                    text = request.prompt,
                    references = request.referenceImages,
                    createdAtLabel = "刚刚",
                ),
                AiCreateMessage(
                    id = "msg_${UUID.randomUUID()}",
                    role = AiCreateMessageRole.Assistant,
                    text = "正在创建图片",
                    task = AiCreateTaskPresentation(
                        localId = localTaskId,
                        taskId = receipt.taskId,
                        status = receipt.status,
                        credits = receipt.chargedCredits,
                        originalPrompt = request.prompt,
                        originalReferences = request.referenceImages,
                    ),
                    createdAtLabel = "刚刚",
                ),
            ),
        )
        updateConversationPreview(request.conversationId, request.prompt)
        return localTaskId
    }

    fun prepareRetry(messageId: String) {
        val task = _uiState.value.messages.firstOrNull { it.id == messageId }?.task ?: return
        val catalog = _uiState.value.catalog
        _uiState.update {
            it.copy(
                prompt = task.originalPrompt,
                draftReferences = task.originalReferences,
                selectedQualityId = catalog.qualities.firstOrNull { option -> option.credits == task.credits }?.id
                    ?: it.selectedQualityId,
                noticeMessage = "已恢复上次参数，请重新确认后提交",
                errorMessage = null,
            )
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(errorMessage = null, noticeMessage = null) }
    }

    fun catalog(): AiCreateCatalog = _uiState.value.catalog

    private fun loadMessages(conversationId: String) {
        viewModelScope.launch {
            gateway.loadMessages(conversationId)
                .onSuccess { messages ->
                    messagesByConversation[conversationId] = messages
                    if (_uiState.value.selectedConversationId == conversationId) {
                        _uiState.update { it.copy(messages = messages) }
                    }
                    messages.mapNotNull(AiCreateMessage::task)
                        .filterNot { it.status.isTerminal() }
                        .forEach { task ->
                            task.taskId?.let { taskId -> observeTask(task.localId, taskId) }
                        }
                }
                .onFailure { error ->
                    if (_uiState.value.selectedConversationId == conversationId) {
                        _uiState.update { it.copy(errorMessage = safeMessage(error)) }
                    }
                }
        }
    }

    private fun observeTask(localId: String, taskId: String) {
        taskObservers.remove(taskId)?.cancel()
        taskObservers[taskId] = viewModelScope.launch {
            gateway.observeTask(taskId)
                .catch { error ->
                    updateTask(localId) {
                        it.copy(status = AiCreateTaskStatus.Failed, safeError = safeMessage(error))
                    }
                }
                .collect { update ->
                    updateTask(localId) {
                        it.copy(
                            taskId = update.taskId,
                            status = update.status,
                            progress = update.progress.coerceIn(0, 100),
                            safeError = update.safeError,
                            results = update.results.ifEmpty { it.results },
                        )
                    }
                    if (update.status.isTerminal()) taskObservers.remove(taskId)?.cancel()
                }
        }
    }

    private fun updateTask(localId: String, transform: (AiCreateTaskPresentation) -> AiCreateTaskPresentation) {
        messagesByConversation.keys.toList().forEach { conversationId ->
            val changed = messagesByConversation[conversationId].orEmpty().map { message ->
                if (message.task?.localId == localId) message.copy(task = transform(message.task)) else message
            }
            messagesByConversation[conversationId] = changed
            if (_uiState.value.selectedConversationId == conversationId) {
                _uiState.update { it.copy(messages = changed) }
            }
        }
    }

    private fun appendMessages(conversationId: String, messages: List<AiCreateMessage>) {
        val updated = messagesByConversation[conversationId].orEmpty() + messages
        messagesByConversation[conversationId] = updated
        if (_uiState.value.selectedConversationId == conversationId) {
            _uiState.update { it.copy(messages = updated) }
        }
    }

    private fun createLocalConversation(select: Boolean): String {
        val id = "local_chat_${UUID.randomUUID()}"
        val conversation = AiCreateConversation(id = id, title = "新对话", preview = "开始一次新的创作")
        messagesByConversation[id] = emptyList()
        _uiState.update { state ->
            state.copy(
                conversations = listOf(conversation) + state.conversations,
                selectedConversationId = if (select) id else state.selectedConversationId,
                messages = if (select) emptyList() else state.messages,
                isLoading = false,
            )
        }
        return id
    }

    private fun updateConversationPreview(conversationId: String, prompt: String) {
        _uiState.update { state ->
            state.copy(
                conversations = state.conversations.map { conversation ->
                    if (conversation.id != conversationId) conversation else conversation.copy(
                        title = if (conversation.title == "新对话") prompt.take(18) else conversation.title,
                        preview = prompt.take(36),
                        updatedAtLabel = "刚刚",
                    )
                }.sortedByDescending { it.id == conversationId },
            )
        }
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() }?.take(160) ?: "服务暂时不可用，请稍后重试"

    class Factory(
        private val gateway: AiCreateGateway,
        private val catalog: AiCreateCatalog,
        private val initialCredits: Int?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AiCreateViewModel(gateway, catalog, initialCredits) as T
    }
}

private fun AiCreateTaskStatus.isTerminal(): Boolean =
    this == AiCreateTaskStatus.Completed || this == AiCreateTaskStatus.Failed || this == AiCreateTaskStatus.Cancelled
