package com.openimage.ai.worker;

import com.openimage.storage.ImageUploadNormalizer;
import com.openimage.storage.LocalStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CanvasWorkerInputService {
    private static final Pattern DATA_IMAGE = Pattern.compile(
        "(?is)^data:(image/(?:jpeg|jpg|png|webp));base64,([a-z0-9+/=\\r\\n]+)$"
    );
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9._-]{1,180}");

    private final LocalStorageService storage;
    private final ImageUploadNormalizer normalizer;
    private final AiWorkerProperties properties;

    public CanvasWorkerInputService(
        LocalStorageService storage,
        ImageUploadNormalizer normalizer,
        AiWorkerProperties properties
    ) {
        this.storage = storage;
        this.normalizer = normalizer;
        this.properties = properties;
    }

    public Map<String, Object> externalize(Map<String, Object> source, String businessTaskId) {
        String scope = "canvas_text_" + sha256(
            (businessTaskId == null ? "" : businessTaskId).getBytes(StandardCharsets.UTF_8)
        ).substring(0, 32);
        Object transformed = transform(source, scope, new AtomicInteger());
        if (!(transformed instanceof Map<?, ?> map)) {
            throw new AiWorkerProviderException("CANVAS_INPUT_INVALID", "画布视觉文案请求格式无效");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    public LocalStorageService.PublicFile open(
        String scope,
        String filename,
        long expires,
        String signature
    ) throws Exception {
        if (!safe(scope) || !safe(filename)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        long now = Instant.now().getEpochSecond();
        int ttl = properties.inputUrlTtlSecondsValue();
        if (expires < now) {
            throw new ResponseStatusException(HttpStatus.GONE, "Canvas input URL expired");
        }
        if (expires > now + (long) ttl * 3L) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Canvas input expiry is invalid");
        }
        String expected = signature(scope, filename, expires);
        if (!constantEquals(expected, signature)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Canvas input signature is invalid");
        }
        LocalStorageService.PublicFile file = storage.publicProjectFile(scope, filename);
        if (file == null || file.resource() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return file;
    }

    private Object transform(Object value, String scope, AtomicInteger imageIndex) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object next = "image_url".equals(key)
                    ? transformImageValue(entry.getValue(), scope, imageIndex)
                    : transform(entry.getValue(), scope, imageIndex);
                result.put(key, next);
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(transform(item, scope, imageIndex));
            return result;
        }
        return value;
    }

    private Object transformImageValue(Object value, String scope, AtomicInteger imageIndex) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object next = "url".equals(key)
                    ? externalizeDataUrl(String.valueOf(entry.getValue()), scope, imageIndex)
                    : transform(entry.getValue(), scope, imageIndex);
                result.put(key, next);
            }
            return result;
        }
        if (value instanceof String text) {
            return externalizeDataUrl(text, scope, imageIndex);
        }
        return value;
    }

    private String externalizeDataUrl(String value, String scope, AtomicInteger imageIndex) {
        Matcher matcher = DATA_IMAGE.matcher(value == null ? "" : value);
        if (!matcher.matches()) return value;
        try {
            String mime = "image/jpg".equalsIgnoreCase(matcher.group(1)) ? "image/jpeg" : matcher.group(1).toLowerCase();
            byte[] source = Base64.getMimeDecoder().decode(matcher.group(2));
            int index = imageIndex.getAndIncrement();
            String extension = "image/jpeg".equals(mime) ? ".jpg" : "image/webp".equals(mime) ? ".webp" : ".png";
            ImageUploadNormalizer.NormalizedImage image = normalizer.normalize(
                source,
                "canvas-input-" + (index + 1) + extension,
                mime,
                ImageUploadNormalizer.DEFAULT_MAX_BYTES,
                ImageUploadNormalizer.DEFAULT_MAX_PIXELS
            );
            String fileId = "input_" + index + "_" + sha256(image.bytes()).substring(0, 16);
            storage.saveProjectImage("", scope, fileId, image);
            String filename = fileId + image.extension();
            long expires = stableExpiry();
            return publicBaseUrl()
                + "/api/public/canvas-worker-inputs/" + scope + "/" + filename
                + "?expires=" + expires
                + "&signature=" + signature(scope, filename, expires);
        } catch (AiWorkerProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiWorkerProviderException("CANVAS_INPUT_INVALID", "画布视觉输入图片无法安全转换");
        }
    }

    private long stableExpiry() {
        long now = Instant.now().getEpochSecond();
        int ttl = properties.inputUrlTtlSecondsValue();
        return ((now / ttl) + 2L) * ttl;
    }

    private String publicBaseUrl() {
        String base = properties.publicBaseUrlValue();
        try {
            URI uri = URI.create(base);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) return base;
        } catch (Exception ignored) {
            // Converted to a provider-safe configuration error below.
        }
        throw new AiWorkerProviderException("WORKER_PUBLIC_HTTPS_REQUIRED", "AI Worker 公网地址必须配置为 HTTPS");
    }

    private String signature(String scope, String filename, long expires) {
        String secret = properties.signingSecretValue();
        if (secret.length() < 32) {
            throw new AiWorkerProviderException("WORKER_SIGNING_SECRET_INVALID", "AI Worker 签名配置无效");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                (scope + "\n" + filename + "\n" + expires).getBytes(StandardCharsets.UTF_8)
            ));
        } catch (Exception exception) {
            throw new IllegalStateException("Canvas input signature failed", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean safe(String value) {
        return value != null && SAFE_PATH.matcher(value).matches();
    }

    private boolean constantEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(
            left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8)
        );
    }
}
