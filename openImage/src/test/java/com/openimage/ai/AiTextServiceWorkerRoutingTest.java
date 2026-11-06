package com.openimage.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.worker.AiWorkerDeferredException;
import com.openimage.ai.worker.AiWorkerJobService;
import com.openimage.ai.worker.AiWorkerProviderException;
import com.openimage.ai.worker.CanvasWorkerInputService;
import com.openimage.domain.mapper.PromptTemplateMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTextServiceWorkerRoutingTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void claimedLocalTextJobStopsBeforeHttpFallback() {
        AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
        AiWorkerJobService workerJobs = mock(AiWorkerJobService.class);
        AiRuntimeConfigService.EffectiveTextConfig local = localConfig();
        AiRuntimeConfigService.EffectiveTextConfig http = httpConfig("http://127.0.0.1:1");
        when(runtime.textProviderChain()).thenReturn(List.of(local, http));
        when(workerJobs.executeText(
            eq(local),
            anyString(),
            eq("openai_chat_completions"),
            anyMap()
        )).thenThrow(new AiWorkerDeferredException("awj-text", "claimed"));

        AiTextService service = service(runtime, workerJobs);

        assertThatThrownBy(() -> invokeCall(service))
            .isInstanceOf(AiWorkerDeferredException.class)
            .extracting(exception -> ((AiWorkerDeferredException) exception).jobId())
            .isEqualTo("awj-text");
    }

    @Test
    void unclaimedLocalTextJobUsesExistingHttpFallback() throws Exception {
        AtomicInteger httpCalls = new AtomicInteger();
        String baseUrl = startTextServer(httpCalls);
        AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
        AiWorkerJobService workerJobs = mock(AiWorkerJobService.class);
        AiRuntimeConfigService.EffectiveTextConfig local = localConfig();
        AiRuntimeConfigService.EffectiveTextConfig http = httpConfig(baseUrl);
        when(runtime.textProviderChain()).thenReturn(List.of(local, http));
        when(workerJobs.executeText(
            eq(local),
            anyString(),
            eq("openai_chat_completions"),
            anyMap()
        )).thenThrow(new AiWorkerProviderException("LOCAL_WORKER_OFFLINE", "offline"));

        String result = invokeCall(service(runtime, workerJobs));

        assertThat(result).isEqualTo("fallback result");
        assertThat(httpCalls.get()).isEqualTo(1);
    }

    @Test
    void canvasTextPreservesWorkerToolCalls() {
        AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
        AiWorkerJobService workerJobs = mock(AiWorkerJobService.class);
        AiRuntimeConfigService.EffectiveTextConfig local = localConfig();
        when(runtime.textProviderChain()).thenReturn(List.of(local));
        when(workerJobs.executeTextRaw(
            eq(local),
            eq("canvas-text-test"),
            eq("openai_chat_completions"),
            anyMap()
        )).thenReturn(Map.of(
            "choices", List.of(Map.of(
                "message", Map.of(
                    "role", "assistant",
                    "content", "",
                    "tool_calls", List.of(Map.of(
                        "id", "call-1",
                        "type", "function",
                        "function", Map.of("name", "propose_image_action", "arguments", "{\"action\":\"generate\",\"prompt\":\"商品主图\"}")
                    ))
                ),
                "finish_reason", "tool_calls"
            ))
        ));

        Map<String, Object> response = service(runtime, workerJobs).generateCanvasText(
            Map.of(
                "messages", List.of(Map.of("role", "user", "content", "生成一张商品主图")),
                "tools", List.of(Map.of("type", "function", "function", Map.of("name", "propose_image_action")))
            ),
            "canvas-text-test"
        );

        assertThat(response).containsKey("choices");
        assertThat(response.toString()).contains("propose_image_action", "商品主图");
    }

    @Test
    void canvasTextRejectsRemoteImageUrls() {
        AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
        AiTextService service = service(runtime, mock(AiWorkerJobService.class));

        assertThatThrownBy(() -> service.generateCanvasText(
            Map.of("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", "https://example.com/private.png")
                ))
            ))),
            "canvas-text-test"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("内嵌数据");
    }

    @Test
    void canvasTextDoesNotSendImagesToDeepSeekOnlyChain() {
        AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
        when(runtime.textProviderChain()).thenReturn(List.of(new AiRuntimeConfigService.EffectiveTextConfig(
            true,
            "deepseek",
            "http://127.0.0.1:1",
            "mock-key",
            "deepseek-v4-flash",
            45,
            "deepseek-text"
        )));

        assertThatThrownBy(() -> service(runtime, mock(AiWorkerJobService.class)).generateCanvasText(
            imageCanvasRequest(),
            "canvas-vision-test"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("支持图片理解");
    }

    @Test
    void canvasTextPreservesImagesForVisionCapableHttpProvider() throws Exception {
        AtomicInteger httpCalls = new AtomicInteger();
        String baseUrl = startTextServer(httpCalls);
        AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
        when(runtime.textProviderChain()).thenReturn(List.of(httpConfig(baseUrl)));

        Map<String, Object> result = service(runtime, mock(AiWorkerJobService.class)).generateCanvasText(
            imageCanvasRequest(),
            "canvas-vision-test"
        );

        assertThat(result.toString()).contains("fallback result");
        assertThat(httpCalls.get()).isEqualTo(1);
    }

    @Test
    void canvasVisionExternalizesInlineImagesBeforeLocalWorker() {
        AiRuntimeConfigService runtime = mock(AiRuntimeConfigService.class);
        AiWorkerJobService workerJobs = mock(AiWorkerJobService.class);
        CanvasWorkerInputService inputs = mock(CanvasWorkerInputService.class);
        AiRuntimeConfigService.EffectiveTextConfig local = localConfig();
        Map<String, Object> externalized = Map.of("messages", List.of(Map.of(
            "role", "user",
            "content", List.of(
                Map.of("type", "text", "text", "描述这张商品图"),
                Map.of("type", "image_url", "image_url", Map.of(
                    "url", "https://suakitsu.test/api/public/canvas-worker-inputs/fixture/input.png?expires=1&signature=abc"
                ))
            )
        )));
        when(runtime.textProviderChain()).thenReturn(List.of(local));
        when(inputs.externalize(anyMap(), eq("canvas-vision-local"))).thenReturn(externalized);
        when(workerJobs.executeTextRaw(
            eq(local),
            eq("canvas-vision-local"),
            eq("openai_chat_completions"),
            argThat(body -> body.toString().contains("https://suakitsu.test/") && !body.toString().contains("data:image"))
        )).thenReturn(Map.of("choices", List.of(Map.of(
            "message", Map.of("role", "assistant", "content", "本机视觉结果"),
            "finish_reason", "stop"
        ))));

        Map<String, Object> result = service(runtime, workerJobs, inputs).generateCanvasText(
            imageCanvasRequest(),
            "canvas-vision-local"
        );

        assertThat(result.toString()).contains("本机视觉结果");
        verify(inputs).externalize(anyMap(), eq("canvas-vision-local"));
    }

    @Test
    void splitsTheGlobalTextDeadlineAcrossRemainingProviders() {
        assertThat(AiTextService.providerAttemptTimeoutSeconds(
            java.time.Duration.ofSeconds(25).toNanos(),
            3
        )).isEqualTo(9);
        assertThat(AiTextService.providerAttemptTimeoutSeconds(
            java.time.Duration.ofSeconds(16).toNanos(),
            2
        )).isEqualTo(8);
        assertThat(AiTextService.providerAttemptTimeoutSeconds(
            java.time.Duration.ofSeconds(8).toNanos(),
            1
        )).isEqualTo(8);
        assertThat(AiTextService.providerAttemptTimeoutSeconds(
            java.time.Duration.ofSeconds(120).toNanos(),
            2,
            120
        )).isEqualTo(60);
    }

    private Map<String, Object> imageCanvasRequest() {
        return Map.of("messages", List.of(Map.of(
            "role", "user",
            "content", List.of(
                Map.of("type", "text", "text", "描述这张商品图"),
                Map.of("type", "image_url", "image_url", Map.of(
                    "url", "data:image/png;base64,iVBORw0KGgo="
                ))
            )
        )));
    }

    private AiTextService service(
        AiRuntimeConfigService runtime,
        AiWorkerJobService workerJobs
    ) {
        return service(runtime, workerJobs, null);
    }

    private AiTextService service(
        AiRuntimeConfigService runtime,
        AiWorkerJobService workerJobs,
        CanvasWorkerInputService inputs
    ) {
        return new AiTextService(
            new ObjectMapper(),
            mock(PromptTemplateMapper.class),
            runtime,
            mock(AiTextCallAuditService.class),
            null,
            workerJobs,
            inputs
        );
    }

    @SuppressWarnings("unchecked")
    private String invokeCall(AiTextService service) throws Exception {
        Method method = AiTextService.class.getDeclaredMethod(
            "callDeepSeek",
            List.class,
            double.class,
            int.class,
            boolean.class
        );
        method.setAccessible(true);
        try {
            return (String) method.invoke(
                service,
                List.of(Map.of("role", "user", "content", "write one product title")),
                0.2,
                100,
                false
            );
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw exception;
        }
    }

    private String startTextServer(AtomicInteger calls) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = """
                {"choices":[{"message":{"content":"fallback result"}}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private AiRuntimeConfigService.EffectiveTextConfig localConfig() {
        return new AiRuntimeConfigService.EffectiveTextConfig(
            true,
            "local-cliproxy-text",
            "",
            "",
            "gpt-5.5",
            30,
            "local-cliproxy-text",
            AiRuntimeConfigService.EXECUTION_PULL_WORKER,
            1,
            AiRuntimeConfigService.OFFLINE_FALLBACK,
            120,
            1
        );
    }

    private AiRuntimeConfigService.EffectiveTextConfig httpConfig(String baseUrl) {
        return new AiRuntimeConfigService.EffectiveTextConfig(
            true,
            "chatgpt",
            baseUrl,
            "mock-key",
            "gpt-5.5",
            10,
            "text-http"
        );
    }
}
