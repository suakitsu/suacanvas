package com.suakitsu.picpilot.feature.video

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suakitsu.picpilot.core.model.VideoModelConfig
import com.suakitsu.picpilot.core.model.VideoPublicConfig
import com.suakitsu.picpilot.core.model.VideoTask
import com.suakitsu.picpilot.core.model.VideoVariant
import com.suakitsu.picpilot.core.network.ApiResult
import com.suakitsu.picpilot.core.repository.PicPilotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class VideoUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isReadingImages: Boolean = false,
    val isOptimizingScript: Boolean = false,
    val isPreparingConfirmation: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
    val config: VideoPublicConfig? = null,
    val tasks: List<VideoTask> = emptyList(),
    val credits: Int = 0,
    val title: String = "",
    val prompt: String = "",
    val originalPrompt: String = "",
    val selectedModelCode: String = "",
    val selectedResolution: String = "",
    val selectedDurationSeconds: Int = 0,
    val selectedAspectRatio: String = "9:16",
    val generateAudio: Boolean = false,
    val seedText: String = "",
    val references: List<VideoReferenceImage> = emptyList(),
    val scriptSummary: String = "",
    val scriptMode: String = "",
    val confirmation: VideoConfirmation? = null,
    val activeTaskId: String = "",
    val retryableSubmission: Boolean = false,
) {
    val enabledModels: List<VideoModelConfig>
        get() = config?.modelConfigs.orEmpty().filter(VideoModelConfig::enabled)

    val selectedModel: VideoModelConfig?
        get() = enabledModels.firstOrNull { it.code == selectedModelCode }

    val selectedVariant: VideoVariant?
        get() = selectedModel?.variants.orEmpty().firstOrNull {
            it.enabled && it.resolution == selectedResolution && it.durationSeconds == selectedDurationSeconds
        }

    val price: Int get() = selectedVariant?.price ?: 0
    val billablePrice: Int get() = if (config?.mockMode == true) 0 else price
    val maxReferenceImages: Int get() = selectedModel?.maxReferenceImages?.coerceAtLeast(1) ?: 1
    val draftLocked: Boolean
        get() = isReadingImages || isOptimizingScript || isPreparingConfirmation || isSubmitting
    val canSubmit: Boolean
        get() = config?.enabled == true && selectedModel != null && selectedVariant != null &&
            references.isNotEmpty() && prompt.trim().length in 4..4000 && !draftLocked
}

