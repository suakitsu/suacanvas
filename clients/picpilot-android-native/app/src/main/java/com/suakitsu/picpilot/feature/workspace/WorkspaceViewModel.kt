package com.suakitsu.picpilot.feature.workspace

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suakitsu.picpilot.core.model.BillingSummary
import com.suakitsu.picpilot.core.model.CreativeChatSummary
import com.suakitsu.picpilot.core.model.ProjectSummary
import com.suakitsu.picpilot.core.model.StationMessage
import com.suakitsu.picpilot.core.model.VideoTask
import com.suakitsu.picpilot.core.network.ApiResult
import com.suakitsu.picpilot.core.repository.PicPilotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class WorkspaceUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val billing: BillingSummary? = null,
    val projects: List<ProjectSummary> = emptyList(),
    val chats: List<CreativeChatSummary> = emptyList(),
    val videos: List<VideoTask> = emptyList(),
    val messages: List<StationMessage> = emptyList(),
) {
    val runningTaskCount: Int
        get() = projects.sumOf { it.runningImages } + videos.count { it.status.isRunningStatus() }

    val unreadMessageCount: Int
        get() = messages.count { !it.read }

    val hasContent: Boolean
        get() = billing != null || projects.isNotEmpty() || chats.isNotEmpty() ||
            videos.isNotEmpty() || messages.isNotEmpty()
}

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repository: PicPilotRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init {
        load(refresh = false)
    }

    fun refresh() = load(refresh = true)

    private fun load(refresh: Boolean) {
        if (_uiState.value.isLoading || _uiState.value.isRefreshing) return
        _uiState.update {
            it.copy(
                isLoading = !refresh,
                isRefreshing = refresh,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val result = coroutineScope {
                    val billing = async { repository.billingSummary() }
                    val projects = async { repository.projects() }
                    val chats = async { repository.creativeChats() }
                    val videos = async { repository.videoTasks() }
                    val messages = async { repository.messages() }
                    WorkspaceLoadResult(
                        billing = billing.await(),
                        projects = projects.await(),
                        chats = chats.await(),
                        videos = videos.await(),
                        messages = messages.await(),
                    )
                }
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = result.failures().distinct().joinToString("；").ifBlank { null },
                        billing = result.billing.successValue() ?: current.billing,
                        projects = result.projects.successValue() ?: current.projects,
                        chats = result.chats.successValue() ?: current.chats,
                        videos = result.videos.successValue() ?: current.videos,
                        messages = result.messages.successValue() ?: current.messages,
                    )
                }
            } finally {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            }
        }
    }
}

private data class WorkspaceLoadResult(
    val billing: ApiResult<BillingSummary>,
    val projects: ApiResult<List<ProjectSummary>>,
    val chats: ApiResult<List<CreativeChatSummary>>,
    val videos: ApiResult<List<VideoTask>>,
    val messages: ApiResult<List<StationMessage>>,
) {
    fun failures(): List<String> = listOf(billing, projects, chats, videos, messages)
        .mapNotNull { (it as? ApiResult.Failure)?.message }
}

private fun String.isRunningStatus(): Boolean = lowercase() in setOf(
    "queued",
    "submitting",
    "submitted",
    "generating",
    "processing",
    "upscaling",
    "quality_check",
)

private fun <T> ApiResult<T>.successValue(): T? = (this as? ApiResult.Success)?.value
