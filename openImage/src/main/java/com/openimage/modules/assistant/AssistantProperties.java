package com.openimage.modules.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "openimage.assistant")
public class AssistantProperties {
    private boolean enabled = false;
    private String baseUrl = "https://api.openai.com";
    private String apiKey = "";
    private String apiKeys = "";
    private String model = "gpt-5.5";
    private int maxInputChars = 3000;
    private int maxHistoryMessages = 12;
    private int maxTokens = 1400;
    private double temperature = 0.35;
    private int timeoutSeconds = 45;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(String apiKeys) {
        this.apiKeys = apiKeys;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxInputChars() {
        return maxInputChars;
    }

    public void setMaxInputChars(int maxInputChars) {
        this.maxInputChars = maxInputChars;
    }

    public int getMaxHistoryMessages() {
        return maxHistoryMessages;
    }

    public void setMaxHistoryMessages(int maxHistoryMessages) {
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean enabledAndConfigured() {
        return enabled && ((apiKey != null && !apiKey.isBlank()) || (apiKeys != null && !apiKeys.isBlank()));
    }

    public String normalizedBaseUrl() {
        String value = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com" : baseUrl.trim();
        return value.replaceAll("/+$", "");
    }
}
