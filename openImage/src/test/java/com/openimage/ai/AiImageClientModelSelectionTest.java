package com.openimage.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.worker.AiWorkerDeferredException;
import com.openimage.ai.worker.AiWorkerJobService;
import com.openimage.ai.worker.AiWorkerProviderException;
import com.openimage.domain.entity.ImageTaskEntity;
import com.openimage.domain.entity.ProductImageEntity;
import com.openimage.domain.entity.ProductProjectEntity;
import com.openimage.risk.RiskControlService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiImageClientModelSelectionTest {
    private static final String ONE_PIXEL_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";
    private static final String ONE_PIXEL_WEBP = "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AA/v89WAAAAA==";

    private final ObjectMapper json = new ObjectMapper();
    private final AiRuntimeConfigService runtimeConfig = mock(AiRuntimeConfigService.class);
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void upgradesHfsyGptImage2ToProForFourKTasks() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_hfsyapi", "hfsyapi", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("2880x2880");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_hfsyapi", "hfsyapi", "gpt-image-2", "2880x2880")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("gpt-image-2pro", request.path("model").asText());
        assertEquals("2880x2880", request.path("size").asText());
        assertEquals("gpt-image-2pro", generated.model());
        verify(runtimeConfig).imageProviderChainForTask("image_hfsyapi", "hfsyapi", "gpt-image-2", "2880x2880");
    }

    @Test
    void keepsHfsyWideFourKAsPixelSizeForPro() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_hfsyapi", "hfsyapi", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("3696x1584");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_hfsyapi", "hfsyapi", "gpt-image-2", "3696x1584")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("gpt-image-2pro", request.path("model").asText());
        assertEquals("3696x1584", request.path("size").asText());
        assertEquals("3696x1584", generated.attempts().get(0).requestSize());
    }

    @Test
    void keepsHfsyGptImage2ForOneKTasks() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_hfsyapi", "hfsyapi", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("1024x1024");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_hfsyapi", "hfsyapi", "gpt-image-2", "1024x1024")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("gpt-image-2", request.path("model").asText());
        assertEquals("1024x1024", request.path("size").asText());
        assertEquals("gpt-image-2", generated.model());
    }

    @Test
    void keepsAishuchGptImage2ForTwoKTasks() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_aishuch", "aishuch", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("2048x2048", "image_aishuch", "aishuch");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_aishuch", "aishuch", "gpt-image-2", "2048x2048")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("gpt-image-2", request.path("model").asText());
        assertEquals("2048x2048", request.path("size").asText());
        assertEquals("2k", request.path("resolution").asText());
        assertEquals("gpt-image-2", generated.model());
        verify(runtimeConfig).imageProviderChainForTask("image_aishuch", "aishuch", "gpt-image-2", "2048x2048");
    }

    @Test
    void forcesYunfeiRequestModelToGptImage2() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_yunfei", "yunfei", baseUrl, "not-allowed");
        ImageTaskEntity task = imageTask("2880x2880", "image_yunfei", "yunfei", "also-not-allowed");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_yunfei", "yunfei", "also-not-allowed", "2880x2880")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("gpt-image-2", request.path("model").asText());
        assertEquals("gpt-image-2", generated.model());
        assertEquals("gpt-image-2", generated.attempts().get(0).model());
    }

    @Test
    void storesApimartReturnedCreditsAsCnyCostDetails() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startApimartImageServer(capturedBody, "0.021", "0.21");
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_aishuch", "aishuch", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("2448x3264", "image_aishuch", "aishuch");
        when(runtimeConfig.imageProviderChainForTask("image_aishuch", "aishuch", "gpt-image-2", "2448x3264")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project(), task, List.of());

        assertEquals(new BigDecimal("0.142613"), generated.actualCost());
        assertEquals(new BigDecimal("0.21"), generated.costDetails().upstreamCost());
        assertEquals("APIMART_CREDIT", generated.costDetails().upstreamCostUnit());
        assertEquals("upstream_credits_cost", generated.costDetails().source());
        assertEquals(new BigDecimal("0.142613"), generated.attempts().get(0).actualCostCny());
    }

    @Test
    void downgradesXinZhiZhuGptImage2ProTaskModelToGptImage2() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_xin", "xin-zhi-zhu", baseUrl, "gpt-image-2pro");
        ImageTaskEntity task = imageTask("2048x2048", "image_xin", "xin-zhi-zhu", "gpt-image-2pro");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_xin", "xin-zhi-zhu", "gpt-image-2pro", "2048x2048")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("gpt-image-2", request.path("model").asText());
        assertEquals("2048x2048", request.path("size").asText());
        assertEquals("gpt-image-2", generated.model());
        assertEquals("gpt-image-2", generated.attempts().get(0).model());
    }

    @Test
    void xinZhiZhuDoesNotAppendLongProviderAdapter() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_xin", "xin-zhi-zhu", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("1024x1024", "image_xin", "xin-zhi-zhu");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_xin", "xin-zhi-zhu", "gpt-image-2", "1024x1024")).thenReturn(List.of(config));

        client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        String prompt = request.path("prompt").asText();
        assertFalse(prompt.contains("Provider adapter:"));
        assertFalse(prompt.contains("This is not a product redesign task"));
    }

    @Test
    void ruaCreativeRequestUsesCompiledModelPromptWithoutAnotherWrapper() throws Exception {
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig(
            "image_rua", "rua.chat", "https://example.invalid", "gpt-image-2"
        );
        ImageTaskEntity task = imageTask("2048x2048", "image_rua", "rua.chat");
        String compiled = "AUTHORITATIVE USER REQUEST\nReference roles are already resolved.\nAdd no new visible text.";
        task.setPromptSnapshot(json.writeValueAsString(java.util.Map.of(
            "aiProviderId", "image_rua",
            "generationSize", "2048x2048",
            "intent", "reference_replace",
            "modelPrompt", compiled
        )));

        assertEquals(compiled, invokePrompt(client(), project(), task, true, config));
    }

    @Test
    void hfsyProviderAdapterDoesNotForbidAnExplicitReplacement() throws Exception {
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig(
            "image_hfsyapi", "hfsyapi", "https://example.invalid", "gpt-image-2"
        );
        ImageTaskEntity task = imageTask("2048x2048", "image_hfsyapi", "hfsyapi");
        task.setPromptSnapshot(json.writeValueAsString(java.util.Map.of(
            "aiProviderId", "image_hfsyapi",
            "generationSize", "2048x2048",
            "intent", "reference_replace",
            "modelPrompt", "Replace the product in image 1 with the product from image 2."
        )));

        String prompt = invokePrompt(client(), project(), task, true, config);

        assertTrue(prompt.contains("if the user explicitly requests replacement"));
        assertTrue(prompt.contains("replace the original with the designated product source"));
        assertFalse(prompt.contains("Do not replace or redesign the product."));
    }

    @Test
    void explicitlyIgnoredUploadsAreNotSentToProvider() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_rua", "rua.chat", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("1024x1024", "image_rua", "rua.chat");
        task.setPromptSnapshot(json.writeValueAsString(java.util.Map.of(
            "aiProviderId", "image_rua",
            "generationSize", "1024x1024",
            "intent", "free_create",
            "reference_policy", "none",
            "modelPrompt", "图片已上传但不用参考图，直接生成一张红色雨伞商品图。"
        )));
        when(runtimeConfig.imageProviderChainForTask("image_rua", "rua.chat", "gpt-image-2", "1024x1024")).thenReturn(List.of(config));

        client().generate(project(), task, List.of(reference("https://example.invalid/ignored.png")));

        JsonNode request = json.readTree(capturedBody.get());
        assertFalse(request.has("reference_images"));
        assertTrue(request.path("prompt").asText().contains("直接生成一张红色雨伞商品图"));
    }

    @Test
    void xinZhiZhuReferenceImagesUseEditsMultipartWithFirstFiveImages() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startRuaEditServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_xin", "xin-zhi-zhu", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("1024x1024", "image_xin", "xin-zhi-zhu");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_xin", "xin-zhi-zhu", "gpt-image-2", "1024x1024")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.of(
            reference(baseUrl + "/ref-1.png"),
            reference(baseUrl + "/ref-2.png"),
            reference(baseUrl + "/ref-3.png"),
            reference(baseUrl + "/ref-4.png"),
            reference(baseUrl + "/ref-5.png"),
            reference(baseUrl + "/ref-6.png")
        ));

        String request = capturedBody.get();
        assertTrue(request.contains("name=\"model\""));
        assertTrue(request.contains("gpt-image-2"));
        assertTrue(request.contains("name=\"response_format\""));
        assertTrue(request.contains("b64_json"));
        assertEquals(5, countOccurrences(request, "name=\"image\""));
        assertFalse(request.contains("reference_images"));
        assertEquals("reference_images_truncated:6->5", generated.attempts().get(0).notes());
    }

    @Test
    void webpReferenceImageIsAcceptedAndSentAsMultipartEdit() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startRuaEditServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_rua", "rua.chat", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("1024x1024", "image_rua", "rua.chat");
        when(runtimeConfig.imageProviderChainForTask("image_rua", "rua.chat", "gpt-image-2", "1024x1024")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(
            project(),
            task,
            List.of(reference(baseUrl + "/ref-1.webp"))
        );

        String request = capturedBody.get();
        assertTrue(request.contains("name=\"image\""));
        assertTrue(request.contains("filename=\"reference-1.webp\""));
        assertTrue(request.contains("Content-Type: image/webp"));
        assertEquals("rua.chat", generated.provider());
        assertTrue(generated.attempts().get(0).success());
    }

    @Test
    void yunfeiCommerceSuiteUsesDirectModelPrompt() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_yunfei_1k", "yunfei-1k", baseUrl, "gpt-image-2");
        ImageTaskEntity task = commerceSuiteImageTask("1024x1024", "image_yunfei_1k", "yunfei-1k");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_yunfei_1k", "yunfei-1k", "gpt-image-2", "1024x1024")).thenReturn(List.of(config));

        client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("Commerce suite model prompt.", request.path("prompt").asText());
    }

    @Test
    void yunfeiReferenceImagesUseEditsMultipartWithOneImage() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startRuaEditServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_yunfei_1k", "yunfei-1k", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("1024x1024", "image_yunfei_1k", "yunfei-1k");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_yunfei_1k", "yunfei-1k", "gpt-image-2", "1024x1024")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.of(
            reference(baseUrl + "/ref-1.png"),
            reference(baseUrl + "/ref-2.png")
        ));

        String request = capturedBody.get();
        assertTrue(request.contains("name=\"model\""));
        assertTrue(request.contains("gpt-image-2"));
        assertTrue(request.contains("name=\"response_format\""));
        assertTrue(request.contains("b64_json"));
        assertEquals(1, countOccurrences(request, "name=\"image\""));
        assertFalse(request.contains("reference_images"));
        assertEquals("reference_images_truncated:2->1", generated.attempts().get(0).notes());
    }

    @Test
    void ruaCommerceSuiteUsesDirectModelPrompt() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_rua", "rua.chat", baseUrl, "gpt-image-2");
        ImageTaskEntity task = commerceSuiteImageTask("1024x1024", "image_rua", "rua.chat");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_rua", "rua.chat", "gpt-image-2", "1024x1024")).thenReturn(List.of(config));

        client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("Commerce suite model prompt.", request.path("prompt").asText());
    }

    @Test
    void downgradesAishuchGptImage2ProTaskModelToGptImage2() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_aishuch", "aishuch", baseUrl, "gpt-image-2pro");
        ImageTaskEntity task = imageTask("2048x2048", "image_aishuch", "aishuch", "gpt-image-2pro");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_aishuch", "aishuch", "gpt-image-2pro", "2048x2048")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("gpt-image-2", request.path("model").asText());
        assertEquals("2048x2048", request.path("size").asText());
        assertEquals("2k", request.path("resolution").asText());
        assertEquals("gpt-image-2", generated.model());
        assertEquals("gpt-image-2", generated.attempts().get(0).model());
    }

    @Test
    void downgradesAnyNonHfsyGptImage2ProTaskModelToGptImage2() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_custom", "custom-image-provider", baseUrl, "gpt-image-2pro");
        ImageTaskEntity task = imageTask("2048x2048", "image_custom", "custom-image-provider", "gpt-image-2pro");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_custom", "custom-image-provider", "gpt-image-2pro", "2048x2048")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("gpt-image-2", request.path("model").asText());
        assertEquals("2048x2048", request.path("size").asText());
        assertEquals("gpt-image-2", generated.model());
        assertEquals("gpt-image-2", generated.attempts().get(0).model());
    }

    @Test
    void sendsAishuchWideFourKAsRatioAndResolution() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_aishuch", "aishuch", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("3696x1584", "image_aishuch", "aishuch");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_aishuch", "aishuch", "gpt-image-2", "3696x1584")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("gpt-image-2", request.path("model").asText());
        assertEquals("3840x1648", request.path("size").asText());
        assertEquals("4k", request.path("resolution").asText());
        assertEquals("3840x1648@4k", generated.attempts().get(0).requestSize());
    }

    @Test
    void sendsAishuchThreeTwoFourKAsSupportedRatio() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_aishuch", "aishuch", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("3504x2336", "image_aishuch", "aishuch");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_aishuch", "aishuch", "gpt-image-2", "3504x2336")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("3520x2336", request.path("size").asText());
        assertEquals("4k", request.path("resolution").asText());
        assertEquals("3520x2336@4k", generated.attempts().get(0).requestSize());
    }

    @Test
    void rewritesMinioReferenceUrlsToBackendPublicProxy() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_hfsyapi", "hfsyapi", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("1024x1024");
        ProductProjectEntity project = project();
        ProductImageEntity reference = new ProductImageEntity();
        reference.setUrl("http://127.0.0.1:19000/openimage/projects/prod_model_selection/ref.jpg");
        when(runtimeConfig.imageProviderChainForTask("image_hfsyapi", "hfsyapi", "gpt-image-2", "1024x1024")).thenReturn(List.of(config));

        client().generate(project, task, List.of(reference));

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("https://suakitsu.com/api/public/files/projects/prod_model_selection/ref.jpg", request.path("reference_images").path(0).asText());
    }

    @Test
    void ruaChatUsesSafeRequestShapeAndNoProUpgrade() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_rua", "rua.chat", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("2880x2880", "image_rua", "rua.chat");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_rua", "rua.chat", "gpt-image-2", "2880x2880")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.<ProductImageEntity>of());

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("gpt-image-2", request.path("model").asText());
        assertEquals("2880x2880", request.path("size").asText());
        assertEquals("png", request.path("output_format").asText());
        assertEquals("auto", request.path("quality").asText());
        assertEquals("low", request.path("moderation").asText());
        assertEquals("", request.path("response_format").asText());
        assertEquals("gpt-image-2", generated.model());
    }

    @Test
    void ruaChatReferenceImagesUseEditsMultipartWithFirstFourImages() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startRuaEditServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_rua", "rua.chat", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("2048x2048", "image_rua", "rua.chat");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_rua", "rua.chat", "gpt-image-2", "2048x2048")).thenReturn(List.of(config));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.of(
            reference(baseUrl + "/ref-1.png"),
            reference(baseUrl + "/ref-2.png"),
            reference(baseUrl + "/ref-3.png"),
            reference(baseUrl + "/ref-4.png"),
            reference(baseUrl + "/ref-5.png")
        ));

        String request = capturedBody.get();
        assertTrue(request.contains("name=\"model\""));
        assertTrue(request.contains("gpt-image-2"));
        assertTrue(request.contains("name=\"output_format\""));
        assertTrue(request.contains("png"));
        assertEquals(4, countOccurrences(request, "name=\"image\""));
        assertFalse(request.contains("reference_images"));
        assertEquals("reference_images_truncated:5->4", generated.attempts().get(0).notes());
    }

    @Test
    void hfsyGptImage2ProUsesOnlyFirstFourReferenceImages() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig config = imageConfig("image_hfsyapi", "hfsyapi", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("2048x2048", "image_hfsyapi", "hfsyapi");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_hfsyapi", "hfsyapi", "gpt-image-2", "2048x2048")).thenReturn(List.of(config));

        client().generate(project, task, List.of(
            reference("projects/prod_model_selection/ref-1.png"),
            reference("projects/prod_model_selection/ref-2.png"),
            reference("projects/prod_model_selection/ref-3.png"),
            reference("projects/prod_model_selection/ref-4.png"),
            reference("projects/prod_model_selection/ref-5.png"),
            reference("projects/prod_model_selection/ref-6.png")
        ));

        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("gpt-image-2pro", request.path("model").asText());
        assertEquals(4, request.path("reference_images").size());
        assertEquals("https://suakitsu.com/api/public/files/projects/prod_model_selection/ref-1.png", request.path("reference_images").path(0).asText());
        assertEquals("https://suakitsu.com/api/public/files/projects/prod_model_selection/ref-4.png", request.path("reference_images").path(3).asText());
    }

    @Test
    void fallsBackFromRuaChatToHfsyapiAndRecordsAttempts() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startFallbackServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig rua = imageConfig("image_rua", "rua.chat", baseUrl, "gpt-image-2");
        AiRuntimeConfigService.EffectiveImageConfig hfsy = imageConfig("image_hfsyapi", "hfsyapi", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("1024x1024", "image_rua", "rua.chat");
        ProductProjectEntity project = project();
        when(runtimeConfig.imageProviderChainForTask("image_rua", "rua.chat", "gpt-image-2", "1024x1024")).thenReturn(List.of(rua, hfsy));

        AiImageClient.GeneratedImage generated = client().generate(project, task, List.<ProductImageEntity>of());

        assertEquals("hfsyapi", generated.provider());
        assertEquals(2, generated.attempts().size());
        assertEquals("rua.chat", generated.attempts().get(0).provider());
        assertEquals("http_524", generated.attempts().get(0).errorType());
        assertEquals("hfsyapi", generated.attempts().get(1).provider());
        assertEquals("success", generated.attempts().get(1).status());
        JsonNode request = json.readTree(capturedBody.get());
        assertEquals("gpt-image-2", request.path("model").asText());
        assertEquals("1024x1024", request.path("size").asText());
    }

    @Test
    void skipsRecentlyFailedProviderForTheNextTask() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        AtomicInteger ruaCalls = new AtomicInteger();
        String baseUrl = startFallbackServer(capturedBody, ruaCalls);
        AiRuntimeConfigService.EffectiveImageConfig rua = imageConfig("image_rua", "rua.chat", baseUrl, "gpt-image-2");
        AiRuntimeConfigService.EffectiveImageConfig hfsy = imageConfig("image_hfsyapi", "hfsyapi", baseUrl, "gpt-image-2");
        when(runtimeConfig.imageProviderChainForTask("image_rua", "rua.chat", "gpt-image-2", "1024x1024"))
            .thenReturn(List.of(rua, hfsy));
        AiImageClient imageClient = client();

        imageClient.generate(project(), imageTask("1024x1024", "image_rua", "rua.chat"), List.of());
        AiImageClient.GeneratedImage second = imageClient.generate(
            project(),
            imageTask("1024x1024", "image_rua", "rua.chat"),
            List.of()
        );

        assertEquals(1, ruaCalls.get());
        assertEquals("skipped", second.attempts().get(0).status());
        assertEquals("circuit_open", second.attempts().get(0).errorType());
        assertEquals("hfsyapi", second.provider());
    }

    @Test
    void localWorkerRunsBeforeHttpProviderAndUsesExistingPromptContract() throws Exception {
        AiWorkerJobService workerJobs = mock(AiWorkerJobService.class);
        AiRuntimeConfigService.EffectiveImageConfig local = localWorkerConfig();
        AiRuntimeConfigService.EffectiveImageConfig http = imageConfig(
            "image_hfsyapi",
            "hfsyapi",
            "http://127.0.0.1:1",
            "gpt-image-2"
        );
        ImageTaskEntity task = imageTask("1024x1024", "image_hfsyapi", "hfsyapi");
        when(runtimeConfig.imageProviderChainForTask(
            "image_hfsyapi",
            "hfsyapi",
            "gpt-image-2",
            "1024x1024"
        )).thenReturn(List.of(local, http));
        when(workerJobs.executeImage(
            org.mockito.ArgumentMatchers.eq(local),
            org.mockito.ArgumentMatchers.eq(task.getId()),
            org.mockito.ArgumentMatchers.eq("openai_images_generations"),
            anyMap(),
            anyList()
        )).thenReturn(new AiWorkerJobService.ImageResult(
            Base64.getDecoder().decode(ONE_PIXEL_PNG),
            "image/png",
            "1x1",
            "awj-local"
        ));

        AiImageClient.GeneratedImage generated = client(null, workerJobs).generate(
            project(),
            task,
            List.of()
        );

        assertEquals("local-cliproxy-image", generated.provider());
        assertEquals("gpt-image-2", generated.model());
        assertEquals(new BigDecimal("0.001000"), generated.actualCost());
        assertEquals(1, generated.attempts().size());
        assertEquals("success", generated.attempts().get(0).status());
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(workerJobs).executeImage(
            org.mockito.ArgumentMatchers.eq(local),
            org.mockito.ArgumentMatchers.eq(task.getId()),
            org.mockito.ArgumentMatchers.eq("openai_images_generations"),
            request.capture(),
            anyList()
        );
        assertEquals("gpt-image-2", request.getValue().get("model"));
        assertTrue(String.valueOf(request.getValue().get("prompt")).contains("Create a clean product image."));
    }

    @Test
    void claimedLocalWorkerJobNeverStartsHttpFallback() throws Exception {
        AiWorkerJobService workerJobs = mock(AiWorkerJobService.class);
        AiRuntimeConfigService.EffectiveImageConfig local = localWorkerConfig();
        AiRuntimeConfigService.EffectiveImageConfig http = imageConfig(
            "image_hfsyapi",
            "hfsyapi",
            "http://127.0.0.1:1",
            "gpt-image-2"
        );
        ImageTaskEntity task = imageTask("1024x1024", "image_hfsyapi", "hfsyapi");
        when(runtimeConfig.imageProviderChainForTask(
            "image_hfsyapi",
            "hfsyapi",
            "gpt-image-2",
            "1024x1024"
        )).thenReturn(List.of(local, http));
        when(workerJobs.executeImage(
            org.mockito.ArgumentMatchers.eq(local),
            anyString(),
            anyString(),
            anyMap(),
            anyList()
        )).thenThrow(new AiWorkerDeferredException("awj-claimed", "claimed"));

        AiImageClient.AiImageDeferredException exception = assertThrows(
            AiImageClient.AiImageDeferredException.class,
            () -> client(null, workerJobs).generate(project(), task, List.of())
        );

        assertEquals("awj-claimed", exception.jobId());
        assertEquals(1, exception.attempts().size());
        assertEquals("queued", exception.attempts().get(0).status());
    }

    @Test
    void unclaimedLocalWorkerFallsBackToExistingHttpProvider() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startImageServer(capturedBody);
        AiWorkerJobService workerJobs = mock(AiWorkerJobService.class);
        AiRuntimeConfigService.EffectiveImageConfig local = localWorkerConfig();
        AiRuntimeConfigService.EffectiveImageConfig http = imageConfig(
            "image_hfsyapi",
            "hfsyapi",
            baseUrl,
            "gpt-image-2"
        );
        ImageTaskEntity task = imageTask("1024x1024", "image_hfsyapi", "hfsyapi");
        when(runtimeConfig.imageProviderChainForTask(
            "image_hfsyapi",
            "hfsyapi",
            "gpt-image-2",
            "1024x1024"
        )).thenReturn(List.of(local, http));
        when(workerJobs.executeImage(
            org.mockito.ArgumentMatchers.eq(local),
            anyString(),
            anyString(),
            anyMap(),
            anyList()
        )).thenThrow(new AiWorkerProviderException("LOCAL_WORKER_OFFLINE", "offline"));

        AiImageClient.GeneratedImage generated = client(null, workerJobs).generate(
            project(),
            task,
            List.of()
        );

        assertEquals("hfsyapi", generated.provider());
        assertEquals(2, generated.attempts().size());
        assertEquals("local-cliproxy-image", generated.attempts().get(0).provider());
        assertEquals("failed", generated.attempts().get(0).status());
        assertFalse(capturedBody.get().isBlank());
    }

    @Test
    void intermediatePolicyFailureDoesNotCountWhenFallbackSucceeds() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>("");
        String baseUrl = startPolicyFallbackServer(capturedBody);
        AiRuntimeConfigService.EffectiveImageConfig rua = imageConfig("image_rua", "rua.chat", baseUrl, "gpt-image-2");
        AiRuntimeConfigService.EffectiveImageConfig hfsy = imageConfig("image_hfsyapi", "hfsyapi", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("1024x1024", "image_rua", "rua.chat");
        ProductProjectEntity project = project();
        RiskControlService riskControl = mock(RiskControlService.class);
        when(runtimeConfig.imageProviderChainForTask("image_rua", "rua.chat", "gpt-image-2", "1024x1024")).thenReturn(List.of(rua, hfsy));

        AiImageClient.GeneratedImage generated = client(riskControl).generate(project, task, List.<ProductImageEntity>of());

        assertEquals("hfsyapi", generated.provider());
        verify(riskControl, never()).recordProviderError(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void finalPolicyFailureCountsOnceAcrossProviderChain() throws Exception {
        String baseUrl = startPolicyRejectServer();
        AiRuntimeConfigService.EffectiveImageConfig rua = imageConfig("image_rua", "rua.chat", baseUrl, "gpt-image-2");
        AiRuntimeConfigService.EffectiveImageConfig hfsy = imageConfig("image_hfsyapi", "hfsyapi", baseUrl, "gpt-image-2");
        ImageTaskEntity task = imageTask("1024x1024", "image_rua", "rua.chat");
        ProductProjectEntity project = project();
        RiskControlService riskControl = mock(RiskControlService.class);
        when(runtimeConfig.imageProviderChainForTask("image_rua", "rua.chat", "gpt-image-2", "1024x1024")).thenReturn(List.of(rua, hfsy));

        assertThrows(AiImageClient.AiImageGenerationException.class, () ->
            client(riskControl).generate(project, task, List.<ProductImageEntity>of()));

        verify(riskControl, times(1)).recordProviderError(
            eq("user_1"), eq("prod_model_selection"), eq("img_model_selection"), eq("image_generation:creative"), anyString()
        );
    }

    @Test
    void capsKnownProviderConcurrency() throws Exception {
        AiImageClient client = client();

        assertEquals(envInt("OPENIMAGE_RUA_CHAT_CONCURRENCY", 2),
            providerSlotPermits(client, imageConfig("image_rua", "rua.chat", "https://api.rua.chat", "gpt-image-2")));
        assertEquals(envInt("OPENIMAGE_XIN_ZHI_ZHU_CONCURRENCY", 10),
            providerSlotPermits(client, imageConfig("image_xin", "xin-zhi-zhu", "https://ea-n-qu153.xin-zhi-zhu.com", "gpt-image-2")));
        assertEquals(envInt("OPENIMAGE_YUNFEI_1K_CONCURRENCY", 10),
            providerSlotPermits(client, imageConfig("image_yunfei_1k", "yunfei-1k", "https://img.yunfei.best", "gpt-image-2")));
        assertEquals(-1,
            providerSlotPermits(client, imageConfig("image_hfsyapi", "hfsyapi", "https://www.hfsyapi.cn", "gpt-image-2")));
    }

    private AiImageClient client() {
        return client(null);
    }

    private AiImageClient client(RiskControlService riskControl) {
        return client(riskControl, null);
    }

    private AiImageClient client(RiskControlService riskControl, AiWorkerJobService workerJobs) {
        AiImageProperties defaults = new AiImageProperties(
            true,
            "hfsyapi",
            "https://www.hfsyapi.cn",
            "sk-test",
            "gpt-image-2",
            "1024x1024",
            "b64_json",
            BigDecimal.ZERO,
            "https://suakitsu.com",
            30
        );
        return new AiImageClient(defaults, runtimeConfig, json, WebClient.builder(), riskControl, workerJobs);
    }

    private int providerSlotPermits(AiImageClient client, AiRuntimeConfigService.EffectiveImageConfig config) throws Exception {
        Method method = AiImageClient.class.getDeclaredMethod("providerSlot", AiRuntimeConfigService.EffectiveImageConfig.class);
        method.setAccessible(true);
        Semaphore slot = (Semaphore) method.invoke(client, config);
        return slot == null ? -1 : slot.availablePermits();
    }

    private String invokePrompt(
        AiImageClient client,
        ProductProjectEntity project,
        ImageTaskEntity task,
        boolean hasReferences,
        AiRuntimeConfigService.EffectiveImageConfig config
    ) throws Exception {
        Method method = AiImageClient.class.getDeclaredMethod(
            "prompt",
            ProductProjectEntity.class,
            ImageTaskEntity.class,
            boolean.class,
            AiRuntimeConfigService.EffectiveImageConfig.class
        );
        method.setAccessible(true);
        return (String) method.invoke(client, project, task, hasReferences, config);
    }

    private int envInt(String key, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(System.getenv().getOrDefault(key, "").trim()));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private AiRuntimeConfigService.EffectiveImageConfig imageConfig(String id, String provider, String baseUrl, String model) {
        return new AiRuntimeConfigService.EffectiveImageConfig(
            true,
            provider,
            baseUrl,
            "sk-test",
            model,
            "1024x1024",
            "b64_json",
            BigDecimal.ZERO,
            "https://suakitsu.com",
            30,
            id
        );
    }

    private AiRuntimeConfigService.EffectiveImageConfig localWorkerConfig() {
        return new AiRuntimeConfigService.EffectiveImageConfig(
            true,
            "local-cliproxy-image",
            "",
            "",
            "gpt-image-2",
            "1024x1024",
            "b64_json",
            new BigDecimal("0.001000"),
            "https://suakitsu.com",
            30,
            "local-cliproxy-image",
            AiRuntimeConfigService.EXECUTION_PULL_WORKER,
            1,
            AiRuntimeConfigService.OFFLINE_FALLBACK,
            120,
            1
        );
    }

    private String startImageServer(AtomicReference<String> capturedBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> respondWithImage(exchange, capturedBody));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String startApimartImageServer(
        AtomicReference<String> capturedBody,
        String cost,
        String creditsCost
    ) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedBody.set(body);
            byte[] response = (
                "{\"cost\":" + cost
                    + ",\"credits_cost\":" + creditsCost
                    + ",\"data\":[{\"b64_json\":\"" + ONE_PIXEL_PNG + "\"}]}"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String startFallbackServer(AtomicReference<String> capturedBody) throws Exception {
        return startFallbackServer(capturedBody, new AtomicInteger());
    }

    private String startFallbackServer(AtomicReference<String> capturedBody, AtomicInteger ruaCalls) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode request = json.readTree(body);
            if ("gpt-image-2".equals(request.path("model").asText()) && request.has("output_format")) {
                ruaCalls.incrementAndGet();
                byte[] response = "{\"error\":\"timeout\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(524, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            capturedBody.set(body);
            respondWithImage(exchange, new AtomicReference<>());
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String startPolicyFallbackServer(AtomicReference<String> capturedBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode request = json.readTree(body);
            if (request.has("output_format")) {
                writeJson(exchange, 400, "{\"error\":{\"message\":\"blocked by content policy\"}}");
                return;
            }
            capturedBody.set(body);
            respondWithImage(exchange, new AtomicReference<>());
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String startPolicyRejectServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images/generations", exchange -> {
            exchange.getRequestBody().readAllBytes();
            writeJson(exchange, 400, "{\"error\":{\"message\":\"blocked by content policy\"}}");
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private String startRuaEditServer(AtomicReference<String> capturedBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/ref-1.png", this::respondWithReferenceImage);
        server.createContext("/ref-1.webp", this::respondWithWebpReferenceImage);
        server.createContext("/ref-2.png", this::respondWithReferenceImage);
        server.createContext("/ref-3.png", this::respondWithReferenceImage);
        server.createContext("/ref-4.png", this::respondWithReferenceImage);
        server.createContext("/ref-5.png", this::respondWithReferenceImage);
        server.createContext("/v1/images/edits", exchange -> respondWithImage(exchange, capturedBody));
        server.start();
        return reachableBaseUrl();
    }

    private String startRuaEditRejectThenGenerationServer(AtomicReference<String> capturedBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/ref-a.png", this::respondWithReferenceImage);
        server.createContext("/ref-b.png", this::respondWithReferenceImage);
        server.createContext("/v1/images/edits", exchange -> {
            byte[] response = "{\"error\":\"unsupported edits shape\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v1/images/generations", exchange -> respondWithImage(exchange, capturedBody));
        server.start();
        return reachableBaseUrl();
    }

    private String reachableBaseUrl() throws Exception {
        return "http://" + InetAddress.getLocalHost().getHostName() + ":" + server.getAddress().getPort();
    }

    private void respondWithReferenceImage(HttpExchange exchange) throws java.io.IOException {
        byte[] image = Base64.getDecoder().decode(ONE_PIXEL_PNG);
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.sendResponseHeaders(200, image.length);
        exchange.getResponseBody().write(image);
        exchange.close();
    }

    private void respondWithWebpReferenceImage(HttpExchange exchange) throws java.io.IOException {
        byte[] image = Base64.getDecoder().decode(ONE_PIXEL_WEBP);
        exchange.getResponseHeaders().set("Content-Type", "image/webp");
        exchange.sendResponseHeaders(200, image.length);
        exchange.getResponseBody().write(image);
        exchange.close();
    }

    private void respondWithImage(HttpExchange exchange, AtomicReference<String> capturedBody) throws java.io.IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        capturedBody.set(body);
        byte[] response = ("{\"data\":[{\"b64_json\":\"" + ONE_PIXEL_PNG + "\"}]}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private String promptSnapshot(String generationSize) throws Exception {
        return promptSnapshot(generationSize, "image_hfsyapi");
    }

    private String promptSnapshot(String generationSize, String providerId) throws Exception {
        return json.writeValueAsString(java.util.Map.of(
            "aiProviderId", providerId,
            "generationSize", generationSize,
            "modelPrompt", "Create a clean product image."
        ));
    }

    private String commerceSuitePromptSnapshot(String generationSize, String providerId) throws Exception {
        return json.writeValueAsString(java.util.Map.of(
            "aiProviderId", providerId,
            "taskKind", "commerce_suite",
            "generationSize", generationSize,
            "modelPrompt", "Commerce suite model prompt."
        ));
    }

    private ImageTaskEntity imageTask(String generationSize) throws Exception {
        return imageTask(generationSize, "image_hfsyapi", "hfsyapi");
    }

    private ImageTaskEntity imageTask(String generationSize, String providerId, String provider) throws Exception {
        return imageTask(generationSize, providerId, provider, "gpt-image-2");
    }

    private ImageTaskEntity imageTask(String generationSize, String providerId, String provider, String model) throws Exception {
        ImageTaskEntity task = new ImageTaskEntity();
        task.setId("img_model_selection");
        task.setProjectId("prod_model_selection");
        task.setUserId("user_1");
        task.setImageType("creative");
        task.setStatus("running");
        task.setPromptSnapshot(promptSnapshot(generationSize, providerId));
        task.setProvider(provider);
        task.setModel(model);
        task.setCreditsCost(40);
        task.setCreatedAt(OffsetDateTime.now());
        task.setUpdatedAt(OffsetDateTime.now());
        task.setDeleted(0);
        return task;
    }

    private ImageTaskEntity commerceSuiteImageTask(String generationSize, String providerId, String provider) throws Exception {
        ImageTaskEntity task = imageTask(generationSize, providerId, provider);
        task.setPromptSnapshot(commerceSuitePromptSnapshot(generationSize, providerId));
        return task;
    }

    private ProductImageEntity reference(String url) {
        ProductImageEntity image = new ProductImageEntity();
        image.setId("ref_" + url.replaceAll("[^0-9]", ""));
        image.setUrl(url);
        image.setFileSize(1024L);
        return image;
    }

    private ProductProjectEntity project() {
        ProductProjectEntity project = new ProductProjectEntity();
        project.setId("prod_model_selection");
        project.setUserId("user_1");
        project.setProductName("Lolita Rabbit Bag");
        project.setDeleted(0);
        return project;
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count += 1;
            index += needle.length();
        }
        return count;
    }
}
