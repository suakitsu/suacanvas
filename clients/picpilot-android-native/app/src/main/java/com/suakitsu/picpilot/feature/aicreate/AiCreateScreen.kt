package com.suakitsu.picpilot.feature.aicreate

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suakitsu.picpilot.ui.ForegroundRefreshEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.suakitsu.picpilot.ui.components.PicPilotConfirmDialog
import com.suakitsu.picpilot.ui.components.PicPilotConfirmationItem
import com.suakitsu.picpilot.ui.components.PicPilotEmptyState
import com.suakitsu.picpilot.ui.components.PicPilotImagePreview
import com.suakitsu.picpilot.ui.components.PicPilotImagePreviewDialog
import com.suakitsu.picpilot.ui.components.PicPilotLoadingState
import com.suakitsu.picpilot.ui.components.PicPilotResponsivePage
import com.suakitsu.picpilot.ui.components.PicPilotStatusBadge
import com.suakitsu.picpilot.ui.components.PicPilotStatusTone
import com.suakitsu.picpilot.ui.components.PicPilotWindowClass
import com.suakitsu.picpilot.ui.components.pageHorizontalPadding
import com.suakitsu.picpilot.ui.theme.picPilotMinimumTouchTarget
import com.suakitsu.picpilot.ui.theme.picPilotSpacing
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun AiCreateRoute(
    modifier: Modifier = Modifier,
    gateway: AiCreateGateway = NoOpAiCreateGateway,
    catalog: AiCreateCatalog = AiCreateCatalog(),
    initialCredits: Int? = null,
) {
    val factory = remember(gateway) {
        AiCreateViewModel.Factory(gateway, catalog, initialCredits)
    }
    val viewModel: AiCreateViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ForegroundRefreshEffect(onRefresh = viewModel::refresh)
    LaunchedEffect(catalog) { viewModel.updateCatalog(catalog) }
    LaunchedEffect(initialCredits) { viewModel.updateBalance(initialCredits) }
    AiCreateScreen(
        state = state,
        catalog = state.catalog,
        actions = AiCreateActions(
            onRefresh = viewModel::refresh,
            onNewConversation = viewModel::newConversation,
            onSelectConversation = viewModel::selectConversation,
            onPromptChange = viewModel::updatePrompt,
            onAddReferences = viewModel::addReferences,
            onRemoveReference = viewModel::removeReference,
            onReferenceResult = viewModel::referenceResult,
            onReferenceMessageImage = viewModel::referenceMessageImage,
            onSelectQuality = viewModel::selectQuality,
            onSelectStyle = viewModel::selectStyle,
            onRequestGeneration = viewModel::requestGeneration,
            onDismissConfirmation = viewModel::dismissConfirmation,
            onConfirmGeneration = viewModel::confirmGeneration,
            onRetry = viewModel::prepareRetry,
            onMessageShown = viewModel::clearMessage,
        ),
        modifier = modifier,
    )
}

