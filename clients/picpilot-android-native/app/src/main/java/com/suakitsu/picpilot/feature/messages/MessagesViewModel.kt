package com.suakitsu.picpilot.feature.messages

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suakitsu.picpilot.core.model.StationMessage
import com.suakitsu.picpilot.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface MessageWriteGateway {
    suspend fun messages(): ApiResult<List<StationMessage>>
    suspend fun markRead(id: String): ApiResult<Unit>
}

@Immutable
data class MessagesUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isMarkingAll: Boolean = false,
    val markingIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val messages: List<StationMessage> = emptyList(),
) {
    val unreadCount: Int get() = messages.count { !it.read }
}

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val gateway: MessageWriteGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MessagesUiState())
    val uiState: StateFlow<MessagesUiState> = _uiState.asStateFlow()

    init { load(refresh = false) }

    fun refresh() = load(refresh = true)

    fun dismissNotice() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    fun markRead(id: String) {
        val message = _uiState.value.messages.firstOrNull { it.id == id } ?: return
        if (message.read || id in _uiState.value.markingIds) return
        _uiState.update {
            it.copy(
                markingIds = it.markingIds + id,
                errorMessage = null,
                messages = it.messages.map { row -> if (row.id == id) row.copy(read = true) else row },
            )
        }
        viewModelScope.launch {
            when (val result = gateway.markRead(id)) {
                is ApiResult.Success -> _uiState.update { it.copy(markingIds = it.markingIds - id) }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(
                        markingIds = it.markingIds - id,
                        errorMessage = result.message,
                        messages = it.messages.map { row -> if (row.id == id) row.copy(read = false) else row },
                    )
                }
            }
        }
    }

    fun markAllRead() {
        val unread = _uiState.value.messages.filterNot(StationMessage::read)
        if (unread.isEmpty() || _uiState.value.isMarkingAll) return
        _uiState.update {
            it.copy(
                isMarkingAll = true,
                errorMessage = null,
                successMessage = null,
                messages = it.messages.map { row -> row.copy(read = true) },
            )
        }
        viewModelScope.launch {
            val failedIds = buildSet {
                unread.forEach { message ->
                    if (gateway.markRead(message.id) is ApiResult.Failure) add(message.id)
                }
            }
            _uiState.update {
                it.copy(
                    isMarkingAll = false,
                    successMessage = if (failedIds.isEmpty()) "全部消息已标记为已读" else null,
                    errorMessage = if (failedIds.isEmpty()) null else "部分消息标记失败，请稍后重试",
                    messages = it.messages.map { row ->
                        if (row.id in failedIds) row.copy(read = false) else row
                    },
                )
            }
        }
    }

    private fun load(refresh: Boolean) {
        if (_uiState.value.isLoading || _uiState.value.isRefreshing) return
        _uiState.update {
            it.copy(
                isLoading = !refresh,
                isRefreshing = refresh,
                errorMessage = null,
                successMessage = null,
            )
        }
        viewModelScope.launch {
            when (val result = gateway.messages()) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        messages = result.value.sortedByDescending(StationMessage::publishedAt),
                    )
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }
}
