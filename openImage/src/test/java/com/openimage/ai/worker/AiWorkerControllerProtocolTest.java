package com.openimage.ai.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiWorkerControllerProtocolTest {
    @Test
    void claimReturnsExplicitEmptyEnvelopeAfterLongPoll() throws Exception {
        AiWorkerJobService jobs = mock(AiWorkerJobService.class);
        ObjectMapper mapper = new ObjectMapper();
        AiWorkerController controller = new AiWorkerController(jobs, mapper);
        AiWorkerPrincipal principal = new AiWorkerPrincipal("awt-test", "fixture", "ppw_test");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AiWorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE, principal);
        when(jobs.claim(principal, "worker-test", List.of("image", "text"), 1, 25))
            .thenReturn(null);

        Map<String, Object> response = controller.claim(
            request,
            mapper.readTree("""
                {
                  "workerId": "worker-test",
                  "capabilities": ["image", "text"],
                  "maxTasks": 1,
                  "waitSeconds": 25
                }
                """)
        );

        assertThat(response)
            .containsEntry("empty", true)
            .containsKey("task")
            .containsKeys("serverTime", "waitedMs");
        assertThat(response.get("task")).isEqualTo(mapper.nullNode());
        ObjectMapper productionMapper = mapper.copy()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        assertThat(productionMapper.writeValueAsString(response))
            .contains("\"task\":null");
        verify(jobs).claim(principal, "worker-test", List.of("image", "text"), 1, 25);
    }

    @Test
    void claimRejectsUnknownEnvelopeFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AiWorkerController controller = new AiWorkerController(
            mock(AiWorkerJobService.class),
            mapper
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
            AiWorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
            new AiWorkerPrincipal("awt-test", "fixture", "ppw_test")
        );

        assertThatThrownBy(() -> controller.claim(
            request,
            mapper.readTree("""
                {
                  "workerId": "worker-test",
                  "capabilities": ["image"],
                  "maxTasks": 1,
                  "waitSeconds": 25,
                  "localApiKey": "must-not-be-accepted"
                }
                """)
        ))
            .isInstanceOf(AiWorkerProtocolException.class)
            .extracting(exception -> ((AiWorkerProtocolException) exception).code())
            .isEqualTo("REQUEST_SCHEMA_INVALID");
    }

    @Test
    void claimRejectsLegacySnakeCaseFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AiWorkerController controller = new AiWorkerController(
            mock(AiWorkerJobService.class),
            mapper
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
            AiWorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
            new AiWorkerPrincipal("awt-test", "fixture", "ppw_test")
        );

        assertThatThrownBy(() -> controller.claim(
            request,
            mapper.readTree("""
                {
                  "worker_id": "worker-test",
                  "capabilities": ["image"],
                  "max_tasks": 1,
                  "wait_seconds": 25
                }
                """)
        ))
            .isInstanceOf(AiWorkerProtocolException.class)
            .extracting(exception -> ((AiWorkerProtocolException) exception).code())
            .isEqualTo("REQUEST_SCHEMA_INVALID");
    }

    @Test
    void claimRejectsDuplicateCapabilities() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AiWorkerController controller = new AiWorkerController(
            mock(AiWorkerJobService.class),
            mapper
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
            AiWorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
            new AiWorkerPrincipal("awt-test", "fixture", "ppw_test")
        );

        assertThatThrownBy(() -> controller.claim(
            request,
            mapper.readTree("""
                {
                  "workerId": "worker-test",
                  "capabilities": ["image", "image"],
                  "maxTasks": 1,
                  "waitSeconds": 25
                }
                """)
        ))
            .isInstanceOf(AiWorkerProtocolException.class)
            .extracting(exception -> ((AiWorkerProtocolException) exception).code())
            .isEqualTo("REQUEST_SCHEMA_INVALID");
    }

    @Test
    void protocolUsesCamelCaseDespiteApplicationSnakeCaseSetting() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        AiWorkerController.ClaimRequest request = mapper.readValue(
            """
                {
                  "workerId": "worker-test",
                  "capabilities": ["image"],
                  "maxTasks": 1,
                  "waitSeconds": 25
                }
                """,
            AiWorkerController.ClaimRequest.class
        );
        assertThat(request.workerId()).isEqualTo("worker-test");
        assertThat(request.maxTasks()).isEqualTo(1);
        assertThat(request.waitSeconds()).isEqualTo(25);

        OffsetDateTime now = OffsetDateTime.parse("2026-07-31T12:00:00Z");
        AiWorkerJobService.ClaimedJob job = new AiWorkerJobService.ClaimedJob(
            "awj_fixture",
            "awl_fixture",
            now.plusSeconds(120),
            now.plusSeconds(80),
            now.plusSeconds(135),
            "image",
            "openai_images_generations",
            "gpt-image-2",
            Map.of("model", "gpt-image-2", "prompt", "fixture"),
            List.of(),
            1
        );
        String json = mapper.writeValueAsString(job);

        assertThat(json)
            .contains("\"jobId\"")
            .contains("\"leaseId\"")
            .contains("\"leaseUntil\"")
            .contains("\"renewAfter\"")
            .contains("\"graceUntil\"")
            .contains("\"inputFiles\"")
            .doesNotContain("\"lease_token\"", "\"lease_id\"");
    }
}
