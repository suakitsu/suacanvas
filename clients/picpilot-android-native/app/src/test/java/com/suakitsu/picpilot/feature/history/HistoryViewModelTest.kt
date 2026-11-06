package com.suakitsu.picpilot.feature.history

import com.suakitsu.picpilot.core.model.CreativeChatSummary
import com.suakitsu.picpilot.core.model.ProjectSummary
import com.suakitsu.picpilot.core.model.VideoTask
import com.suakitsu.picpilot.core.network.ApiResult
import com.suakitsu.picpilot.core.repository.PicPilotRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun searchStatusAndIncrementalLoading_areAppliedWithoutNetworkPaging() = runTest(dispatcher) {
        val repository = repositoryWith(
            projects = (1..25).map { index ->
                ProjectSummary(
                    id = "project-$index",
                    productName = "雨伞 $index",
                    status = if (index % 2 == 0) "success" else "running",
                    updatedAt = "2026-08-12T10:${index.toString().padStart(2, '0')}:00+08:00",
                )
            },
        )
        val viewModel = HistoryViewModel(repository)
        advanceUntilIdle()

        assertEquals(20, viewModel.uiState.value.visibleEntries.size)
        assertTrue(viewModel.uiState.value.hasMore)

        viewModel.loadMore()
        assertEquals(25, viewModel.uiState.value.visibleEntries.size)

        viewModel.rememberScroll(11, 24)
        assertEquals(11 to 24, viewModel.scrollPosition())

        viewModel.updateQuery("雨伞 2")
        viewModel.selectStatusFilter(HistoryStatusFilter.Completed)
        assertTrue(viewModel.uiState.value.visibleEntries.all { it.title.contains("雨伞 2") && it.status == "success" })
    }

    @Test
    fun projectHide_usesUserEndpointAndRemovesOnlyLocalEntry() = runTest(dispatcher) {
        val repository = repositoryWith(projects = listOf(ProjectSummary(id = "project-1", productName = "商品图", status = "success")))
        coEvery { repository.hideProject("project-1") } returns ApiResult.Success(buildJsonObject { })
        val viewModel = HistoryViewModel(repository)
        advanceUntilIdle()

        viewModel.requestHide(viewModel.uiState.value.entries.single())
        viewModel.confirmHide()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.hideProject("project-1") }
        assertTrue(viewModel.uiState.value.entries.isEmpty())
        assertTrue(viewModel.uiState.value.successMessage?.contains("隐藏") == true)
    }

    @Test
    fun chatAndActiveVideo_doNotExposeHideAction() = runTest(dispatcher) {
        val repository = repositoryWith(
            chats = listOf(CreativeChatSummary(id = "chat-1", title = "对话", latestStatus = "success")),
            videos = listOf(VideoTask(id = "video-1", title = "视频", status = "generating")),
        )
        val viewModel = HistoryViewModel(repository)
        advanceUntilIdle()

        val chat = viewModel.uiState.value.entries.first { it.type == HistoryEntryType.Chat }
        val video = viewModel.uiState.value.entries.first { it.type == HistoryEntryType.Video }
        assertFalse(chat.canHide)
        assertFalse(video.canHide)

        viewModel.requestHide(chat)
        assertEquals(null, viewModel.uiState.value.pendingHide)
        coVerify(exactly = 0) { repository.hideVideoTask(any()) }
    }

    private fun repositoryWith(
        projects: List<ProjectSummary> = emptyList(),
        chats: List<CreativeChatSummary> = emptyList(),
        videos: List<VideoTask> = emptyList(),
    ): PicPilotRepository {
        val repository = mockk<PicPilotRepository>(relaxed = true)
        coEvery { repository.projects() } returns ApiResult.Success(projects)
        coEvery { repository.creativeChats() } returns ApiResult.Success(chats)
        coEvery { repository.videoTasks() } returns ApiResult.Success(videos)
        return repository
    }
}
