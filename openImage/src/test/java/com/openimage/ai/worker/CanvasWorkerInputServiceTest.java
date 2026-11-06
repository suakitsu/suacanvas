package com.openimage.ai.worker;

import com.openimage.storage.ImageUploadNormalizer;
import com.openimage.storage.ImageUploadException;
import com.openimage.storage.LocalStorageService;
import com.openimage.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanvasWorkerInputServiceTest {
    private static final String SIGNING_SECRET = "canvas-worker-input-test-signing-secret-0001";

    @Test
    void replacesInlineImageWithStableSignedHttpsUrl(@TempDir Path directory) throws Exception {
        CanvasWorkerInputService service = service(directory);
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png());
        Map<String, Object> source = Map.of("messages", List.of(Map.of(
            "role", "user",
            "content", List.of(
                Map.of("type", "text", "text", "描述商品图"),
                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl, "detail", "high"))
            )
        )));

        Map<String, Object> first = service.externalize(source, "canvas-reverse-fixture");
        Map<String, Object> second = service.externalize(source, "canvas-reverse-fixture");
        String signedUrl = imageUrl(first);

        assertThat(signedUrl).startsWith("https://suakitsu.test/api/public/canvas-worker-inputs/");
        assertThat(signedUrl).doesNotContain("data:image", dataUrl.substring(dataUrl.indexOf(',') + 1));
        assertThat(imageUrl(second)).isEqualTo(signedUrl);
        assertThat(source.toString()).contains("data:image/png;base64");

        URI uri = URI.create(signedUrl);
        String[] path = uri.getPath().split("/");
        Map<String, String> query = query(uri.getRawQuery());
        LocalStorageService.PublicFile file = service.open(
            path[path.length - 2],
            path[path.length - 1],
            Long.parseLong(query.get("expires")),
            query.get("signature")
        );
        assertThat(file.contentType()).isEqualTo("image/png");
        assertThat(file.resource().contentLength()).isGreaterThan(0);
    }

    @Test
    void rejectsExpiredOrTamperedSignedUrl(@TempDir Path directory) {
        CanvasWorkerInputService service = service(directory);

        assertThatThrownBy(() -> service.open("canvas_text_fixture", "input.png", Instant.now().getEpochSecond() - 1, "bad"))
            .hasMessageContaining("expired");
        assertThatThrownBy(() -> service.open("canvas_text_fixture", "input.png", Instant.now().getEpochSecond() + 60, "bad"))
            .hasMessageContaining("signature");
    }

    @SuppressWarnings("unchecked")
    private String imageUrl(Map<String, Object> request) {
        List<Map<String, Object>> messages = (List<Map<String, Object>>) request.get("messages");
        List<Map<String, Object>> content = (List<Map<String, Object>>) messages.get(0).get("content");
        Map<String, Object> image = (Map<String, Object>) content.get(1).get("image_url");
        return String.valueOf(image.get("url"));
    }

    private CanvasWorkerInputService service(Path directory) {
        ImageUploadNormalizer normalizer = new ImageUploadNormalizer((source, maxPixels) -> {
            throw new ImageUploadException("IMAGE_CORRUPT", "HEIC is not expected in this fixture");
        });
        StorageProperties storageProperties = new StorageProperties(
            "local", directory.toString(), "", "", "", "", "", "", true, false
        );
        LocalStorageService storage = new LocalStorageService(storageProperties, normalizer);
        AiWorkerProperties workerProperties = new AiWorkerProperties(
            "https://suakitsu.test",
            "fixture-token-pepper-not-used-here",
            SIGNING_SECRET,
            60,
            300,
            25L * 1024L * 1024L,
            15
        );
        return new CanvasWorkerInputService(storage, normalizer, workerProperties);
    }

    private Map<String, String> query(String value) {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        for (String part : value.split("&")) {
            String[] pair = part.split("=", 2);
            result.put(
                URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                URLDecoder.decode(pair.length > 1 ? pair[1] : "", StandardCharsets.UTF_8)
            );
        }
        return result;
    }

    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, new Color(31, 92, 210).getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
