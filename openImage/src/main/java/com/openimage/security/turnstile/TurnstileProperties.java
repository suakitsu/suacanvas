package com.openimage.security.turnstile;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "openimage.turnstile")
public class TurnstileProperties {
    private boolean enabled = false;
    private String secretKey = "";
    private String verifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    private int timeoutSeconds = 5;
    private boolean billingEnabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecretKey() {
        return secretKey == null ? "" : secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getVerifyUrl() {
        return verifyUrl == null || verifyUrl.isBlank()
            ? "https://challenges.cloudflare.com/turnstile/v0/siteverify"
            : verifyUrl;
    }

    public void setVerifyUrl(String verifyUrl) {
        this.verifyUrl = verifyUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds <= 0 ? 5 : timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isBillingEnabled() {
        return billingEnabled;
    }

    public void setBillingEnabled(boolean billingEnabled) {
        this.billingEnabled = billingEnabled;
    }
}
