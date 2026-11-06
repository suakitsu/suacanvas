package com.suakitsu.picpilot.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suakitsu.picpilot.ui.ForegroundRefreshEffect
import coil.compose.AsyncImage
import com.suakitsu.picpilot.core.media.resolveMediaUrl
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun HistoryRoute(
    onEntryClick: (HistoryEntryType, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ForegroundRefreshEffect(onRefresh = viewModel::refresh)
    val initialPosition = remember(viewModel) { viewModel.scrollPosition() }
    val gridState = rememberLazyGridState(initialPosition.first, initialPosition.second)
    val scope = rememberCoroutineScope()

    LaunchedEffect(gridState, viewModel) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) -> viewModel.rememberScroll(index, offset) }
    }

    fun resetScroll() {
        scope.launch { gridState.scrollToItem(0) }
    }

    HistoryScreen(
        state = state,
        gridState = gridState,
        onRefresh = viewModel::refresh,
        onQueryChanged = { viewModel.updateQuery(it); resetScroll() },
        onFilterSelected = { viewModel.selectFilter(it); resetScroll() },
        onStatusFilterSelected = { viewModel.selectStatusFilter(it); resetScroll() },
        onLoadMore = viewModel::loadMore,
        onEntryClick = onEntryClick,
        onHideRequested = viewModel::requestHide,
        onHideConfirmed = viewModel::confirmHide,
        onHideDismissed = viewModel::dismissHide,
        onNoticeDismissed = viewModel::dismissNotice,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    gridState: LazyGridState,
    onRefresh: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onFilterSelected: (HistoryFilter) -> Unit,
    onStatusFilterSelected: (HistoryStatusFilter) -> Unit,
    onLoadMore: () -> Unit,
    onEntryClick: (HistoryEntryType, String) -> Unit,
    onHideRequested: (HistoryEntry) -> Unit,
    onHideConfirmed: () -> Unit,
    onHideDismissed: () -> Unit,
    onNoticeDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    state.pendingHide?.let { entry ->
        HideConfirmationDialog(
            entry = entry,
            isHiding = entry.keyForUi() in state.hidingIds,
            onConfirm = onHideConfirmed,
            onDismiss = onHideDismissed,
        )
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            state.isLoading && state.entries.isEmpty() -> LoadingState()
            state.errorMessage != null && state.entries.isEmpty() -> ErrorState(state.errorMessage, onRefresh)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 280.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { PageHeader(onRefresh) }
                state.errorMessage?.let { message ->
                    item(span = { GridItemSpan(maxLineSpan) }) { InlineNotice(message, true, onNoticeDismissed) }
                }
                state.successMessage?.let { message ->
                    item(span = { GridItemSpan(maxLineSpan) }) { InlineNotice(message, false, onNoticeDismissed) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    HistoryControls(
                        state = state,
                        onQueryChanged = onQueryChanged,
                        onFilterSelected = onFilterSelected,
                        onStatusFilterSelected = onStatusFilterSelected,
                    )
                }
                if (state.visibleEntries.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(filtered = state.hasActiveFilters || state.entries.isNotEmpty(), onRefresh = onRefresh)
                    }
                } else {
                    items(items = state.visibleEntries, key = { it.keyForUi() }) { entry ->
                        HistoryCard(
                            entry = entry,
                            isHiding = entry.keyForUi() in state.hidingIds,
                            onClick = { onEntryClick(entry.type, entry.id) },
                            onHide = { onHideRequested(entry) },
                        )
                    }
                }
                if (state.hasMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = onLoadMore) {
                                Text("加载更多（${state.visibleEntries.size}/${state.filteredEntries.size}）")
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun PageHeader(onRefresh: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("历史记录", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("搜索并打开图片项目、AI 对话或视频", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新历史记录")
        }
    }
}

@Composable
private fun HistoryControls(
    state: HistoryUiState,
    onQueryChanged: (String) -> Unit,
    onFilterSelected: (HistoryFilter) -> Unit,
    onStatusFilterSelected: (HistoryStatusFilter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("搜索历史记录") },
            placeholder = { Text("名称、规格、状态或任务 ID") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (state.query.isBlank()) null else {
                {
                    IconButton(onClick = { onQueryChanged("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "清空搜索")
                    }
                }
            },
        )
        FilterRow(state.filter, onFilterSelected)
        StatusFilterRow(state.statusFilter, onStatusFilterSelected)
        Text(
            "共 ${state.filteredEntries.size} 条，当前显示 ${state.visibleEntries.size} 条",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilterRow(selected: HistoryFilter, onSelected: (HistoryFilter) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryFilter.entries.forEach { filter ->
            FilterChip(selected = selected == filter, onClick = { onSelected(filter) }, label = {
                Text(when (filter) {
                    HistoryFilter.All -> "全部类型"
                    HistoryFilter.Projects -> "图片项目"
                    HistoryFilter.Chats -> "AI 对话"
                    HistoryFilter.Videos -> "视频"
                })
            })
        }
    }
}

@Composable
private fun StatusFilterRow(selected: HistoryStatusFilter, onSelected: (HistoryStatusFilter) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryStatusFilter.entries.forEach { filter ->
            FilterChip(selected = selected == filter, onClick = { onSelected(filter) }, label = {
                Text(when (filter) {
                    HistoryStatusFilter.All -> "全部状态"
                    HistoryStatusFilter.InProgress -> "进行中"
                    HistoryStatusFilter.Completed -> "已完成"
                    HistoryStatusFilter.Failed -> "失败/取消"
                })
            })
        }
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry, isHiding: Boolean, onClick: () -> Unit, onHide: () -> Unit) {
    Card(shape = RoundedCornerShape(10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) { Preview(entry) }
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(entry.type.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(entry.title, modifier = Modifier.weight(1f).clickable(onClick = onClick), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (entry.canHide) {
                        IconButton(onClick = onHide, enabled = !isHiding) {
                            if (isHiding) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.DeleteOutline, contentDescription = "从我的历史记录隐藏")
                        }
                    }
                }
                if (entry.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(entry.status)
                    Spacer(Modifier.weight(1f))
                    entry.credits?.let { credits -> Text("$credits 积分", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                }
                if (entry.timestamp.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(entry.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun HideConfirmationDialog(entry: HistoryEntry, isHiding: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!isHiding) onDismiss() },
        icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
        title = { Text("从历史记录中隐藏？") },
        text = { Text("“${entry.title}”将不再出现在你的历史记录中。此操作不会执行管理员物理删除。") },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isHiding) {
                if (isHiding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("确认隐藏")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isHiding) { Text("取消") } },
    )
}

@Composable
private fun Preview(entry: HistoryEntry) {
    val canShowImage = entry.type != HistoryEntryType.Video && entry.previewUrl.isNotBlank()
    Surface(modifier = Modifier.fillMaxWidth().height(156.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        if (canShowImage) {
            AsyncImage(
                model = resolveMediaUrl(entry.previewUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = entry.type.icon(), contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val normalized = status.lowercase()
    val (label, colors) = when (normalized) {
        "images_completed", "completed", "succeeded", "success" -> "已完成" to (MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer)
        "images_failed", "images_partial_failed", "failed", "timeout", "cancelled", "canceled" -> "失败" to (MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer)
        "queued", "pending" -> "排队中" to (MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer)
        "draft", "copy_generated", "facts_confirmed" -> "准备中" to (MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer)
        "images_queued", "submitting", "submission_unknown", "submitted", "generating", "processing", "running", "upscaling", "quality_check" -> "生成中" to (MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer)
        else -> status.ifBlank { "未知" } to (MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(color = colors.first, contentColor = colors.second, shape = RoundedCornerShape(6.dp)) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

private fun HistoryEntryType.icon(): ImageVector = when (this) {
    HistoryEntryType.Project -> Icons.Outlined.FolderOpen
    HistoryEntryType.Chat -> Icons.Outlined.AutoAwesome
    HistoryEntryType.Video -> Icons.Outlined.Movie
}

private fun HistoryEntry.keyForUi() = "${type.name}:$id"

@Composable
private fun InlineNotice(message: String, isError: Boolean, onDismiss: () -> Unit) {
    Surface(color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.History, contentDescription = null, tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭提示") }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在读取历史记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Text(message)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("重新加载") }
        }
    }
}

@Composable
private fun EmptyState(filtered: Boolean, onRefresh: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(if (filtered) "没有符合条件的记录" else "暂无历史记录", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRefresh) { Text("刷新") }
    }
}
