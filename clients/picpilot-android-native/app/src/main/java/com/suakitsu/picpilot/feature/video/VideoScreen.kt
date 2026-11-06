package com.suakitsu.picpilot.feature.video

import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suakitsu.picpilot.ui.ForegroundRefreshEffect
import coil.compose.AsyncImage
import com.suakitsu.picpilot.core.model.VideoModelConfig
import com.suakitsu.picpilot.core.model.VideoTask
import com.suakitsu.picpilot.core.model.VideoVariant
import com.suakitsu.picpilot.ui.components.PicPilotImagePreview
import com.suakitsu.picpilot.ui.components.PicPilotImagePreviewDialog

@Composable
fun VideoRoute(
    onTaskClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: VideoViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ForegroundRefreshEffect(onRefresh = viewModel::refresh)
    VideoScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onTaskClick = onTaskClick,
        onTitleChange = viewModel::updateTitle,
        onPromptChange = viewModel::updatePrompt,
        onSeedChange = viewModel::updateSeed,
        onModelSelect = viewModel::selectModel,
        onResolutionSelect = viewModel::selectResolution,
        onDurationSelect = viewModel::selectDuration,
        onAspectRatioSelect = viewModel::selectAspectRatio,
        onGenerateAudioChange = viewModel::setGenerateAudio,
        onImagesSelected = viewModel::addImages,
        onImageRemove = viewModel::removeImage,
        onOptimizeScript = viewModel::optimizeScript,
        onPrepareConfirmation = viewModel::prepareConfirmation,
        onDismissConfirmation = viewModel::dismissConfirmation,
        onConfirm = viewModel::confirmSubmission,
        onDismissNotice = viewModel::dismissNotice,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(
    state: VideoUiState,
    onRefresh: () -> Unit,
    onTaskClick: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onSeedChange: (String) -> Unit,
    onModelSelect: (String) -> Unit,
    onResolutionSelect: (String) -> Unit,
    onDurationSelect: (Int) -> Unit,
    onAspectRatioSelect: (String) -> Unit,
    onGenerateAudioChange: (Boolean) -> Unit,
    onImagesSelected: (List<android.net.Uri>) -> Unit,
    onImageRemove: (String) -> Unit,
    onOptimizeScript: () -> Unit,
    onPrepareConfirmation: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onConfirm: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onImagesSelected(uris)
    }

    state.confirmation?.let { confirmation ->
        ConfirmationDialog(
            confirmation = confirmation,
            isRetry = state.retryableSubmission,
            isSubmitting = state.isSubmitting,
            onDismiss = onDismissConfirmation,
            onConfirm = onConfirm,
        )
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            state.isLoading && state.config == null -> LoadingState()
            state.errorMessage != null && state.config == null -> ErrorState(state.errorMessage, onRefresh)
            else -> VideoContent(
                state = state,
                onRefresh = onRefresh,
                onTaskClick = onTaskClick,
                onTitleChange = onTitleChange,
                onPromptChange = onPromptChange,
                onSeedChange = onSeedChange,
                onModelSelect = onModelSelect,
                onResolutionSelect = onResolutionSelect,
                onDurationSelect = onDurationSelect,
                onAspectRatioSelect = onAspectRatioSelect,
                onGenerateAudioChange = onGenerateAudioChange,
                onPickImages = {
                    picker.launch(
                        arrayOf(
                            "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif",
                            "application/octet-stream",
                        ),
                    )
                },
                onImageRemove = onImageRemove,
                onOptimizeScript = onOptimizeScript,
                onPrepareConfirmation = onPrepareConfirmation,
                onDismissNotice = onDismissNotice,
            )
        }
    }
}

