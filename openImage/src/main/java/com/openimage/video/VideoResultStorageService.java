package com.openimage.video;

import com.openimage.storage.LocalStorageService;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Service
public class VideoResultStorageService {
    private static final long MAX_VIDEO_BYTES = 500L * 1024L * 1024L;

    private final LocalStorageService storage;
    private final HttpClient http;

    public VideoResultStorageService(LocalStorageService storage) {
        this.storage = storage;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public StoredVideo persist(String taskId, String upstreamUrl) throws Exception {
        URI uri = URI.create(upstreamUrl == null ? "" : upstreamUrl.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("视频上游返回了无效的结果地址");
        }

        Path temporary = Files.createTempFile("picpilot-video-", ".mp4");
        try {
            HttpResponse<Path> response = http.send(
                HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofFile(temporary)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("下载视频结果失败，HTTP " + response.statusCode());
            }
            long size = Files.size(temporary);
            if (size <= 0 || size > MAX_VIDEO_BYTES) {
                throw new IllegalStateException("视频结果文件大小异常");
            }
            String contentType = response.headers().firstValue("content-type")
                .map(value -> value.split(";", 2)[0].trim())
                .filter(value -> value.startsWith("video/"))
                .orElse("video/mp4");
            LocalStorageService.StoredFile stored = storage.saveGeneratedVideo(taskId, temporary, contentType);
            return new StoredVideo(stored.url(), stored.fileSize(), contentType);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record StoredVideo(String url, long fileSize, String contentType) {}
}
