package com.openimage.modules.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@EnableConfigurationProperties(AfdianProperties.class)
public class AfdianClient {
    private final AfdianProperties properties;
    private final ObjectMapper json;
    private final WebClient webClient;

    public AfdianClient(AfdianProperties properties, ObjectMapper json, WebClient.Builder builder) {
        this.properties = properties;
        this.json = json;
        this.webClient = builder.build();
    }

    public boolean configured() {
        return properties.isEnabled();
    }

    public List<AfdianOrder> queryRecentOrders(int maxPages) {
        List<AfdianOrder> rows = new ArrayList<>();
        int pages = Math.max(1, Math.min(maxPages, 5));
        for (int page = 1; page <= pages; page++) {
            JsonNode data = request("/api/open/query-order", Map.of("page", page));
            JsonNode list = data == null ? null : data.get("list");
            if (list != null && list.isArray()) {
                list.forEach(item -> rows.add(toOrder(item)));
            }
            int totalPage = data == null ? 1 : data.path("total_page").asInt(1);
            if (page >= totalPage) {
                break;
            }
        }
        return rows;
    }

    public AfdianOrder queryOrder(String outTradeNo) {
        if (outTradeNo == null || outTradeNo.isBlank()) {
            return null;
        }
        JsonNode data = request("/api/open/query-order", Map.of("out_trade_no", outTradeNo.trim()));
        JsonNode list = data == null ? null : data.get("list");
        if (list == null || !list.isArray() || list.size() == 0) {
            return null;
        }
        return toOrder(list.get(0));
    }

    private JsonNode request(String path, Map<String, Object> params) {
        if (!configured()) {
            throw new IllegalArgumentException("爱发电自动同步未配置，请等待管理员确认");
        }
        try {
            String paramsJson = json.writeValueAsString(params);
            long ts = System.currentTimeMillis() / 1000;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("user_id", properties.getUserId());
            body.put("params", paramsJson);
            body.put("ts", ts);
            body.put("sign", sign(paramsJson, ts));
            JsonNode response = webClient.post()
                .uri(URI.create(properties.baseUrlValue() + path))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(15));
            if (response == null) {
                throw new IllegalArgumentException("爱发电无响应，请稍后重试");
            }
            if (response.path("ec").asInt() != 200) {
                throw new IllegalArgumentException("爱发电查询失败：" + response.path("em").asText("unknown"));
            }
            return response.get("data");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("爱发电查询失败，请稍后重试");
        }
    }

    private String sign(String paramsJson, long ts) throws Exception {
        String value = properties.getApiToken() + "params" + paramsJson + "ts" + ts + "user_id" + properties.getUserId();
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            builder.append(String.format("%02x", item & 0xff));
        }
        return builder.toString();
    }

    private AfdianOrder toOrder(JsonNode node) {
        return new AfdianOrder(
            text(node, "out_trade_no"),
            text(node, "custom_order_id"),
            text(node, "remark"),
            text(node, "total_amount"),
            node.path("status").asInt(0),
            text(node, "user_id"),
            node
        );
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    public record AfdianOrder(String outTradeNo, String customOrderId, String remark, String totalAmount, int status, String userId, JsonNode raw) {
        public boolean paid() {
            return status == 2;
        }

        public int totalAmountCents() {
            try {
                return new BigDecimal(totalAmount).multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValueExact();
            } catch (Exception ignored) {
                return -1;
            }
        }
    }
}
