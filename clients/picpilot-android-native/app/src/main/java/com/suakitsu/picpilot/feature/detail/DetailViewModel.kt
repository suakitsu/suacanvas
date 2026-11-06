package com.suakitsu.picpilot.feature.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class DetailUiState(
    val requestedKind: DetailKind,
    val requestedId: String,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val payload: DetailPayload? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    val hasContent: Boolean get() = payload != null
}

class DetailViewModel(
    private val kind: DetailKind,
    private val itemId: String,
    private val gateway: DetailGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        DetailUiState(requestedKind = kind, requestedId = itemId),
    )
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        load(refresh = false)
    }

    fun refresh() = load(refresh = true)

    private fun load(refresh: Boolean) {
        if (_uiState.value.isLoading || _uiState.value.isRefreshing) return
        if (itemId.isBlank()) {
            _uiState.update {
                it.copy(
                    errorCode = "INVALID_DETAIL_ID",
                    errorMessage = "记录 ID 无效，无法打开详情。",
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                isLoading = !refresh,
                isRefreshing = refresh,
                errorCode = null,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                when (kind) {
                    DetailKind.Project -> gateway.loadProject(itemId).map(DetailPayload::Project)
                    DetailKind.CreativeChat -> gateway.loadCreativeChat(itemId).map(DetailPayload::CreativeChat)
                    DetailKind.Video -> gateway.loadVideo(itemId).map(DetailPayload::Video)
                }
            }.getOrElse {
                DetailLoadResult.Failure(
                    code = "DETAIL_REQUEST_FAILED",
                    message = "详情加载失败，请检查网络后重试。",
                )
            }

            _uiState.update { current ->
                when (result) {
                    is DetailLoadResult.Success -> current.copy(
                        isLoading = false,
                        isRefreshing = false,
                        payload = result.value,
                        errorCode = null,
                        errorMessage = null,
                    )

                    is DetailLoadResult.Failure -> current.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorCode = result.code,
                        errorMessage = result.message.ifBlank { "详情加载失败，请稍后重试。" },
                    )
                }
            }
        }
    }

    companion object {
        fun factory(kind: DetailKind, itemId: String, gateway: DetailGateway): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DetailViewModel(kind, itemId, gateway) as T
            }
    }
}

private inline fun <T, R> DetailLoadResult<T>.map(transform: (T) -> R): DetailLoadResult<R> =
    when (this) {
        is DetailLoadResult.Success -> DetailLoadResult.Success(transform(value))
        is DetailLoadResult.Failure -> this
    }