@Composable
private fun VideoContent(
    state: VideoUiState,
    onRefresh: () -> Unit,
    onTaskClick: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onSeedChange: (String) -> Unit,
    onModelSelect: (String) -> Unit,
    onResolutionSelect: (String) -> Unit,
    onDurationSelect: (Int) -> Unit,
    onAspectRatioSelect: (String) -> Unit,
    onGenerateAudioChange: (Boolean) -> Unit,
    onPickImages: () -> Unit,
    onImageRemove: (String) -> Unit,
    onOptimizeScript: () -> Unit,
    onPrepareConfirmation: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val isWide = LocalConfiguration.current.screenWidthDp >= 840
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (isWide) 24.dp else 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHeader(state, onRefresh) }
        state.errorMessage?.let { item { InlineError(it) } }
        state.noticeMessage?.let {
            item { NoticeMessage(it, onDismissNotice) }
        }
        item { ServiceNotice(state) }
        item {
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    CreationForm(
                        state = state,
                        onTitleChange = onTitleChange,
                        onPromptChange = onPromptChange,
                        onSeedChange = onSeedChange,
                        onModelSelect = onModelSelect,
                        onResolutionSelect = onResolutionSelect,
                        onDurationSelect = onDurationSelect,
                        onAspectRatioSelect = onAspectRatioSelect,
                        onGenerateAudioChange = onGenerateAudioChange,
                        onPickImages = onPickImages,
                        onImageRemove = onImageRemove,
                        onOptimizeScript = onOptimizeScript,
                        modifier = Modifier.weight(1.65f),
                    )
                    CostAndSubmit(
                        state = state,
                        onSubmit = onPrepareConfirmation,
                        modifier = Modifier.weight(0.85f),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CreationForm(
                        state = state,
                        onTitleChange = onTitleChange,
                        onPromptChange = onPromptChange,
                        onSeedChange = onSeedChange,
                        onModelSelect = onModelSelect,
                        onResolutionSelect = onResolutionSelect,
                        onDurationSelect = onDurationSelect,
                        onAspectRatioSelect = onAspectRatioSelect,
                        onGenerateAudioChange = onGenerateAudioChange,
                        onPickImages = onPickImages,
                        onImageRemove = onImageRemove,
                        onOptimizeScript = onOptimizeScript,
                    )
                    CostAndSubmit(state, onPrepareConfirmation)
                }
            }
        }
        item { SectionHeader("最近任务", "异步生成，无需停留在本页") }
        if (state.tasks.isEmpty()) {
            item { EmptySection("暂无视频任务") }
        } else {
            items(state.tasks, key = VideoTask::id) { task ->
                TaskCard(task = task, onClick = { onTaskClick(task.id) })
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun PageHeader(state: VideoUiState, onRefresh: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("商品视频", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "上传商品参考图，按后台可用模型生成视频",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.isRefreshing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else FilledTonalIconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新视频配置和任务")
        }
    }
}

@Composable
private fun ServiceNotice(state: VideoUiState) {
    val enabled = state.config?.enabled == true && state.enabledModels.isNotEmpty()
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (enabled) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(38.dp),
                color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(9.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Movie, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (enabled) "视频服务可用" else "当前没有可用的视频模型", fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.config?.mockMode == true) "当前为 Mock 模式，不会创建真实视频" else "价格和参数实时读取管理员配置",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.config?.mockMode == true) StateBadge("Mock")
        }
    }
}

