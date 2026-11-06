package com.openimage.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiImageResponseAdapterTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void findsAishuchSubmittedTaskId() throws Exception {
        JsonNode payload = json.readTree("""
            {
              "code": 200,
              "data": [
                {
                  "status": "submitted",
                  "task_id": "task_01KVC0RD7SQ45CB7P7Z5140EGF"
                }
              ]
            }
            """);

        assertEquals("task_01KVC0RD7SQ45CB7P7Z5140EGF", AiImageResponseAdapter.firstTaskId(payload).orElse(""));
        assertEquals("submitted", AiImageResponseAdapter.status(payload).orElse(""));
        assertTrue(AiImageResponseAdapter.isPendingStatus("submitted"));
    }

    @Test
    void findsAishuchCompletedUrlArray() throws Exception {
        JsonNode payload = json.readTree("""
            {
              "code": 200,
              "data": {
                "status": "completed",
                "cost": 0.021,
                "credits_cost": 0.21,
                "result": {
                  "images": [
                    {
                      "expires_at": 1781827622,
                      "url": [
                        "https://cdn.example.test/images/result.png?signature=redacted"
                      ]
                    }
                  ]
                }
              }
            }
            """);

        AiImageResponseAdapter.ImagePayload image = AiImageResponseAdapter.firstImage(payload).orElseThrow();

        assertEquals("url", image.type());
        assertEquals("https://cdn.example.test/images/result.png?signature=redacted", image.value());
        assertEquals(new BigDecimal("0.21"), AiImageResponseAdapter.creditsCost(payload).orElseThrow());
        assertEquals(new BigDecimal("0.021"), AiImageResponseAdapter.usdCost(payload).orElseThrow());
    }

    @Test
    void findsOpenAiCompatibleBase64() throws Exception {
        JsonNode payload = json.readTree("""
            {
              "created": 1781827622,
              "data": [
                {
                  "b64_json": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB"
                }
              ]
            }
            """);

        AiImageResponseAdapter.ImagePayload image = AiImageResponseAdapter.firstImage(payload).orElseThrow();

        assertTrue(image.base64());
        assertEquals("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB", image.value());
    }
}
