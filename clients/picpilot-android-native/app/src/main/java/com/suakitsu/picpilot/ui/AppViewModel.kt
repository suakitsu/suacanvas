package com.suakitsu.picpilot.ui

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suakitsu.picpilot.core.network.ApiResult
import com.suakitsu.picpilot.core.repository.PicPilotRepository
import com.suakitsu.picpilot.core.settings.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SessionState { CHECKING, SIGNED_OUT, SIGNED_IN }

internal fun ApiResult.Failure.invalidatesSession(): Boolean = when (code.uppercase()) {
    "HTTP_401",
    "UNAUTHORIZED",
    "AUTH_REQUIRED",
    "TOKEN_EXPIRED",
    "AUTH_TOKEN_EXPIRED",
    "JWT_EXPIRED",
    "INVALID_TOKEN",
    "INVALID_ACCESS_TOKEN",
    -> true

    else -> false
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: PicPilotRepository,
    private val preferences: AppPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _session = MutableStateFlow(SessionState.CHECKING)
    val session: StateFlow<SessionState> = _session.asStateFlow()
    val theme = preferences.theme
    val language = preferences.language
    private var sessionVerification: Job? = null

    init { verifySession() }

    fun verifySession() {
        if (sessionVerification?.isActive == true) return
        sessionVerification = viewModelScope.launch {
            if (repository.token.first().isBlank()) {
                _session.value = SessionState.SIGNED_OUT
                return@launch
            }
            _session.value = when (val result = repository.me()) {
                is ApiResult.Success -> {
                    preferences.update(result.value.theme, result.value.uiLanguage)
                    SessionState.SIGNED_IN
                }
                is ApiResult.Failure -> {
                    if (result.invalidatesSession()) {
                        repository.logout()
                        SessionState.SIGNED_OUT
                    } else {
                        SessionState.SIGNED_IN
                    }
                }
            }
        }
    }

    fun authenticated() { _session.value = SessionState.SIGNED_IN }
    fun logout() {
        repository.logout()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            context.getSharedPreferences("picpilot_web_canvas", Context.MODE_PRIVATE).edit().clear().apply()
        }
        _session.value = SessionState.SIGNED_OUT
    }
}
