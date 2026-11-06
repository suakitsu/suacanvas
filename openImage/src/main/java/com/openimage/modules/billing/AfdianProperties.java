package com.openimage.modules.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openimage.payments.afdian")
public class AfdianProperties {
    private boolean enabled;
    private String userId = "";
    private String apiToken = "";
    private String baseUrl = "https://www.ifdian.net";
    private String webhookSecret = "";
    private long webhookMaxSkewSeconds = 300;

    public boolean isEnabled() {
        return enabled && !blank(userId) && !blank(apiToken);
    }

    public boolean rawEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUserId() {
        return userId == null ? "" : userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getApiToken() {
        return apiToken == null ? "" : apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public String baseUrlValue() {
        if (blank(baseUrl)) {
            return "https://www.ifdian.net";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getWebhookSecret() {
        return webhookSecret == null ? "" : webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public long getWebhookMaxSkewSeconds() {
        return webhookMaxSkewSeconds <= 0 ? 300 : webhookMaxSkewSeconds;
    }

    public void setWebhookMaxSkewSeconds(long webhookMaxSkewSeconds) {
        this.webhookMaxSkewSeconds = webhookMaxSkewSeconds;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
