package com.openimage.ai.worker;

import org.springframework.http.HttpStatus;

import java.util.Set;

public final class AiWorkerErrorPolicy {
    private static final Set<String> RETRYABLE_CODES = Set.of(
        "WORKER_HEARTBEAT_REQUIRED",
        "WORKER_INTERNAL_ERROR",
        "WORKER_WAIT_INTERRUPTED",
        "LOCAL_WORKER_OFFLINE",
        "ARTIFACT_READ_FAILED",
        "NETWORK_ERROR",
        "UPSTREAM_TIMEOUT",
        "UPSTREAM_RATE_LIMITED",
        "UPSTREAM_UNAVAILABLE",
        "WORKER_RUNTIME_ERROR",
        "OUTPUT_DIMENSION_MISMATCH",
        "OUTPUT_ASPECT_RATIO_MISMATCH"
    );

    private AiWorkerErrorPolicy() {
    }

    public static boolean retryable(HttpStatus status, String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        if (RETRYABLE_CODES.contains(normalized)
            || normalized.startsWith("UPSTREAM_5")
            || normalized.startsWith("NETWORK_")) {
            return true;
        }
        return status != null && (status.is5xxServerError()
            || status == HttpStatus.REQUEST_TIMEOUT
            || status == HttpStatus.TOO_MANY_REQUESTS);
    }
}
