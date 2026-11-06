package com.suakitsu.picpilot.feature.history

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suakitsu.picpilot.core.model.CreativeChatSummary
import com.suakitsu.picpilot.core.model.ProjectSummary
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

private const val HISTORY_PAGE_SIZE = 20

enum class HistoryFilter {
    All,
    Projects,
    Chats,
    Videos,
}

enum class HistoryStatusFilter {
    All,
    InProgress,
    Completed,
    Failed,
}

enum class HistoryEntryType {
    Project,
    Chat,
    Video,
}

@Immutable
data class HistoryEntry(
    val id: String,
    val type: HistoryEntryType,
    val title: String,
    val subtitle: String,
    val status: String,
    val timestamp: String,
    val credits: Int? = null,
    val previewUrl: String = "",
) {
    val canHide: Boolean
        get() = when (type) {
            HistoryEntryType.Project -> true
            HistoryEntryType.Chat -> false
            HistoryEntryType.Video -> status.normalizedStatus() in videoDeletableStatuses
        }
}

@Immutable
data class HistoryUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val query: String = "",
    val filter: HistoryFilter = HistoryFilter.All,
    val statusFilter: HistoryStatusFilter = HistoryStatusFilter.All,
    val entries: List<HistoryEntry> = emptyList(),
    val visibleCount: Int = HISTORY_PAGE_SIZE,
    val pendingHide: HistoryEntry? = null,
    val hidingIds: Set<String> = emptySet(),
) {
    val filteredEntries: List<HistoryEntry>
        get() = filterHistoryEntries(entries, query, filter, statusFilter)

    val visibleEntries: List<HistoryEntry>
        get() = filteredEntries.take(visibleCount)

    val hasMore: Boolean
        get() = visibleEntries.size < filteredEntries.size

    val hasActiveFilters: Boolean
        get() = query.isNotBlank() || filter != HistoryFilter.All || statusFilter != HistoryStatusFilter.All
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: PicPilotRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private var scrollIndex = 0
    private var scrollOffset = 0

    init {
        load(refresh = false)
    }

    fun refresh() = load(refresh = true)

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query, visibleCount = HISTORY_PAGE_SIZE) }
    }

    fun selectFilter(filter: HistoryFilter) {
        _uiState.update { it.copy(filter = filter, visibleCount = HISTORY_PAGE_SIZE) }
    }

    fun selectStatusFilter(filter: HistoryStatusFilter) {
        _uiState.update { it.copy(statusFilter = filter, visibleCount = HISTORY_PAGE_SIZE) }
    }

    fun loadMore() {
        _uiState.update { state ->
            if (!state.hasMore) state else state.copy(visibleCount = state.visibleCount + HISTORY_PAGE_SIZE)
        }
    }

    fun rememberScroll(index: Int, offset: Int) {
        scrollIndex = index.coerceAtLeast(0)
        scrollOffset = offset.coerceAtLeast(0)
    }

    fun scrollPosition(): Pair<Int, Int> = scrollIndex to scrollOffset

    fun requestHide(entry: HistoryEntry) {
        if (!entry.canHide || entry.key in _uiState.value.hidingIds) return
        _uiState.update { it.copy(pendingHide = entry, errorMessage = null, successMessage = null) }
    }

    fun dismissHide() {
        val pendingKey = _uiState.value.pendingHide?.key
        if (pendingKey != null && pendingKey in _uiState.value.hidingIds) return
        _uiState.update { it.copy(pendingHide = null) }
    }

    fun confirmHide() {
        val entry = _uiState.value.pendingHide ?: return
        if (!entry.canHide || entry.key in _uiState.value.hidingIds) return
        _uiState.update { it.copy(hidingIds = it.hidingIds + entry.key, errorMessage = null, successMessage = null) }
        viewModelScope.launch {
            val result = when (entry.type) {
                HistoryEntryType.Project -> repository.hideProject(entry.id)
                HistoryEntryType.Video -> repository.hideVideoTask(entry.id)
                HistoryEntryType.Chat -> return@launch
            }
            _uiState.update { state ->
                when (result) {
                    is ApiResult.Success -> state.copy(
                        pendingHide = null,
                        hidingIds = state.hidingIds - entry.key,
                        entries = state.entries.filterNot { it.key == entry.key },
                        successMessage = "已从你的历史记录中隐藏",
                    )
                    is ApiResult.Failure -> state.copy(
                        hidingIds = state.hidingIds - entry.key,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    private fun load(refresh: Boolean) {
        if (_uiState.value.isLoading || _uiState.value.isRefreshing) return
        _uiState.update {
            it.copy(isLoading = !refresh, isRefreshing = refresh, errorMessage = null, successMessage = null)
        }
        viewModelScope.launch {
            try {
                val result = coroutineScope {
                    val projects = async { repository.projects() }
                    val chats = async { repository.creativeChats() }
                    val videos = async { repository.videoTasks() }
                    HistoryLoadResult(projects.await(), chats.await(), videos.await())
                }
                _uiState.update { current ->
                    val successfulEntries = mergeHistoryEntries(result)
                    current.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = result.failures().distinct().joinToString("；").ifBlank { null },
                        entries = if (successfulEntries.isNotEmpty() || result.allSuccessful()) {
                            successfulEntries
                        } else {
                            current.entries
                        },
                    )
                }
            } finally {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            }
        }
    }
}

private data class HistoryLoadResult(
    val projects: ApiResult<List<ProjectSummary>>,
    val chats: ApiResult<List<CreativeChatSummary>>,
    val videos: ApiResult<List<VideoTask>>,
) {
    fun failures(): List<String> = listOf(projects, chats, videos)
        .mapNotNull { (it as? ApiResult.Failure)?.message }

    fun allSuccessful(): Boolean = listOf(projects, chats, videos)
        .all { it is ApiResult.Success }
}

private fun mergeHistoryEntries(result: HistoryLoadResult): List<HistoryEntry> {
    val chats = result.chats.successValue().orEmpty().map(CreativeChatSummary::toHistoryEntry)
    val chatIds = chats.mapTo(mutableSetOf()) { it.id }
    val projects = result.projects.successValue().orEmpty().mapNotNull { project ->
        if (project.sourceType == "creative_chat") {
            val chatId = project.sourceChatId.ifBlank { project.id }
            if (chatId in chatIds) null else project.toChatFallback(chatId)
        } else {
            project.toHistoryEntry()
        }
    }
    val videos = result.videos.successValue().orEmpty().map(VideoTask::toHistoryEntry)
    return (projects + chats + videos)
        .distinctBy(HistoryEntry::key)
        .sortedByDescending(HistoryEntry::timestamp)
}

internal fun filterHistoryEntries(
    entries: List<HistoryEntry>,
    query: String,
    typeFilter: HistoryFilter,
    statusFilter: HistoryStatusFilter,
): List<HistoryEntry> {
    val keyword = query.trim().lowercase()
    return entries.filter { entry ->
        val matchesType = when (typeFilter) {
            HistoryFilter.All -> true
            HistoryFilter.Projects -> entry.type == HistoryEntryType.Project
            HistoryFilter.Chats -> entry.type == HistoryEntryType.Chat
            HistoryFilter.Videos -> entry.type == HistoryEntryType.Video
        }
        val matchesStatus = when (statusFilter) {
            HistoryStatusFilter.All -> true
            HistoryStatusFilter.InProgress -> entry.status.normalizedStatus() in activeStatuses
            HistoryStatusFilter.Completed -> entry.status.normalizedStatus() in completedStatuses
            HistoryStatusFilter.Failed -> entry.status.normalizedStatus() in failedStatuses
        }
        val matchesQuery = keyword.isBlank() || listOf(entry.id, entry.title, entry.subtitle, entry.status)
            .any { it.lowercase().contains(keyword) }
        matchesType && matchesStatus && matchesQuery
    }
}

private val HistoryEntry.key: String get() = "${type.name}:$id"

private val activeStatuses = setOf(
    "draft", "copy_generated", "facts_confirmed", "images_queued",
    "queued", "pending", "submitting", "submission_unknown", "submitted",
    "generating", "processing", "running", "upscaling", "quality_check",
)
private val completedStatuses = setOf("images_completed", "completed", "succeeded", "success")
private val failedStatuses = setOf("images_failed", "images_partial_failed", "failed", "timeout", "cancelled", "canceled")
private val videoDeletableStatuses = setOf("completed", "failed", "timeout", "cancelled")

private fun String.normalizedStatus() = trim().lowercase()

private fun ProjectSummary.toHistoryEntry() = HistoryEntry(
    id = id,
    type = HistoryEntryType.Project,
    title = productName.ifBlank { brand.ifBlank { "未命名项目" } },
    subtitle = listOf(country, language).filter(String::isNotBlank).joinToString(" · ")
        .ifBlank { "商品图片项目" },
    status = status,
    timestamp = updatedAt.ifBlank { createdAt },
    credits = creditsCost,
    previewUrl = coverUrl.ifBlank { previewUrls.firstOrNull().orEmpty() },
)

private fun ProjectSummary.toChatFallback(chatId: String) = HistoryEntry(
    id = chatId,
    type = HistoryEntryType.Chat,
    title = productName.ifBlank { "AI 创作对话" },
    subtitle = listOf(language, "$versionCount 个版本").filter(String::isNotBlank).joinToString(" · "),
    status = status,
    timestamp = updatedAt.ifBlank { createdAt },
    previewUrl = coverUrl.ifBlank { previewUrls.firstOrNull().orEmpty() },
)

private fun CreativeChatSummary.toHistoryEntry() = HistoryEntry(
    id = id,
    type = HistoryEntryType.Chat,
    title = title.ifBlank { "AI 创作对话" },
    subtitle = listOf(style, size, "$versionCount 个版本").filter(String::isNotBlank).joinToString(" · "),
    status = latestStatus,
    timestamp = updatedAt,
    previewUrl = latestResultUrl,
)

private fun VideoTask.toHistoryEntry() = HistoryEntry(
    id = id,
    type = HistoryEntryType.Video,
    title = title.ifBlank { "商品视频" },
    subtitle = listOf(aspectRatio, if (durationSeconds > 0) "${durationSeconds}秒" else "", requestedResolution)
        .filter(String::isNotBlank)
        .joinToString(" · "),
    status = status,
    timestamp = updatedAt.ifBlank { createdAt },
    credits = creditsCost,
    previewUrl = resultUrl,
)

private fun <T> ApiResult<T>.successValue(): T? = (this as? ApiResult.Success)?.value
