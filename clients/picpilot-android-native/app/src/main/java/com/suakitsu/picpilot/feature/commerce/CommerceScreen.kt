package com.suakitsu.picpilot.feature.commerce

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suakitsu.picpilot.ui.ForegroundRefreshEffect
import coil.compose.AsyncImage
import com.suakitsu.picpilot.core.media.resolveMediaUrl
import com.suakitsu.picpilot.ui.components.PicPilotConfirmationItem
import com.suakitsu.picpilot.ui.components.PicPilotConfirmDialog
import com.suakitsu.picpilot.ui.components.PicPilotImagePreview
import com.suakitsu.picpilot.ui.components.PicPilotImagePreviewDialog
import com.suakitsu.picpilot.ui.components.PicPilotResponsivePage
import com.suakitsu.picpilot.ui.components.PicPilotWindowClass
import com.suakitsu.picpilot.ui.components.pageHorizontalPadding
import com.suakitsu.picpilot.ui.theme.picPilotMinimumTouchTarget
import com.suakitsu.picpilot.ui.theme.picPilotSpacing

@Composable
fun CommerceRoute(
    modifier: Modifier = Modifier,
    initialKind: CommerceCreationKind = CommerceCreationKind.PLATFORM_SUITE,
    initialProjectId: String? = null,
    submitter: CommerceSubmitter? = null,
    onOpenTask: (String) -> Unit = {},
    viewModel: CommerceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ForegroundRefreshEffect(onRefresh = viewModel::refreshConfiguration)
    LaunchedEffect(initialKind) {
        if (state.kind != initialKind) viewModel.selectKind(initialKind)
    }
    LaunchedEffect(submitter) {
        viewModel.attachSubmitter(submitter)
    }
    LaunchedEffect(initialProjectId) {
        initialProjectId?.takeIf(String::isNotBlank)?.let(viewModel::restoreProject)
    }
    CommerceScreen(
        state = state,
        actions = CommerceActions(
            selectKind = viewModel::selectKind,
            updateProductName = viewModel::updateProductName,
            updateCategory = viewModel::updateCategory,
            updateBrand = viewModel::updateBrand,
            updateProductFacts = viewModel::updateProductFacts,
            updateSellingPoints = viewModel::updateSellingPoints,
            addImages = viewModel::addImages,
            removeImage = viewModel::removeImage,
            clearImageError = viewModel::clearImageError,
            selectPlatform = viewModel::selectPlatform,
            selectResolution = viewModel::selectResolution,
            toggleDeliverable = viewModel::toggleDeliverable,
            selectSourceLanguage = viewModel::selectSourceLanguage,
            selectTargetLanguage = viewModel::selectTargetLanguage,
            setPreserveLayout = viewModel::setPreserveLayout,
            updateCustomRequirements = viewModel::updateCustomRequirements,
            next = viewModel::next,
            previous = viewModel::previous,
            requestConfirmation = viewModel::requestConfirmation,
            dismissConfirmation = viewModel::dismissConfirmation,
            confirmSubmission = viewModel::confirmSubmission,
            startAnotherGeneration = viewModel::startAnotherGeneration,
            refreshQuote = viewModel::refreshQuote,
            openTask = onOpenTask,
        ),
        modifier = modifier,
    )
}

