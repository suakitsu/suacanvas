package com.openimage.ai.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openimage.ai-worker")
public record AiWorkerProperties(
    String publicBaseUrl,
    String tokenPepper,
    String signingSecret,
    Integer heartbeatOfflineSeconds,
    Integer inputUrlTtlSeconds,
    Long maxArtifactBytes,
    Integer leaseGraceSeconds
) {
    public String publicBaseUrlValue() {
        return publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    public String tokenPepperValue() {
        return tokenPepper == null ? "" : tokenPepper;
    }

    public String signingSecretValue() {
        return signingSecret == null ? "" : signingSecret;
    }

    public int heartbeatOfflineSecondsValue() {
        return clamp(heartbeatOfflineSeconds, 15, 300, 60);
    }

    public int inputUrlTtlSecondsValue() {
        return clamp(inputUrlTtlSeconds, 60, 1800, 300);
    }

    public long maxArtifactBytesValue() {
        long value = maxArtifactBytes == null ? 25L * 1024L * 1024L : maxArtifactBytes;
        return Math.max(1024L, Math.min(value, 50L * 1024L * 1024L));
    }

    public int leaseGraceSecondsValue() {
        return clamp(leaseGraceSeconds, 5, 120, 15);
    }

    private int clamp(Integer value, int min, int max, int fallback) {
        int resolved = value == null ? fallback : value;
        return Math.max(min, Math.min(max, resolved));
    }
}
