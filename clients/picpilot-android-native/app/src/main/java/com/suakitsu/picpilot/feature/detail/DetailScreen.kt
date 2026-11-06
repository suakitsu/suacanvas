@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.suakitsu.picpilot.feature.detail

import android.content.Context
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suakitsu.picpilot.ui.ForegroundRefreshEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.suakitsu.picpilot.ui.components.PicPilotEmptyState
import com.suakitsu.picpilot.ui.components.PicPilotErrorState
import com.suakitsu.picpilot.ui.components.PicPilotLoadingState
import com.suakitsu.picpilot.ui.components.PicPilotImagePreview
import com.suakitsu.picpilot.ui.components.PicPilotImagePreviewDialog
import com.suakitsu.picpilot.ui.components.PicPilotStatusBadge
import com.suakitsu.picpilot.ui.components.PicPilotStatusTone
import java.util.Locale

@Composable
fun DetailRoute(
    kind: DetailKind,
    itemId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    gateway: DetailGateway = NoOpDetailGateway,
) {
    val viewModel: DetailViewModel = viewModel(
        key = "detail_${kind.name}_$itemId",
        factory = DetailViewModel.factory(kind, itemId, gateway),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ForegroundRefreshEffect(onRefresh = viewModel::refresh)
    DetailScreen(
        state = state,
        onNavigateBack = onNavigateBack,
        onRefresh = viewModel::refresh,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    state: DetailUiState,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            DetailTopBar(
                title = when (state.requestedKind) {
                    DetailKind.Project -> "项目详情"
                    DetailKind.CreativeChat -> "AI 对话详情"
                    DetailKind.Video -> "视频详情"
                },
                onNavigateBack = onNavigateBack,
                onRefresh = onRefresh,
                isRefreshing = state.isRefreshing,
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.isLoading && state.payload == null -> PicPilotLoadingState(
                    modifier = Modifier.fillMaxSize(),
                    title = "正在读取详情",
                    message = "按记录 ID 加载对应内容",
                )

                state.errorMessage != null && state.payload == null -> PicPilotErrorState(
                    modifier = Modifier.fillMaxSize(),
                    title = "无法打开详情",
                    message = state.errorMessage,
                    onRetry = onRefresh,
                )

                state.payload != null -> Box(Modifier.fillMaxSize()) {
                    when (val payload = requireNotNull(state.payload)) {
                        is DetailPayload.Project -> ProjectDetailContent(payload.value)
                        is DetailPayload.CreativeChat -> CreativeChatDetailContent(payload.value)
                        is DetailPayload.Video -> VideoDetailContent(payload.value)
                    }
                    if (state.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                            .size(28.dp),
                        )
                    }
                    state.errorMessage?.let { message ->
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                                .widthIn(max = 640.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = RoundedCornerShape(8.dp),
                            shadowElevation = 4.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = onRefresh) { Text("重试") }
                            }
                        }
                    }
                }

                else -> PicPilotEmptyState(
                    modifier = Modifier.fillMaxSize(),
                    title = "暂无详情",
                    message = "记录可能已被删除或隐藏。",
                    actionLabel = "重新加载",
                    onAction = onRefresh,
                )
            }
        }
    }
}

@Composable
private fun DetailTopBar(
    title: String,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
) {
    Surface(shadowElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(54.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            FilledTonalIconButton(onClick = onRefresh, enabled = !isRefreshing) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
            }
        }
    }
}

