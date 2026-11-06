package com.suakitsu.picpilot.feature.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MovieCreation
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.suakitsu.picpilot.core.media.resolveMediaUrl
import com.suakitsu.picpilot.core.model.ProjectSummary
import com.suakitsu.picpilot.ui.ForegroundRefreshEffect
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun WorkspaceRoute(
    onProjectClick: (String) -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onMessagesClick: () -> Unit = {},
    onCommerceClick: () -> Unit = {},
    onVideoClick: () -> Unit = {},
    onAiCreateClick: () -> Unit = {},
    onCanvasClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: WorkspaceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ForegroundRefreshEffect(onRefresh = viewModel::refresh)
    WorkspaceScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onProjectClick = onProjectClick,
        onHistoryClick = onHistoryClick,
        onMessagesClick = onMessagesClick,
        onCommerceClick = onCommerceClick,
        onVideoClick = onVideoClick,
        onAiCreateClick = onAiCreateClick,
        onCanvasClick = onCanvasClick,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    state: WorkspaceUiState,
    onRefresh: () -> Unit,
    onProjectClick: (String) -> Unit,
    onHistoryClick: () -> Unit,
    onMessagesClick: () -> Unit,
    onCommerceClick: () -> Unit,
    onVideoClick: () -> Unit,
    onAiCreateClick: () -> Unit,
    onCanvasClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            state.isLoading && !state.hasContent -> LoadingState()
            state.errorMessage != null && !state.hasContent -> ErrorState(state.errorMessage, onRefresh)
            !state.hasContent -> EmptyState(onRefresh)
            else -> WorkspaceContent(
                state = state,
                onRefresh = onRefresh,
                onProjectClick = onProjectClick,
                onHistoryClick = onHistoryClick,
                onMessagesClick = onMessagesClick,
                onCommerceClick = onCommerceClick,
                onVideoClick = onVideoClick,
                onAiCreateClick = onAiCreateClick,
                onCanvasClick = onCanvasClick,
            )
        }
    }
}

@Composable
private fun WorkspaceContent(
    state: WorkspaceUiState,
    onRefresh: () -> Unit,
    onProjectClick: (String) -> Unit,
    onHistoryClick: () -> Unit,
    onMessagesClick: () -> Unit,
    onCommerceClick: () -> Unit,
    onVideoClick: () -> Unit,
    onAiCreateClick: () -> Unit,
    onCanvasClick: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { HomeHeader(onRefresh = onRefresh, onMessagesClick = onMessagesClick, unread = state.unreadMessageCount) }
        state.errorMessage?.let { message -> item { InlineError(message, onRefresh) } }
        item {
            BalanceOverview(
                credits = state.billing?.credits ?: 0,
                running = state.runningTaskCount,
                projects = state.projects.size,
            )
        }
        item {
            QuickActions(
                onCommerceClick = onCommerceClick,
                onVideoClick = onVideoClick,
                onAiCreateClick = onAiCreateClick,
                onCanvasClick = onCanvasClick,
            )
        }
        item { SectionHeader("最近创作", "查看全部", onHistoryClick) }
        item {
            if (state.projects.isEmpty()) {
                SectionEmpty("还没有创作项目，先从上面的入口开始")
            } else {
                ProjectList(state.projects.take(5), onProjectClick)
            }
        }
        item { SectionHeader("任务概览", "历史记录", onHistoryClick) }
        item {
            CompactStats(
                imageCount = state.projects.sumOf(ProjectSummary::totalImages),
                videoCount = state.videos.size,
                chatCount = state.chats.size,
            )
        }
        if (state.messages.isNotEmpty()) {
            item { SectionHeader("最新消息", "全部消息", onMessagesClick) }
            item { MessageList(state, onMessagesClick) }
        }
        item { Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
private fun HomeHeader(onRefresh: () -> Unit, onMessagesClick: () -> Unit, unread: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("PicPilot", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("今天想创作什么？", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onMessagesClick) {
            Box {
                Icon(Icons.Outlined.NotificationsNone, contentDescription = "站内消息")
                if (unread > 0) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).size(8.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error,
                    ) {}
                }
            }
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "刷新工作台") }
    }
}