@HiltViewModel
class VideoViewModel @Inject constructor(
    private val repository: PicPilotRepository,
    private val imageReader: VideoImageReader,
    private val pendingStore: VideoPendingSubmissionStore,
    private val gateway: VideoSubmissionGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()
    private var pollingJob: Job? = null

    init {
        load(refresh = false)
    }

    fun refresh() = load(refresh = true)
    fun dismissNotice() = _uiState.update { it.copy(noticeMessage = null) }
    fun dismissConfirmation() = _uiState.update { it.copy(confirmation = null) }
    fun updateTitle(value: String) = _uiState.update {
        if (it.draftLocked) it else it.copy(
            title = value.take(80),
            confirmation = null,
            retryableSubmission = false,
        )
    }
    fun updatePrompt(value: String) = _uiState.update {
        if (it.draftLocked) it else it.copy(
            prompt = value.take(4000),
            originalPrompt = "",
            scriptSummary = "",
            scriptMode = "",
            confirmation = null,
            retryableSubmission = false,
        )
    }
    fun updateSeed(value: String) = _uiState.update {
        if (it.draftLocked) it else it.copy(
            seedText = value.filter(Char::isDigit).take(10),
            confirmation = null,
            retryableSubmission = false,
        )
    }

    fun selectModel(code: String) {
        _uiState.update { current ->
            if (current.draftLocked) return@update current
            val model = current.enabledModels.firstOrNull { it.code == code } ?: return@update current
            val variant = preferredVariant(model, null, null) ?: return@update current
            current.copy(
                selectedModelCode = model.code,
                selectedResolution = variant.resolution,
                selectedDurationSeconds = variant.durationSeconds,
                selectedAspectRatio = preferredAspectRatio(model, current.selectedAspectRatio),
                generateAudio = model.audioToggle,
                references = current.references.take(model.maxReferenceImages.coerceAtLeast(1)),
                prompt = current.originalPrompt.ifBlank { current.prompt },
                originalPrompt = "",
                scriptSummary = "",
                scriptMode = "",
                confirmation = null,
                retryableSubmission = false,
            )
        }
    }

    fun selectResolution(resolution: String) {
        _uiState.update { current ->
            if (current.draftLocked) return@update current
            val model = current.selectedModel ?: return@update current
            val variant = preferredVariant(model, resolution, current.selectedDurationSeconds) ?: return@update current
            current.copy(
                selectedResolution = variant.resolution,
                selectedDurationSeconds = variant.durationSeconds,
                prompt = current.originalPrompt.ifBlank { current.prompt },
                originalPrompt = "",
                scriptSummary = "",
                scriptMode = "",
                confirmation = null,
                retryableSubmission = false,
            )
        }
    }

    fun selectDuration(seconds: Int) {
        _uiState.update { current ->
            if (current.draftLocked) return@update current
            val model = current.selectedModel ?: return@update current
            val variant = preferredVariant(model, current.selectedResolution, seconds) ?: return@update current
            current.copy(
                selectedResolution = variant.resolution,
                selectedDurationSeconds = variant.durationSeconds,
                prompt = current.originalPrompt.ifBlank { current.prompt },
                originalPrompt = "",
                scriptSummary = "",
                scriptMode = "",
                confirmation = null,
                retryableSubmission = false,
            )
        }
    }

    fun selectAspectRatio(value: String) {
        _uiState.update { current ->
            if (current.draftLocked) return@update current
            if (value in current.selectedModel?.aspectRatios.orEmpty()) {
                current.copy(
                    selectedAspectRatio = value,
                    prompt = current.originalPrompt.ifBlank { current.prompt },
                    originalPrompt = "",
                    scriptSummary = "",
                    scriptMode = "",
                    confirmation = null,
                    retryableSubmission = false,
                )
            } else current
        }
    }

    fun setGenerateAudio(enabled: Boolean) {
        _uiState.update { current ->
            if (current.draftLocked) return@update current
            current.copy(
                generateAudio = enabled && current.selectedModel?.audioToggle == true,
                prompt = current.originalPrompt.ifBlank { current.prompt },
                originalPrompt = "",
                scriptSummary = "",
                scriptMode = "",
                confirmation = null,
                retryableSubmission = false,
            )
        }
    }

    fun addImages(uris: List<Uri>) {
        if (_uiState.value.draftLocked || uris.isEmpty()) return
        val current = _uiState.value
        val slots = (current.maxReferenceImages - current.references.size).coerceAtLeast(0)
        if (slots == 0) {
            _uiState.update { it.copy(noticeMessage = "当前模型最多支持 ${it.maxReferenceImages} 张参考图") }
            return
        }
        _uiState.update { it.copy(isReadingImages = true, noticeMessage = null) }
        viewModelScope.launch {
            val result = imageReader.read(uris, slots)
            _uiState.update { state ->
                val merged = (state.references + result.images)
                    .distinctBy { it.uri.toString() }
                    .take(state.maxReferenceImages)
                state.copy(
                    isReadingImages = false,
                    references = merged,
                    prompt = state.originalPrompt.ifBlank { state.prompt },
                    originalPrompt = "",
                    scriptSummary = "",
                    scriptMode = "",
                    confirmation = null,
                    retryableSubmission = false,
                    noticeMessage = result.errors.joinToString("；").ifBlank { null },
                )
            }
        }
    }

    fun removeImage(localId: String) = _uiState.update {
        if (it.draftLocked) it else it.copy(
            references = it.references.filterNot { image -> image.localId == localId },
            prompt = it.originalPrompt.ifBlank { it.prompt },
            originalPrompt = "",
            scriptSummary = "",
            scriptMode = "",
            confirmation = null,
            retryableSubmission = false,
        )
    }

    fun optimizeScript() {
        val state = _uiState.value
        val validation = validateDraft(state, checkCredits = false)
        if (validation != null) {
            _uiState.update { it.copy(noticeMessage = validation) }
            return
        }
        if (state.draftLocked) return
        _uiState.update { it.copy(isOptimizingScript = true, noticeMessage = null) }
        val sourcePrompt = state.originalPrompt.ifBlank { state.prompt }.trim()
        viewModelScope.launch {
            val result = gateway.previewScript(
                VideoScriptPreviewRequest(
                    prompt = sourcePrompt,
                    modelCode = state.selectedModelCode,
                    durationSeconds = state.selectedDurationSeconds,
                    resolution = state.selectedResolution,
                    aspectRatio = state.selectedAspectRatio,
                    generateAudio = state.generateAudio,
                    referenceCount = state.references.size,
                ),
            )
            _uiState.update { current ->
                when (result) {
                    is VideoGatewayResult.Success -> current.copy(
                        isOptimizingScript = false,
                        originalPrompt = sourcePrompt,
                        prompt = result.value.optimizedPrompt.take(4000),
                        scriptSummary = result.value.summary,
                        scriptMode = result.value.mode,
                        confirmation = null,
                        retryableSubmission = false,
                        noticeMessage = "脚本已优化，请确认后再生成",
                    )
                    is VideoGatewayResult.Failure -> current.copy(
                        isOptimizingScript = false,
                        noticeMessage = result.message,
                    )
                }
            }
        }
    }

    fun prepareConfirmation() {
        val draft = _uiState.value
        val validation = validateDraft(draft, checkCredits = false)
        if (validation != null) {
            _uiState.update { it.copy(noticeMessage = validation) }
            return
        }
        if (draft.draftLocked) return
        _uiState.update { it.copy(isPreparingConfirmation = true, noticeMessage = null, confirmation = null) }
        viewModelScope.launch {
            val (configResult, billingResult) = coroutineScope {
                val config = async { repository.videoConfig() }
                val billing = async { repository.billingSummary() }
                config.await() to billing.await()
            }
            val latestConfig = configResult.successValue()
            val latestCredits = billingResult.successValue()?.credits
            if (latestConfig == null || latestCredits == null) {
                val message = listOfNotNull(
                    (configResult as? ApiResult.Failure)?.message,
                    (billingResult as? ApiResult.Failure)?.message,
                ).distinct().joinToString("；").ifBlank { "无法确认最新价格，请稍后重试" }
                _uiState.update { it.copy(isPreparingConfirmation = false, noticeMessage = message) }
                return@launch
            }

            var prepared = reconcileSelection(
                _uiState.value.copy(config = latestConfig, credits = latestCredits),
            )
            val selectionError = validateDraft(prepared, checkCredits = false)
            if (selectionError != null) {
                _uiState.update {
                    prepared.copy(
                        isPreparingConfirmation = false,
                        confirmation = null,
                        noticeMessage = selectionError,
                    )
                }
                return@launch
            }

            if (prepared.scriptSummary.isBlank()) {
                when (val script = gateway.previewScript(
                    VideoScriptPreviewRequest(
                        prompt = prepared.prompt.trim(),
                        modelCode = prepared.selectedModelCode,
                        durationSeconds = prepared.selectedDurationSeconds,
                        resolution = prepared.selectedResolution,
                        aspectRatio = prepared.selectedAspectRatio,
                        generateAudio = prepared.generateAudio,
                        referenceCount = prepared.references.size,
                    ),
                )) {
                    is VideoGatewayResult.Success -> prepared = prepared.copy(
                        originalPrompt = prepared.prompt.trim(),
                        prompt = script.value.optimizedPrompt.take(4000),
                        scriptSummary = script.value.summary,
                        scriptMode = script.value.mode,
                    )
                    is VideoGatewayResult.Failure -> {
                        _uiState.update {
                            prepared.copy(
                                isPreparingConfirmation = false,
                                confirmation = null,
                                noticeMessage = script.message,
                            )
                        }
                        return@launch
                    }
                }
            }

            val pendingFingerprint = videoRequestFingerprint(prepared.asCreateRequest())
            val canReplayPending = pendingStore.hasPending(pendingFingerprint)
            prepared = prepared.copy(retryableSubmission = prepared.retryableSubmission || canReplayPending)
            val finalValidation = validateDraft(prepared, checkCredits = true)
            val model = prepared.selectedModel
            val variant = prepared.selectedVariant
            if (finalValidation != null || model == null || variant == null) {
                _uiState.update {
                    prepared.copy(
                        isPreparingConfirmation = false,
                        confirmation = null,
                        noticeMessage = finalValidation ?: "所选模型配置已变化，请重新选择",
                    )
                }
                return@launch
            }

            val notices = buildList {
                if (draft.price > 0 && draft.price != variant.price) {
                    add("后台价格已由 ${draft.price} 调整为 ${variant.price} 积分")
                }
                if (latestConfig.mockMode) add("Mock 模式：标价 ${variant.price} 积分，本次不扣积分")
            }
            _uiState.update {
                prepared.copy(
                    isPreparingConfirmation = false,
                    confirmation = VideoConfirmation(
                        modelName = model.name,
                        resolution = variant.resolution,
                        durationSeconds = variant.durationSeconds,
                        aspectRatio = prepared.selectedAspectRatio,
                        generateAudio = prepared.generateAudio,
                        referenceCount = prepared.references.size,
                        price = prepared.billablePrice,
                        credits = latestCredits,
                        priceNotice = notices.joinToString("；"),
                    ),
                )
            }
        }
    }

    fun confirmSubmission() {
        val state = _uiState.value
        if (state.confirmation == null || state.isSubmitting) return
        val validation = validateDraft(state, checkCredits = true)
        if (validation != null) {
            _uiState.update { it.copy(confirmation = null, noticeMessage = validation) }
            return
        }
        val seed = state.seedText.takeIf(String::isNotBlank)?.toLongOrNull()
        if (state.seedText.isNotBlank() && (seed == null || seed !in 0..Int.MAX_VALUE.toLong())) {
            _uiState.update { it.copy(confirmation = null, noticeMessage = "随机种子必须在 0 到 2147483647 之间") }
            return
        }

        val draft = state.asCreateRequest(seed)
        val request = draft.copy(
            idempotencyKey = pendingStore.getOrCreate(videoRequestFingerprint(draft)),
        )
        _uiState.update {
            it.copy(
                isSubmitting = true,
                confirmation = null,
                noticeMessage = null,
                retryableSubmission = false,
            )
        }
        viewModelScope.launch {
            when (val result = gateway.createTask(request)) {
                is VideoGatewayResult.Success -> {
                    pendingStore.clearIfMatches(request.idempotencyKey)
                    _uiState.update { current ->
                        current.copy(
                            isSubmitting = false,
                            tasks = listOf(result.value) + current.tasks.filterNot { it.id == result.value.id },
                            activeTaskId = result.value.id,
                            noticeMessage = "任务已创建，可离开本页等待生成",
                            retryableSubmission = false,
                        )
                    }
                    repository.billingSummary().successValue()?.credits?.let { latestCredits ->
                        _uiState.update { it.copy(credits = latestCredits) }
                    }
                    pollActiveTasks()
                }
                is VideoGatewayResult.Failure -> {
                    if (!result.retryable) pendingStore.clearIfMatches(request.idempotencyKey)
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            noticeMessage = if (result.retryable) {
                                "${result.message}。再次确认将安全重试同一任务，不会重复扣费"
                            } else result.message,
                            retryableSubmission = result.retryable,
                        )
                    }
                }
            }
        }
    }

    private fun pollActiveTasks() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            repeat(MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                val activeIds = _uiState.value.tasks
                    .filter { it.status.isActiveVideoStatus() }
                    .map(VideoTask::id)
                if (activeIds.isEmpty()) return@launch
                activeIds.forEach { taskId ->
                    when (val result = gateway.getTask(taskId)) {
                        is VideoGatewayResult.Success -> _uiState.update { current ->
                            current.copy(tasks = current.tasks.map { if (it.id == taskId) result.value else it })
                        }
                        is VideoGatewayResult.Failure -> Unit
                    }
                }
            }
        }
    }

    private fun load(refresh: Boolean) {
        if (_uiState.value.isLoading || _uiState.value.isRefreshing) return
        _uiState.update { it.copy(isLoading = !refresh, isRefreshing = refresh, errorMessage = null) }
        viewModelScope.launch {
            try {
                val (configResult, tasksResult, billingResult) = coroutineScope {
                    val config = async { repository.videoConfig() }
                    val tasks = async { repository.videoTasks() }
                    val billing = async { repository.billingSummary() }
                    Triple(config.await(), tasks.await(), billing.await())
                }
                val failures = listOfNotNull(
                    (configResult as? ApiResult.Failure)?.message,
                    (tasksResult as? ApiResult.Failure)?.message,
                    (billingResult as? ApiResult.Failure)?.message,
                )
                _uiState.update { current ->
                    reconcileSelection(
                        current.copy(
                            isLoading = false,
                            isRefreshing = false,
                            errorMessage = failures.distinct().joinToString("；").ifBlank { null },
                            config = configResult.successValue() ?: current.config,
                            tasks = tasksResult.successValue() ?: current.tasks,
                            credits = billingResult.successValue()?.credits ?: current.credits,
                        ),
                    )
                }
                if (_uiState.value.tasks.any { it.status.isActiveVideoStatus() }) pollActiveTasks()
            } finally {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            }
        }
    }
}

