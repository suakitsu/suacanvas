package com.openimage.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.common.ApiResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/public/video-webhooks")
public class VideoWebhookController {
    private final VideoProperties properties;
    private final VideoTaskWorker worker;
    private final ObjectMapper json;
    private final TaskExecutor taskExecutor;

    public VideoWebhookController(
        VideoProperties properties,
        VideoTaskWorker worker,
        ObjectMapper json,
        @Qualifier("videoWebhookTaskExecutor") TaskExecutor taskExecutor
    ) {
        this.properties = properties;
        this.worker = worker;
        this.json = json;
        this.taskExecutor = taskExecutor;
    }

    @PostMapping("/apimart")
    public ApiResponse<Map<String, Object>> apimart(
        @RequestHeader(name = "X-Webhook-Secret", required = false) String secret,
        @RequestBody String body
    ) throws Exception {
        return receive(secret, body);
    }

    @PostMapping("/apimart/{secret}/callback")
    public ApiResponse<Map<String, Object>> apimartCallback(
        @PathVariable String secret,
        @RequestBody String body
    ) throws Exception {
        return receive(secret, body);
    }

    private ApiResponse<Map<String, Object>> receive(String secret, String body) throws Exception {
        String expected = properties.getWebhookSecret() == null ? "" : properties.getWebhookSecret().trim();
        if (expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "视频 Webhook 尚未启用");
        }
        if (secret == null || !constantTimeEquals(expected, secret.trim())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook 签名无效");
        }
        JsonNode root = json.readTree(body == null || body.isBlank() ? "{}" : body);
        String taskId = text(root, "task_id", "data.task_id", "data.0.task_id", "id", "data.id", "data.0.id");
        if (!taskId.isBlank()) {
            taskExecutor.execute(() -> worker.acceptApimartCallback(taskId, root));
        }
        return ApiResponse.ok(Map.of("received", true, "task_id", taskId));
    }

    private String text(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode current = root;
            for (String part : path.split("\\.")) {
                if (current == null) break;
                if (current.isArray()) {
                    try { current = current.get(Integer.parseInt(part)); }
                    catch (NumberFormatException ignored) { current = null; }
                } else {
                    current = current.get(part);
                }
            }
            if (current != null && !current.isContainerNode()) {
                String value = current.asText("").trim();
                if (!value.isBlank()) return value;
            }
        }
        return "";
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left.length() != right.length()) return false;
        int diff = 0;
        for (int i = 0; i < left.length(); i++) diff |= left.charAt(i) ^ right.charAt(i);
        return diff == 0;
    }
}
