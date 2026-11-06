package com.suakitsu.picpilot.core.network

import com.suakitsu.picpilot.core.model.ApiEnvelope
import com.suakitsu.picpilot.core.model.BillingSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEnvelopeTest {
    @Test
    fun successEnvelopeReturnsData() = runTest {
        val result = apiCall {
            ApiEnvelope(
                success = true,
                code = "OK",
                data = BillingSummary(credits = 120, pendingOrders = 1),
            )
        }

        assertTrue(result is ApiResult.Success)
        assertEquals(120, (result as ApiResult.Success).value.credits)
    }

    @Test
    fun businessFailureKeepsSafeCodeAndMessage() = runTest {
        val result: ApiResult<BillingSummary> = apiCall {
            ApiEnvelope(success = false, code = "INSUFFICIENT_CREDITS", message = "积分不足")
        }

        assertEquals(
            ApiResult.Failure("积分不足", "INSUFFICIENT_CREDITS"),
            result,
        )
    }
}