internal fun reconcileSelection(state: VideoUiState): VideoUiState {
    val models = state.enabledModels
    val model = models.firstOrNull { it.code == state.selectedModelCode }
        ?: models.firstOrNull { it.code == state.config?.defaultModel }
        ?: models.firstOrNull()
        ?: return state.copy(
            selectedModelCode = "",
            selectedResolution = "",
            selectedDurationSeconds = 0,
            generateAudio = false,
            retryableSubmission = false,
        )
    val variant = preferredVariant(model, state.selectedResolution, state.selectedDurationSeconds)
        ?: return state.copy(selectedModelCode = model.code)
    val modelChanged = state.selectedModelCode != model.code
    val nextRatio = preferredAspectRatio(model, state.selectedAspectRatio)
    val referencesChanged = state.references.size > model.maxReferenceImages.coerceAtLeast(1)
    val selectionChanged = modelChanged ||
        state.selectedResolution != variant.resolution ||
        state.selectedDurationSeconds != variant.durationSeconds ||
        state.selectedAspectRatio != nextRatio || referencesChanged
    return state.copy(
        selectedModelCode = model.code,
        selectedResolution = variant.resolution,
        selectedDurationSeconds = variant.durationSeconds,
        selectedAspectRatio = nextRatio,
        generateAudio = if (modelChanged) model.audioToggle else state.generateAudio && model.audioToggle,
        references = state.references.take(model.maxReferenceImages.coerceAtLeast(1)),
        prompt = if (selectionChanged) state.originalPrompt.ifBlank { state.prompt } else state.prompt,
        originalPrompt = if (selectionChanged) "" else state.originalPrompt,
        scriptSummary = if (selectionChanged) "" else state.scriptSummary,
        scriptMode = if (selectionChanged) "" else state.scriptMode,
        retryableSubmission = if (selectionChanged) false else state.retryableSubmission,
    )
}

