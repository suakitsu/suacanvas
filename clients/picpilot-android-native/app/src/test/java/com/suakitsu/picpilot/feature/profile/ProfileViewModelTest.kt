package com.suakitsu.picpilot.feature.profile

import android.net.Uri
import com.suakitsu.picpilot.core.model.BillingSummary
import com.suakitsu.picpilot.core.model.UserProfile
import com.suakitsu.picpilot.core.network.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun save_sendsCanonicalLanguageAndThemeAndRefreshesProfile() = runTest(dispatcher) {
        val gateway = FakeProfileGateway()
        val viewModel = ProfileViewModel(gateway)
        advanceUntilIdle()

        viewModel.beginEdit()
        viewModel.setNickname("新昵称")
        viewModel.setLanguage("en")
        viewModel.setTheme("dark")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(Triple("新昵称", "en-US", "dark"), gateway.saved)
        assertEquals("新昵称", viewModel.uiState.value.profile?.nickname)
        assertFalse(viewModel.uiState.value.isEditing)
        assertTrue(viewModel.uiState.value.successMessage?.isNotBlank() == true)
    }

    @Test
    fun refresh_failure_keepsPreviouslyLoadedProfile() = runTest(dispatcher) {
        val gateway = FakeProfileGateway()
        val viewModel = ProfileViewModel(gateway)
        advanceUntilIdle()
        gateway.failReads = true

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals("原昵称", viewModel.uiState.value.profile?.nickname)
        assertTrue(viewModel.uiState.value.errorMessage?.isNotBlank() == true)
    }

    private class FakeProfileGateway : ProfileWriteGateway {
        var saved: Triple<String, String, String>? = null
        var failReads = false
        private var current = UserProfile(
            id = "u_test",
            emailMasked = "u***@example.com",
            nickname = "原昵称",
            credits = 88,
        )

        override suspend fun profile(): ApiResult<UserProfile> =
            if (failReads) ApiResult.Failure("读取失败") else ApiResult.Success(current)

        override suspend fun billing(): ApiResult<BillingSummary> =
            if (failReads) ApiResult.Failure("读取失败") else ApiResult.Success(BillingSummary(88, 0))

        override suspend fun updateProfile(
            nickname: String,
            uiLanguage: String,
            theme: String,
        ): ApiResult<UserProfile> {
            saved = Triple(nickname, uiLanguage, theme)
            current = current.copy(nickname = nickname, uiLanguage = uiLanguage, theme = theme)
            return ApiResult.Success(current)
        }

        override suspend fun uploadAvatar(uri: Uri): ApiResult<UserProfile> = ApiResult.Success(current)
    }
}