@Composable
private fun ProjectDetailContent(project: ProjectDetailModel) {
    var preview by remember { mutableStateOf<PicPilotImagePreview?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = if (wide) 28.dp else 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DetailHero(
                    title = project.productName.ifBlank { "未命名项目" },
                    subtitle = listOf(project.brand, project.country, project.language)
                        .filter(String::isNotBlank)
                        .joinToString(" · "),
                    status = project.status,
                    credits = project.creditsCost,
                )
            }
            if (project.latestFailureReason.isNotBlank()) {
                item { SafeErrorBanner(project.latestFailureReason) }
            }
            item {
                ProjectMetrics(project)
            }
            if (project.assets.isNotEmpty()) {
                item { SectionTitle("商品素材", "${project.assets.size} 张") }
                item {
                    AdaptiveMediaGrid(wide = wide) {
                        project.assets.forEach { asset ->
                            ImageMediaCard(
                                title = userFacingAssetName(asset.originalFilename, asset.imageType),
                                subtitle = imageMeta(asset.width, asset.height, asset.format, asset.fileSize),
                                url = asset.url,
                                status = userFacingImageType(asset.imageType),
                                onPreview = {
                                    preview = PicPilotImagePreview(asset.url, asset.originalFilename, asset.mimeType)
                                },
                            )
                        }
                    }
                }
            }
            if (project.imageTasks.isNotEmpty()) {
                item { SectionTitle("生成任务", "${project.imageTasks.size} 条") }
                items(project.imageTasks, key = ProjectImageTask::id) { task ->
                    ProjectTaskCard(
                        task = task,
                        onPreview = task.resultUrl.takeIf(String::isNotBlank)?.let { url ->
                            { preview = PicPilotImagePreview(url, task.imageName.ifBlank { task.id }, "image/png") }
                        },
                    )
                }
            }
        }
    }
    preview?.let { request ->
        PicPilotImagePreviewDialog(request = request, onDismiss = { preview = null })
    }
}

@Composable
private fun CreativeChatDetailContent(chat: CreativeChatDetailModel) {
    var preview by remember { mutableStateOf<PicPilotImagePreview?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = if (wide) 28.dp else 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DetailHero(
                    title = chat.title.ifBlank { "AI 创作对话" },
                    subtitle = listOf(chat.style, chat.size, chat.language, "${chat.versionCount} 个版本")
                        .filter(String::isNotBlank)
                        .joinToString(" · "),
                    status = chat.latestStatus,
                    credits = null,
                )
            }
            if (chat.messages.isEmpty() && chat.versions.isEmpty()) {
                item {
                    PicPilotEmptyState(
                        title = "此对话暂无内容",
                        message = "新消息或生成版本会显示在这里。",
                    )
                }
            } else {
                if (chat.messages.isNotEmpty()) {
                    item { SectionTitle("对话记录", "${chat.messages.size} 条") }
                    items(chat.messages, key = CreativeMessage::id) { message ->
                        ChatMessageCard(
                            message = message,
                            onPreview = message.resultUrl.takeIf(String::isNotBlank)?.let { url ->
                                { preview = PicPilotImagePreview(url, "对话结果", "image/png") }
                            },
                        )
                    }
                }
                if (chat.versions.isNotEmpty()) {
                    item { SectionTitle("生成版本", "${chat.versions.size} 个") }
                    items(chat.versions, key = CreativeVersion::id) { version ->
                        CreativeVersionCard(
                            version = version,
                            onPreview = version.resultUrl.takeIf(String::isNotBlank)?.let { url ->
                                { preview = PicPilotImagePreview(url, "版本 ${version.versionNo}", "image/png") }
                            },
                        )
                    }
                }
            }
        }
    }
    preview?.let { request ->
        PicPilotImagePreviewDialog(request = request, onDismiss = { preview = null })
    }
}

