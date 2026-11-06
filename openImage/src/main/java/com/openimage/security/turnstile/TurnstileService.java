package com.openimage.security.turnstile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class TurnstileService {
    public static final String FAILURE_MESSAGE = "验证失败，请刷新后重试";
    private static final Logger log = LoggerFactory.getLogger(TurnstileService.class);

    private final TurnstileProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    public TurnstileService(TurnstileProperties properties, ObjectMapper json) {
        this(properties, json, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
            .build());
    }

    TurnstileService(TurnstileProperties properties, ObjectMapper json, HttpClient http) {
        this.properties = properties;
        this.json = json;
        this.http = http;
    }

    public void verify(String token, HttpServletRequest request) {
        verify(token, clientIp(request));
    }

    public void verifyBilling(String token, HttpServletRequest request) {
        if (!properties.isBillingEnabled()) {
            return;
        }
        verify(token, request);
    }

    public void verify(String token, String remoteIp) {
        if (!properties.isEnabled()) {
            return;
        }
        String secret = properties.getSecretKey().trim();
        String responseToken = token == null ? "" : token.trim();
        if (secret.isBlank() || responseToken.isBlank()) {
            throw rejected();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getVerifyUrl()))
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(secret, responseToken, remoteIp)))
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Turnstile verify returned non-success status: {}", response.statusCode());
                throw rejected();
            }
            JsonNode payload = json.readTree(response.body() == null ? "{}" : response.body());
            if (!payload.path("success").asBoolean(false)) {
                log.warn("Turnstile verify failed");
                throw rejected();
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log.warn("Turnstile verify interrupted");
            throw rejected();
        } catch (IOException | RuntimeException error) {
            log.warn("Turnstile verify request failed: {}", error.getClass().getSimpleName());
            throw rejected();
        }
    }

    private String formBody(String secret, String token, String remoteIp) {
        StringBuilder body = new StringBuilder()
            .append("secret=").append(encode(secret))
            .append("&response=").append(encode(token));
        String ip = remoteIp == null ? "" : remoteIp.trim();
        if (!ip.isBlank()) {
            body.append("&remoteip=").append(encode(ip));
        }
        return body.toString();
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private IllegalArgumentException rejected() {
        return new IllegalArgumentException(FAILURE_MESSAGE);
    }
}
