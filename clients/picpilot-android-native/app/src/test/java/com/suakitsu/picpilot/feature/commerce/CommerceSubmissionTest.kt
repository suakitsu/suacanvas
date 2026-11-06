package com.suakitsu.picpilot.feature.commerce

import androidx.lifecycle.SavedStateHandle
import com.suakitsu.picpilot.core.model.CommerceCatalogDto
import com.suakitsu.picpilot.core.model.ImageTask
import com.suakitsu.picpilot.core.model.ImageTranslationLanguageDto
import com.suakitsu.picpilot.integration.CommerceProjectUpdate
import com.suakitsu.picpilot.integration.ProductionCommerceGateway
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class CommerceSubmissionTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    @Test
    fun submitRequiresServerQuoteAndExplicitConfirmationAndOnlyRunsOnce() = runTest(dispatcher) {
        val fixture = fixture(CommerceQuote(1, 6, 6, isServerQuote = true))
        var submitCalls = 0
        fixture.viewModel.attachSubmitter {
            submitCalls += 1
            CommerceSubmissionResult.Accepted("project-1", 6)
        }
        fixture.viewModel.setValidReviewState()
        fixture.viewModel.refreshQuote()
        advanceUntilIdle()

        fixture.viewModel.confirmSubmission()
        runCurrent()
        assertEquals(0, submitCalls)

        fixture.viewModel.requestConfirmation()
        assertEquals(CommerceSubmissionPhase.AWAITING_CONFIRMATION, fixture.viewModel.uiState.value.submissionPhase)
        fixture.viewModel.confirmSubmission()
        fixture.viewModel.confirmSubmission()
        advanceUntilIdle()

        assertEquals(1, submitCalls)
        assertEquals(CommerceSubmissionPhase.SUBMITTED, fixture.viewModel.uiState.value.submissionPhase)
        assertEquals("project-1", fixture.viewModel.uiState.value.submittedTaskId)
    }

    @Test
    fun changedServerQuoteRequiresASecondConfirmationAndDoesNotSubmit() = runTest(dispatcher) {
        val initial = CommerceQuote(1, 6, 6, isServerQuote = true)
        val changed = CommerceQuote(1, 9, 9, isServerQuote = true)
        val fixture = fixture(initial)
        var submitCalls = 0
        fixture.viewModel.attachSubmitter {
            submitCalls += 1
            CommerceSubmissionResult.Accepted("should-not-exist", 9)
        }
        fixture.viewModel.setValidReviewState()
        fixture.viewModel.refreshQuote()
        advanceUntilIdle()
        coEvery { fixture.gateway.estimate(any()) } returns Result.success(changed)

        fixture.viewModel.requestConfirmation()
        fixture.viewModel.confirmSubmission()
        advanceUntilIdle()

        assertEquals(0, submitCalls)
        assertEquals(9, fixture.viewModel.uiState.value.quote?.totalCredits)
        assertEquals(CommerceSubmissionPhase.IDLE, fixture.viewModel.uiState.value.submissionPhase)
        assertTrue(fixture.viewModel.uiState.value.submissionMessage.orEmpty().contains("重新确认"))
    }

    @Test
    fun unknownSubmissionResultIsNotAutomaticallySentAgain() = runTest(dispatcher) {
        val fixture = fixture(CommerceQuote(1, 6, 6, isServerQuote = true))
        var submitCalls = 0
        fixture.viewModel.attachSubmitter {
            submitCalls += 1
            CommerceSubmissionResult.Rejected(
                "提交结果暂时无法确认，请先到历史记录核对，避免重复扣费",
                retryable = false,
            )
        }
        fixture.viewModel.setValidReviewState()
        fixture.viewModel.refreshQuote()
        advanceUntilIdle()

        fixture.viewModel.requestConfirmation()
        fixture.viewModel.confirmSubmission()
        advanceUntilIdle()
        advanceUntilIdle()

        assertEquals(1, submitCalls)
        assertEquals(CommerceSubmissionPhase.ERROR, fixture.viewModel.uiState.value.submissionPhase)
        assertTrue(fixture.viewModel.uiState.value.submissionMessage.orEmpty().contains("避免重复扣费"))
    }

    @Test
    fun imageTranslationWithoutEstimateIsBlockedInsteadOfGuessingPrice() = runTest(dispatcher) {
        val fixture = fixture(CommerceQuote(1, 6, 6, isServerQuote = true))
        var submitCalls = 0
        fixture.viewModel.attachSubmitter {
            submitCalls += 1
            CommerceSubmissionResult.Accepted("translation", 6)
        }
        fixture.viewModel.selectKind(CommerceCreationKind.IMAGE_TRANSLATION)
        fixture.viewModel.setValidReviewState(kind = CommerceCreationKind.IMAGE_TRANSLATION)
        advanceUntilIdle()

        fixture.viewModel.requestConfirmation()
        fixture.viewModel.confirmSubmission()
        advanceUntilIdle()

        assertEquals(0, submitCalls)
        assertNull(fixture.viewModel.uiState.value.quote)
        assertEquals(CommerceQuotePhase.UNAVAILABLE, fixture.viewModel.uiState.value.quotePhase)
        assertTrue(fixture.viewModel.uiState.value.quoteMessage.orEmpty().contains("服务端暂未提供"))
    }

    @Test
    fun acceptedProjectIsObservedThroughQueuedRunningAndCompletedStates() = runTest(dispatcher) {
        val quote = CommerceQuote(1, 6, 6, isServerQuote = true)
        val fixture = fixture(quote)
        every { fixture.gateway.observeProject("project-flow") } returns flowOf(
            CommerceProjectUpdate(
                projectId = "project-flow",
                status = "images_queued",
                tasks = listOf(imageTask("queued")),
                totalCredits = 6,
                terminal = false,
            ),
            CommerceProjectUpdate(
                projectId = "project-flow",
                status = "completed",
                tasks = listOf(imageTask("success", resultUrl = "https://example.invalid/result.png")),
                totalCredits = 6,
                terminal = true,
            ),
        )
        fixture.viewModel.attachSubmitter { CommerceSubmissionResult.Accepted("project-flow", 6) }
        fixture.viewModel.setValidReviewState()
        fixture.viewModel.refreshQuote()
        advanceUntilIdle()

        fixture.viewModel.requestConfirmation()
        fixture.viewModel.confirmSubmission()
        advanceUntilIdle()

        val state = fixture.viewModel.uiState.value
        assertEquals("project-flow", state.submittedTaskId)
        assertEquals(CommerceTaskPhase.COMPLETED, state.projectTasks.single().phase)
        assertTrue(state.projectTerminal)
        assertFalse(state.isProjectRefreshing)
    }

    @Test
    fun savedProjectIdRestoresObservationWithoutCreatingAnotherTask() = runTest(dispatcher) {
        val gateway = configuredGateway(CommerceQuote(1, 6, 6, isServerQuote = true))
        every { gateway.observeProject("saved-project") } returns flowOf(
            CommerceProjectUpdate(
                projectId = "saved-project",
                status = "images_queued",
                tasks = listOf(imageTask("queued")),
                totalCredits = 6,
                terminal = false,
            ),
        )
        val viewModel = CommerceViewModel(
            imageReader = mockk(relaxed = true),
            gateway = gateway,
            savedStateHandle = SavedStateHandle(mapOf("commerce_project_id" to "saved-project")),
        )
        advanceUntilIdle()

        assertEquals("saved-project", viewModel.uiState.value.submittedTaskId)
        assertEquals(CommerceTaskPhase.QUEUED, viewModel.uiState.value.projectTasks.single().phase)
        assertEquals(CommerceSubmissionPhase.SUBMITTED, viewModel.uiState.value.submissionPhase)
    }

    private fun fixture(quote: CommerceQuote): Fixture {
        val gateway = configuredGateway(quote)
        every { gateway.observeProject(any()) } returns flowOf(
            CommerceProjectUpdate("unused", "completed", listOf(imageTask("success")), 6, true),
        )
        return Fixture(
            gateway = gateway,
            viewModel = CommerceViewModel(
                imageReader = mockk(relaxed = true),
                gateway = gateway,
                savedStateHandle = SavedStateHandle(),
            ),
        )
    }

    private fun configuredGateway(quote: CommerceQuote): ProductionCommerceGateway =
        mockk<ProductionCommerceGateway>().also { gateway ->
            coEvery { gateway.loadCatalog() } returns Result.success(CommerceCatalogDto())
            coEvery { gateway.loadTranslationLanguages() } returns Result.success(
                listOf(ImageTranslationLanguageDto("ms-MY", "马来语（马来西亚）", "Bahasa Melayu", "Malay")),
            )
            coEvery { gateway.loadBalance() } returns Result.success(500)
            coEvery { gateway.estimate(any()) } returns Result.success(quote)
        }

    private fun CommerceViewModel.setValidReviewState(
        kind: CommerceCreationKind = CommerceCreationKind.PLATFORM_SUITE,
    ) {
        val state = mutableState()
        state.value = state.value.copy(
            kind = kind,
            productName = if (kind == CommerceCreationKind.IMAGE_TRANSLATION) "" else "折叠伞",
            category = if (kind == CommerceCreationKind.IMAGE_TRANSLATION) "" else "雨具",
            productFacts = if (kind == CommerceCreationKind.IMAGE_TRANSLATION) "" else "红色折叠晴雨伞",
            referenceImages = listOf(
                CommerceReferenceImage("i1", "content://fixture/1", "a.HEIC", "image/heic", 100),
            ),
            step = CommerceWizardStep.REVIEW,
            targetLanguageCode = "ms-MY",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun CommerceViewModel.mutableState(): MutableStateFlow<CommerceUiState> {
        val field = CommerceViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        return field.get(this) as MutableStateFlow<CommerceUiState>
    }

    private fun imageTask(status: String, resultUrl: String = "") = ImageTask(
        id = "image-1",
        imageType = "main_image",
        imageName = "商品主图",
        status = status,
        resultUrl = resultUrl,
        creditsCost = 6,
        safeError = if (status == "failed") "生成失败" else "",
    )

    private data class Fixture(
        val gateway: ProductionCommerceGateway,
        val viewModel: CommerceViewModel,
    )
}
