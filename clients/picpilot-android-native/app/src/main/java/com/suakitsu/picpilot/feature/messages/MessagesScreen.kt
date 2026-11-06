package com.suakitsu.picpilot.feature.messages

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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suakitsu.picpilot.ui.ForegroundRefreshEffect
import com.suakitsu.picpilot.core.model.StationMessage

@Composable
fun MessagesRoute(
    modifier: Modifier = Modifier,
    viewModel: MessagesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ForegroundRefreshEffect(onRefresh = viewModel::refresh)
    MessagesScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onMarkRead = viewModel::markRead,
        onMarkAllRead = viewModel::markAllRead,
        onDismissNotice = viewModel::dismissNotice,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    state: MessagesUiState,
    onRefresh: () -> Unit,
    onMarkRead: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            state.isLoading && state.messages.isEmpty() -> LoadingState()
            state.errorMessage != null && state.messages.isEmpty() -> ErrorState(state.errorMessage, onRefresh)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PageHeader(
                        unreadCount = state.unreadCount,
                        isMarkingAll = state.isMarkingAll,
                        onRefresh = onRefresh,
                        onMarkAllRead = onMarkAllRead,
                    )
                }
                state.errorMessage?.let { message ->
                    item(span = { GridItemSpan(maxLineSpan) }) { InlineNotice(message, true, onDismissNotice) }
                }
                state.successMessage?.let { message ->
                    item(span = { GridItemSpan(maxLineSpan) }) { InlineNotice(message, false, onDismissNotice) }
                }
                if (state.messages.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(onRefresh) }
                } else {
                    items(state.messages, key = StationMessage::id) { message ->
                        MessageCard(
                            message = message,
                            isMarkingRead = message.id in state.markingIds,
                            onMarkRead = { onMarkRead(message.id) },
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun PageHeader(
    unreadCount: Int,
    isMarkingAll: Boolean,
    onRefresh: () -> Unit,
    onMarkAllRead: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("站内信", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError, shape = CircleShape) {
                        Text(
                            unreadCount.coerceAtMost(99).toString(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(
                "平台通知与服务消息",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalIconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新站内信")
        }
        }
        if (unreadCount > 0 || isMarkingAll) {
            OutlinedButton(
                onClick = onMarkAllRead,
                enabled = !isMarkingAll && unreadCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.MarkEmailRead, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isMarkingAll) "正在标记全部已读" else "全部标为已读")
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: StationMessage,
    isMarkingRead: Boolean,
    onMarkRead: () -> Unit,
) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    Card(
        onClick = {
            expanded = !expanded
            if (!message.read) onMarkRead()
        },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (message.read) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            },
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (isMarkingRead) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (message.read) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary,
                    contentColor = if (message.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Outlined.NotificationsNone, contentDescription = null, modifier = Modifier.padding(9.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        message.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (message.read) FontWeight.SemiBold else FontWeight.Bold,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (message.publishedAt.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            message.publishedAt,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!message.read) {
                    Surface(modifier = Modifier.size(9.dp), shape = CircleShape, color = MaterialTheme.colorScheme.error) {}
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (message.content.length > 100 || message.content.count { it == '\n' } > 2) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = {
                        expanded = !expanded
                        if (!message.read) onMarkRead()
                    }) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (expanded) "收起消息" else "展开消息",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineNotice(message: String, isError: Boolean, onDismiss: () -> Unit) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.MarkEmailRead,
                contentDescription = null,
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在加载站内信", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun EmptyState(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.NotificationsNone, contentDescription = null, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text("暂无站内消息", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRefresh) { Text("刷新") }
    }
}
