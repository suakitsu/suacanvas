package com.suakitsu.picpilot.feature.aicreate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiCreateSubmissionTest {
    private val dispatcher = StandardTestDispatcher()
    private val serverCatalog = AiCreateCatalog(
        qualities = listOf(
            AiCreateQualityOption("1k", "1K", "1024 x 1024", 6),
            AiCreateQualityOption("2k", "2K", "2048 x 2048", 15),
            AiCreateQualityOption("4k", "4K", "2880 x 2880", 30),
        ),
        authoritative = true,
    )

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    @Test
    fun generationDoesNotRunBeforeConfirmationAndIsSingleFlight() = runTest(dispatcher) {
        var submitCalls = 0
        val gateway = mockGateway(
            estimate = { Result.success(6) },
            submit = { request ->
                submitCalls += 1
                Result.success(AiCreateSubmitReceipt("task-1", chargedCredits = request.expectedCredits))
            },
        )
        val viewModel = AiCreateViewModel(gateway, serverCatalog, initialCredits = 100)
        advanceUntilIdle()
        viewModel.updatePrompt("生成一张白底商品主图")

        viewModel.requestGeneration()
        advanceUntilIdle()
        assertEquals(0, submitCalls)
        viewModel.confirmGeneration()
        viewModel.confirmGeneration()
        advanceUntilIdle()

        assertEquals(1, submitCalls)
    }

    @Test
    fun generationIsDisabledUntilAuthoritativeCatalogArrives() = runTest(dispatcher) {
        var estimateCalls = 0
        val gateway = mockGateway(
            catalogResult = Result.failure(IllegalStateException("catalog pending")),
            estimate = {
                estimateCalls += 1
                Result.success(19)
            },
        )
        val viewModel = AiCreateViewModel(gateway, AiCreateCatalog(), initialCredits = 100)
        advanceUntilIdle()
        viewModel.updatePrompt("生成一张用于商城的主图")

        viewModel.requestGeneration()
        advanceUntilIdle()
        assertEquals(0, estimateCalls)
        assertNull(viewModel.uiState.value.pendingRequest)

        val dynamicCatalog = serverCatalog.copy(
            qualities = serverCatalog.qualities.map { if (it.id == "1k") it.copy(credits = 18) else it },
        )
        viewModel.updateCatalog(dynamicCatalog)
        viewModel.requestGeneration()
        advanceUntilIdle()

        assertEquals(1, estimateCalls)
        assertEquals(19, viewModel.uiState.value.pendingRequest?.expectedCredits)
        assertTrue(viewModel.uiState.value.catalog.authoritative)
    }

    @Test
    fun changedPriceRequiresASecondConfirmationBeforeSubmit() = runTest(dispatcher) {
        val estimates = ArrayDeque(listOf(6, 9, 9))
        var submitCalls = 0
        val gateway = mockGateway(
            estimate = { Result.success(estimates.removeFirst()) },
            submit = { request ->
                submitCalls += 1
                Result.success(AiCreateSubmitReceipt("task-priced", chargedCredits = request.expectedCredits))
            },
        )
        val viewModel = AiCreateViewModel(gateway, serverCatalog, initialCredits = 100)
        advanceUntilIdle()
        viewModel.updatePrompt("生成一张真实感商品图片")
        viewModel.requestGeneration()
        advanceUntilIdle()

        viewModel.confirmGeneration()
        advanceUntilIdle()
        assertEquals(0, submitCalls)
        assertEquals(9, viewModel.uiState.value.pendingRequest?.expectedCredits)
        assertTrue(viewModel.uiState.value.noticeMessage.orEmpty().contains("重新确认"))

        viewModel.confirmGeneration()
        advanceUntilIdle()
        assertEquals(1, submitCalls)
        assertNull(viewModel.uiState.value.pendingRequest)
    }

    @Test
    fun lastMomentServerPriceChangeDoesNotCreateAFailedLocalTask() = runTest(dispatcher) {
        val gateway = mockGateway(
            estimate = { Result.success(6) },
            submit = { Result.failure(AiCreateQuoteChangedException(8)) },
        )
        val viewModel = AiCreateViewModel(gateway, serverCatalog, initialCredits = 100)
        advanceUntilIdle()
        viewModel.updatePrompt("生成一张高质感商品场景图")
        viewModel.requestGeneration()
        advanceUntilIdle()
        viewModel.confirmGeneration()
        advanceUntilIdle()

        assertEquals(8, viewModel.uiState.value.pendingRequest?.expectedCredits)
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertTrue(viewModel.uiState.value.noticeMessage.orEmpty().contains("重新确认"))
    }

    @Test
    fun acceptedSubmissionPublishesAuthoritativeBalance() = runTest(dispatcher) {
        val gateway = mockGateway(
            estimate = { Result.success(6) },
            submit = {
                Result.success(
                    AiCreateSubmitReceipt(
                        taskId = "task-balance",
                        chargedCredits = 6,
                        balanceAfter = 42,
                    ),
                )
            },
        )
        val viewModel = AiCreateViewModel(gateway, serverCatalog, initialCredits = 48)
        advanceUntilIdle()
        viewModel.updatePrompt("生成一张干净的产品主图")
        viewModel.requestGeneration()
        advanceUntilIdle()
        viewModel.confirmGeneration()
        advanceUntilIdle()

        assertEquals(42, viewModel.uiState.value.availableCredits)
    }

    @Test
    fun refreshReloadsBalanceFromServer() = runTest(dispatcher) {
        var serverBalance = 100
        val gateway = object : AiCreateGateway {
            override suspend fun loadCatalog() = Result.success(serverCatalog)
            override suspend fun loadBalance() = Result.success(serverBalance)
            override suspend fun estimate(request: AiCreateGenerateRequest) = Result.success(6)
            override suspend fun submit(request: AiCreateGenerateRequest) =
                Result.failure<AiCreateSubmitReceipt>(AssertionError("本测试不得提交"))
        }
        val viewModel = AiCreateViewModel(gateway)
        advanceUntilIdle()
        assertEquals(100, viewModel.uiState.value.availableCredits)

        serverBalance = 77
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(77, viewModel.uiState.value.availableCredits)
    }

    @Test
    fun processRecreationRestoresHistoryWithoutSubmittingAgain() = runTest(dispatcher) {
        var submitCalls = 0
        val restoredTask = AiCreateTaskPresentation(
            localId = "server_task-restored",
            taskId = "task-restored",
            status = AiCreateTaskStatus.Generating,
            originalPrompt = "恢复中的任务",
        )
        val gateway = object : AiCreateGateway {
            override suspend fun loadCatalog() = Result.success(serverCatalog)
            override suspend fun loadBalance() = Result.success(80)
            override suspend fun loadConversations() = Result.success(
                listOf(AiCreateConversation("chat-restored", "历史对话")),
            )
            override suspend fun loadMessages(conversationId: String) = Result.success(
                listOf(
                    AiCreateMessage(
                        id = "assistant-restored",
                        role = AiCreateMessageRole.Assistant,
                        text = "正在生成",
                        task = restoredTask,
                    ),
                ),
            )
            override suspend fun estimate(request: AiCreateGenerateRequest) = Result.success(6)
            override suspend fun submit(request: AiCreateGenerateRequest): Result<AiCreateSubmitReceipt> {
                submitCalls += 1
                return Result.failure(AssertionError("恢复流程不得提交收费 POST"))
            }
            override fun observeTask(taskId: String) = flowOf(
                AiCreateTaskUpdate(
                    taskId = taskId,
                    status = AiCreateTaskStatus.Completed,
                    progress = 100,
                    results = listOf(AiCreateResultImage("result-restored", "https://example.test/restored.png")),
                ),
            )
        }

        val rebuilt = AiCreateViewModel(gateway)
        advanceUntilIdle()

        assertEquals(0, submitCalls)
        assertEquals("chat-restored", rebuilt.uiState.value.selectedConversationId)
        assertEquals(AiCreateTaskStatus.Completed, rebuilt.uiState.value.messages.single().task?.status)
        assertFalse(rebuilt.uiState.value.messages.single().task?.results.isNullOrEmpty())
    }

    @Test
    fun completedHistoryDoesNotStartAnotherTaskObserver() = runTest(dispatcher) {
        var observeCalls = 0
        val gateway = object : AiCreateGateway {
            override suspend fun loadCatalog() = Result.success(serverCatalog)
            override suspend fun loadBalance() = Result.success(80)
            override suspend fun loadConversations() = Result.success(
                listOf(AiCreateConversation("chat-completed", "已完成对话")),
            )
            override suspend fun loadMessages(conversationId: String) = Result.success(
                listOf(
                    AiCreateMessage(
                        id = "assistant-completed",
                        role = AiCreateMessageRole.Assistant,
                        text = "图片已完成",
                        task = AiCreateTaskPresentation(
                            localId = "server_task-completed",
                            taskId = "task-completed",
                            status = AiCreateTaskStatus.Completed,
                            results = listOf(AiCreateResultImage("result-completed", "https://example.test/result.png")),
                        ),
                    ),
                ),
            )
            override suspend fun submit(request: AiCreateGenerateRequest) =
                Result.failure<AiCreateSubmitReceipt>(AssertionError("本测试不得提交"))

            override fun observeTask(taskId: String) = emptyFlow<AiCreateTaskUpdate>().also {
                observeCalls += 1
            }
        }

        val rebuilt = AiCreateViewModel(gateway)
        advanceUntilIdle()

        assertEquals(0, observeCalls)
        assertEquals(AiCreateTaskStatus.Completed, rebuilt.uiState.value.messages.single().task?.status)
    }

    private fun mockGateway(
        catalogResult: Result<AiCreateCatalog> = Result.success(serverCatalog),
        estimate: suspend (AiCreateGenerateRequest) -> Result<Int> = { Result.success(6) },
        submit: suspend (AiCreateGenerateRequest) -> Result<AiCreateSubmitReceipt> = {
            Result.failure(AssertionError("Mock 未配置 submit"))
        },
    ) = object : AiCreateGateway {
        override suspend fun loadCatalog() = catalogResult
        override suspend fun loadBalance() = Result.success(100)
        override suspend fun estimate(request: AiCreateGenerateRequest) = estimate(request)
        override suspend fun submit(request: AiCreateGenerateRequest) = submit(request)
    }
}
