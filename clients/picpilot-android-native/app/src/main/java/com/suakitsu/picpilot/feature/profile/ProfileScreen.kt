package com.suakitsu.picpilot.feature.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.suakitsu.picpilot.core.media.resolveMediaUrl
import com.suakitsu.picpilot.core.model.UserProfile

@Composable
fun ProfileRoute(
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProfileScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onLogout = onLogout,
        onBeginEdit = viewModel::beginEdit,
        onCancelEdit = viewModel::cancelEdit,
        onNicknameChange = viewModel::setNickname,
        onLanguageChange = viewModel::setLanguage,
        onThemeChange = viewModel::setTheme,
        onSave = viewModel::save,
        onAvatarSelected = viewModel::uploadAvatar,
        onDismissNotice = viewModel::dismissNotice,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onNicknameChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onThemeChange: (String) -> Unit,
    onSave: () -> Unit,
    onAvatarSelected: (android.net.Uri) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showLogoutConfirmation by remember { mutableStateOf(false) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onAvatarSelected(uri)
    }
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            state.isLoading && state.profile == null -> LoadingState()
            state.errorMessage != null && state.profile == null -> ErrorState(state.errorMessage, onRefresh)
            state.profile == null -> EmptyState(onRefresh)
            else -> ProfileContent(
                profile = state.profile,
                credits = state.billing?.credits ?: state.profile.credits,
                pendingOrders = state.billing?.pendingOrders ?: 0,
                errorMessage = state.errorMessage,
                successMessage = state.successMessage,
                state = state,
                onRefresh = onRefresh,
                onBeginEdit = onBeginEdit,
                onCancelEdit = onCancelEdit,
                onNicknameChange = onNicknameChange,
                onLanguageChange = onLanguageChange,
                onThemeChange = onThemeChange,
                onSave = onSave,
                onAvatarClick = { avatarPicker.launch("image/*") },
                onDismissNotice = onDismissNotice,
                onRequestLogout = { showLogoutConfirmation = true },
            )
        }
    }
    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            icon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
            title = { Text("退出登录？") },
            text = { Text("将清除本机登录状态，不会删除账号或服务端数据。") },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirmation = false
                        onLogout()
                    },
                ) {
                    Text("确认退出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmation = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProfileContent(
    profile: UserProfile,
    credits: Int,
    pendingOrders: Int,
    errorMessage: String?,
    successMessage: String?,
    state: ProfileUiState,
    onRefresh: () -> Unit,
    onBeginEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onNicknameChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onThemeChange: (String) -> Unit,
    onSave: () -> Unit,
    onAvatarClick: () -> Unit,
    onDismissNotice: () -> Unit,
    onRequestLogout: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageHeader(onRefresh) }
        errorMessage?.let { message -> item { InlineNotice(message, true, onDismissNotice) } }
        successMessage?.let { message -> item { InlineNotice(message, false, onDismissNotice) } }
        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth >= 600.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IdentityCard(
                            profile = profile,
                            isUploading = state.isUploadingAvatar,
                            onAvatarClick = onAvatarClick,
                            modifier = Modifier.weight(1.35f),
                        )
                        CreditsCard(credits = credits, pendingOrders = pendingOrders, modifier = Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        IdentityCard(profile = profile, isUploading = state.isUploadingAvatar, onAvatarClick = onAvatarClick)
                        CreditsCard(credits = credits, pendingOrders = pendingOrders)
                    }
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    InfoRow(Icons.Outlined.MailOutline, "邮箱", profile.emailDisplay.ifBlank { profile.emailMasked })
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    InfoRow(Icons.Outlined.Badge, "账号角色", profile.role.ifBlank { "user" })
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    InfoRow(Icons.Outlined.Language, "界面语言", profile.uiLanguage.ifBlank { "zh-CN" })
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    InfoRow(Icons.Outlined.DarkMode, "主题", profile.theme.ifBlank { "system" })
                }
            }
        }
        item {
            if (state.isEditing) {
                EditProfileCard(
                    nickname = state.nicknameDraft,
                    language = state.languageDraft,
                    theme = state.themeDraft,
                    isSaving = state.isSaving,
                    onNicknameChange = onNicknameChange,
                    onLanguageChange = onLanguageChange,
                    onThemeChange = onThemeChange,
                    onSave = onSave,
                    onCancel = onCancelEdit,
                )
            } else {
                OutlinedButton(onClick = onBeginEdit, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("编辑个人资料")
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onRequestLogout,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("退出登录")
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun PageHeader(onRefresh: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("个人中心", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "账号资料与积分概览",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalIconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新个人资料")
        }
    }
}

@Composable
private fun IdentityCard(
    profile: UserProfile,
    isUploading: Boolean,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.BottomEnd) {
                if (profile.avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = resolveMediaUrl(profile.avatarUrl),
                        contentDescription = "用户头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(CircleShape),
                    )
                } else {
                    Surface(modifier = Modifier.size(72.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(42.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                FilledTonalIconButton(
                    onClick = onAvatarClick,
                    enabled = !isUploading,
                    modifier = Modifier.size(32.dp),
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Outlined.PhotoCamera,
                            contentDescription = "更换头像",
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.nickname.ifBlank { "PicPilot 用户" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    profile.emailDisplay.ifBlank { profile.emailMasked },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CreditsCard(credits: Int, pendingOrders: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Text("当前积分", style = MaterialTheme.typography.labelLarge)
            Text(credits.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("待处理订单 $pendingOrders", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EditProfileCard(
    nickname: String,
    language: String,
    theme: String,
    isSaving: Boolean,
    onNicknameChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onThemeChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("编辑个人资料", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = nickname,
                onValueChange = onNicknameChange,
                enabled = !isSaving,
                label = { Text("昵称") },
                supportingText = { Text("${nickname.length}/40") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PreferenceChips(
                title = "界面语言",
                selected = language,
                options = listOf("zh-CN" to "中文", "en-US" to "English", "ja-JP" to "日本語"),
                enabled = !isSaving,
                onSelected = onLanguageChange,
            )
            PreferenceChips(
                title = "主题偏好",
                selected = theme,
                options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"),
                enabled = !isSaving,
                onSelected = onThemeChange,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                ) { Text("取消") }
                Button(
                    onClick = onSave,
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isSaving) "保存中" else "保存")
                }
            }
        }
    }
}

@Composable
private fun PreferenceChips(
    title: String,
    selected: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    enabled = enabled,
                    label = {
                        Text(
                            label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value.ifBlank { "未设置" },
            modifier = Modifier.weight(1.5f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
                if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.AccountCircle,
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
            Text("正在加载个人资料", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("暂无个人资料", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRefresh) { Text("刷新") }
        }
    }
}
