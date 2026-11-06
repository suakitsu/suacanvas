package com.openimage.modules.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.AiRuntimeConfigService;
import com.openimage.ai.AiTextCallAuditService;
import com.openimage.ai.worker.AiWorkerJobService;
import com.openimage.domain.mapper.AiTextCallLogMapper;
import com.openimage.risk.RiskControlService;
import com.openimage.security.CurrentUser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retriesNextApiKeyAndNormalizesModelAlias() throws Exception {
        List<CapturedRequest> captured = new ArrayList<>();
        String baseUrl = startServer(captured);
        RiskControlService riskControl = mock(RiskControlService.class);
        AssistantService service = new AssistantService(properties(baseUrl), runtimeConfig(baseUrl), textAudit(), riskControl, json);
        AssistantDtos.AssistantChatRequest request = new AssistantDtos.AssistantChatRequest(
            List.of(new AssistantDtos.AssistantMessage("user", "帮我写一个杯子的 Listing 标题")),
            Map.of("pathname", "/ai-create"),
            "zh-CN",
            "5.6terra"
        );

        AssistantDtos.AssistantChatResponse response = service.chat(
            new CurrentUser("user-1", "u@example.com", "user"),
            request
        );

        assertEquals("ok", response.reply());
        assertEquals("gpt-5.6-terra", response.model());
        assertEquals(2, captured.size());
        assertEquals("Bearer bad-key", captured.get(0).authorization());
        assertEquals("Bearer good-key", captured.get(1).authorization());
        assertEquals("gpt-5.6-terra", captured.get(1).body().path("model").asText());
        verify(riskControl).checkBeforeProvider(
            "user-1", "", "", "global_assistant", "帮我写一个杯子的 Listing 标题", "user_input"
        );
        verify(riskControl, never()).recordProviderError(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void exposesSelectableModelsInStatus() {
        AssistantService service = new AssistantService(properties("http://127.0.0.1:1"), runtimeConfig("http://127.0.0.1:1"), textAudit(), json);

        AssistantDtos.AssistantStatusResponse status = service.status();

        assertTrue(status.enabled());
        assertEquals("gpt-5.5", status.model());
        assertEquals(List.of("gpt-5.5", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"), status.models());
    }

    @Test
    void routesCopyAgentThroughPullWorkerWithSelectedModel() throws Exception {
        AiRuntimeConfigService runtimeConfig = mock(AiRuntimeConfigService.class);
        AiWorkerJobService workerJobs = mock(AiWorkerJobService.class);
        AiRuntimeConfigService.EffectiveTextConfig local = new AiRuntimeConfigService.EffectiveTextConfig(
            true,
            "local-cliproxy-text",
            "",
            "",
            "gpt-5.5",
            60,
            "local-cliproxy-text",
            "pull_worker",
            20,
            "queue",
            120,
            4
        );
        when(runtimeConfig.textProviderChain()).thenReturn(List.of(local));
        when(workerJobs.executeText(
            argThat(config -> config != null && "gpt-5.6-terra".equals(config.model())),
            anyString(),
            eq("openai_chat_completions"),
            argThat(body -> body != null && "gpt-5.6-terra".equals(body.get("model")))
        )).thenReturn("""
            {"reply":"本机文案完成","intent":"chat","action":"none","action_label":"","action_href":"","suggested_prompt":"","confidence":0.9}
            """);
        AssistantService service = new AssistantService(
            properties("http://127.0.0.1:1"),
            runtimeConfig,
            textAudit(),
            mock(RiskControlService.class),
            json,
            workerJobs
        );

        AssistantDtos.AssistantChatResponse response = service.chat(
            new CurrentUser("user-1", "u@example.com", "user"),
            new AssistantDtos.AssistantChatRequest(
                List.of(new AssistantDtos.AssistantMessage("user", "写三条卖点")),
                Map.of("tab", "copy", "task", "copywriting_agent"),
                "zh-CN",
                "5.6terra"
            )
        );

        assertEquals("本机文案完成", response.reply());
        assertEquals("gpt-5.6-terra", response.model());
        verify(workerJobs).executeText(
            argThat(config -> config != null && "gpt-5.6-terra".equals(config.model())),
            argThat(id -> id != null && id.startsWith("assistant_")),
            eq("openai_chat_completions"),
            argThat(body -> body != null && Boolean.FALSE.equals(body.get("stream")))
        );
    }

    private AssistantProperties properties(String baseUrl) {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(baseUrl);
        properties.setApiKeys("bad-key,good-key");
        properties.setModel("gpt-5.5");
        properties.setTimeoutSeconds(2);
        properties.setMaxTokens(300);
        return properties;
    }

    private AiRuntimeConfigService runtimeConfig(String baseUrl) {
        AiRuntimeConfigService runtimeConfig = mock(AiRuntimeConfigService.class);
        when(runtimeConfig.textProviderChain()).thenReturn(List.of(
            new AiRuntimeConfigService.EffectiveTextConfig(true, "chatgpt", baseUrl, "bad-key", "gpt-5.5", 2, "text_bad"),
            new AiRuntimeConfigService.EffectiveTextConfig(true, "chatgpt", baseUrl, "good-key", "gpt-5.5", 2, "text_good")
        ));
        return runtimeConfig;
    }

    private AiTextCallAuditService textAudit() {
        return new AiTextCallAuditService(mock(AiTextCallLogMapper.class));
    }

    private String startServer(List<CapturedRequest> captured) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, captured));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, List<CapturedRequest> captured) throws java.io.IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        captured.add(new CapturedRequest(
            exchange.getRequestHeaders().getFirst("Authorization"),
            json.readTree(body)
        ));
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if ("Bearer bad-key".equals(auth)) {
            write(exchange, 503, "{\"error\":{\"message\":\"Service temporarily unavailable\"}}");
            return;
        }
        write(exchange, 200, """
            {
              "choices": [
                {
                  "message": {
                    "content": "{\\"reply\\":\\"ok\\",\\"intent\\":\\"chat\\",\\"action\\":\\"none\\",\\"action_label\\":\\"\\",\\"action_href\\":\\"\\",\\"suggested_prompt\\":\\"\\",\\"confidence\\":0.9}"
                  }
                }
              ]
            }
            """);
    }

    private void write(HttpExchange exchange, int status, String responseBody) throws java.io.IOException {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedRequest(String authorization, JsonNode body) {}
}
