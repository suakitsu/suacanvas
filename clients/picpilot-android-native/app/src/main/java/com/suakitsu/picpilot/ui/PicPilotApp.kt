package com.suakitsu.picpilot.ui

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.suakitsu.picpilot.feature.auth.AuthScreen
import com.suakitsu.picpilot.ui.theme.PicPilotTheme

@Composable
fun PicPilotApp(viewModel: AppViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsState()
    val theme by viewModel.theme.collectAsState(initial = "system")
    val language by viewModel.language.collectAsState(initial = "zh-CN")
    val systemDark = isSystemInDarkTheme()
    val context = LocalContext.current
    ForegroundRefreshEffect(onRefresh = viewModel::verifySession)
    LaunchedEffect(language) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                LocaleList.forLanguageTags(language)
        }
    }
    PicPilotTheme(darkTheme = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }) {
        Crossfade(targetState = session, label = "session") { state ->
            when (state) {
                SessionState.CHECKING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                SessionState.SIGNED_OUT -> AuthScreen(onAuthenticated = viewModel::authenticated)
                SessionState.SIGNED_IN -> MainShell(onLogout = viewModel::logout)
            }
        }
    }
}
