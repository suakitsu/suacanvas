package com.openimage.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VideoWebhookControllerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsDocumentedRootPayloadAndDispatchesQuickly() throws Exception {
        VideoProperties properties = new VideoProperties();
        properties.setWebhookSecret("callback-secret");
        VideoTaskWorker worker = mock(VideoTaskWorker.class);
        VideoWebhookController controller = new VideoWebhookController(
            properties, worker, json, Runnable::run
        );

        ApiResponse<Map<String, Object>> response = controller.apimartCallback(
            "callback-secret",
            """
                {
                  "id": "task_callback",
                  "status": "completed",
                  "result": {"videos": [{"url": ["https://cdn.example.com/result.mp4"]}]}
                }
                """
        );

        assertThat(response.success()).isTrue();
        assertThat(response.data()).containsEntry("task_id", "task_callback");
        verify(worker).acceptApimartCallback(eq("task_callback"), any(JsonNode.class));
    }

    @Test
    void rejectsForgedCallbackPathToken() {
        VideoProperties properties = new VideoProperties();
        properties.setWebhookSecret("callback-secret");
        VideoWebhookController controller = new VideoWebhookController(
            properties, mock(VideoTaskWorker.class), json, Runnable::run
        );

        assertThatThrownBy(() -> controller.apimartCallback("wrong", "{}"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("401");
    }
}
