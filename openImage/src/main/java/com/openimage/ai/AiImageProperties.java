package com.openimage.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "openimage.ai.image")
public record AiImageProperties(
    Boolean enabled,
    String provider,
    String baseUrl,
    String apiKey,
    String model,
    String size,
    String responseFormat,
    BigDecimal actualCost,
    String publicBaseUrl,
    Integer timeoutSeconds
) {
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled) && apiKey != null && !apiKey.isBlank();
    }

    public String providerName() {
        return blankToDefault(provider, "hfsyapi");
    }

    public String baseUrlValue() {
        return blankToDefault(baseUrl, "https://www.hfsyapi.cn");
    }

    public String modelValue() {
        return blankToDefault(model, "gpt-image-2");
    }

    public String sizeValue() {
        return blankToDefault(size, "1024x1024");
    }

    public String responseFormatValue() {
        return blankToDefault(responseFormat, "b64_json");
    }

    public BigDecimal actualCostValue() {
        return actualCost == null ? BigDecimal.ZERO : actualCost;
    }

    public int timeoutSecondsValue() {
        return timeoutSeconds == null || timeoutSeconds <= 0 ? 120 : timeoutSeconds;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
