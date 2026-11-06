package com.suakitsu.picpilot.core.network

import com.suakitsu.picpilot.core.model.ApiEnvelope
import com.suakitsu.picpilot.core.model.BillingSummary
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class SafeRetryInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder()
            .addInterceptor(SafeRetryInterceptor(retryDelayMillis = 0))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun retriesReadOnlyRequestAfterTransientGatewayFailure() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        client.newCall(Request.Builder().url(server.url("/api/me")).build()).execute().use {
            assertEquals(200, it.code)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun neverRetriesGenerationRequest() {
        server.enqueue(MockResponse().setResponseCode(503))
        val body = "{}".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(server.url("/api/projects/creative-chats/generate")).post(body).build()

        client.newCall(request).execute().use { assertEquals(503, it.code) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun retriesQuoteLookupBecauseItCannotCreateAChargedTask() {
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val body = "{}".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(server.url("/api/projects/creative-chats/estimate")).post(body).build()

        client.newCall(request).execute().use { assertEquals(200, it.code) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun exposesSafeServerCodeAndMessage() {
        val body = """{"success":false,"code":"INSUFFICIENT_CREDITS","message":"积分不足"}"""
            .toResponseBody("application/json".toMediaType())
        val response = Response.error<ApiEnvelope<BillingSummary>>(400, body)

        assertEquals(
            ApiResult.Failure("积分不足", "INSUFFICIENT_CREDITS"),
            HttpException(response).toApiFailure(),
        )
    }

    @Test
    fun hidesUnsafeServerDetailsAndKeepsStatusCode() {
        val body = """{"code":"UPSTREAM_ERROR","message":"IllegalStateException\nstack trace"}"""
            .toResponseBody("application/json".toMediaType())
        val response = Response.error<ApiEnvelope<BillingSummary>>(503, body)

        assertEquals(
            ApiResult.Failure("服务连接暂时繁忙（HTTP 503），请稍后重试", "UPSTREAM_ERROR"),
            HttpException(response).toApiFailure(),
        )
    }
}