data class AiCreateActions(
    val onRefresh: () -> Unit = {},
    val onNewConversation: () -> Unit = {},
    val onSelectConversation: (String) -> Unit = {},
    val onPromptChange: (String) -> Unit = {},
    val onAddReferences: (List<AiCreateReference>) -> Unit = {},
    val onRemoveReference: (String) -> Unit = {},
    val onReferenceResult: (String, AiCreateResultImage) -> Unit = { _, _ -> },
    val onReferenceMessageImage: (String, AiCreateReference) -> Unit = { _, _ -> },
    val onSelectQuality: (String) -> Unit = {},
    val onSelectStyle: (String) -> Unit = {},
    val onRequestGeneration: () -> Unit = {},
    val onDismissConfirmation: () -> Unit = {},
    val onConfirmGeneration: () -> Unit = {},
    val onRetry: (String) -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiCreateScreen(
    state: AiCreateUiState,
    catalog: AiCreateCatalog,
    actions: AiCreateActions,
    modifier: Modifier = Modifier,
) {
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    LaunchedEffect(state.errorMessage, state.noticeMessage) {
        val message = state.errorMessage ?: state.noticeMessage ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        actions.onMessageShown()
    }

    PicPilotResponsivePage(
        modifier = modifier,
        applySafeDrawingInsets = false,
        maxContentWidth = 1600.dp,
    ) { windowClass ->
        val compact = windowClass == PicPilotWindowClass.Compact
        if (compact) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    Surface(modifier = Modifier.fillMaxHeight().width(308.dp)) {
                        ConversationPane(
                            conversations = state.conversations,
                            selectedId = state.selectedConversationId,
                            onNew = {
                                actions.onNewConversation()
                                scope.launch { drawerState.close() }
                            },
                            onSelect = {
                                actions.onSelectConversation(it)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
            ) {
                ChatScaffold(
                    state = state,
                    catalog = catalog,
                    actions = actions,
                    windowClass = windowClass,
                    snackbarHost = snackbarHost,
                    onOpenConversations = { scope.launch { drawerState.open() } },
                )
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier.fillMaxHeight().width(if (windowClass == PicPilotWindowClass.Expanded) 280.dp else 240.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    ConversationPane(
                        conversations = state.conversations,
                        selectedId = state.selectedConversationId,
                        onNew = actions.onNewConversation,
                        onSelect = actions.onSelectConversation,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                HorizontalDivider(modifier = Modifier.fillMaxHeight().width(1.dp))
                ChatScaffold(
                    state = state,
                    catalog = catalog,
                    actions = actions,
                    windowClass = windowClass,
                    snackbarHost = snackbarHost,
                    onOpenConversations = null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    state.pendingRequest?.let { request ->
        PicPilotConfirmDialog(
            visible = true,
            title = "确认生成图片",
            message = "确认后才会创建任务并按实际配置扣除积分。",
            items = buildList {
                add(PicPilotConfirmationItem("清晰度", "${request.quality.label} · ${request.quality.pixels}"))
                add(PicPilotConfirmationItem("创作风格", request.style.label))
                add(PicPilotConfirmationItem("参考图片", "${request.referenceImages.size} 张"))
                add(PicPilotConfirmationItem("预计消耗", "${request.expectedCredits} 积分", emphasize = true))
                state.availableCredits?.let { add(PicPilotConfirmationItem("当前余额", "$it 积分")) }
            },
            warning = if (state.availableCredits != null) "提交后请等待任务完成，避免重复创建。" else null,
            confirmLabel = "确认并生成",
            dismissLabel = "再检查一下",
            onConfirm = actions.onConfirmGeneration,
            onDismiss = actions.onDismissConfirmation,
            isSubmitting = state.isSubmitting || state.isEstimating,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScaffold(
    state: AiCreateUiState,
    catalog: AiCreateCatalog,
    actions: AiCreateActions,
    windowClass: PicPilotWindowClass,
    snackbarHost: SnackbarHostState,
    onOpenConversations: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var showSettings by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            AiCreateTopBar(
                state = state,
                onRefresh = actions.onRefresh,
                onOpenConversations = onOpenConversations,
                onOpenSettings = if (windowClass == PicPilotWindowClass.Expanded) null else {
                    { showSettings = true }
                },
            )
        },
    ) { padding ->
        if (state.isLoading && state.messages.isEmpty()) {
            PicPilotLoadingState(
                title = "正在准备 AI 创作",
                message = "正在同步会话与任务状态",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                ChatPane(
                    state = state,
                    catalog = catalog,
                    actions = actions,
                    windowClass = windowClass,
                    modifier = Modifier.weight(1f),
                )
                if (windowClass == PicPilotWindowClass.Expanded) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    GenerationSettings(
                        state = state,
                        catalog = catalog,
                        onSelectQuality = actions.onSelectQuality,
                        onSelectStyle = actions.onSelectStyle,
                        modifier = Modifier.fillMaxHeight().width(276.dp).padding(20.dp),
                    )
                }
            }
        }
    }

    if (showSettings && windowClass != PicPilotWindowClass.Expanded) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            GenerationSettings(
                state = state,
                catalog = catalog,
                onSelectQuality = actions.onSelectQuality,
                onSelectStyle = actions.onSelectStyle,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AiCreateTopBar(
    state: AiCreateUiState,
    onRefresh: () -> Unit,
    onOpenConversations: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onOpenConversations?.let {
                IconButton(onClick = it) { Icon(Icons.Outlined.Menu, contentDescription = "打开会话列表") }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text("AI 生图助手", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    state.conversations.firstOrNull { it.id == state.selectedConversationId }?.title ?: "新对话",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.availableCredits?.let {
                PicPilotStatusBadge(text = "$it 积分", tone = PicPilotStatusTone.Info)
            }
            IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                if (state.isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新会话")
                }
            }
            onOpenSettings?.let {
                IconButton(onClick = it) { Icon(Icons.Outlined.Settings, contentDescription = "生成设置") }
            }
        }
    }
}

@Composable
private fun ConversationPane(
    conversations: List<AiCreateConversation>,
    selectedId: String?,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.picPilotSpacing
    Column(modifier = modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
            Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("创作会话", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Button(onClick = onNew, modifier = Modifier.fillMaxWidth().picPilotMinimumTouchTarget()) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("新对话")
        }
        Spacer(Modifier.height(spacing.md))
        if (conversations.isEmpty()) {
            PicPilotEmptyState(title = "暂无会话", message = "创建一个对话开始生图")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(conversations, key = AiCreateConversation::id) { conversation ->
                    NavigationDrawerItem(
                        selected = conversation.id == selectedId,
                        onClick = { onSelect(conversation.id) },
                        icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                        label = {
                            Column {
                                Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (conversation.preview.isNotBlank()) {
                                    Text(
                                        conversation.preview,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        badge = conversation.updatedAtLabel.takeIf { it.isNotBlank() }?.let { label ->
                            { Text(label, style = MaterialTheme.typography.labelSmall) }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatPane(
    state: AiCreateUiState,
    catalog: AiCreateCatalog,
    actions: AiCreateActions,
    windowClass: PicPilotWindowClass,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    Column(modifier = modifier.fillMaxHeight().imePadding()) {
        if (state.messages.isEmpty()) {
            WelcomePanel(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onSuggestion = actions.onPromptChange,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = windowClass.pageHorizontalPadding(), vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.messages, key = AiCreateMessage::id) { message ->
                    ChatMessage(
                        message = message,
                        onReferenceResult = actions.onReferenceResult,
                        onReferenceMessageImage = actions.onReferenceMessageImage,
                        onRetry = actions.onRetry,
                    )
                }
            }
        }
        HorizontalDivider()
        Composer(
            state = state,
            catalog = catalog,
            onPromptChange = actions.onPromptChange,
            onAddReferences = actions.onAddReferences,
            onRemoveReference = actions.onRemoveReference,
            onGenerate = actions.onRequestGeneration,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WelcomePanel(modifier: Modifier = Modifier, onSuggestion: (String) -> Unit) {
    Box(modifier = modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 680.dp)) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("把想法变成图片", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "上传参考图或直接描述画面。生成前会再次确认规格与积分。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            listOf(
                "为这款商品生成干净高级的白底主图",
                "保留人物与姿势，只替换服装和场景",
                "制作适合社交媒体投放的商业海报",
            ).forEach { suggestion ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSuggestion(suggestion) },
                ) {
                    Text(suggestion, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ChatMessage(
    message: AiCreateMessage,
    onReferenceResult: (String, AiCreateResultImage) -> Unit,
    onReferenceMessageImage: (String, AiCreateReference) -> Unit,
    onRetry: (String) -> Unit,
) {
    val isUser = message.role == AiCreateMessageRole.User
    val isGenerationReply = !isUser && message.task != null
    val visibleReferences = if (isGenerationReply) emptyList() else message.references
    val visibleText = if (isGenerationReply) "" else message.text
    var preview by remember(message.id) { mutableStateOf<PicPilotImagePreview?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Surface(
            modifier = Modifier.widthIn(max = 680.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(10.dp),
            border = if (isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (visibleReferences.isNotEmpty()) {
                    MessageReferences(
                        message = message.copy(references = visibleReferences),
                        onPreview = { reference ->
                            preview = PicPilotImagePreview(
                                url = reference.uri,
                                title = reference.displayName,
                                mimeType = reference.mimeType,
                            )
                        },
                        onReferenceMessageImage = onReferenceMessageImage,
                    )
                    if (visibleText.isNotBlank()) Spacer(Modifier.height(10.dp))
                }
                if (visibleText.isNotBlank()) {
                    Text(visibleText, style = MaterialTheme.typography.bodyLarge)
                }
                message.task?.let { task ->
                    if (visibleText.isNotBlank()) Spacer(Modifier.height(12.dp))
                    TaskResultBlock(message.id, task, onReferenceResult, onRetry)
                }
            }
        }
    }
    preview?.let { request ->
        PicPilotImagePreviewDialog(request = request, onDismiss = { preview = null })
    }
}

@Composable
private fun MessageReferences(
    message: AiCreateMessage,
    onPreview: (AiCreateReference) -> Unit,
    onReferenceMessageImage: (String, AiCreateReference) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(message.references, key = AiCreateReference::id) { reference ->
            Box(
                modifier = Modifier
                    .width(104.dp)
                    .height(132.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .semantics {
                        role = Role.Image
                        contentDescription = reference.displayName
                    },
            ) {
                AsyncImage(
                    model = reference.uri,
                    contentDescription = reference.displayName,
                    modifier = Modifier.fillMaxWidth().height(94.dp),
                    contentScale = ContentScale.Crop,
                )
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(38.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        onClick = { onPreview(reference) },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Visibility, contentDescription = "预览${reference.displayName}", modifier = Modifier.size(18.dp))
                        }
                    }
                    Surface(
                        onClick = { onReferenceMessageImage(message.id, reference) },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.Link,
                                contentDescription = "引用${reference.displayName}",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskResultBlock(
    messageId: String,
    task: AiCreateTaskPresentation,
    onReferenceResult: (String, AiCreateResultImage) -> Unit,
    onRetry: (String) -> Unit,
) {
    val (statusText, tone) = task.status.presentation()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PicPilotStatusBadge(text = statusText, tone = tone)
            Spacer(Modifier.weight(1f))
            Text("${task.credits} 积分", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (task.status in setOf(AiCreateTaskStatus.Queued, AiCreateTaskStatus.Submitting, AiCreateTaskStatus.Generating)) {
            Spacer(Modifier.height(10.dp))
            if (task.progress > 0) {
                LinearProgressIndicator(progress = { task.progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${task.progress}%", style = MaterialTheme.typography.labelSmall)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (task.results.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ResultImageGrid(messageId, task.results, onReferenceResult)
        }
        task.safeError?.takeIf { it.isNotBlank() }?.let { error ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            }
        }
        if (task.status == AiCreateTaskStatus.Failed || task.status == AiCreateTaskStatus.Cancelled) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { onRetry(messageId) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("恢复参数")
            }
        }
    }
}

@Composable
private fun ResultImageGrid(
    messageId: String,
    results: List<AiCreateResultImage>,
    onReferenceResult: (String, AiCreateResultImage) -> Unit,
) {
    var preview by remember { mutableStateOf<PicPilotImagePreview?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        results.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { image ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                preview = PicPilotImagePreview(
                                    url = image.url,
                                    title = image.displayName,
                                    mimeType = image.mimeType,
                                )
                            },
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column {
                            AsyncImage(
                                model = image.url,
                                contentDescription = image.displayName,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                contentScale = ContentScale.Fit,
                            )
                            TextButton(
                                onClick = { onReferenceResult(messageId, image) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("引用这张图")
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    preview?.let { request ->
        PicPilotImagePreviewDialog(request = request, onDismiss = { preview = null })
    }
}

@Composable
private fun Composer(
    state: AiCreateUiState,
    catalog: AiCreateCatalog,
    onPromptChange: (String) -> Unit,
    onAddReferences: (List<AiCreateReference>) -> Unit,
    onRemoveReference: (String) -> Unit,
    onGenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val references = uris.take(AI_CREATE_MAX_REFERENCES + 1).map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            AiCreateReference(
                id = "device_${UUID.randomUUID()}",
                uri = uri.toString(),
                displayName = context.displayName(uri),
                mimeType = context.contentResolver.getType(uri) ?: "image/*",
                source = AiCreateReferenceSource.Device,
            )
        }
        onAddReferences(references)
    }
    val quality = catalog.quality(state.selectedQualityId)
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            AnimatedVisibility(state.draftReferences.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    items(state.draftReferences, key = AiCreateReference::id) { reference ->
                        DraftReference(reference, onRemove = { onRemoveReference(reference.id) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(
                    onClick = { picker.launch(arrayOf("image/*")) },
                    modifier = Modifier
                        .picPilotMinimumTouchTarget()
                        .semantics { contentDescription = "添加参考图片，已选 ${state.draftReferences.size} 张" },
                ) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                }
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = state.prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 4,
                    placeholder = { Text("描述要生成或修改的画面") },
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onGenerate,
                    enabled = state.catalog.authoritative &&
                        !state.isCatalogLoading &&
                        !state.isEstimating &&
                        !state.isSubmitting,
                    modifier = Modifier.picPilotMinimumTouchTarget(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when {
                            state.isEstimating -> "报价中"
                            state.catalog.authoritative -> "${quality.credits}积分"
                            else -> "--"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftReference(reference: AiCreateReference, onRemove: () -> Unit) {
    var preview by remember(reference.id) { mutableStateOf<PicPilotImagePreview?>(null) }
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                preview = PicPilotImagePreview(
                    url = reference.uri,
                    title = reference.displayName,
                    mimeType = reference.mimeType,
                )
            },
    ) {
        AsyncImage(
            model = reference.uri,
            contentDescription = reference.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(32.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f)),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "移除${reference.displayName}", tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(18.dp))
        }
        if (reference.source == AiCreateReferenceSource.Conversation) {
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text("引用", modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    preview?.let { request ->
        PicPilotImagePreviewDialog(request = request, onDismiss = { preview = null })
    }
}

@Composable
private fun GenerationSettings(
    state: AiCreateUiState,
    catalog: AiCreateCatalog,
    onSelectQuality: (String) -> Unit,
    onSelectStyle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("生成设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text("清晰度", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            catalog.qualities.forEach { quality ->
                FilterChip(
                    selected = quality.id == state.selectedQualityId,
                    enabled = state.catalog.authoritative && !state.isCatalogLoading,
                    onClick = { onSelectQuality(quality.id) },
                    label = { Text(quality.label) },
                    leadingIcon = if (quality.id == state.selectedQualityId) {
                        { Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }
        Text(
            if (state.catalog.authoritative) {
                catalog.quality(state.selectedQualityId).let { "${it.pixels} · ${it.credits} 积分" }
            } else {
                "正在同步服务端配置与价格"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        Text("画面风格", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            catalog.styles.forEach { style ->
                FilterChip(
                    selected = style.id == state.selectedStyleId,
                    enabled = state.catalog.authoritative && !state.isCatalogLoading,
                    onClick = { onSelectStyle(style.id) },
                    label = { Text(style.label) },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = if (style.id == state.selectedStyleId) {
                        { Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text("本次预计消耗", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (state.catalog.authoritative) {
                        "${catalog.quality(state.selectedQualityId).credits} 积分"
                    } else {
                        "--"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("最终以确认弹窗显示为准", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun AiCreateTaskStatus.presentation(): Pair<String, PicPilotStatusTone> = when (this) {
    AiCreateTaskStatus.Queued -> "排队中" to PicPilotStatusTone.Neutral
    AiCreateTaskStatus.Submitting -> "正在提交" to PicPilotStatusTone.Info
    AiCreateTaskStatus.Generating -> "生成中" to PicPilotStatusTone.Info
    AiCreateTaskStatus.Completed -> "已完成" to PicPilotStatusTone.Success
    AiCreateTaskStatus.Failed -> "生成失败" to PicPilotStatusTone.Error
    AiCreateTaskStatus.Cancelled -> "已取消" to PicPilotStatusTone.Warning
}

private fun android.content.Context.displayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index).orEmpty().ifBlank { "图片" }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "图片"
}
