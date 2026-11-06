package com.suakitsu.picpilot.integration

import com.suakitsu.picpilot.core.model.CommerceEstimateRequestDto
import com.suakitsu.picpilot.core.model.CreativeChatGenerateRequestDto
import com.suakitsu.picpilot.core.network.PicPilotApi
import com.suakitsu.picpilot.integration.upload.OrderedMultipartFactory
import com.suakitsu.picpilot.integration.upload.UploadContentSource
import com.suakitsu.picpilot.integration.upload.UploadDescriptor
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class ProductionApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PicPilotApi
    private lateinit var uploads: FixtureUploadSource
    private lateinit var multipart: OrderedMultipartFactory

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PicPilotApi::class.java)
        uploads = FixtureUploadSource()
        multipart = OrderedMultipartFactory(uploads)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun creativeEstimateUsesSnakeCaseAndParsesAuthoritativeCredits() = runTest {
        server.enqueue(jsonResponse(creativeEstimateResponse))

        val response = api.estimateCreativeChat(
            CreativeChatGenerateRequestDto(
                chatId = "chat-1",
                message = "Generate a product image",
                size = "2048x2048",
                referenceUrls = listOf("https://assets.example.test/ref.jpg"),
                textMode = "none",
            ),
        )

        assertTrue(response.success)
        assertEquals(24, response.data?.creditsCost)
        val request = server.takeRequest()
        assertEquals("/api/projects/creative-chats/estimate", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"chat_id\":\"chat-1\""))
        assertTrue(body.contains("\"reference_urls\":[\"https://assets.example.test/ref.jpg\"]"))
        assertTrue(body.contains("\"text_mode\":\"none\""))
        assertFalse(body.contains("chatId"))
    }

    @Test
    fun creativeMultipartKeepsFieldsAndFilesInDeclaredOrder() = runTest {
        uploads.add("content://fixture/zero", "ZERO", "", "zero.HEIC", null)
        uploads.add("content://fixture/one", "ONE", "image/png", "one.png", 3)
        uploads.add("content://fixture/two", "TWO", "image/jpeg", "two.jpg", 3)
        server.enqueue(jsonResponse(creativeGenerateResponse))

        val parts = buildList {
            add(multipart.text("chatId", "chat-1"))
            add(multipart.text("message", "Generate a clean product image"))
            add(multipart.text("style", "商品摄影"))
            add(multipart.text("size", "1024x1024"))
            add(multipart.text("language", "zh-CN"))
            addAll(multipart.repeatedText("referenceUrls", listOf("https://assets.example.test/owned.png")))
            addAll(
                multipart.imageParts(
                    fieldName = "references",
                    uploads = listOf(
                        UploadDescriptor("content://fixture/two", "two.jpg", "image/jpeg", order = 2),
                        UploadDescriptor("content://fixture/zero", "zero.HEIC", "application/octet-stream", order = 0),
                        UploadDescriptor("content://fixture/one", "one.png", "image/png", order = 1),
                    ),
                ),
            )
        }

        val response = api.generateCreativeChatMultipart(parts)

        assertEquals(30, response.data?.project?.imageTasks?.single()?.creditsCost)
        val request = server.takeRequest()
        assertEquals("/api/projects/creative-chats/generate", request.path)
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data;"))
        val body = request.body.readUtf8()
        assertPartOrder(
            body,
            "name=\"chatId\"",
            "name=\"message\"",
            "name=\"referenceUrls\"",
            "filename=\"zero.HEIC\"",
            "filename=\"one.png\"",
            "filename=\"two.jpg\"",
        )
        assertTrue(body.contains("Content-Type: image/heic"))
        assertTrue(body.contains("ZERO"))
        assertTrue(body.contains("ONE"))
        assertTrue(body.contains("TWO"))
    }

    @Test
    fun commerceEstimateAndMultipartParseServerCredits() = runTest {
        server.enqueue(jsonResponse(commerceEstimateResponse))
        val estimate = api.estimateCommerce(
            CommerceEstimateRequestDto(
                platform = "pinduoduo",
                selectedSlots = listOf("main_image", "detail"),
                resolution = "2K",
            ),
        )
        assertEquals(48, estimate.data?.creditsCost)
        val estimateRequest = server.takeRequest()
        assertEquals("/api/commerce-suite/estimate", estimateRequest.path)
        assertTrue(estimateRequest.body.readUtf8().contains("\"selected_slots\":[\"main_image\",\"detail\"]"))

        uploads.add("content://fixture/commerce-1", "COMMERCE_ONE", "image/webp", "first.webp", 12)
        uploads.add("content://fixture/commerce-0", "COMMERCE_ZERO", "image/jpeg", "second.jpg", 13)
        server.enqueue(jsonResponse(commerceGenerateResponse))
        val parts = buildList {
            add(multipart.text("platform", "pinduoduo"))
            add(multipart.text("product_info", "{\"product_name\":\"umbrella\"}"))
            add(multipart.text("selected_slots", "[\"main_image\",\"detail\"]"))
            add(multipart.text("resolution", "2K"))
            add(multipart.text("overlay_options", "{\"preserve_layout\":true}"))
            addAll(
                multipart.imageParts(
                    "references",
                    listOf(
                        UploadDescriptor("content://fixture/commerce-1", "first.webp", "image/webp", order = 1),
                        UploadDescriptor("content://fixture/commerce-0", "second.jpg", "image/jpeg", order = 0),
                    ),
                ),
            )
        }
        val generated = api.generateCommerceMultipart(parts)

        assertEquals(48, generated.data?.creditsCost)
        assertEquals(24, generated.data?.createdTasks?.first()?.creditsCost)
        val generateRequest = server.takeRequest()
        assertEquals("/api/commerce-suite/generate", generateRequest.path)
        val body = generateRequest.body.readUtf8()
        assertPartOrder(
            body,
            "name=\"platform\"",
            "name=\"product_info\"",
            "name=\"selected_slots\"",
            "name=\"resolution\"",
            "name=\"overlay_options\"",
            "filename=\"second.jpg\"",
            "filename=\"first.webp\"",
        )
    }

    @Test
    fun translationUsesDedicatedEndpointAndRejectsMoreThanTenImages() = runTest {
        uploads.add("content://fixture/translation", "TRANSLATION", "image/png", "source.png", 11)
        server.enqueue(jsonResponse(translationGenerateResponse))
        val parts = buildList {
            add(multipart.text("target_language", "ms-MY"))
            add(multipart.text("resolution", "1K"))
            addAll(
                multipart.imageParts(
                    "images",
                    listOf(UploadDescriptor("content://fixture/translation", "source.png", "image/png", order = 0)),
                    maxTotalBytes = 28L * 1024L * 1024L,
                ),
            )
        }

        val response = api.generateImageTranslation(parts)

        assertEquals(6, response.data?.creditsCost)
        assertEquals("ms-MY", response.data?.targetLanguage?.code)
        val request = server.takeRequest()
        assertEquals("/api/image-translation/generate", request.path)
        val body = request.body.readUtf8()
        assertPartOrder(body, "name=\"target_language\"", "name=\"resolution\"", "name=\"images\"")

        val tooMany = (0..10).map { index ->
            UploadDescriptor("content://fixture/translation", "source-$index.png", "image/png", order = index)
        }
        val failure = runCatching { multipart.imageParts("images", tooMany, maxFiles = 10) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("10"))
    }

    private fun assertPartOrder(body: String, vararg needles: String) {
        var previous = -1
        needles.forEach { needle ->
            val current = body.indexOf(needle)
            assertTrue("Missing multipart field: $needle", current >= 0)
            assertTrue("Multipart field out of order: $needle", current > previous)
            previous = current
        }
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}