@Composable
private fun BalanceOverview(credits: Int, running: Int, projects: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("可用积分", color = Color.White.copy(alpha = 0.76f), style = MaterialTheme.typography.bodySmall)
                    Text(credits.toString(), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                }
                Surface(color = Color.White.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("$running 个任务进行中", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "已管理 $projects 个项目",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun QuickActions(
    onCommerceClick: () -> Unit,
    onVideoClick: () -> Unit,
    onAiCreateClick: () -> Unit,
    onCanvasClick: () -> Unit,
) {
    val actions = listOf(
        QuickAction("电商制图", Icons.Outlined.Storefront, MaterialTheme.colorScheme.primaryContainer, onCommerceClick),
        QuickAction("AI 创作", Icons.Outlined.AutoAwesome, MaterialTheme.colorScheme.secondaryContainer, onAiCreateClick),
        QuickAction("AI 画布", Icons.Outlined.AccountTree, MaterialTheme.colorScheme.tertiaryContainer, onCanvasClick),
        QuickAction("商品视频", Icons.Outlined.MovieCreation, MaterialTheme.colorScheme.surfaceContainerHigh, onVideoClick),
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp)) {
            actions.forEach { action ->
                Column(
                    modifier = Modifier.weight(1f).clickable(onClick = action.onClick),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(modifier = Modifier.size(42.dp), color = action.color, shape = RoundedCornerShape(11.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(action.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(action.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

private data class QuickAction(val label: String, val icon: ImageVector, val color: Color, val onClick: () -> Unit)

@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.clickable(onClick = onAction).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(action, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ProjectList(projects: List<ProjectSummary>, onProjectClick: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            projects.forEachIndexed { index, project ->
                ProjectRow(project, onClick = { onProjectClick(project.id) })
                if (index != projects.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = 78.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(project: ProjectSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cover = project.coverUrl.ifBlank { project.previewUrls.firstOrNull().orEmpty() }
        if (cover.isNotBlank()) {
            SubcomposeAsyncImage(
                model = resolveMediaUrl(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)),
            ) {
                when (painter.state) {
                    is coil.compose.AsyncImagePainter.State.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.BrokenImage, null, tint = MaterialTheme.colorScheme.outline)
                    }
                    is coil.compose.AsyncImagePainter.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    else -> SubcomposeAsyncImageContent()
                }
            }
        } else {
            Surface(Modifier.size(54.dp), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(9.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.primary) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                project.productName.ifBlank { project.brand.ifBlank { "未命名项目" } },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${project.successImages}/${project.totalImages} 张完成 · ${project.creditsCost} 积分",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(formatTime(project.updatedAt.ifBlank { project.createdAt }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = "查看项目", tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun CompactStats(imageCount: Int, videoCount: Int, chatCount: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            StatItem("图片", imageCount, Modifier.weight(1f))
            StatDivider()
            StatItem("视频", videoCount, Modifier.weight(1f))
            StatDivider()
            StatItem("AI 对话", chatCount, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatItem(label: String, count: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatDivider() {
    Surface(Modifier.width(1.dp).height(34.dp), color = MaterialTheme.colorScheme.outlineVariant) {}
}

@Composable
private fun MessageList(state: WorkspaceUiState, onMessagesClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            state.messages.take(3).forEachIndexed { index, message ->
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onMessagesClick).padding(14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (!message.read) Surface(Modifier.padding(top = 6.dp).size(7.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {}
                    else Spacer(Modifier.width(7.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(message.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(message.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                }
                if (index != state.messages.take(3).lastIndex) HorizontalDivider(Modifier.padding(start = 31.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun InlineError(message: String, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, contentDescription = "重试") }
        }
    }
}

@Composable
private fun SectionEmpty(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Text(text, Modifier.fillMaxWidth().padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在同步工作台", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Text(message)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("重新加载") }
        }
    }
}

@Composable
private fun EmptyState(onRefresh: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp))
            Text("还没有创作内容", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRefresh) { Text("刷新") }
        }
    }
}

private val homeTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatTime(value: String): String = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).format(homeTimeFormatter)
}.recoverCatching {
    OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(homeTimeFormatter)
}.getOrElse {
    value.replace('T', ' ').take(16)
}
