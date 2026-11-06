package com.openimage.ai.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiWorkerProtocolFixtureTest {
    @Test
    void fixedFixtureContainsOnlyNonProductionProtocolValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream input = getClass().getResourceAsStream(
            "/fixtures/local-worker-protocol-v1.json"
        )) {
            assertThat(input).isNotNull();
            JsonNode fixture = mapper.readTree(input);
            String token = fixture.path("workerToken").asText();

            assertThat(token)
                .startsWith("ppw_test_")
                .contains("not_a_production_token")
                .doesNotContain("Bearer ", "sk-");
            assertThat(fixture.path("imageModel").asText()).isEqualTo("gpt-image-2");
            assertThat(textValues(fixture.path("textModels")))
                .containsExactly(
                    "gpt-5.6-sol",
                    "gpt-5.5",
                    "gpt-5.6-terra",
                    "gpt-5.6-luna"
                );
            assertThat(textValues(fixture.path("protocols")))
                .containsExactlyInAnyOrderElementsOf(AiWorkerJobService.PROTOCOLS);
            assertThat(fixture.path("claim").path("workerId").asText())
                .isEqualTo("fixture-worker-01");
            assertThat(fixture.path("workerId").asText()).isEqualTo("fixture-worker-01");
        }
    }

    private List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }
}
