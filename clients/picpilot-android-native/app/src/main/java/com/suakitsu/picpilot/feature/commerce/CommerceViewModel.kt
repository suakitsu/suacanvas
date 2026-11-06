package com.suakitsu.picpilot.feature.commerce

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suakitsu.picpilot.core.model.CommerceCatalogDto
import com.suakitsu.picpilot.core.model.ImageTask
import com.suakitsu.picpilot.integration.ProductionCommerceGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class CommerceViewModel @Inject constructor(
    private val imageReader: CommerceImageReader,
    private val gateway: ProductionCommerceGateway,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CommerceUiState())
    val uiState: StateFlow<CommerceUiState> = _uiState.asStateFlow()

    private var submitter: CommerceSubmitter? = null
    private var quoteJob: Job? = null
    private var projectJob: Job? = null

    init {
        refreshConfiguration()
        savedStateHandle.get<String>(SAVED_PROJECT_ID)
            ?.takeIf(String::isNotBlank)
            ?.let(::restoreProject)
    }

    fun attachSubmitter(value: CommerceSubmitter?) {
        submitter = value
    }

    fun refreshConfiguration() {
        if (_uiState.value.isConfigurationLoading) return
        _uiState.update {
            it.copy(
                isConfigurationLoading = true,
                isBalanceLoading = true,
                quotePhase = CommerceQuotePhase.LOADING,
                quoteMessage = null,
            )
        }
        viewModelScope.launch {
            val catalogResult = gateway.loadCatalog()
            val languagesResult = gateway.loadTranslationLanguages()
            val balanceResult = gateway.loadBalance()
            _uiState.update { current ->
                val catalog = catalogResult.getOrNull()
                val languages = languagesResult.getOrNull().orEmpty().map { language ->
                    CommerceLanguageOption(language.code, language.name, language.nativeName)
                }
                val selectedTarget = current.targetLanguageCode.takeIf { selected ->
                    languages.any { it.code == selected }
                } ?: languages.firstOrNull { it.code == DEFAULT_TARGET_LANGUAGE }?.code
                    ?: languages.firstOrNull()?.code.orEmpty()
                current.copy(
                    targetLanguageCode = selectedTarget,
                    translationLanguages = languages,
                    catalogUpdatedAt = catalog?.updatedAt.orEmpty(),
                    availablePlatformCodes = catalog.enabledPlatformCodes(),
                    availableDeliverableCodes = catalog.enabledDeliverableCodes(),
                    availableCredits = balanceResult.getOrNull(),
                    isBalanceLoading = false,
                    isConfigurationLoading = false,
                    submissionMessage = catalogResult.exceptionOrNull()?.message
                        ?: languagesResult.exceptionOrNull()?.message
                        ?: balanceResult.exceptionOrNull()?.message,
                )
            }
            scheduleQuote(refreshBalance = false, immediate = true)
        }
    }

    fun refreshQuote() = scheduleQuote(refreshBalance = true, immediate = true)

    fun selectKind(kind: CommerceCreationKind) {
        _uiState.update { current ->
            val platform = when (kind) {
                CommerceCreationKind.AMAZON_LISTING,
                CommerceCreationKind.AMAZON_A_PLUS,
                -> CommercePlatform.AMAZON
                CommerceCreationKind.DOMESTIC_ECOMMERCE -> {
                    current.platform.takeIf { it in domesticPlatforms } ?: CommercePlatform.TAOBAO_TMALL
                }
                else -> current.platform
            }
            val deliverables = when (kind) {
                CommerceCreationKind.AMAZON_LISTING -> amazonListingDeliverables
                CommerceCreationKind.AMAZON_A_PLUS -> amazonAPlusDeliverables
                CommerceCreationKind.PLATFORM_SUITE -> defaultSuiteDeliverables
                CommerceCreationKind.IMAGE_TRANSLATION -> emptySet()
                CommerceCreationKind.DOMESTIC_ECOMMERCE -> domesticDeliverables
            }
            current.copy(
                kind = kind,
                platform = platform,
                deliverables = deliverables,
                fieldErrors = emptyMap(),
            ).clearConfirmation().invalidateQuote()
        }
        scheduleQuote(refreshBalance = true)
    }

    fun updateProductName(value: String) = updateText("productName") { copy(productName = value) }
    fun updateCategory(value: String) = updateText("category") { copy(category = value) }
    fun updateBrand(value: String) = _uiState.update { it.copy(brand = value).clearConfirmation() }
    fun updateProductFacts(value: String) = updateText("productFacts") { copy(productFacts = value) }
    fun updateSellingPoints(value: String) = _uiState.update { it.copy(coreSellingPoints = value).clearConfirmation() }
    fun updateCustomRequirements(value: String) = _uiState.update { it.copy(customRequirements = value).clearConfirmation() }

    fun selectPlatform(value: CommercePlatform) {
        _uiState.update {
            it.copy(platform = value, fieldErrors = it.fieldErrors - "platform")
                .clearConfirmation()
                .invalidateQuote()
        }
        scheduleQuote(refreshBalance = true)
    }

    fun selectResolution(value: CommerceResolution) {
        _uiState.update { it.copy(resolution = value).clearConfirmation().invalidateQuote() }
        scheduleQuote(refreshBalance = true)
    }

    fun toggleDeliverable(value: CommerceDeliverable) {
        _uiState.update { current ->
            val changed = current.deliverables.toMutableSet().apply {
                if (!add(value)) remove(value)
            }
            current.copy(
                deliverables = changed,
                fieldErrors = current.fieldErrors - "deliverables",
            ).clearConfirmation().invalidateQuote()
        }
        scheduleQuote(refreshBalance = true)
    }

    fun selectSourceLanguage(value: CommerceLanguage) {
        _uiState.update { current ->
            val target = current.targetLanguageCode.takeUnless { code -> value.matches(code) }
                ?: current.translationLanguages.firstOrNull { !value.matches(it.code) }?.code.orEmpty()
            current.copy(sourceLanguage = value, targetLanguageCode = target)
                .clearConfirmation()
                .invalidateQuote()
        }
        scheduleQuote(refreshBalance = true)
    }

    fun selectTargetLanguage(code: String) {
        _uiState.update { current ->
            val errors = if (current.sourceLanguage != CommerceLanguage.AUTO && current.sourceLanguage.matches(code)) {
                current.fieldErrors + ("targetLanguage" to "源语言和目标语言不能相同")
            } else {
                current.fieldErrors - "targetLanguage"
            }
            current.copy(targetLanguageCode = code, fieldErrors = errors)
                .clearConfirmation()
                .invalidateQuote()
        }
        scheduleQuote(refreshBalance = true)
    }

    fun setPreserveLayout(value: Boolean) {
        _uiState.update { it.copy(preserveLayout = value).clearConfirmation() }
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty() || _uiState.value.isReadingImages) return
        val remaining = MAX_REFERENCE_IMAGES - _uiState.value.referenceImages.size
        if (remaining <= 0) {
            _uiState.update { it.copy(imageError = "最多可添加 $MAX_REFERENCE_IMAGES 张参考图") }
            return
        }
        _uiState.update { it.copy(isReadingImages = true, imageError = null) }
        viewModelScope.launch {
            val result = imageReader.read(uris.take(remaining))
            _uiState.update { current ->
                val existingUris = current.referenceImages.mapTo(mutableSetOf()) { it.uri }
                val additions = result.images.filter { existingUris.add(it.uri) }
                val limitWarning = if (uris.size > remaining) "仅添加前 $remaining 张，最多支持 $MAX_REFERENCE_IMAGES 张" else null
                current.copy(
                    referenceImages = (current.referenceImages + additions).take(MAX_REFERENCE_IMAGES),
                    isReadingImages = false,
                    imageError = (result.errors + listOfNotNull(limitWarning)).joinToString("\n").ifBlank { null },
                    fieldErrors = current.fieldErrors - "referenceImages",
                ).clearConfirmation().invalidateQuote()
            }
            scheduleQuote(refreshBalance = true)
        }
    }

    fun removeImage(localId: String) {
        _uiState.update {
            it.copy(referenceImages = it.referenceImages.filterNot { image -> image.localId == localId })
                .clearConfirmation()
                .invalidateQuote()
        }
        scheduleQuote(refreshBalance = true)
    }

    fun clearImageError() = _uiState.update { it.copy(imageError = null) }

    fun next() {
        val state = _uiState.value
        val errors = when (state.step) {
            CommerceWizardStep.PRODUCT -> validateProduct(state)
            CommerceWizardStep.PLATFORM -> validatePlatform(state)
            CommerceWizardStep.REVIEW -> emptyMap()
        }
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(fieldErrors = errors, submissionMessage = "请先补全标记的内容") }
            return
        }
        _uiState.update { current ->
            current.copy(
                step = when (current.step) {
                    CommerceWizardStep.PRODUCT -> CommerceWizardStep.PLATFORM
                    CommerceWizardStep.PLATFORM -> CommerceWizardStep.REVIEW
                    CommerceWizardStep.REVIEW -> CommerceWizardStep.REVIEW
                },
                fieldErrors = emptyMap(),
                submissionMessage = null,
            )
        }
        if (_uiState.value.step == CommerceWizardStep.REVIEW) scheduleQuote(refreshBalance = true, immediate = true)
    }

    fun previous() {
        if (_uiState.value.isSubmitting) return
        _uiState.update { current ->
            current.copy(
                step = when (current.step) {
                    CommerceWizardStep.PRODUCT -> CommerceWizardStep.PRODUCT
                    CommerceWizardStep.PLATFORM -> CommerceWizardStep.PRODUCT
                    CommerceWizardStep.REVIEW -> CommerceWizardStep.PLATFORM
                },
            ).clearConfirmation()
        }
    }

    fun requestConfirmation() {
        val state = _uiState.value
        val errors = validateProduct(state) + validatePlatform(state)
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(fieldErrors = errors, submissionMessage = "提交内容已变化，请返回检查") }
            return
        }
        if (!state.pricingReady) {
            _uiState.update {
                it.copy(
                    submissionPhase = CommerceSubmissionPhase.ERROR,
                    submissionMessage = state.quoteMessage ?: "服务端报价和余额加载完成后才能确认",
                )
            }
            return
        }
        val quote = requireNotNull(state.quote)
        if (requireNotNull(state.availableCredits) < quote.totalCredits) {
            _uiState.update {
                it.copy(
                    submissionPhase = CommerceSubmissionPhase.ERROR,
                    submissionMessage = "积分不足，本次需要 ${quote.totalCredits} 积分",
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                submissionPhase = CommerceSubmissionPhase.AWAITING_CONFIRMATION,
                submissionMessage = null,
            )
        }
    }

    fun dismissConfirmation() {
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(submissionPhase = CommerceSubmissionPhase.IDLE) }
    }

    fun confirmSubmission() {
        val state = _uiState.value
        if (state.submissionPhase != CommerceSubmissionPhase.AWAITING_CONFIRMATION || !state.pricingReady) return
        val confirmedQuote = requireNotNull(state.quote)
        val request = state.toRequest(confirmedQuote)
        _uiState.update {
            it.copy(
                submissionPhase = CommerceSubmissionPhase.VERIFYING_QUOTE,
                submissionMessage = "正在向服务端复核价格与余额",
            )
        }
        viewModelScope.launch {
            val freshQuote = gateway.estimate(request).getOrElse { error ->
                _uiState.update {
                    it.copy(
                        submissionPhase = CommerceSubmissionPhase.ERROR,
                        quotePhase = CommerceQuotePhase.ERROR,
                        quoteMessage = error.message ?: "服务端报价失败",
                        submissionMessage = error.message ?: "服务端报价失败，本次未提交",
                    )
                }
                return@launch
            }
            if (!freshQuote.samePriceAs(confirmedQuote)) {
                _uiState.update {
                    it.copy(
                        quote = freshQuote,
                        quotePhase = CommerceQuotePhase.READY,
                        quoteMessage = null,
                        quoteKey = it.currentQuoteKey(),
                        submissionPhase = CommerceSubmissionPhase.IDLE,
                        submissionMessage = "服务端价格已变为 ${freshQuote.totalCredits} 积分，请重新确认",
                    )
                }
                return@launch
            }
            val balance = gateway.loadBalance().getOrElse { error ->
                _uiState.update {
                    it.copy(
                        submissionPhase = CommerceSubmissionPhase.ERROR,
                        availableCredits = null,
                        isBalanceLoading = false,
                        submissionMessage = error.message ?: "余额加载失败，本次未提交",
                    )
                }
                return@launch
            }
            if (balance < freshQuote.totalCredits) {
                _uiState.update {
                    it.copy(
                        availableCredits = balance,
                        submissionPhase = CommerceSubmissionPhase.ERROR,
                        submissionMessage = "积分不足，本次需要 ${freshQuote.totalCredits} 积分",
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    availableCredits = balance,
                    submissionPhase = CommerceSubmissionPhase.SUBMITTING,
                    submissionMessage = "正在提交，请勿重复点击",
                )
            }
            val result = submitter?.invoke(request) ?: gateway.submit(request)
            when (result) {
                is CommerceSubmissionResult.Accepted -> {
                    savedStateHandle[SAVED_PROJECT_ID] = result.taskId
                    _uiState.update { current ->
                        current.copy(
                            submissionPhase = CommerceSubmissionPhase.SUBMITTED,
                            submissionMessage = "任务已提交，服务端扣除 ${result.chargedCredits} 积分",
                            submittedTaskId = result.taskId,
                            isProjectRefreshing = true,
                        )
                    }
                    observeProject(result.taskId)
                    refreshBalanceOnly()
                }
                is CommerceSubmissionResult.Rejected -> {
                    _uiState.update { current ->
                        current.copy(
                            submissionPhase = CommerceSubmissionPhase.ERROR,
                            submissionMessage = result.userMessage,
                        )
                    }
                    if (result.retryable) scheduleQuote(refreshBalance = true, immediate = true)
                }
                CommerceSubmissionResult.NotConnected -> _uiState.update { current ->
                    current.copy(
                        submissionPhase = CommerceSubmissionPhase.ERROR,
                        submissionMessage = "原生电商提交接口尚未接线，本次没有创建任务或扣除积分",
                    )
                }
            }
        }
    }

    fun restoreProject(projectId: String) {
        if (projectId.isBlank() || projectId == _uiState.value.submittedTaskId && projectJob?.isActive == true) return
        savedStateHandle[SAVED_PROJECT_ID] = projectId
        _uiState.update {
            it.copy(
                submittedTaskId = projectId,
                submissionPhase = CommerceSubmissionPhase.SUBMITTED,
                submissionMessage = "正在恢复项目状态",
                isProjectRefreshing = true,
            )
        }
        observeProject(projectId)
    }

    fun startAnotherGeneration() {
        projectJob?.cancel()
        savedStateHandle.remove<String>(SAVED_PROJECT_ID)
        _uiState.update {
            it.copy(
                submissionPhase = CommerceSubmissionPhase.IDLE,
                submissionMessage = null,
                submittedTaskId = null,
                projectStatus = null,
                projectTasks = emptyList(),
                projectTotalCredits = 0,
                isProjectRefreshing = false,
                projectTerminal = false,
            )
        }
    }

    private fun scheduleQuote(refreshBalance: Boolean, immediate: Boolean = false) {
        quoteJob?.cancel()
        val key = _uiState.value.currentQuoteKey()
        _uiState.update {
            if (it.kind == CommerceCreationKind.IMAGE_TRANSLATION) {
                it.copy(
                    quote = null,
                    quotePhase = CommerceQuotePhase.UNAVAILABLE,
                    quoteMessage = TRANSLATION_QUOTE_UNAVAILABLE,
                    quoteKey = key,
                    isBalanceLoading = refreshBalance || it.isBalanceLoading,
                )
            } else {
                it.copy(
                    quote = null,
                    quotePhase = CommerceQuotePhase.LOADING,
                    quoteMessage = null,
                    quoteKey = key,
                    isBalanceLoading = refreshBalance || it.isBalanceLoading,
                )
            }
        }
        quoteJob = viewModelScope.launch {
            if (!immediate) delay(QUOTE_DEBOUNCE_MS)
            val snapshot = _uiState.value
            val balanceResult = if (refreshBalance) gateway.loadBalance() else null
            if (snapshot.kind == CommerceCreationKind.IMAGE_TRANSLATION) {
                _uiState.update { current ->
                    if (current.currentQuoteKey() != key) current else current.copy(
                        availableCredits = balanceResult?.getOrNull() ?: current.availableCredits,
                        isBalanceLoading = false,
                        quotePhase = CommerceQuotePhase.UNAVAILABLE,
                        quoteMessage = TRANSLATION_QUOTE_UNAVAILABLE,
                        quoteKey = key,
                    )
                }
                return@launch
            }
            if (snapshot.deliverables.isEmpty()) {
                _uiState.update { current ->
                    if (current.currentQuoteKey() != key) current else current.copy(
                        availableCredits = balanceResult?.getOrNull() ?: current.availableCredits,
                        isBalanceLoading = false,
                        quotePhase = CommerceQuotePhase.IDLE,
                        quoteMessage = "请至少选择一种输出画面",
                        quoteKey = key,
                    )
                }
                return@launch
            }
            val quoteResult = gateway.estimate(snapshot.toRequest(null))
            _uiState.update { current ->
                if (current.currentQuoteKey() != key) return@update current
                val quote = quoteResult.getOrNull()
                current.copy(
                    quote = quote,
                    quotePhase = if (quote != null && quote.isServerQuote) CommerceQuotePhase.READY else CommerceQuotePhase.ERROR,
                    quoteMessage = quoteResult.exceptionOrNull()?.message,
                    quoteKey = key,
                    availableCredits = balanceResult?.getOrNull() ?: current.availableCredits,
                    isBalanceLoading = false,
                    submissionMessage = balanceResult?.exceptionOrNull()?.message ?: current.submissionMessage,
                )
            }
        }
    }

    private fun refreshBalanceOnly() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBalanceLoading = true) }
            val result = gateway.loadBalance()
            _uiState.update {
                it.copy(
                    availableCredits = result.getOrNull() ?: it.availableCredits,
                    isBalanceLoading = false,
                )
            }
        }
    }

    private fun observeProject(projectId: String) {
        projectJob?.cancel()
        projectJob = viewModelScope.launch {
            gateway.observeProject(projectId)
                .catch { error ->
                    _uiState.update {
                        it.copy(
                            isProjectRefreshing = false,
                            submissionMessage = error.message ?: "项目状态刷新失败，请稍后重试",
                        )
                    }
                }
                .collect { update ->
                    _uiState.update {
                        it.copy(
                            submittedTaskId = projectId,
                            projectStatus = update.status,
                            projectTasks = update.tasks.map(ImageTask::toProgress),
                            projectTotalCredits = update.totalCredits,
                            isProjectRefreshing = !update.terminal,
                            projectTerminal = update.terminal,
                            submissionPhase = CommerceSubmissionPhase.SUBMITTED,
                            submissionMessage = if (update.terminal) "项目处理完成" else "项目正在后台处理",
                        )
                    }
                    if (update.terminal) refreshBalanceOnly()
                }
        }
    }

    private fun validateProduct(state: CommerceUiState): Map<String, String> = buildMap {
        if (state.kind != CommerceCreationKind.IMAGE_TRANSLATION && state.productName.trim().length < 2) {
            put("productName", "请填写至少 2 个字符的商品名称")
        }
        if (state.kind != CommerceCreationKind.IMAGE_TRANSLATION && state.category.trim().length < 2) {
            put("category", "请填写商品类目")
        }
        if (state.kind != CommerceCreationKind.IMAGE_TRANSLATION && state.productFacts.trim().length < 4) {
            put("productFacts", "请填写真实商品信息，至少 4 个字符")
        }
        if (state.referenceImages.isEmpty()) {
            put("referenceImages", if (state.kind == CommerceCreationKind.IMAGE_TRANSLATION) "图片翻译至少需要 1 张图片" else "请至少添加 1 张商品参考图")
        }
    }

    private fun validatePlatform(state: CommerceUiState): Map<String, String> = buildMap {
        if (state.kind == CommerceCreationKind.IMAGE_TRANSLATION) {
            if (state.targetLanguageCode.isBlank()) put("targetLanguage", "服务端目标语言尚未加载")
            if (state.sourceLanguage != CommerceLanguage.AUTO && state.sourceLanguage.matches(state.targetLanguageCode)) {
                put("targetLanguage", "源语言和目标语言不能相同")
            }
        } else if (state.deliverables.isEmpty()) {
            put("deliverables", "请至少选择一种输出画面")
        }
    }

    private inline fun updateText(
        key: String,
        update: CommerceUiState.() -> CommerceUiState,
    ) {
        _uiState.update { current ->
            current.update().copy(fieldErrors = current.fieldErrors - key).clearConfirmation()
        }
    }

    companion object {
        private const val MAX_REFERENCE_IMAGES = 10
        private const val QUOTE_DEBOUNCE_MS = 250L
        private const val DEFAULT_TARGET_LANGUAGE = "ms-MY"
        private const val SAVED_PROJECT_ID = "commerce_project_id"
        const val TRANSLATION_QUOTE_UNAVAILABLE = "服务端暂未提供图片翻译报价，当前不能提交收费任务"
    }
}

