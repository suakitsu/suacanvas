package com.suakitsu.picpilot.feature.detail

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailContractTest {
    @Test
    fun inMemoryGateway_loadsExactRequestedIds() = runTest {
        val expectedProject = ProjectDetailModel(id = "project-older", productName = "旧项目")
        val expectedChat = CreativeChatDetailModel(id = "chat-middle", title = "指定对话")
        val expectedVideo = VideoTaskDetailModel(id = "video-first", title = "指定视频")
        val gateway = InMemoryDetailGateway(
            projects = listOf(
                expectedProject,
                ProjectDetailModel(id = "project-latest", productName = "最新项目"),
            ),
            chats = listOf(
                expectedChat,
                CreativeChatDetailModel(id = "chat-latest", title = "最新对话"),
            ),
            videos = listOf(
                expectedVideo,
                VideoTaskDetailModel(id = "video-latest", title = "最新视频"),
            ),
        )

        assertEquals(expectedProject, (gateway.loadProject("project-older") as DetailLoadResult.Success).value)
        assertEquals(expectedChat, (gateway.loadCreativeChat("chat-middle") as DetailLoadResult.Success).value)
        assertEquals(expectedVideo, (gateway.loadVideo("video-first") as DetailLoadResult.Success).value)
    }

    @Test
    fun inMemoryGateway_neverFallsBackToLatestRecord() = runTest {
        val gateway = InMemoryDetailGateway(
            videos = listOf(VideoTaskDetailModel(id = "video-latest", title = "最新视频")),
        )

        val result = gateway.loadVideo("video-missing")

        assertTrue(result is DetailLoadResult.Failure)
        assertEquals("VIDEO_TASK_NOT_FOUND", (result as DetailLoadResult.Failure).code)
    }

    @Test
    fun noOpGateway_performsNoIoAndReturnsStableSafeFailure() = runTest {
        val project = NoOpDetailGateway.loadProject("project-1")
        val chat = NoOpDetailGateway.loadCreativeChat("chat-1")
        val video = NoOpDetailGateway.loadVideo("video-1")

        listOf(project, chat, video).forEach { result ->
            assertTrue(result is DetailLoadResult.Failure)
            result as DetailLoadResult.Failure
            assertEquals("DETAIL_GATEWAY_NOT_CONNECTED", result.code)
            assertTrue(result.message.isNotBlank())
        }
    }

    @Test
    fun detailModels_keepStatusCreditsAndSafeErrorsPerTask() {
        val task = ProjectImageTask(
            id = "image-task-7",
            imageType = "main_image",
            imageName = "主图",
            status = "failed",
            creditsCost = 6,
            safeError = "图片生成失败，积分已按规则处理。",
        )
        val project = ProjectDetailModel(
            id = "project-7",
            productName = "商品",
            status = "images_partial_failed",
            creditsCost = 48,
            imageTasks = listOf(task),
        )

        assertEquals("failed", project.imageTasks.single().status)
        assertEquals(6, project.imageTasks.single().creditsCost)
        assertEquals("图片生成失败，积分已按规则处理。", project.imageTasks.single().safeError)
        assertEquals(48, project.creditsCost)
    }
}