data class CommerceActions(
    val selectKind: (CommerceCreationKind) -> Unit,
    val updateProductName: (String) -> Unit,
    val updateCategory: (String) -> Unit,
    val updateBrand: (String) -> Unit,
    val updateProductFacts: (String) -> Unit,
    val updateSellingPoints: (String) -> Unit,
    val addImages: (List<Uri>) -> Unit,
    val removeImage: (String) -> Unit,
    val clearImageError: () -> Unit,
    val selectPlatform: (CommercePlatform) -> Unit,
    val selectResolution: (CommerceResolution) -> Unit,
    val toggleDeliverable: (CommerceDeliverable) -> Unit,
    val selectSourceLanguage: (CommerceLanguage) -> Unit,
    val selectTargetLanguage: (String) -> Unit,
    val setPreserveLayout: (Boolean) -> Unit,
    val updateCustomRequirements: (String) -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val requestConfirmation: () -> Unit,
    val dismissConfirmation: () -> Unit,
    val confirmSubmission: () -> Unit,
    val startAnotherGeneration: () -> Unit,
    val refreshQuote: () -> Unit,
    val openTask: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommerceScreen(
    state: CommerceUiState,
    actions: CommerceActions,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.submissionMessage, state.submissionPhase) {
        if (state.submissionPhase == CommerceSubmissionPhase.ERROR) {
            state.submissionMessage?.let { snackbarHostState.showSnackbar(it) }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        PicPilotResponsivePage(
            modifier = Modifier.padding(innerPadding),
            applySafeDrawingInsets = false,
        ) { windowClass ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = windowClass.pageHorizontalPadding(),
                    end = windowClass.pageHorizontalPadding(),
                    top = MaterialTheme.picPilotSpacing.lg,
                    bottom = MaterialTheme.picPilotSpacing.xxxl,
                ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.picPilotSpacing.lg),
            ) {
                item(key = "header") { CommerceHeader() }
                item(key = "kinds") {
                    CreationKindPicker(selected = state.kind, onSelect = actions.selectKind)
                }
                item(key = "steps") {
                    CommerceStepIndicator(current = state.step, windowClass = windowClass)
                }
                item(key = "body") {
                    when (state.step) {
                        CommerceWizardStep.PRODUCT -> ProductStep(state, actions, windowClass)
                        CommerceWizardStep.PLATFORM -> PlatformStep(state, actions, windowClass)
                        CommerceWizardStep.REVIEW -> ReviewStep(state, actions, windowClass)
                    }
                }
                state.submissionMessage?.takeIf {
                    state.submissionPhase != CommerceSubmissionPhase.ERROR
                }?.let { message ->
                    item(key = "submission-message") {
                        SubmissionNotice(state = state, message = message, actions = actions)
                    }
                }
                if (state.submittedTaskId != null) {
                    item(key = "project-progress") {
                        ProjectProgressSection(state = state, actions = actions)
                    }
                }
                item(key = "navigation") {
                    WizardActions(state = state, actions = actions, windowClass = windowClass)
                }
            }
        }
    }

    val confirmationQuote = state.quote
    PicPilotConfirmDialog(
        visible = confirmationQuote != null &&
            (state.submissionPhase == CommerceSubmissionPhase.AWAITING_CONFIRMATION || state.isSubmitting),
        title = "确认生成并扣除积分",
        message = "提交后将创建 ${confirmationQuote?.outputCount ?: 0} 个图片任务。每次再次生成都会重新确认。",
        items = listOf(
            PicPilotConfirmationItem("业务类型", state.kind.title),
            PicPilotConfirmationItem("平台与规格", "${state.platform.title} · ${state.resolution.title}"),
            PicPilotConfirmationItem("预计消耗", "${confirmationQuote?.totalCredits ?: 0} 积分", emphasize = true),
            PicPilotConfirmationItem("当前积分", state.availableCredits?.let { "$it 积分" } ?: "正在读取"),
        ),
        warning = "确认时会再次请求服务端报价；如价格变化，本次不会提交，需重新确认。",
        confirmLabel = when (state.submissionPhase) {
            CommerceSubmissionPhase.VERIFYING_QUOTE -> "正在复核价格"
            CommerceSubmissionPhase.SUBMITTING -> "正在提交"
            else -> "确认并开始生成"
        },
        dismissLabel = "再检查一下",
        onConfirm = actions.confirmSubmission,
        onDismiss = actions.dismissConfirmation,
        isSubmitting = state.isSubmitting,
    )
}

