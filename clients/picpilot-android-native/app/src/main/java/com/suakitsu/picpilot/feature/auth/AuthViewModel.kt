package com.suakitsu.picpilot.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suakitsu.picpilot.core.model.UserProfile
import com.suakitsu.picpilot.core.network.ApiResult
import com.suakitsu.picpilot.core.repository.PicPilotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthMode { LOGIN, CODE_LOGIN, REGISTER, RESET }

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val code: String = "",
    val remember: Boolean = true,
    val passwordVisible: Boolean = false,
    val loading: Boolean = false,
    val cooldownSeconds: Int = 0,
    val error: String = "",
    val notice: String = "",
    val authenticatedUser: UserProfile? = null,
    val resetCompleted: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: PicPilotRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()
    private var cooldownJob: Job? = null

    fun setMode(mode: AuthMode) {
        _state.update { it.copy(mode = mode, error = "", notice = "", resetCompleted = false) }
    }

    fun updateEmail(value: String) = _state.update { it.copy(email = value.take(160), error = "") }
    fun updatePassword(value: String) = _state.update { it.copy(password = value.take(32), error = "") }
    fun updateConfirmPassword(value: String) = _state.update { it.copy(confirmPassword = value.take(32), error = "") }
    fun updateCode(value: String) = _state.update { it.copy(code = value.filter(Char::isDigit).take(8), error = "") }
    fun toggleRemember() = _state.update { it.copy(remember = !it.remember) }
    fun togglePasswordVisibility() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun sendCode() {
        val current = _state.value
        if (current.loading || current.cooldownSeconds > 0) return
        if (!EMAIL_REGEX.matches(current.email.trim())) {
            _state.update { it.copy(error = "请输入正确的邮箱地址") }
            return
        }
        val purpose = when (current.mode) {
            AuthMode.REGISTER -> "register"
            AuthMode.RESET -> "reset_password"
            else -> "login"
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = "", notice = "") }
            when (val result = repository.sendCode(current.email, purpose)) {
                is ApiResult.Success -> {
                    val seconds = result.value.cooldownSeconds.coerceIn(1, 300)
                    _state.update { it.copy(loading = false, notice = "验证码已发送", cooldownSeconds = seconds) }
                    startCooldown(seconds)
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    fun submit() {
        val current = _state.value
        if (current.loading) return
        validate(current)?.let { message ->
            _state.update { it.copy(error = message) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = "", notice = "") }
            when (current.mode) {
                AuthMode.LOGIN -> authenticate(repository.login(current.email, current.password, current.remember))
                AuthMode.CODE_LOGIN -> authenticate(repository.loginWithCode(current.email, current.code, current.remember))
                AuthMode.REGISTER -> authenticate(repository.register(current.email, current.password, current.confirmPassword, current.code))
                AuthMode.RESET -> when (val result = repository.resetPassword(current.email, current.code, current.password, current.confirmPassword)) {
                    is ApiResult.Success -> _state.update {
                        AuthUiState(email = current.email, notice = "密码已重置，请使用新密码登录", resetCompleted = true)
                    }
                    is ApiResult.Failure -> _state.update { it.copy(loading = false, error = result.message) }
                }
            }
        }
    }

    private fun authenticate(result: ApiResult<com.suakitsu.picpilot.core.model.AuthResponse>) {
        when (result) {
            is ApiResult.Success -> _state.update { it.copy(loading = false, authenticatedUser = result.value.user) }
            is ApiResult.Failure -> _state.update { it.copy(loading = false, error = result.message) }
        }
    }

    private fun validate(state: AuthUiState): String? {
        if (!EMAIL_REGEX.matches(state.email.trim())) return "请输入正确的邮箱地址"
        if (state.mode == AuthMode.CODE_LOGIN && state.code.length < 4) return "请输入邮箱验证码"
        if (state.mode == AuthMode.LOGIN && state.password.isBlank()) return "请输入密码"
        if (state.mode == AuthMode.REGISTER || state.mode == AuthMode.RESET) {
            if (!PASSWORD_REGEX.matches(state.password)) return "密码需为 8-16 位，并同时包含字母和数字"
            if (state.confirmPassword != state.password) return "两次输入的密码不一致"
            if (state.code.length < 4) return "请输入邮箱验证码"
        }
        return null
    }

    private fun startCooldown(initial: Int) {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch {
            for (seconds in initial downTo 1) {
                _state.update { it.copy(cooldownSeconds = seconds) }
                delay(1_000)
            }
            _state.update { it.copy(cooldownSeconds = 0) }
        }
    }

    private companion object {
        val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        val PASSWORD_REGEX = Regex("^(?=.*[A-Za-z])(?=.*\\d)\\S{8,16}$")
    }
}
