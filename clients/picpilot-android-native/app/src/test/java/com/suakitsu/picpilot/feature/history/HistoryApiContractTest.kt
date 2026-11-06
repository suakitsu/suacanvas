package com.suakitsu.picpilot.feature.history

import com.suakitsu.picpilot.core.network.PicPilotApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class HistoryApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PicPilotApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PicPilotApi::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun projectAndVideoHide_useExistingUserDeleteEndpoints() = runTest {
        server.enqueue(jsonResponse("""{"project_id":"project-1","deleted":true}"""))
        server.enqueue(jsonResponse("""{"task_id":"video-1","hidden":true}"""))

        val project = api.hideProject("project-1")
        val projectRequest = server.takeRequest()
        assertEquals("DELETE", projectRequest.method)
        assertEquals("/api/projects/project-1", projectRequest.path)
        assertTrue(project.data?.get("deleted").toString().contains("true"))

        val video = api.hideVideoTask("video-1")
        val videoRequest = server.takeRequest()
        assertEquals("DELETE", videoRequest.method)
        assertEquals("/api/videos/tasks/video-1", videoRequest.path)
        assertTrue(video.data?.get("hidden").toString().contains("true"))
    }

    private fun jsonResponse(data: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"success":true,"code":"OK","message":"","data":$data}""")
}
