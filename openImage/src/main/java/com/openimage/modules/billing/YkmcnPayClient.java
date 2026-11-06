package com.openimage.modules.billing;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

@Service
public class YkmcnPayClient {
    private final WebClient webClient;

    public YkmcnPayClient(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    public String submitUrl(YkmcnConfig config, YkmcnOrder order) {
        Map<String, String> params = submitParams(config, order);
        String sign = sign(params, config.key());
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(config.baseUrlValue() + "/submit.php");
        params.forEach(url::queryParam);
        url.queryParam("sign", sign);
        url.queryParam("sign_type", "MD5");
        return url.build().encode().toUriString();
    }

    public Map<String, String> submitParams(YkmcnConfig config, YkmcnOrder order) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", config.pid());
        params.put("type", config.type());
        params.put("out_trade_no", order.orderId());
        params.put("notify_url", config.notifyUrl());
        params.put("return_url", config.returnUrl(order.orderId()));
        params.put("name", config.name(order.credits()));
        params.put("money", money(order.amountCents()));
        params.put("param", config.param(order.orderId()));
        return params;
    }

    public YkmcnGatewayOrder queryOrder(YkmcnConfig config, String orderId) {
        try {
            String url = UriComponentsBuilder.fromUriString(config.baseUrlValue() + "/api.php")
                .queryParam("act", "order")
                .queryParam("pid", config.pid())
                .queryParam("key", config.key())
                .queryParam("out_trade_no", orderId)
                .build()
                .encode()
                .toUriString();
            JsonNode response = webClient.get()
                .uri(URI.create(url))
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(config.timeoutSeconds()));
            if (response == null) {
                throw new IllegalArgumentException("支付服务无响应，请稍后重试");
            }
            return new YkmcnGatewayOrder(
                text(response, "trade_no"),
                text(response, "out_trade_no"),
                text(response, "money"),
                response.path("status").asInt(0),
                response.path("code").asInt(0),
                text(response, "msg"),
                response
            );
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("支付查单失败，请稍后重试");
        }
    }

    public boolean verifyNotify(Map<String, String> params, String key) {
        String actual = value(params, "sign");
        if (actual.isBlank()) {
            return false;
        }
        String expected = sign(params, key);
        return MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8), actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String sign(Map<String, String> params, String key) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || "sign".equals(name) || "sign_type".equals(name) || value == null || value.isBlank()) {
                continue;
            }
            sorted.put(name, value);
        }
        StringBuilder source = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (source.length() > 0) {
                source.append('&');
            }
            source.append(entry.getKey()).append('=').append(entry.getValue());
        }
        source.append(key == null ? "" : key);
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                hex.append(String.format("%02x", item & 0xff));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalArgumentException("支付签名失败");
        }
    }

    static String money(Integer amountCents) {
        int cents = amountCents == null ? 0 : amountCents;
        return BigDecimal.valueOf(cents, 2).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    static String value(Map<String, String> params, String name) {
        if (params == null || name == null) {
            return "";
        }
        String exact = params.get(name);
        if (exact != null) {
            return exact.trim();
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue() == null ? "" : entry.getValue().trim();
            }
        }
        return "";
    }

    public record YkmcnOrder(String orderId, Integer amountCents, Integer credits) {}

    public record YkmcnGatewayOrder(String tradeNo, String outTradeNo, String money, int status, int code, String message, JsonNode raw) {
        public boolean paid() {
            return status == 1;
        }

        public int amountCents() {
            try {
                return new BigDecimal(money).multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValueExact();
            } catch (Exception ignored) {
                return -1;
            }
        }
    }

    public record YkmcnConfig(
        String baseUrl,
        String pid,
        String key,
        String type,
        String notifyUrl,
        String returnUrlTemplate,
        String nameTemplate,
        String paramTemplate,
        String label,
        String instructions,
        int timeoutSeconds,
        int paymentWindowMinutes
    ) {
        public String baseUrlValue() {
            String value = baseUrl == null || baseUrl.isBlank() ? "https://pay.ykmcn.com" : baseUrl.trim();
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }

        public String returnUrl(String orderId) {
            return replace(returnUrlTemplate, orderId, "");
        }

        public String name(Integer credits) {
            String template = nameTemplate == null || nameTemplate.isBlank() ? "PicPilot {credits}积分" : nameTemplate;
            return replace(template, "", String.valueOf(credits == null ? 0 : credits));
        }

        public String param(String orderId) {
            return replace(paramTemplate == null ? "{order_id}" : paramTemplate, orderId, "");
        }

        public String hint(String orderId) {
            return replace(instructions, orderId, "");
        }

        public String labelValue() {
            return label == null || label.isBlank() ? "立即支付" : label.trim();
        }

        public boolean configured() {
            return pid != null && !pid.isBlank()
                && key != null && !key.isBlank()
                && notifyUrl != null && !notifyUrl.isBlank()
                && returnUrlTemplate != null && !returnUrlTemplate.isBlank();
        }

        private String replace(String value, String orderId, String credits) {
            return (value == null ? "" : value)
                .replace("{order_id}", orderId == null ? "" : orderId)
                .replace("{credits}", credits == null ? "" : credits);
        }
    }
}