@Composable
private fun CreationForm(
    state: VideoUiState,
    onTitleChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onSeedChange: (String) -> Unit,
    onModelSelect: (String) -> Unit,
    onResolutionSelect: (String) -> Unit,
    onDurationSelect: (Int) -> Unit,
    onAspectRatioSelect: (String) -> Unit,
    onGenerateAudioChange: (Boolean) -> Unit,
    onPickImages: () -> Unit,
    onImageRemove: (String) -> Unit,
    onOptimizeScript: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionHeader("生成设置", "先选模型规格，再补充图片和脚本")
            ModelSelector(state, onModelSelect)
            VariantSelector(state, onResolutionSelect, onDurationSelect)
            RatioAndAudioSelector(state, onAspectRatioSelect, onGenerateAudioChange)
            HorizontalDivider()
            ReferencePicker(state, onPickImages, onImageRemove)
            HorizontalDivider()
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("任务名称（可选）") },
                singleLine = true,
                enabled = !state.draftLocked,
                supportingText = { Text("便于在历史任务中识别") },
            )
            OutlinedTextField(
                value = state.prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("视频描述") },
                placeholder = { Text("例如：镜头环绕商品，展示材质细节和使用场景，不要字幕和水印") },
                minLines = 5,
                maxLines = 10,
                enabled = !state.draftLocked,
                isError = state.prompt.isNotBlank() && state.prompt.trim().length < 4,
                supportingText = {
                    Row(Modifier.fillMaxWidth()) {
                        Text("至少 4 个字符")
                        Spacer(Modifier.weight(1f))
                        Text("${state.prompt.length}/4000")
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onOptimizeScript,
                    enabled = !state.draftLocked,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isOptimizingScript) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.isOptimizingScript) "正在优化" else "AI 优化脚本")
                }
                OutlinedTextField(
                    value = state.seedText,
                    onValueChange = onSeedChange,
                    modifier = Modifier.weight(0.8f),
                    label = { Text("随机种子") },
                    placeholder = { Text("可选") },
                    singleLine = true,
                    enabled = !state.draftLocked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            if (state.scriptSummary.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("脚本摘要", fontWeight = FontWeight.SemiBold)
                        Text(state.scriptSummary, style = MaterialTheme.typography.bodySmall)
                        if (state.scriptMode.isNotBlank()) {
                            Text(
                                if (state.scriptMode == "storyboard") "分镜脚本" else "单镜头脚本",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelSelector(state: VideoUiState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.selectedModel
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("模型", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (state.enabledModels.isEmpty()) {
            EmptySection("管理员尚未启用视频模型")
        } else {
            Box(Modifier.fillMaxWidth()) {
                Surface(
                    onClick = { if (!state.draftLocked) expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    enabled = !state.draftLocked,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(selected?.name ?: "请选择模型", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                selected?.description?.takeIf(String::isNotBlank) ?: "按画质、速度和成本选择",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "选择视频模型")
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f),
                ) {
                    state.enabledModels.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(model.name, fontWeight = FontWeight.SemiBold)
                                    model.description.takeIf(String::isNotBlank)?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            leadingIcon = if (model.code == state.selectedModelCode) {
                                { Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                            } else null,
                            onClick = {
                                onSelect(model.code)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VariantSelector(
    state: VideoUiState,
    onResolutionSelect: (String) -> Unit,
    onDurationSelect: (Int) -> Unit,
) {
    val model = state.selectedModel ?: return
    val enabledVariants = model.variants.filter(VideoVariant::enabled)
    val resolutions = enabledVariants.map(VideoVariant::resolution).distinct()
    val durations = enabledVariants.filter { it.resolution == state.selectedResolution }
        .map(VideoVariant::durationSeconds).distinct().sorted()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("分辨率", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                resolutions.forEach { value ->
                    FilterChip(
                        selected = value == state.selectedResolution,
                        onClick = { onResolutionSelect(value) },
                        enabled = !state.draftLocked,
                        label = { Text(value.uppercase()) },
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("时长", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                durations.forEach { seconds ->
                    FilterChip(
                        selected = seconds == state.selectedDurationSeconds,
                        onClick = { onDurationSelect(seconds) },
                        enabled = !state.draftLocked && (model.durationSelectable || seconds == model.defaultDurationSeconds || durations.size == 1),
                        label = { Text("$seconds 秒") },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatioAndAudioSelector(
    state: VideoUiState,
    onAspectRatioSelect: (String) -> Unit,
    onGenerateAudioChange: (Boolean) -> Unit,
) {
    val model = state.selectedModel ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("画面比例", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            model.aspectRatios.forEach { ratio ->
                FilterChip(
                    selected = ratio == state.selectedAspectRatio,
                    onClick = { onAspectRatioSelect(ratio) },
                    enabled = !state.draftLocked,
                    label = { Text(ratio) },
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("生成音轨", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (model.audioToggle) "由所选模型同步生成音频" else "当前模型不支持音轨开关",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.generateAudio,
                    onCheckedChange = onGenerateAudioChange,
                    enabled = model.audioToggle && !state.draftLocked,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReferencePicker(
    state: VideoUiState,
    onPickImages: () -> Unit,
    onImageRemove: (String) -> Unit,
) {
    var preview by remember { mutableStateOf<PicPilotImagePreview?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("商品参考图", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "至少 1 张，最多 ${state.maxReferenceImages} 张；支持 JPG、PNG、WebP、HEIC",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onPickImages,
                enabled = !state.draftLocked && state.references.size < state.maxReferenceImages,
            ) {
                if (state.isReadingImages) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text(if (state.references.isEmpty()) "选择图片" else "继续添加")
            }
        }
        if (state.references.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.Image, contentDescription = null, Modifier.size(34.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("尚未添加商品参考图", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            FlowRow(
                maxItemsInEachRow = if (LocalConfiguration.current.screenWidthDp >= 600) 5 else 3,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.references.forEachIndexed { index, image ->
                    Box(
                        modifier = Modifier
                            .width(if (LocalConfiguration.current.screenWidthDp >= 600) 112.dp else 96.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                preview = PicPilotImagePreview(
                                    url = image.uri.toString(),
                                    title = image.displayName,
                                    mimeType = image.mimeType,
                                )
                            }
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        AsyncImage(
                            model = image.uri,
                            contentDescription = "参考图 ${index + 1}",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        Surface(
                            modifier = Modifier.align(Alignment.TopStart).padding(5.dp),
                            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text("${index + 1}", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                        }
                        FilledTonalIconButton(
                            onClick = { onImageRemove(image.localId) },
                            enabled = !state.draftLocked,
                            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(34.dp),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "移除 ${image.displayName}", Modifier.size(18.dp))
                        }
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
private fun CostAndSubmit(
    state: VideoUiState,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Wallet, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Text("费用与提交", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            SummaryLine("模型", state.selectedModel?.name ?: "未选择")
            SummaryLine("规格", listOfNotNull(
                state.selectedResolution.takeIf(String::isNotBlank)?.uppercase(),
                state.selectedDurationSeconds.takeIf { it > 0 }?.let { "$it 秒" },
                state.selectedAspectRatio,
            ).joinToString(" · "))
            SummaryLine("参考图", "${state.references.size} / ${state.maxReferenceImages} 张")
            SummaryLine("音轨", if (state.generateAudio) "生成" else "不生成")
            HorizontalDivider()
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("本次预计消耗", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                    "${state.billablePrice} 积分",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text("余额 ${state.credits}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            if (state.config?.mockMode == true && state.price > 0) {
                Text("后台标价 ${state.price} 积分 · Mock 本次不扣费", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.credits < state.billablePrice && !state.retryableSubmission) {
                Text("积分不足，请先充值后再创建任务", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = onSubmit,
                enabled = state.canSubmit && !state.isPreparingConfirmation &&
                    (state.credits >= state.billablePrice || state.retryableSubmission),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                when {
                    state.isPreparingConfirmation -> {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在核对价格")
                    }
                    state.isSubmitting -> {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在安全提交")
                    }
                    else -> {
                        Icon(
                            if (state.retryableSubmission) Icons.Outlined.Refresh else Icons.AutoMirrored.Outlined.Send,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.retryableSubmission) "安全重试" else "确认生成")
                    }
                }
            }
            Text(
                "点击后仍会显示二次确认；异步生成期间可以离开页面。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConfirmationDialog(
    confirmation: VideoConfirmation,
    isRetry: Boolean,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        icon = { Icon(if (isRetry) Icons.Outlined.Refresh else Icons.Outlined.Movie, contentDescription = null) },
        title = { Text(if (isRetry) "安全重试当前任务？" else "确认生成商品视频？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (confirmation.priceNotice.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(confirmation.priceNotice, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                SummaryLine("模型", confirmation.modelName)
                SummaryLine("规格", "${confirmation.resolution.uppercase()} · ${confirmation.durationSeconds} 秒 · ${confirmation.aspectRatio}")
                SummaryLine("参考图", "${confirmation.referenceCount} 张")
                SummaryLine("音轨", if (confirmation.generateAudio) "生成" else "不生成")
                HorizontalDivider()
                SummaryLine("本次扣除", "${confirmation.price} 积分", strong = true)
                SummaryLine("当前余额", "${confirmation.credits} 积分")
                Text(
                    if (isRetry) "将复用原任务标识；若服务端已创建任务，不会再次扣费。" else "确认后才会创建任务并按服务端价格扣除积分。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !isSubmitting) {
                if (isSubmitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (isRetry) "安全重试" else "确认并生成")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !isSubmitting) { Text("取消") }
        },
    )
}

@Composable
private fun SummaryLine(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.weight(1.3f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium,
            color = if (strong) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TaskCard(task: VideoTask, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.title.ifBlank { "商品视频" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = listOf(task.model, task.aspectRatio, "${task.durationSeconds} 秒", task.requestedResolution.uppercase())
                        .filter { it.isNotBlank() && it != "0 秒" }
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TaskStatusBadge(task.status)
            }
            if (task.status.isActive()) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    Text("${task.progress.coerceIn(0, 100)}%", style = MaterialTheme.typography.labelSmall)
                    if (task.status == "submission_unknown") {
                        Spacer(Modifier.width(8.dp))
                        Text("正在确认上游状态，请勿重复创建", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${task.creditsCost} 积分", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                if (task.refundedAt.isNotBlank() && task.status.lowercase() != "completed") StateBadge("已退款", success = true)
            }
            if (task.safeError.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Text(task.safeError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3)
            }
            if (task.updatedAt.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(task.updatedAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TaskStatusBadge(status: String) {
    val normalized = status.lowercase()
    val label = when (normalized) {
        "queued" -> "排队中"
        "submitting" -> "正在提交"
        "submission_unknown" -> "确认状态中"
        "submitted", "generating" -> "生成中"
        "upscaling" -> "处理中"
        "quality_check" -> "质量检查"
        "completed", "succeeded", "success" -> "已完成"
        "failed" -> "生成失败"
        "timeout" -> "已超时"
        "cancelled" -> "已取消"
        else -> status.ifBlank { "未知" }
    }
    StateBadge(
        label,
        success = normalized in setOf("completed", "succeeded", "success"),
        error = normalized in setOf("failed", "timeout", "cancelled"),
    )
}

@Composable
private fun StateBadge(label: String, success: Boolean = false, error: Boolean = false) {
    val container = when {
        error -> MaterialTheme.colorScheme.errorContainer
        success -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        error -> MaterialTheme.colorScheme.onErrorContainer
        success -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(6.dp)) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

private fun String.isActive(): Boolean = lowercase() in setOf(
    "queued", "submitting", "submission_unknown", "submitted", "generating", "upscaling", "quality_check",
)

@Composable
private fun NoticeMessage(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭提示") }
        }
    }
}

@Composable
private fun InlineError(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptySection(message: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Movie, contentDescription = null, Modifier.size(34.dp))
            Spacer(Modifier.height(8.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在同步视频配置", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Text(message)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("重新加载") }
        }
    }
}