private val domesticPlatforms = setOf(
    CommercePlatform.TAOBAO_TMALL,
    CommercePlatform.JD,
    CommercePlatform.PINDUODUO,
    CommercePlatform.DOUYIN,
    CommercePlatform.XIAOHONGSHU,
)

private fun CommerceUiState.clearConfirmation(): CommerceUiState = copy(
    submissionPhase = CommerceSubmissionPhase.IDLE,
    submissionMessage = null,
)

private fun CommerceUiState.invalidateQuote(): CommerceUiState = copy(
    quote = null,
    quotePhase = CommerceQuotePhase.LOADING,
    quoteMessage = null,
    quoteKey = null,
)

private fun CommerceUiState.currentQuoteKey(): String = buildString {
    append(kind.code).append('|').append(platform.code).append('|').append(resolution.code)
    append('|').append(deliverables.sortedBy(CommerceDeliverable::ordinal).joinToString(",") { it.code })
    if (kind == CommerceCreationKind.IMAGE_TRANSLATION) {
        append('|').append(referenceImages.size).append('|').append(targetLanguageCode)
    }
}

private fun CommerceUiState.toRequest(quoteOverride: CommerceQuote?): CommerceSubmitRequest = CommerceSubmitRequest(
    kind = kind.code,
    productName = productName.trim(),
    category = category.trim(),
    brand = brand.trim(),
    productFacts = productFacts.trim(),
    coreSellingPoints = coreSellingPoints.trim(),
    inputImages = referenceImages.mapIndexed { index, image ->
        CommerceInputImage(
            localId = image.localId,
            contentUri = image.uri,
            displayName = image.displayName,
            mimeType = image.mimeType,
            sizeBytes = image.sizeBytes,
            width = image.width,
            height = image.height,
            order = index,
        )
    },
    platform = platform.code,
    resolution = resolution.code,
    deliverables = deliverables.sortedBy(CommerceDeliverable::ordinal).map(CommerceDeliverable::code),
    sourceLanguage = sourceLanguage.code.takeUnless { it == CommerceLanguage.AUTO.code },
    targetLanguage = targetLanguageCode.takeIf { kind == CommerceCreationKind.IMAGE_TRANSLATION },
    preserveLayout = preserveLayout,
    customRequirements = customRequirements.trim(),
    expectedOutputCount = quoteOverride?.outputCount ?: 0,
    quotedCredits = quoteOverride?.totalCredits ?: 0,
)