@Composable
private fun CommerceHeader() {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Storefront, contentDescription = null)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "电商制图",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "选业务，补素材，确认费用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CreationKindPicker(
    selected: CommerceCreationKind,
    onSelect: (CommerceCreationKind) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("选择业务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        role = Role.DropdownList
                        contentDescription = "当前业务：${selected.title}，点击切换"
                    },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(38.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(9.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (selected == CommerceCreationKind.IMAGE_TRANSLATION) Icons.Outlined.Translate else Icons.Outlined.Image,
                                contentDescription = null,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(selected.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            selected.shortDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 300.dp, max = 520.dp),
            ) {
                CommerceCreationKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(kind.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    kind.shortDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(
                                if (kind == CommerceCreationKind.IMAGE_TRANSLATION) Icons.Outlined.Translate else Icons.Outlined.Image,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = if (kind == selected) {
                            { Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        onClick = {
                            expanded = false
                            onSelect(kind)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CommerceStepIndicator(current: CommerceWizardStep, windowClass: PicPilotWindowClass) {
    val labels = listOf("商品与素材", "平台与画面", "费用确认")
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (windowClass == PicPilotWindowClass.Compact) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepNumber(number = current.number, active = true, completed = false)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("第 ${current.number} / 3 步", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(labels[current.ordinal], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEachIndexed { index, label ->
                    val stepNumber = index + 1
                    StepNumber(
                        number = stepNumber,
                        active = current.number == stepNumber,
                        completed = current.number > stepNumber,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (current.number == stepNumber) FontWeight.Bold else FontWeight.Medium,
                        color = if (current.number >= stepNumber) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (index < labels.lastIndex) Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}

@Composable
private fun StepNumber(number: Int, active: Boolean, completed: Boolean) {
    Surface(
        modifier = Modifier.size(32.dp),
        shape = RoundedCornerShape(16.dp),
        color = when {
            active -> MaterialTheme.colorScheme.primary
            completed -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = when {
            active -> MaterialTheme.colorScheme.onPrimary
            completed -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (completed) Icon(Icons.Outlined.Check, contentDescription = "已完成", modifier = Modifier.size(18.dp))
            else Text(number.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProductStep(
    state: CommerceUiState,
    actions: CommerceActions,
    windowClass: PicPilotWindowClass,
) {
    SectionSurface(
        title = if (state.kind == CommerceCreationKind.IMAGE_TRANSLATION) "上传待翻译图片" else "说清商品与真实信息",
        subtitle = if (state.kind == CommerceCreationKind.IMAGE_TRANSLATION) {
            "支持一次选择多张图片；仅修改语言时请在下一步保持版式。"
        } else {
            "参考图用于保持商品结构、颜色和材质，不确定的信息可以留空。"
        },
        icon = Icons.Outlined.PhotoLibrary,
    ) {
        if (state.kind != CommerceCreationKind.IMAGE_TRANSLATION) {
            if (windowClass == PicPilotWindowClass.Compact) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CommerceTextField(
                        value = state.productName,
                        onValueChange = actions.updateProductName,
                        label = "商品名称 *",
                        placeholder = "例如：便携式折叠晴雨伞",
                        error = state.fieldErrors["productName"],
                    )
                    CommerceTextField(
                        value = state.category,
                        onValueChange = actions.updateCategory,
                        label = "类目 *",
                        placeholder = "例如：雨具 / 折叠伞",
                        error = state.fieldErrors["category"],
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CommerceTextField(
                        value = state.productName,
                        onValueChange = actions.updateProductName,
                        label = "商品名称 *",
                        placeholder = "例如：便携式折叠晴雨伞",
                        error = state.fieldErrors["productName"],
                        modifier = Modifier.weight(1f),
                    )
                    CommerceTextField(
                        value = state.category,
                        onValueChange = actions.updateCategory,
                        label = "类目 *",
                        placeholder = "例如：雨具 / 折叠伞",
                        error = state.fieldErrors["category"],
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            CommerceTextField(
                value = state.brand,
                onValueChange = actions.updateBrand,
                label = "品牌",
                placeholder = "可选",
            )
            CommerceTextField(
                value = state.productFacts,
                onValueChange = actions.updateProductFacts,
                label = "商品真实信息 *",
                placeholder = "材质、结构、尺寸、颜色、包装等已确认信息",
                error = state.fieldErrors["productFacts"],
                minLines = 3,
                maxLines = 6,
            )
            CommerceTextField(
                value = state.coreSellingPoints,
                onValueChange = actions.updateSellingPoints,
                label = "核心卖点",
                placeholder = "每行一个卖点，避免夸大或未经确认的参数",
                minLines = 3,
                maxLines = 6,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        CommercePhotoPicker(state = state, actions = actions)
    }
}

@Composable
private fun CommercePhotoPicker(state: CommerceUiState, actions: CommerceActions) {
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_REFERENCE_IMAGES),
    ) { uris ->
        if (uris.isNotEmpty()) actions.addImages(uris)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("参考图片 *", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "最多 $MAX_REFERENCE_IMAGES 张，支持 JPG、PNG、WebP、HEIC / HEIF",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${state.referenceImages.size}/$MAX_REFERENCE_IMAGES", style = MaterialTheme.typography.labelLarge)
        }
        OutlinedButton(
            onClick = {
                actions.clearImageError()
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            enabled = !state.isReadingImages && state.referenceImages.size < MAX_REFERENCE_IMAGES,
            modifier = Modifier.fillMaxWidth().picPilotMinimumTouchTarget(),
        ) {
            if (state.isReadingImages) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在处理图片")
            } else {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.referenceImages.isEmpty()) "选择图片" else "继续添加")
            }
        }
        state.fieldErrors["referenceImages"]?.let { FieldError(it) }
        state.imageError?.let { error ->
            InlineMessage(
                message = error,
                isError = true,
                actionLabel = "关闭",
                onAction = actions.clearImageError,
            )
        }
        if (state.referenceImages.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.referenceImages, key = CommerceReferenceImage::localId) { image ->
                    ReferenceImageCard(image = image, onRemove = { actions.removeImage(image.localId) })
                }
            }
        } else if (!state.isReadingImages) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "尚未添加图片",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReferenceImageCard(image: CommerceReferenceImage, onRemove: () -> Unit) {
    var preview by remember(image.localId) { mutableStateOf<PicPilotImagePreview?>(null) }
    OutlinedCard(
        modifier = Modifier.width(160.dp).clickable {
            preview = PicPilotImagePreview(
                url = image.uri,
                title = image.displayName,
                mimeType = image.mimeType,
            )
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Box {
            AsyncImage(
                model = Uri.parse(image.uri),
                contentDescription = "参考图 ${image.displayName}",
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                contentScale = ContentScale.Crop,
            )
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(20.dp),
            ) {
                IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "移除 ${image.displayName}")
                }
            }
        }
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(image.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            Text(
                "${image.readableDimensions} · ${image.readableSize}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    preview?.let { request ->
        PicPilotImagePreviewDialog(request = request, onDismiss = { preview = null })
    }
}

@Composable
private fun PlatformStep(
    state: CommerceUiState,
    actions: CommerceActions,
    windowClass: PicPilotWindowClass,
) {
    SectionSurface(
        title = if (state.kind == CommerceCreationKind.IMAGE_TRANSLATION) "选择翻译语言" else "选择平台与输出画面",
        subtitle = when (state.kind) {
            CommerceCreationKind.IMAGE_TRANSLATION -> "源语言可自动识别；目标语言不能与源语言相同。"
            CommerceCreationKind.AMAZON_LISTING, CommerceCreationKind.AMAZON_A_PLUS -> "Amazon 规则已锁定，选择输出规格与画面。"
            else -> "平台影响画面规范、内容密度与视觉风格。"
        },
        icon = if (state.kind == CommerceCreationKind.IMAGE_TRANSLATION) Icons.Outlined.Language else Icons.Outlined.Storefront,
    ) {
        if (state.kind == CommerceCreationKind.IMAGE_TRANSLATION) {
            if (windowClass == PicPilotWindowClass.Compact) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SourceLanguageMenu(
                        label = "源语言",
                        selected = state.sourceLanguage,
                        onSelect = actions.selectSourceLanguage,
                    )
                    TargetLanguageMenu(
                        label = "目标语言",
                        selectedCode = state.targetLanguageCode,
                        options = state.translationLanguages,
                        onSelect = actions.selectTargetLanguage,
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SourceLanguageMenu(
                        label = "源语言",
                        selected = state.sourceLanguage,
                        onSelect = actions.selectSourceLanguage,
                        modifier = Modifier.weight(1f),
                    )
                    TargetLanguageMenu(
                        label = "目标语言",
                        selectedCode = state.targetLanguageCode,
                        options = state.translationLanguages,
                        onSelect = actions.selectTargetLanguage,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            state.fieldErrors["targetLanguage"]?.let { FieldError(it) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("严格保留原图版式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "仅替换文字语言，尽量保持商品、构图与排版位置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.preserveLayout, onCheckedChange = actions.setPreserveLayout)
            }
        } else {
            Text("目标平台", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            PlatformChips(state = state, onSelect = actions.selectPlatform)
            state.fieldErrors["platform"]?.let { FieldError(it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("输出清晰度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            ResolutionPicker(selected = state.resolution, onSelect = actions.selectResolution)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("输出画面", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "已选择 ${state.deliverables.size} 项，可按本次需求增减",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DeliverableGrid(state = state, actions = actions, windowClass = windowClass)
            state.fieldErrors["deliverables"]?.let { FieldError(it) }
        }
        CommerceTextField(
            value = state.customRequirements,
            onValueChange = actions.updateCustomRequirements,
            label = "补充要求",
            placeholder = if (state.kind == CommerceCreationKind.IMAGE_TRANSLATION) {
                "例如：品牌名保持原文，规格数字不要修改"
            } else {
                "例如：保持商品结构真实，不生成未经确认的参数与认证"
            },
            minLines = 3,
            maxLines = 7,
        )
    }
}

@Composable
private fun PlatformChips(state: CommerceUiState, onSelect: (CommercePlatform) -> Unit) {
    val kindChoices = when (state.kind) {
        CommerceCreationKind.AMAZON_LISTING, CommerceCreationKind.AMAZON_A_PLUS -> listOf(CommercePlatform.AMAZON)
        CommerceCreationKind.DOMESTIC_ECOMMERCE -> domesticPlatformChoices
        else -> CommercePlatform.entries
    }
    val choices = if (state.availablePlatformCodes.isEmpty()) {
        kindChoices
    } else {
        kindChoices.filter { it.code in state.availablePlatformCodes }
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { platform ->
            FilterChip(
                selected = state.platform == platform,
                onClick = { onSelect(platform) },
                label = { Text(platform.title) },
                leadingIcon = if (state.platform == platform) {
                    { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
            )
        }
    }
}

@Composable
private fun ResolutionPicker(selected: CommerceResolution, onSelect: (CommerceResolution) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CommerceResolution.entries.forEach { resolution ->
            Card(
                onClick = { onSelect(resolution) },
                modifier = Modifier.widthIn(min = 130.dp),
                border = if (selected == resolution) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected == resolution) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(resolution.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(resolution.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("价格由服务端实时计算", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun DeliverableGrid(state: CommerceUiState, actions: CommerceActions, windowClass: PicPilotWindowClass) {
    val kindChoices = when (state.kind) {
        CommerceCreationKind.AMAZON_LISTING -> amazonListingDeliverables.toList()
        CommerceCreationKind.AMAZON_A_PLUS -> amazonAPlusDeliverables.toList()
        CommerceCreationKind.DOMESTIC_ECOMMERCE -> domesticDeliverables.toList()
        else -> CommerceDeliverable.entries
    }
    val choices = if (state.availableDeliverableCodes.isEmpty()) {
        kindChoices
    } else {
        kindChoices.filter { it.code in state.availableDeliverableCodes }
    }
    val columns = if (windowClass == PicPilotWindowClass.Expanded) 3 else if (windowClass == PicPilotWindowClass.Medium) 2 else 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.chunked(columns).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { item ->
                    val selected = item in state.deliverables
                    OutlinedCard(
                        onClick = { actions.toggleDeliverable(item) },
                        modifier = Modifier.weight(1f).semantics {
                            role = Role.Checkbox
                            this.selected = selected
                        },
                        border = BorderStroke(
                            if (selected) 1.5.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Checkbox(checked = selected, onCheckedChange = null)
                            Spacer(Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ReviewStep(
    state: CommerceUiState,
    actions: CommerceActions,
    windowClass: PicPilotWindowClass,
) {
    val isTranslation = state.kind == CommerceCreationKind.IMAGE_TRANSLATION
    SectionSurface(
        title = "核对内容与费用",
        subtitle = "只有点击确认弹窗中的生成按钮后，才会创建任务。",
        icon = Icons.Outlined.Payments,
    ) {
        ReviewSummaryRow(
            windowClass = windowClass,
            items = listOf(
                ReviewItem("业务", state.kind.title),
                ReviewItem("参考图", "${state.referenceImages.size} 张"),
                ReviewItem(
                    if (isTranslation) "翻译" else "平台",
                    if (isTranslation) "${state.sourceLanguage.title} → ${state.targetLanguageTitle}" else state.platform.title,
                ),
                ReviewItem("输出", state.quote?.let { "${it.outputCount} 张 · ${state.resolution.title}" } ?: "等待服务端报价"),
            ),
        )
        if (!isTranslation) {
            ReviewBlock("商品信息") {
                ReviewLine("商品", state.productName)
                ReviewLine("类目", state.category)
                state.brand.takeIf(String::isNotBlank)?.let { ReviewLine("品牌", it) }
                ReviewLine("真实信息", state.productFacts)
                state.coreSellingPoints.takeIf(String::isNotBlank)?.let { ReviewLine("卖点", it) }
            }
            ReviewBlock("输出画面") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.deliverables.sortedBy(CommerceDeliverable::ordinal).forEach { item ->
                        AssistChip(onClick = {}, label = { Text(item.title) })
                    }
                }
            }
        } else {
            ReviewBlock("翻译约束") {
                ReviewLine("版式", if (state.preserveLayout) "严格保留原图版式" else "允许 AI 调整排版")
                ReviewLine("处理", "每张输入图对应一张输出图")
            }
        }
        state.customRequirements.takeIf(String::isNotBlank)?.let { requirements ->
            ReviewBlock("补充要求") { Text(requirements, style = MaterialTheme.typography.bodyMedium) }
        }
        QuoteCard(state = state, onRetry = actions.refreshQuote)
        if (state.fieldErrors.isNotEmpty()) {
            InlineMessage(
                message = state.fieldErrors.values.distinct().joinToString("；"),
                isError = true,
            )
        }
    }
}

@Composable
private fun ReviewSummaryRow(windowClass: PicPilotWindowClass, items: List<ReviewItem>) {
    val columns = if (windowClass == PicPilotWindowClass.Compact) 2 else 4
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { item ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private data class ReviewItem(val label: String, val value: String)

@Composable
private fun ReviewBlock(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun ReviewLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.widthIn(min = 72.dp, max = 96.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun QuoteCard(state: CommerceUiState, onRetry: () -> Unit) {
    val quote = state.quote
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Payments, contentDescription = null, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("本次预计消耗", style = MaterialTheme.typography.labelLarge)
                when (state.quotePhase) {
                    CommerceQuotePhase.LOADING -> {
                        Text("正在读取服务端报价", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("报价完成前不能确认提交", style = MaterialTheme.typography.bodySmall)
                    }
                    CommerceQuotePhase.READY -> if (quote != null) {
                        Text("${quote.totalCredits} 积分", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${quote.outputCount} 张 · ${quote.sourceLabel}", style = MaterialTheme.typography.bodySmall)
                    }
                    CommerceQuotePhase.UNAVAILABLE, CommerceQuotePhase.ERROR, CommerceQuotePhase.IDLE -> {
                        Text(state.quoteMessage ?: "尚未取得服务端报价", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("当前不会创建收费任务", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    if (state.isBalanceLoading) "正在读取余额" else state.availableCredits?.let { "当前余额 $it 积分" } ?: "余额暂不可用",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.quotePhase == CommerceQuotePhase.ERROR || state.quotePhase == CommerceQuotePhase.IDLE) {
                TextButton(onClick = onRetry) { Text("重新报价") }
            }
        }
    }
}

@Composable
private fun SubmissionNotice(state: CommerceUiState, message: String, actions: CommerceActions) {
    val success = state.submissionPhase == CommerceSubmissionPhase.SUBMITTED
    Surface(
        color = if (success) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            if (success) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.submittedTaskId?.let { taskId ->
                        Button(onClick = { actions.openTask(taskId) }) { Text("查看任务") }
                    }
                    OutlinedButton(onClick = actions.startAnotherGeneration) { Text("再次生成") }
                }
            }
        }
    }
}

@Composable
private fun ProjectProgressSection(state: CommerceUiState, actions: CommerceActions) {
    var preview by remember { mutableStateOf<PicPilotImagePreview?>(null) }
    SectionSurface(
        title = "任务状态",
        subtitle = "离开页面后任务仍会继续；再次进入会按项目编号恢复。",
        icon = Icons.Outlined.PhotoLibrary,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    projectStatusLabel(state.projectStatus, state.projectTerminal),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${state.projectTasks.size} 个图片任务 · 服务端记录 ${state.projectTotalCredits} 积分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isProjectRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
        state.projectTasks.forEach { task ->
            OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (task.resultUrl.isNotBlank()) {
                        AsyncImage(
                            model = resolveMediaUrl(task.resultUrl),
                            contentDescription = task.name,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    preview = PicPilotImagePreview(
                                        url = resolveMediaUrl(task.resultUrl),
                                        title = task.name,
                                    )
                                },
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Surface(
                            modifier = Modifier.size(64.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Image, contentDescription = null)
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(task.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${taskPhaseLabel(task.phase)} · ${task.creditsCost} 积分",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        task.safeError.takeIf(String::isNotBlank)?.let { error ->
                            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (task.refundState == CommerceRefundState.NOT_REPORTED) {
                            Text(
                                "退款状态：服务端详情暂未提供可核验字段",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        state.submittedTaskId?.let { projectId ->
            OutlinedButton(onClick = { actions.openTask(projectId) }) { Text("查看项目详情") }
        }
    }
    preview?.let { request ->
        PicPilotImagePreviewDialog(request = request, onDismiss = { preview = null })
    }
}

private fun taskPhaseLabel(phase: CommerceTaskPhase): String = when (phase) {
    CommerceTaskPhase.QUEUED -> "排队中"
    CommerceTaskPhase.RUNNING -> "生成中"
    CommerceTaskPhase.COMPLETED -> "已完成"
    CommerceTaskPhase.FAILED -> "失败"
}

private fun projectStatusLabel(status: String?, terminal: Boolean): String = when {
    terminal -> "项目处理完成"
    status.equals("images_queued", true) -> "项目排队中"
    status.equals("images_generating", true) -> "项目生成中"
    status.isNullOrBlank() -> "正在读取项目状态"
    else -> status
}

@Composable
private fun WizardActions(
    state: CommerceUiState,
    actions: CommerceActions,
    windowClass: PicPilotWindowClass,
) {
    val compact = windowClass == PicPilotWindowClass.Compact
    val nextAction: @Composable () -> Unit = {
        Button(
            onClick = if (state.step == CommerceWizardStep.REVIEW) actions.requestConfirmation else actions.next,
            enabled = !state.isReadingImages && !state.isSubmitting &&
                (state.step != CommerceWizardStep.REVIEW || state.pricingReady),
            modifier = Modifier.then(if (compact) Modifier.fillMaxWidth() else Modifier).picPilotMinimumTouchTarget(),
        ) {
            Icon(
                if (state.step == CommerceWizardStep.REVIEW) Icons.Outlined.AutoAwesome else Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
            )
            Spacer(Modifier.width(8.dp))
            Text(if (state.step == CommerceWizardStep.REVIEW) "确认并开始生成" else "下一步")
        }
    }
    val backAction: @Composable () -> Unit = {
        if (state.canGoBack) {
            OutlinedButton(
                onClick = actions.previous,
                enabled = !state.isSubmitting,
                modifier = Modifier.then(if (compact) Modifier.fillMaxWidth() else Modifier).picPilotMinimumTouchTarget(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("上一步")
            }
        }
    }

    if (compact) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            nextAction()
            backAction()
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            backAction()
            if (state.canGoBack) Spacer(Modifier.width(10.dp))
            nextAction()
        }
    }
}

@Composable
private fun SectionSurface(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.picPilotSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.picPilotSpacing.md),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun CommerceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    minLines: Int = 1,
    maxLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = error?.let { message -> { Text(message) } },
        isError = error != null,
        minLines = minLines,
        maxLines = maxLines,
        singleLine = maxLines == 1,
        shape = RoundedCornerShape(8.dp),
    )
}

@Composable
private fun SourceLanguageMenu(
    label: String,
    selected: CommerceLanguage,
    onSelect: (CommerceLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected.title, style = MaterialTheme.typography.bodyLarge)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CommerceLanguage.entries.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.title) },
                    onClick = {
                        onSelect(language)
                        expanded = false
                    },
                    leadingIcon = if (language == selected) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun TargetLanguageMenu(
    label: String,
    selectedCode: String,
    options: List<CommerceLanguageOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.code == selectedCode }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { if (options.isNotEmpty()) expanded = true },
            enabled = options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected?.title ?: "正在加载服务端语言", style = MaterialTheme.typography.bodyLarge)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { language ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(language.title)
                            language.nativeName.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = {
                        onSelect(language.code)
                        expanded = false
                    },
                    leadingIcon = if (language.code == selectedCode) {
                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun FieldError(message: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(6.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun InlineMessage(
    message: String,
    isError: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

private val domesticPlatformChoices = listOf(
    CommercePlatform.TAOBAO_TMALL,
    CommercePlatform.JD,
    CommercePlatform.PINDUODUO,
    CommercePlatform.DOUYIN,
    CommercePlatform.XIAOHONGSHU,
)
