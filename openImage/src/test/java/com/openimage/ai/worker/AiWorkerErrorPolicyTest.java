package com.openimage.ai.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiWorkerErrorPolicyTest {

    @Test
    void treatsWorkerOutputSizeFailuresAsRetryable() {
        assertTrue(AiWorkerErrorPolicy.retryable(null, "OUTPUT_DIMENSION_MISMATCH"));
        assertTrue(AiWorkerErrorPolicy.retryable(null, "OUTPUT_ASPECT_RATIO_MISMATCH"));
    }

    @Test
    void keepsNonRetryableWorkerFailuresClosed() {
        assertFalse(AiWorkerErrorPolicy.retryable(null, "CONTENT_POLICY_REJECTED"));
    }
}
