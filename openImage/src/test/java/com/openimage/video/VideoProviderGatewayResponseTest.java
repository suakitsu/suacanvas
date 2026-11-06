package com.openimage.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.VideoTaskEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VideoProviderGatewayResponseTest {
    private final ObjectMapper json = new ObjectMapper();
    private final VideoProviderGateway gateway =
        new VideoProviderGateway(new VideoProperties(), null, json);

    @Test
    void readsTaskIdFromDocumentedApimartDataArray() throws Exception {
        assertThat(gateway.apimartTaskId(json.readTree("""
            {
              "code": 200,
              "data": [
                {
                  "status": "submitted",
                  "task_id": "task_01KMCGF6BQGN3X28H3KSR50X5T"
                }
              ]
            }
            """))).isEqualTo("task_01KMCGF6BQGN3X28H3KSR50X5T");
    }

    @Test
    void keepsSupportingLegacyFlatTaskIdResponses() throws Exception {
        assertThat(gateway.apimartTaskId(json.readTree("""
            {"data":{"task_id":"task_legacy"}}
            """))).isEqualTo("task_legacy");
    }

    @Test
    void readsVideoUrlFromDocumentedApimartResultArray() throws Exception {
        assertThat(gateway.apimartResultUrl(json.readTree("""
            {
              "code": 200,
              "data": {
                "status": "completed",
                "result": {
                  "videos": [
                    {
                      "url": ["https://cdn.example.com/result.mp4"]
                    }
                  ]
                }
              }
            }
            """))).isEqualTo("https://cdn.example.com/result.mp4");
    }

    @Test
    void parsesDocumentedRootWebhookPayload() throws Exception {
        VideoProviderGateway.PollResult result = gateway.parseApimartPollResult(json.readTree("""
            {
              "id": "task_callback",
              "status": "completed",
              "progress": 100,
              "actual_time": 187,
              "cost": 0.528,
              "credits_cost": 5.28,
              "completed": 1785077934,
              "result": {
                "videos": [{
                  "url": ["https://cdn.example.com/callback.mp4"],
                  "expires_at": 1785164334
                }]
              }
            }
            """), 10);

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.resultUrl()).isEqualTo("https://cdn.example.com/callback.mp4");
        assertThat(result.metadata())
            .containsEntry("actual_cost", "3.585701")
            .containsEntry("upstream_cost", "5.28")
            .containsEntry("upstream_cost_unit", "APIMART_CREDIT")
            .containsEntry("cost_conversion_rate", "0.679110")
            .containsEntry("cost_source", "upstream_credits_cost")
            .containsEntry("upstream_credits_cost", "5.28")
            .containsEntry("upstream_actual_time_seconds", "187")
            .containsEntry("upstream_result_expires_at", "1785164334");
    }

    @Test
    void convertsApimartUsdCostWhenCreditsCostIsMissing() throws Exception {
        VideoProviderGateway.PollResult result = gateway.parseApimartPollResult(json.readTree("""
            {
              "status": "completed",
              "cost": 0.528,
              "result": {
                "videos": [{
                  "url": ["https://cdn.example.com/callback.mp4"]
                }]
              }
            }
            """), 10);

        assertThat(result.metadata())
            .containsEntry("actual_cost", "3.585701")
            .containsEntry("upstream_cost", "0.528")
            .containsEntry("upstream_cost_unit", "USD")
            .containsEntry("cost_conversion_rate", "6.791100")
            .containsEntry("cost_source", "upstream_cost");
    }

    @Test
    void buildsDocumentedSeedanceBodyForEveryConfiguredModel() {
        for (String model : List.of(
            "doubao-seedance-2.0-mini",
            "doubao-seedance-2.0-fast",
            "doubao-seedance-2.0"
        )) {
            VideoTaskEntity task = task(model);
            Map<String, Object> body = gateway.apimartRequestBody(
                task, "seedance", List.of("https://cdn.example.com/product.jpg")
            );

            assertThat(body)
                .containsEntry("model", model)
                .containsEntry("duration", 10)
                .containsEntry("resolution", "720p")
                .containsEntry("size", "9:16")
                .containsEntry("generate_audio", true);
            assertThat(body).doesNotContainKey("aspect_ratio");
        }
    }

    @Test
    void buildsDocumentedGeminiBodyWithoutSeedanceOnlyParameters() {
        VideoTaskEntity task = task("gemini-omni-flash-preview");
        Map<String, Object> body = gateway.apimartRequestBody(
            task, "gemini", List.of("https://cdn.example.com/product.jpg")
        );

        assertThat(body)
            .containsEntry("model", "gemini-omni-flash-preview")
            .containsEntry("aspect_ratio", "9:16");
        assertThat(body)
            .doesNotContainKeys("duration", "resolution", "size", "generate_audio");
    }

    @Test
    void addsWebhookBaseUrlThatApimartWillAppendCallbackTo() {
        VideoProperties properties = new VideoProperties();
        properties.setPublicBaseUrl("https://api.suakitsu.com/");
        properties.setWebhookSecret("callback-secret");
        VideoProviderGateway configured = new VideoProviderGateway(properties, null, json);

        Map<String, Object> body = configured.apimartRequestBody(
            task("gemini-omni-flash-preview"),
            "gemini",
            List.of("https://cdn.example.com/product.jpg")
        );

        assertThat(body).containsEntry(
            "webhook",
            "https://api.suakitsu.com/api/public/video-webhooks/apimart/callback-secret"
        );
    }

    @Test
    void buildsHfsyRequestFromTheFixedRouteWithoutApimartFields() {
        VideoTaskEntity task = task("sd-2.0-fast-480");
        task.setRequestedResolution("480p");
        Map<String, Object> body = gateway.hfsyRequestBody(
            task, List.of("https://cdn.example.com/product.jpg")
        );

        assertThat(body)
            .containsEntry("model", "sd-2.0-fast-480")
            .containsEntry("ratio", "9:16")
            .containsEntry("duration", 10)
            .containsEntry("size", "large")
            .containsEntry("watermark", false);
        assertThat(body).containsKey("images");
        assertThat(body).doesNotContainKeys("resolution", "image_urls", "generate_audio");
    }

    @Test
    void hfsyAcceptsMiniAndUsesItsReferenceSyntax() {
        VideoTaskEntity task = task("sd-2.0-mini");
        task.setMetadata("{\"generate_audio\":false}");

        Map<String, Object> body = gateway.hfsyRequestBody(
            task, List.of("https://cdn.example.com/product.jpg")
        );

        assertThat(body)
            .containsEntry("model", "sd-2.0-mini")
            .containsEntry("size", "large");
        assertThat(body).doesNotContainKey("generate_audio");
        assertThat(String.valueOf(body.get("prompt"))).contains("@图1");

        task.setRequestedResolution("480p");
        assertThat(gateway.hfsyRequestBody(
            task, List.of("https://cdn.example.com/product.jpg")
        ).get("size")).isEqualTo("small");
    }

    @Test
    void storyboardPromptAllowsPlannedCutsWhileSimplePromptStaysContinuous() {
        VideoTaskEntity storyboard = task("sd-2.0-fast");
        storyboard.setPrompt("镜头1：展示商品正面；镜头2：切换到使用场景");
        VideoTaskEntity single = task("sd-2.0-fast");
        single.setPrompt("商品在桌面上缓慢旋转");

        String storyboardPrompt = String.valueOf(
            gateway.hfsyRequestBody(storyboard, List.of("https://cdn.example.com/product.jpg")).get("prompt")
        );
        String singlePrompt = String.valueOf(
            gateway.hfsyRequestBody(single, List.of("https://cdn.example.com/product.jpg")).get("prompt")
        );

        assertThat(storyboardPrompt)
            .contains("Cuts and transitions are allowed")
            .doesNotContain("No cuts, no scene replacement");
        assertThat(singlePrompt)
            .contains("No cuts, no scene replacement")
            .doesNotContain("Cuts and transitions are allowed");
    }

    @Test
    void parsesHfsyTaskIdSuccessFailureAndFixedCallCost() throws Exception {
        assertThat(gateway.hfsyTaskId(json.readTree("""
            {"data":{"task_id":"hfsy-task-1","status":"QUEUED"}}
            """))).isEqualTo("hfsy-task-1");

        VideoTaskEntity task = task("sd-2.0-fast");
        VideoProviderGateway.PollResult completed = gateway.parseHfsyPollResult(json.readTree("""
            {"status":"SUCCESS","result_url":"https://cdn.example.com/hfsy.mp4","progress":100}
            """), task);
        VideoProviderGateway.PollResult failed = gateway.parseHfsyPollResult(json.readTree("""
            {"status":"FAILURE","fail_reason":"upstream rejected"}
            """), task);

        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.resultUrl()).isEqualTo("https://cdn.example.com/hfsy.mp4");
        assertThat(completed.metadata()).containsEntry("actual_cost", "3.000000");
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.error()).isEqualTo("upstream rejected");

        VideoTaskEntity mini = task("sd-2.0-mini");
        VideoProviderGateway.PollResult miniCompleted = gateway.parseHfsyPollResult(json.readTree("""
            {"status":"SUCCESS","result_url":"https://cdn.example.com/mini.mp4"}
            """), mini);
        assertThat(miniCompleted.metadata()).containsEntry("actual_cost", "1.200000");
    }

    private VideoTaskEntity task(String model) {
        VideoTaskEntity task = new VideoTaskEntity();
        task.setModel(model);
        task.setPrompt("Make a product video");
        task.setAspectRatio("9:16");
        task.setDurationSeconds(10);
        task.setRequestedResolution("720p");
        task.setMetadata("{}");
        return task;
    }
}