private fun CommerceQuote.samePriceAs(other: CommerceQuote): Boolean =
    isServerQuote && other.isServerQuote && outputCount == other.outputCount && totalCredits == other.totalCredits

private fun CommerceLanguage.matches(serverCode: String): Boolean = when (this) {
    CommerceLanguage.AUTO -> false
    else -> code.substringBefore('-').equals(serverCode.substringBefore('-'), ignoreCase = true)
}

private fun CommerceCatalogDto?.enabledPlatformCodes(): Set<String> = this?.platforms.orEmpty()
    .filter { it.enabled }
    .mapTo(linkedSetOf()) { it.code }

private fun CommerceCatalogDto?.enabledDeliverableCodes(): Set<String> {
    val enabledTemplates = this?.templates.orEmpty().filter { it.enabled }.mapTo(linkedSetOf()) { it.slot }
    return enabledTemplates.ifEmpty { this?.slots.orEmpty().mapTo(linkedSetOf()) { it.code } }
}

private fun ImageTask.toProgress(): CommerceTaskProgress {
    val phase = when (status.lowercase()) {
        "queued" -> CommerceTaskPhase.QUEUED
        "running", "submitting", "submitted", "generating" -> CommerceTaskPhase.RUNNING
        "success", "completed" -> CommerceTaskPhase.COMPLETED
        else -> CommerceTaskPhase.FAILED
    }
    return CommerceTaskProgress(
        id = id,
        name = imageName.ifBlank { imageType.ifBlank { "图片任务" } },
        phase = phase,
        resultUrl = resultUrl,
        creditsCost = creditsCost,
        safeError = safeError,
        refundState = if (phase == CommerceTaskPhase.FAILED) CommerceRefundState.NOT_REPORTED else CommerceRefundState.NOT_APPLICABLE,
    )
}