internal fun preferredVariant(
    model: VideoModelConfig,
    resolution: String?,
    duration: Int?,
): VideoVariant? {
    val enabled = model.variants.filter(VideoVariant::enabled)
    return enabled.firstOrNull { it.resolution == resolution && it.durationSeconds == duration }
        ?: enabled.firstOrNull { it.resolution == resolution }
        ?: enabled.firstOrNull {
            it.resolution == model.defaultResolution && it.durationSeconds == model.defaultDurationSeconds
        }
        ?: enabled.firstOrNull()
}

private fun preferredAspectRatio(model: VideoModelConfig, requested: String): String =
    requested.takeIf { it in model.aspectRatios }
        ?: "9:16".takeIf { it in model.aspectRatios }
        ?: model.aspectRatios.firstOrNull()
        ?: "9:16"

internal fun validateDraft(state: VideoUiState, checkCredits: Boolean): String? = when {
    state.config?.enabled != true -> "视频服务当前未启用"
    state.selectedModel == null -> "请选择可用的视频模型"
    state.selectedVariant == null -> "所选分辨率和时长组合当前不可用"
    state.selectedAspectRatio !in state.selectedModel?.aspectRatios.orEmpty() -> "所选画面比例当前不可用"
    state.references.isEmpty() -> "请至少选择一张商品参考图"
    state.references.size > state.maxReferenceImages -> "当前模型最多支持 ${state.maxReferenceImages} 张参考图"
    state.prompt.trim().length < 4 -> "请填写至少 4 个字符的商品视频描述"
    state.prompt.trim().length > 4000 -> "商品视频描述不能超过 4000 个字符"
    checkCredits && !state.retryableSubmission && state.credits < state.billablePrice -> "积分不足，本次需要 ${state.billablePrice} 积分"
    else -> null
}

private fun String.isActiveVideoStatus(): Boolean = lowercase() in setOf(
    "queued", "submitting", "submission_unknown", "submitted", "generating", "upscaling", "quality_check",
)

private fun VideoUiState.asCreateRequest(
    parsedSeed: Long? = seedText.takeIf(String::isNotBlank)?.toLongOrNull(),
): VideoCreateRequest = VideoCreateRequest(
    title = title,
    prompt = prompt.trim(),
    originalPrompt = originalPrompt,
    modelCode = selectedModelCode,
    resolution = selectedResolution,
    durationSeconds = selectedDurationSeconds,
    aspectRatio = selectedAspectRatio,
    generateAudio = generateAudio,
    seed = parsedSeed,
    references = references,
    idempotencyKey = "",
)

private fun <T> ApiResult<T>.successValue(): T? = (this as? ApiResult.Success)?.value
private const val POLL_INTERVAL_MS = 5_000L
private const val MAX_POLL_ATTEMPTS = 720
