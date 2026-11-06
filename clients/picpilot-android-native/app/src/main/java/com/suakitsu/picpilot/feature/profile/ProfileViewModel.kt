package com.suakitsu.picpilot.feature.profile

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suakitsu.picpilot.core.model.BillingSummary
import com.suakitsu.picpilot.core.model.UserProfile
import com.suakitsu.picpilot.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface ProfileWriteGateway {
    suspend fun profile(): ApiResult<UserProfile>
    suspend fun billing(): ApiResult<BillingSummary>
    suspend fun updateProfile(nickname: String, uiLanguage: String, theme: String): ApiResult<UserProfile>
    suspend fun uploadAvatar(uri: Uri): ApiResult<UserProfile>
}

@Immutable
data class ProfileUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val isUploadingAvatar: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val profile: UserProfile? = null,
    val billing: BillingSummary? = null,
    val nicknameDraft: String = "",
    val languageDraft: String = "zh-CN",
    val themeDraft: String = "system",
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val gateway: ProfileWriteGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init { load(refresh = false) }

    fun refresh() = load(refresh = true)

    fun beginEdit() {
        val profile = _uiState.value.profile ?: return
        _uiState.update {
            it.copy(
                isEditing = true,
                errorMessage = null,
                successMessage = null,
                nicknameDraft = profile.nickname,
                languageDraft = normalizeLanguage(profile.uiLanguage),
                themeDraft = normalizeTheme(profile.theme),
            )
        }
    }

    fun cancelEdit() {
        val profile = _uiState.value.profile
        _uiState.update {
            it.copy(
                isEditing = false,
                errorMessage = null,
                nicknameDraft = profile?.nickname.orEmpty(),
                languageDraft = normalizeLanguage(profile?.uiLanguage),
                themeDraft = normalizeTheme(profile?.theme),
            )
        }
    }

    fun setNickname(value: String) {
        if (value.length <= 40) _uiState.update { it.copy(nicknameDraft = value) }
    }

    fun setLanguage(value: String) {
        _uiState.update { it.copy(languageDraft = normalizeLanguage(value)) }
    }

    fun setTheme(value: String) {
        _uiState.update { it.copy(themeDraft = normalizeTheme(value)) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving || state.profile == null) return
        if (state.nicknameDraft.length > 40) {
            _uiState.update { it.copy(errorMessage = "昵称不能超过 40 个字符") }
            return
        }
        _uiState.update { it.copy(isSaving = true, errorMessage = null, successMessage = null) }
        viewModelScope.launch {
            when (
                val result = gateway.updateProfile(
                    nickname = state.nicknameDraft,
                    uiLanguage = normalizeLanguage(state.languageDraft),
                    theme = normalizeTheme(state.themeDraft),
                )
            ) {
                is ApiResult.Success -> syncProfile(result.value, "个人资料已保存")
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isSaving = false, errorMessage = result.message)
                }
            }
        }
    }

    fun uploadAvatar(uri: Uri) {
        if (_uiState.value.isUploadingAvatar) return
        _uiState.update {
            it.copy(isUploadingAvatar = true, errorMessage = null, successMessage = null)
        }
        viewModelScope.launch {
            when (val result = gateway.uploadAvatar(uri)) {
                is ApiResult.Success -> syncProfile(result.value, "头像已更新")
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isUploadingAvatar = false, errorMessage = result.message)
                }
            }
        }
    }

    private fun syncProfile(profile: UserProfile, message: String? = null) {
        _uiState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                isSaving = false,
                isUploadingAvatar = false,
                isEditing = false,
                errorMessage = null,
                successMessage = message,
                profile = profile,
                nicknameDraft = profile.nickname,
                languageDraft = normalizeLanguage(profile.uiLanguage),
                themeDraft = normalizeTheme(profile.theme),
            )
        }
    }

    private fun load(refresh: Boolean) {
        val state = _uiState.value
        if (state.isLoading || state.isRefreshing || state.isSaving || state.isUploadingAvatar) return
        _uiState.update {
            it.copy(
                isLoading = !refresh,
                isRefreshing = refresh,
                errorMessage = null,
                successMessage = null,
            )
        }
        viewModelScope.launch {
            val (profileResult, billingResult) = coroutineScope {
                val profile = async { gateway.profile() }
                val billing = async { gateway.billing() }
                profile.await() to billing.await()
            }
            val failures = listOfNotNull(
                (profileResult as? ApiResult.Failure)?.message,
                (billingResult as? ApiResult.Failure)?.message,
            )
            val profile = (profileResult as? ApiResult.Success)?.value
            val billing = (billingResult as? ApiResult.Success)?.value
            _uiState.update { current ->
                val resolvedProfile = profile ?: current.profile
                current.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = failures.distinct().joinToString("；").ifBlank { null },
                    profile = resolvedProfile,
                    billing = billing ?: current.billing,
                    nicknameDraft = resolvedProfile?.nickname ?: current.nicknameDraft,
                    languageDraft = resolvedProfile?.uiLanguage?.let(::normalizeLanguage) ?: current.languageDraft,
                    themeDraft = resolvedProfile?.theme?.let(::normalizeTheme) ?: current.themeDraft,
                )
            }
        }
    }

    private fun normalizeLanguage(value: String?): String = when (value) {
        "en", "en-US" -> "en-US"
        "ja", "ja-JP" -> "ja-JP"
        else -> "zh-CN"
    }

    private fun normalizeTheme(value: String?): String = when (value) {
        "light", "dark" -> value
        else -> "system"
    }
}
