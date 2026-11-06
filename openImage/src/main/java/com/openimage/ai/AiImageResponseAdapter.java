package com.openimage.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class AiImageResponseAdapter {
    private static final Set<String> BASE64_KEYS = Set.of(
        "b64json",
        "b64data",
        "base64",
        "imagebase64",
        "base64image",
        "imageb64"
    );
    private static final Set<String> URL_KEYS = Set.of(
        "url",
        "urls",
        "uri",
        "imageurl",
        "imageurls",
        "image",
        "images",
        "output",
        "outputs",
        "outputurl",
        "outputurls",
        "result",
        "resulturl",
        "resulturls",
        "downloadurl",
        "downloadurls",
        "signedurl",
        "fileurl"
    );
    private static final Set<String> TASK_KEYS = Set.of(
        "taskid",
        "jobid",
        "generationid",
        "predictionid",
        "requestid"
    );
    private static final Set<String> PENDING_STATUSES = Set.of(
        "submitted",
        "pending",
        "queued",
        "inprogress",
        "processing",
        "running",
        "starting",
        "waiting",
        "created"
    );
    private static final Set<String> FAILURE_STATUSES = Set.of(
        "failure",
        "failed",
        "error",
        "errored",
        "cancelled",
        "canceled",
        "timeout",
        "expired"
    );

    private AiImageResponseAdapter() {
    }

    static Optional<ImagePayload> firstImage(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return Optional.empty();
        }
        return firstImage(root, "");
    }

    static Optional<String> firstTaskId(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return Optional.empty();
        }
        return firstTaskId(root, "");
    }

    static Optional<String> status(JsonNode root) {
        return firstText(root, "status");
    }

    static Optional<BigDecimal> creditsCost(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return Optional.empty();
        }
        for (JsonNode candidate : new JsonNode[] {
            root.path("data").path("credits_cost"),
            root.path("data").path("metadata").path("credits_cost"),
            root.path("metadata").path("credits_cost"),
            root.path("credits_cost")
        }) {
            Optional<BigDecimal> parsed = decimal(candidate);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }

    static Optional<BigDecimal> usdCost(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return Optional.empty();
        }
        for (JsonNode candidate : new JsonNode[] {
            root.path("data").path("cost"),
            root.path("data").path("metadata").path("cost"),
            root.path("metadata").path("cost"),
            root.path("cost")
        }) {
            Optional<BigDecimal> parsed = decimal(candidate);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }

    static boolean hasFailure(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return false;
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull() && !error.isMissingNode()) {
            return true;
        }
        JsonNode code = root.get("code");
        if (code != null && code.isNumber()) {
            int value = code.asInt();
            if (value != 0 && value != 200) {
                return true;
            }
        }
        return status(root)
            .map(AiImageResponseAdapter::normalize)
            .map(FAILURE_STATUSES::contains)
            .orElse(false);
    }

    static boolean isPendingStatus(String value) {
        return PENDING_STATUSES.contains(normalize(value));
    }

    static boolean isAishuch(String provider, String baseUrl) {
        String text = ((provider == null ? "" : provider) + " " + (baseUrl == null ? "" : baseUrl)).toLowerCase(Locale.ROOT);
        return text.contains("aishuch") || text.contains("apimart");
    }

    static boolean isRuaChat(String provider, String baseUrl) {
        String text = ((provider == null ? "" : provider) + " " + (baseUrl == null ? "" : baseUrl)).toLowerCase(Locale.ROOT);
        return text.contains("rua") || text.contains("rua.chat");
    }

    static boolean isXinZhiZhu(String provider, String baseUrl) {
        String text = ((provider == null ? "" : provider) + " " + (baseUrl == null ? "" : baseUrl)).toLowerCase(Locale.ROOT);
        return text.contains("xin-zhi-zhu") || text.contains("xinzhi") || text.contains("xinzhizhu");
    }

    static String pathSegment(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String failureSummary(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return "";
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull() && !error.isMissingNode()) {
            return error.toString();
        }
        Optional<String> message = firstText(root, "message");
        if (message.isPresent()) {
            return message.get();
        }
        return root.toString();
    }

    private static Optional<ImagePayload> firstImage(JsonNode node, String keyName) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        String normalizedKey = normalize(keyName);
        if (node.isTextual()) {
            String value = node.asText("").trim();
            if (value.isBlank()) {
                return Optional.empty();
            }
            if (BASE64_KEYS.contains(normalizedKey) || value.startsWith("data:image/") || looksLikeBase64(value)) {
                return Optional.of(new ImagePayload("base64", value));
            }
            if (URL_KEYS.contains(normalizedKey) || looksLikeUrl(value)) {
                if (looksLikeUrl(value)) {
                    return Optional.of(new ImagePayload("url", value));
                }
            }
            return Optional.empty();
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                Optional<ImagePayload> found = firstImage(item, keyName);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (!node.isObject()) {
            return Optional.empty();
        }

        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            String normalized = normalize(field);
            if (BASE64_KEYS.contains(normalized) || URL_KEYS.contains(normalized)) {
                Optional<ImagePayload> found = firstImage(node.get(field), field);
                if (found.isPresent()) {
                    return found;
                }
            }
        }

        fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            Optional<ImagePayload> found = firstImage(node.get(field), field);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstTaskId(JsonNode node, String keyName) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        String normalizedKey = normalize(keyName);
        if (node.isTextual()) {
            String value = node.asText("").trim();
            if (value.isBlank()) {
                return Optional.empty();
            }
            if (TASK_KEYS.contains(normalizedKey) || ("id".equals(normalizedKey) && isTaskLike(value))) {
                return Optional.of(value);
            }
            return Optional.empty();
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                Optional<String> found = firstTaskId(item, keyName);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (!node.isObject()) {
            return Optional.empty();
        }
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            String normalized = normalize(field);
            if (TASK_KEYS.contains(normalized) || "id".equals(normalized)) {
                Optional<String> found = firstTaskId(node.get(field), field);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            Optional<String> found = firstTaskId(node.get(field), field);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstText(JsonNode node, String keyName) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        String normalizedTarget = normalize(keyName);
        if (node.isObject()) {
            Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                JsonNode child = node.get(field);
                if (normalize(field).equals(normalizedTarget) && child != null && child.isTextual() && !child.asText("").isBlank()) {
                    return Optional.of(child.asText("").trim());
                }
            }
            fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                Optional<String> found = firstText(node.get(fieldNames.next()), keyName);
                if (found.isPresent()) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<String> found = firstText(child, keyName);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static boolean looksLikeUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean looksLikeBase64(String value) {
        if (value.length() < 120 || value.contains(" ") || value.contains("\n") || value.contains("\r")) {
            return false;
        }
        return value.matches("^[A-Za-z0-9+/=_-]+$");
    }

    private static boolean isTaskLike(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("task_") || lower.startsWith("job_") || lower.startsWith("pred_");
    }

    private static Optional<BigDecimal> decimal(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return Optional.empty();
        }
        String text = value.isNumber() || value.isTextual() ? value.asText("").trim() : "";
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            BigDecimal parsed = new BigDecimal(text);
            return parsed.signum() >= 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    record ImagePayload(String type, String value) {
        boolean base64() {
            return "base64".equals(type);
        }
    }
}