private class FixtureUploadSource : UploadContentSource {
    private data class Fixture(
        val bytes: ByteArray,
        val mimeType: String,
        val displayName: String,
        val declaredLength: Long?,
    )

    private val fixtures = mutableMapOf<String, Fixture>()

    fun add(uri: String, content: String, mimeType: String, displayName: String, declaredLength: Long?) {
        fixtures[uri] = Fixture(content.toByteArray(), mimeType, displayName, declaredLength)
    }

    override fun open(uri: String): InputStream = ByteArrayInputStream(fixture(uri).bytes)
    override fun contentLength(uri: String): Long? = fixture(uri).declaredLength
    override fun mimeType(uri: String): String? = fixture(uri).mimeType
    override fun displayName(uri: String): String? = fixture(uri).displayName

    private fun fixture(uri: String): Fixture = requireNotNull(fixtures[uri]) { "Unknown fixture URI: $uri" }
}

private const val creativeEstimateResponse = """
{
  "success": true,
  "code": "OK",
  "message": "",
  "request_id": "req-estimate",
  "data": {"size":"2048x2048","quality_label":"2K","credits_cost":24}
}
"""

private const val creativeGenerateResponse = """
{
  "success": true,
  "code": "OK",
  "message": "",
  "request_id": "req-creative",
  "data": {
    "chat": {"chat":{"id":"chat-1"},"messages":[],"versions":[]},
    "project": {
      "project":{"id":"project-1","total_images":1,"credits_cost":30},
      "original_info":{},"generated_copy":{},"confirmed_facts":{},"product_images":[],
      "image_tasks":[{"id":"task-1","status":"queued","credits_cost":30}]
    },
    "final_prompt":"normalized prompt",
    "version_no":1
  }
}
"""

private const val commerceEstimateResponse = """
{
  "success":true,
  "code":"OK",
  "message":"",
  "request_id":"req-commerce-estimate",
  "data":{
    "platform":"pinduoduo","resolution":"2K","image_count":2,"credits_cost":48,
    "slot_costs":[
      {"slot":"main_image","name":"Main","size":"2048x2048","credits_cost":24},
      {"slot":"detail","name":"Detail","size":"2048x2048","credits_cost":24}
    ]
  }
}
"""

private const val commerceGenerateResponse = """
{
  "success":true,
  "code":"OK",
  "message":"",
  "request_id":"req-commerce-generate",
  "data":{
    "project_id":"commerce-project-1",
    "project":{
      "project":{"id":"commerce-project-1","total_images":2,"credits_cost":48},
      "original_info":{},"generated_copy":{},"confirmed_facts":{},"product_images":[],
      "image_tasks":[
        {"id":"commerce-task-1","status":"queued","credits_cost":24},
        {"id":"commerce-task-2","status":"queued","credits_cost":24}
      ]
    },
    "created_tasks":[
      {"id":"commerce-task-1","status":"queued","credits_cost":24},
      {"id":"commerce-task-2","status":"queued","credits_cost":24}
    ],
    "credits_cost":48,
    "mock_mode":false
  }
}
"""

private const val translationGenerateResponse = """
{
  "success":true,
  "code":"OK",
  "message":"",
  "request_id":"req-translation",
  "data":{
    "project_id":"translation-project-1",
    "project":{
      "project":{"id":"translation-project-1","total_images":1,"credits_cost":6},
      "original_info":{},"generated_copy":{},"confirmed_facts":{},"product_images":[],
      "image_tasks":[{"id":"translation-task-1","status":"queued","credits_cost":6}]
    },
    "created_tasks":[{"id":"translation-task-1","status":"queued","credits_cost":6}],
    "target_language":{"code":"ms-MY","name":"Malay","native_name":"Bahasa Melayu"},
    "resolution":"1K",
    "credits_cost":6
  }
}
"""
