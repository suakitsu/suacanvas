package com.suakitsu.picpilot.feature.messages

import com.suakitsu.picpilot.core.model.StationMessage
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun markAllRead_updatesEveryUnreadMessageThroughGateway() = runTest(dispatcher) {
        val gateway = FakeMessageGateway()
        val viewModel = MessagesViewModel(gateway)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.unreadCount)
        viewModel.markAllRead()
        advanceUntilIdle()

        assertEquals(setOf("m1", "m2"), gateway.marked.toSet())
        assertEquals(0, viewModel.uiState.value.unreadCount)
        assertTrue(viewModel.uiState.value.successMessage?.contains("全部") == true)
    }

    @Test
    fun failedSingleRead_restoresUnreadState() = runTest(dispatcher) {
        val gateway = FakeMessageGateway(failId = "m1")
        val viewModel = MessagesViewModel(gateway)
        advanceUntilIdle()

        viewModel.markRead("m1")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.messages.first { it.id == "m1" }.read.not())
        assertTrue(viewModel.uiState.value.errorMessage?.isNotBlank() == true)
    }

    private class FakeMessageGateway(private val failId: String? = null) : MessageWriteGateway {
        val marked = mutableListOf<String>()
        override suspend fun messages() = ApiResult.Success(
            listOf(
                StationMessage("m1", "消息一", "内容一", "2026-08-12T10:00:00+08:00", false),
                StationMessage("m2", "消息二", "内容二", "2026-08-12T09:00:00+08:00", false),
            ),
        )

        override suspend fun markRead(id: String): ApiResult<Unit> {
            marked += id
            return if (id == failId) ApiResult.Failure("标记失败") else ApiResult.Success(Unit)
        }
    }
}