@Composable
private fun VideoDetailContent(video: VideoTaskDetailModel) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<PicPilotImagePreview?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = if (wide) 28.dp else 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DetailHero(
                    title = video.title.ifBlank { "商品视频" },
                    subtitle = listOf(
                        video.aspectRatio,
                        video.durationSeconds.takeIf { it > 0 }?.let { "${it}秒" }.orEmpty(),
                        video.requestedResolution,
                    ).filter(String::isNotBlank).joinToString(" · "),
                    status = video.status,
                    credits = video.creditsCost,
                )
            }
            if (video.safeError.isNotBlank()) {
                item { SafeErrorBanner(video.safeError) }
            }
            if (video.resultUrl.isNotBlank() && video.status.equals("completed", ignoreCase = true)) {
                item {
                    VideoPlayer(
                        url = video.resultUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 960.dp)
                            .aspectRatio(video.aspectRatio.toFloatRatio()),
                    )
                }
                item {
                    MediaActionRow(
                        onDownload = {
                            showActionResult(
                                context,
                                DetailMediaActions.download(context, video.resultUrl, video.title, "video/mp4"),
                            )
                        },
                        onShare = {
                            showActionResult(
                                context,
                                DetailMediaActions.shareRemote(context, video.resultUrl, "video/mp4"),
                            )
                        },
                    )
                }
            } else {
                item { VideoProgressCard(video.status, video.progress, video.queueAhead) }
            }
            if (video.sourceImageUrls.isNotEmpty()) {
                item { SectionTitle("参考图片", "${video.sourceImageUrls.size} 张") }
                item {
                    AdaptiveMediaGrid(wide = wide) {
                        video.sourceImageUrls.forEachIndexed { index, url ->
                            ImageMediaCard(
                                title = "参考图 ${index + 1}",
                                subtitle = "视频输入素材",
                                url = url,
                                status = "input",
                                onPreview = { preview = PicPilotImagePreview(url, "参考图 ${index + 1}", "image/jpeg") },
                            )
                        }
                    }
                }
            }
            item {
                VideoFacts(video)
            }
        }
    }
    preview?.let { request ->
        PicPilotImagePreviewDialog(request = request, onDismiss = { preview = null })
    }
}

@Composable
private fun DetailHero(
    title: String,
    subtitle: String,
    status: String,
    credits: Int?,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                StatusBadge(status)
            }
            if (subtitle.isNotBlank() || credits != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subtitle,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    credits?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "$it 积分",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectMetrics(project: ProjectDetailModel) {
    AdaptiveFacts(
        listOf(
            DetailField("总任务", project.totalImages.toString()),
            DetailField("已完成", project.successImages.toString()),
            DetailField("生成中", project.runningImages.toString()),
            DetailField("失败", project.failedImages.toString()),
        ),
    )
}

@Composable
private fun VideoFacts(video: VideoTaskDetailModel) {
    AdaptiveFacts(
        listOf(
            DetailField("模型", video.model.ifBlank { "未返回" }),
            DetailField("进度", "${video.progress.coerceIn(0, 100)}%"),
        ),
    )
}

@Composable
private fun AdaptiveFacts(fields: List<DetailField>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 4,
    ) {
        fields.forEach { field ->
            Surface(
                modifier = Modifier.widthIn(min = 132.dp).weight(1f),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(field.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(field.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun AdaptiveMediaGrid(wide: Boolean, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = if (wide) 3 else 2,
        content = content,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageMediaCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    url: String,
    status: String,
    onPreview: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = modifier.widthIn(min = 150.dp, max = 320.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .combinedClickable(
                    role = Role.Image,
                    onClick = onPreview,
                    onLongClick = {
                        showActionResult(context, DetailMediaActions.shareRemote(context, url, "image/*"))
                    },
                ),
        ) {
            when (painter.state) {
                is coil.compose.AsyncImagePainter.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
                is coil.compose.AsyncImagePainter.State.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.BrokenImage, contentDescription = "图片加载失败", modifier = Modifier.size(38.dp))
                }
                else -> SubcomposeAsyncImageContent()
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = onPreview, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.ZoomIn, contentDescription = "查看原图")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectTaskCard(task: ProjectImageTask, onPreview: (() -> Unit)?) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.imageName.ifBlank { task.imageType.ifBlank { "图片任务" } }, fontWeight = FontWeight.SemiBold)
                }
                StatusBadge(task.status)
            }
            if (task.resultUrl.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                SubcomposeAsyncImage(
                    model = task.resultUrl,
                    contentDescription = task.imageName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .combinedClickable(
                            role = Role.Image,
                            onClick = { onPreview?.invoke() },
                            onLongClick = {
                                showActionResult(
                                    context,
                                    DetailMediaActions.shareRemote(context, task.resultUrl, "image/*"),
                                )
                            },
                        ),
                ) {
                    when (painter.state) {
                        is coil.compose.AsyncImagePainter.State.Loading -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(Modifier.size(28.dp)) }
                        is coil.compose.AsyncImagePainter.State.Error -> MediaLoadError(
                            modifier = Modifier.fillMaxSize(),
                            compact = true,
                        )
                        else -> SubcomposeAsyncImageContent()
                    }
                }
                Spacer(Modifier.height(8.dp))
                MediaActionRow(
                    primaryLabel = "查看原图",
                    onPrimary = onPreview,
                    onDownload = {
                        showActionResult(
                            context,
                            DetailMediaActions.download(context, task.resultUrl, task.imageName.ifBlank { task.id }, "image/png"),
                        )
                    },
                    onShare = {
                        showActionResult(context, DetailMediaActions.shareRemote(context, task.resultUrl, "image/*"))
                    },
                )
            }
            if (task.safeError.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                SafeErrorBanner(task.safeError)
            }
            if (task.creditsCost > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${task.creditsCost} 积分",
                    modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatMessageCard(message: CreativeMessage, onPreview: (() -> Unit)?) {
    val user = message.role.equals("user", ignoreCase = true)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(if (user) 0.88f else 0.96f),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (user) "我" else "PicPilot", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    if (message.status.isNotBlank()) StatusBadge(message.status)
                }
                if (message.content.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(message.content)
                }
                if (message.resultUrl.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    AsyncImage(
                        model = message.resultUrl,
                        contentDescription = "对话生成结果",
                        modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    OutlinedButton(onClick = { onPreview?.invoke() }, enabled = onPreview != null) {
                        Icon(Icons.Outlined.ZoomIn, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("查看原图")
                    }
                }
            }
        }
    }
}

@Composable
private fun CreativeVersionCard(version: CreativeVersion, onPreview: (() -> Unit)?) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("版本 ${version.versionNo}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusBadge(version.status)
            }
            val meta = listOf(version.style, version.size)
                .filter(String::isNotBlank).joinToString(" · ")
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (version.resultUrl.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                AsyncImage(
                    model = version.resultUrl,
                    contentDescription = "版本 ${version.versionNo} 结果",
                    modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit,
                )
                MediaActionRow(
                    primaryLabel = "查看原图",
                    onPrimary = onPreview,
                    onDownload = {
                        showActionResult(context, DetailMediaActions.download(context, version.resultUrl, "version_${version.versionNo}", "image/png"))
                    },
                    onShare = {
                        showActionResult(context, DetailMediaActions.shareRemote(context, version.resultUrl, "image/*"))
                    },
                )
            }
            if (version.safeError.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                SafeErrorBanner(version.safeError)
            }
        }
    }
}

