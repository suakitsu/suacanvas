package com.suakitsu.picpilot.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.suakitsu.picpilot.R
import com.suakitsu.picpilot.BuildConfig

@Composable
fun AuthScreen(
    onAuthenticated: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.authenticatedUser) {
        if (state.authenticatedUser != null) onAuthenticated()
    }
    LaunchedEffect(state.resetCompleted) {
        if (state.resetCompleted) viewModel.setMode(AuthMode.LOGIN)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .imePadding()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFF3F7FF), Color(0xFFF8FAFC), Color.White),
                ),
            ),
    ) {
        val wide = maxWidth >= 720.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                AuthBrandPanel(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
            Box(
                modifier = Modifier
                    .weight(if (wide) 0.85f else 1f)
                    .fillMaxHeight()
                    .padding(horizontal = if (wide) 52.dp else 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                AuthForm(
                    state = state,
                    showCompactBrand = !wide,
                    onMode = viewModel::setMode,
                    onEmail = viewModel::updateEmail,
                    onPassword = viewModel::updatePassword,
                    onConfirm = viewModel::updateConfirmPassword,
                    onCode = viewModel::updateCode,
                    onRemember = viewModel::toggleRemember,
                    onVisibility = viewModel::togglePasswordVisibility,
                    onSendCode = viewModel::sendCode,
                    onSubmit = viewModel::submit,
                )
            }
        }
    }
}

@Composable
private fun AuthBrandPanel(modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Brush.linearGradient(listOf(Color(0xFF1D4ED8), Color(0xFF2563EB), Color(0xFF0891B2))))
            .padding(56.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.picpilot_brand_mark),
                    contentDescription = "PicPilot",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.padding(8.dp).fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("PicPilot", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text("AI Commerce Studio", color = Color.White.copy(alpha = .74f), fontSize = 15.sp)
        Spacer(Modifier.height(36.dp))
        Text(
            "把商品资料、视觉创作与多平台内容生产，放进一个真正顺手的工作台。",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            lineHeight = 36.sp,
        )
    }
}

@Composable
private fun AuthForm(
    state: AuthUiState,
    showCompactBrand: Boolean,
    onMode: (AuthMode) -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onCode: (String) -> Unit,
    onRemember: () -> Unit,
    onVisibility: () -> Unit,
    onSendCode: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        if (showCompactBrand) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.picpilot_brand_mark),
                    contentDescription = "PicPilot",
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("PicPilot", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("AI Commerce Studio", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (BuildConfig.FLAVOR == "staging") {
                    SuggestionChip(onClick = {}, label = { Text("测试环境") })
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        if (state.mode == AuthMode.LOGIN || state.mode == AuthMode.CODE_LOGIN) {
            Text("欢迎回来", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("登录后继续你的商品视觉工作", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(AuthMode.LOGIN to "密码登录", AuthMode.CODE_LOGIN to "验证码登录").forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = state.mode == entry.first,
                        onClick = { onMode(entry.first) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(entry.second) }
                }
            }
        } else {
            Text(
                if (state.mode == AuthMode.REGISTER) "创建 PicPilot 账号" else "重置密码",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (state.mode == AuthMode.REGISTER) "开始管理你的商品视觉资产" else "验证邮箱后设置新密码",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = state.email,
            onValueChange = onEmail,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("邮箱") },
            leadingIcon = { Icon(Icons.Outlined.Email, null) },
            singleLine = true,
            enabled = !state.loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )

        if (state.mode != AuthMode.CODE_LOGIN) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = onPassword,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (state.mode == AuthMode.LOGIN) "密码" else "新密码（8-16 位，含字母和数字）") },
                leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                trailingIcon = {
                    IconButton(
                        onClick = onVisibility,
                        modifier = Modifier.semantics { contentDescription = if (state.passwordVisible) "隐藏密码" else "显示密码" },
                    ) {
                        Icon(if (state.passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null)
                    }
                },
                visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                enabled = !state.loading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            )
        }

        if (state.mode == AuthMode.REGISTER || state.mode == AuthMode.RESET) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.confirmPassword,
                onValueChange = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("确认密码") },
                leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !state.loading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            )
        }

        if (state.mode != AuthMode.LOGIN) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.code,
                    onValueChange = onCode,
                    modifier = Modifier.weight(1f),
                    label = { Text("邮箱验证码") },
                    singleLine = true,
                    enabled = !state.loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onSendCode,
                    enabled = !state.loading && state.cooldownSeconds == 0,
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(if (state.cooldownSeconds > 0) "${state.cooldownSeconds}s" else "发送验证码")
                }
            }
        }

        if (state.mode == AuthMode.LOGIN || state.mode == AuthMode.CODE_LOGIN) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.remember, onCheckedChange = { onRemember() }, enabled = !state.loading)
                    Text("保持登录")
                }
                TextButton(onClick = { onMode(AuthMode.RESET) }, enabled = !state.loading) { Text("忘记密码") }
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }

        if (state.error.isNotBlank()) {
            Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (state.notice.isNotBlank()) {
            Text(state.notice, color = Color(0xFF047857), modifier = Modifier.padding(vertical = 8.dp))
        }

        Button(
            onClick = onSubmit,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (state.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Text(
                when (state.mode) {
                    AuthMode.LOGIN, AuthMode.CODE_LOGIN -> "登录"
                    AuthMode.REGISTER -> "注册并进入工作台"
                    AuthMode.RESET -> "确认重置密码"
                },
            )
        }

        Spacer(Modifier.height(10.dp))
        TextButton(
            onClick = { onMode(if (state.mode == AuthMode.REGISTER) AuthMode.LOGIN else AuthMode.REGISTER) },
            enabled = !state.loading,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(if (state.mode == AuthMode.REGISTER) "已有账号，返回登录" else "没有账号？立即注册")
        }
    }
}
