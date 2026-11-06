package com.suakitsu.picpilot.integration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suakitsu.picpilot.core.model.CommerceCatalogDto
import com.suakitsu.picpilot.core.model.ImageTranslationLanguageDto
import com.suakitsu.picpilot.core.network.ApiResult
import com.suakitsu.picpilot.core.repository.PicPilotRepository
import com.suakitsu.picpilot.feature.aicreate.AiCreateCatalog
import com.suakitsu.picpilot.feature.aicreate.AiCreateConversation
import com.suakitsu.picpilot.feature.aicreate.AiCreateGateway
import com.suakitsu.picpilot.feature.aicreate.AiCreateGenerateRequest
import com.suakitsu.picpilot.feature.aicreate.AiCreateMessage
import com.suakitsu.picpilot.feature.aicreate.AiCreateSubmitReceipt
import com.suakitsu.picpilot.feature.aicreate.AiCreateTaskUpdate
import com.suakitsu.picpilot.feature.commerce.CommerceSubmissionResult
import com.suakitsu.picpilot.feature.commerce.CommerceQuote
import com.suakitsu.picpilot.feature.commerce.CommerceSubmitRequest
import com.suakitsu.picpilot.feature.commerce.CommerceSubmitter
import com.suakitsu.picpilot.feature.detail.DetailGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NativeIntegrationState(
    val balance: Int? = null,
    val aiCatalog: AiCreateCatalog = AiCreateCatalog(),
    val commerceCatalog: CommerceCatalogDto? = null,
    val translationLanguages: List<ImageTranslationLanguageDto> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class NativeIntegrationViewModel @Inject constructor(
    private val repository: PicPilotRepository,
    private val productionAiCreateGateway: ProductionAiCreateGateway,
    val commerceGateway: ProductionCommerceGateway,
    private val productionDetailGateway: ProductionDetailGateway,
) : ViewModel() {
    private val _state = MutableStateFlow(NativeIntegrationState())
    val state: StateFlow<NativeIntegrationState> = _state.asStateFlow()

    private val _balance = MutableStateFlow<Int?>(null)
    val balance: StateFlow<Int?> = _balance.asStateFlow()

    private val _aiCatalog = MutableStateFlow(AiCreateCatalog())
    val aiCatalog: StateFlow<AiCreateCatalog> = _aiCatalog.asStateFlow()

    private val _commerceCatalog = MutableStateFlow<CommerceCatalogDto?>(null)
    val commerceCatalog: StateFlow<CommerceCatalogDto?> = _commerceCatalog.asStateFlow()

    val aiCreateGateway: AiCreateGateway = object : AiCreateGateway {
        override suspend fun loadConversations(): Result<List<AiCreateConversation>> =
            productionAiCreateGateway.loadConversations()

        override suspend fun loadMessages(conversationId: String): Result<List<AiCreateMessage>> =
            productionAiCreateGateway.loadMessages(conversationId)

        override suspend fun loadCatalog(): Result<AiCreateCatalog> =
            productionAiCreateGateway.loadCatalog()

        override suspend fun loadBalance(): Result<Int> =
            productionAiCreateGateway.loadBalance().onSuccess { publishBalance(it) }

        override suspend fun estimate(request: AiCreateGenerateRequest): Result<Int> =
            productionAiCreateGateway.estimate(request)

        override suspend fun submit(request: AiCreateGenerateRequest): Result<AiCreateSubmitReceipt> {
            val result = productionAiCreateGateway.submit(request)
            result.getOrNull()?.let { receipt ->
                if (receipt.balanceAfter != null) {
                    publishBalance(receipt.balanceAfter)
                } else {
                    productionAiCreateGateway.loadBalance().onSuccess { publishBalance(it) }
                }
            }
            return result
        }

        override fun observeTask(taskId: String): Flow<AiCreateTaskUpdate> =
            productionAiCreateGateway.observeTask(taskId)
    }

    val detailGateway: DetailGateway = productionDetailGateway

    val commerceSubmitter: CommerceSubmitter = { request ->
        commerceGateway.submit(request).also { result ->
            if (result is CommerceSubmissionResult.Accepted) refreshBalanceNow()
        }
    }

    suspend fun estimateAiCreate(request: AiCreateGenerateRequest): Result<Int> =
        productionAiCreateGateway.estimate(request)

    suspend fun estimateCommerce(request: CommerceSubmitRequest): Result<CommerceQuote> =
        commerceGateway.estimate(request)

    init {
        refreshAll()
    }

    fun refreshAll() {
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true, errorMessage = null) }
        viewModelScope.launch {
            coroutineScope {
                val balanceRequest = async { repository.me() }
                val aiCatalogRequest = async { productionAiCreateGateway.loadCatalog() }
                val commerceCatalogRequest = async { commerceGateway.loadCatalog() }
                val languagesRequest = async { commerceGateway.loadTranslationLanguages() }

                val errors = mutableListOf<String>()
                when (val result = balanceRequest.await()) {
                    is ApiResult.Success -> publishBalance(result.value.credits)
                    is ApiResult.Failure -> errors += result.message
                }
                aiCatalogRequest.await()
                    .onSuccess(::publishAiCatalog)
                    .onFailure { errors += it.message.orEmpty() }
                commerceCatalogRequest.await()
                    .onSuccess(::publishCommerceCatalog)
                    .onFailure { errors += it.message.orEmpty() }
                languagesRequest.await()
                    .onSuccess { languages -> _state.update { it.copy(translationLanguages = languages) } }
                    .onFailure { errors += it.message.orEmpty() }

                _state.update {
                    it.copy(
                        isRefreshing = false,
                        errorMessage = errors.firstOrNull(String::isNotBlank),
                    )
                }
            }
        }
    }

    fun refreshBalance() {
        viewModelScope.launch { refreshBalanceNow() }
    }

    fun refreshAiCreate() {
        viewModelScope.launch {
            productionAiCreateGateway.loadCatalog().onSuccess(::publishAiCatalog)
            productionAiCreateGateway.loadBalance().onSuccess(::publishBalance)
        }
    }

    private suspend fun refreshBalanceNow() {
        when (val result = repository.me()) {
            is ApiResult.Success -> publishBalance(result.value.credits)
            is ApiResult.Failure -> _state.update { it.copy(errorMessage = result.message) }
        }
    }

    private fun publishBalance(value: Int) {
        _balance.value = value
        _state.update { it.copy(balance = value) }
    }

    private fun publishAiCatalog(value: AiCreateCatalog) {
        _aiCatalog.value = value
        _state.update { it.copy(aiCatalog = value) }
    }

    private fun publishCommerceCatalog(value: CommerceCatalogDto) {
        _commerceCatalog.value = value
        _state.update { it.copy(commerceCatalog = value) }
    }
}