@Composable
private fun VideoPlayer(url: String, modifier: Modifier = Modifier) {
    var error by remember(url) { mutableStateOf(false) }
    var videoView by remember(url) { mutableStateOf<VideoView?>(null) }
    DisposableEffect(url) {
        onDispose {
            videoView?.stopPlayback()
            videoView = null
        }
    }
    Card(modifier = modifier, shape = RoundedCornerShape(8.dp)) {
        if (error) {
            PicPilotErrorState(
                title = "视频无法播放",
                message = "可尝试下载后使用系统播放器打开。",
                onRetry = { error = false },
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                factory = { context ->
                    VideoView(context).apply {
                        videoView = this
                        val controller = MediaController(context)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setOnPreparedListener { player ->
                            player.isLooping = false
                            controller.show()
                        }
                        setOnErrorListener { _, _, _ ->
                            error = true
                            true
                        }
                    }
                },
                update = { view ->
                    if (view.tag != url) {
                        view.tag = url
                        view.setVideoURI(Uri.parse(url))
                        view.seekTo(1)
                    }
                },
            )
        }
    }
}

@Composable
private fun VideoProgressCard(status: String, progress: Int, queueAhead: Int?) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            StatusBadge(status)
            Spacer(Modifier.height(8.dp))
            Text("进度 ${progress.coerceIn(0, 100)}%", style = MaterialTheme.typography.titleMedium)
            queueAhead?.let {
                Spacer(Modifier.height(4.dp))
                Text("当前任务正在排队，前方还有 $it 个任务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MediaActionRow(
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    onDownload: () -> Unit,
    onShare: () -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (primaryLabel != null && onPrimary != null) {
            FilledTonalButton(onClick = onPrimary) {
                Icon(Icons.Outlined.ZoomIn, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(primaryLabel)
            }
        }
        OutlinedButton(onClick = onDownload) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("下载")
        }
        OutlinedButton(onClick = onShare) {
            Icon(Icons.Outlined.Share, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("分享")
        }
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FieldSection(title: String, fields: List<DetailField>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            fields.forEachIndexed { index, field ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text(field.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(field.value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LongTextSection(title: String, text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SafeErrorBanner(message: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("任务未完成", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun TimelineFooter(createdAt: String, updatedAt: String, sourceType: String) {
    val lines = listOfNotNull(
        createdAt.takeIf(String::isNotBlank)?.let { "创建：$it" },
        updatedAt.takeIf(String::isNotBlank)?.let { "更新：$it" },
        sourceType.takeIf(String::isNotBlank)?.let { "来源：$it" },
    )
    if (lines.isNotEmpty()) {
        Text(
            lines.joinToString("\n"),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusBadge(status: String) {
    val normalized = status.lowercase(Locale.ROOT)
    val (label, tone) = when (normalized) {
        "completed", "succeeded", "success", "images_completed" -> "已完成" to PicPilotStatusTone.Success
        "failed", "timeout", "cancelled", "images_failed" -> "失败" to PicPilotStatusTone.Error
        "queued", "images_queued" -> "排队中" to PicPilotStatusTone.Warning
        "running", "submitting", "submission_unknown", "submitted", "generating", "upscaling", "quality_check" -> "生成中" to PicPilotStatusTone.Info
        "images_partial_failed" -> "部分失败" to PicPilotStatusTone.Warning
        "draft" -> "草稿" to PicPilotStatusTone.Neutral
        "copy_generated" -> "文案已生成" to PicPilotStatusTone.Info
        "facts_confirmed" -> "信息已确认" to PicPilotStatusTone.Success
        else -> status.ifBlank { "未知" } to PicPilotStatusTone.Neutral
    }
    PicPilotStatusBadge(text = label, tone = tone)
}

@Composable
private fun MediaLoadError(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(if (compact) 12.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.BrokenImage,
            contentDescription = null,
            tint = if (compact) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
            modifier = Modifier.size(if (compact) 34.dp else 48.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (compact) "图片暂时无法加载" else "原图加载失败，请检查网络后重试",
            color = if (compact) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = onRetry) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("重新加载")
            }
        }
    }
}

private fun imageMeta(width: Int, height: Int, format: String, bytes: Long): String = buildList {
    if (width > 0 && height > 0) add("${width}×$height")
    if (format.isNotBlank()) add(format.uppercase(Locale.ROOT))
    if (bytes > 0) add(formatBytes(bytes))
}.joinToString(" · ")

private fun userFacingAssetName(filename: String, imageType: String): String {
    val normalized = filename.trim().lowercase(Locale.ROOT)
    return when {
        normalized.isBlank() -> userFacingImageType(imageType)
        normalized in setOf("generated-image", "generated_image", "image", "output") -> "生成图片"
        normalized.startsWith("img_") -> "生成图片"
        else -> filename
    }
}

private fun userFacingImageType(imageType: String): String = when (imageType.trim().lowercase(Locale.ROOT)) {
    "reference_history" -> "历史参考图"
    "reference", "input", "source" -> "参考图片"
    "generated", "generated_image", "output", "result" -> "生成图片"
    "main", "main_image" -> "商品主图"
    else -> imageType.takeIf(String::isNotBlank) ?: "商品素材"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun String.toFloatRatio(): Float {
    val parts = split(':')
    val width = parts.getOrNull(0)?.toFloatOrNull()
    val height = parts.getOrNull(1)?.toFloatOrNull()
    return if (width != null && height != null && width > 0f && height > 0f) width / height else 16f / 9f
}

private fun showActionResult(context: Context, success: Boolean) {
    if (!success) Toast.makeText(context, "操作失败，请检查媒体地址后重试", Toast.LENGTH_SHORT).show()
}
