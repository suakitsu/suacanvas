package com.openimage.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@EnableConfigurationProperties(StorageProperties.class)
public class LocalStorageService {
    private final StorageProperties properties;
    private final ImageUploadNormalizer imageNormalizer;
    private final S3Client s3;
    private volatile boolean bucketReady = false;

    public LocalStorageService(StorageProperties properties, ImageUploadNormalizer imageNormalizer) {
        this.properties = properties;
        this.imageNormalizer = imageNormalizer;
        this.s3 = properties.minioEnabled() ? S3Client.builder()
            .endpointOverride(URI.create(properties.minioEndpoint()))
            .region(Region.of(properties.regionValue()))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.minioAccessKey(), properties.minioSecretKey())))
            .forcePathStyle(properties.pathStyleAccess())
            .build() : null;
    }

    public StoredFile saveProjectImage(String userId, String projectId, String fileId, MultipartFile file) throws IOException {
        return saveProjectImage(userId, projectId, fileId, imageNormalizer.normalize(file));
    }

    public StoredFile saveProjectImageForJpegOrPng(
        String userId,
        String projectId,
        String fileId,
        MultipartFile file
    ) throws IOException {
        return saveProjectImage(userId, projectId, fileId, imageNormalizer.normalizeForJpegOrPng(file));
    }

    public StoredFile saveProjectImage(String userId, String projectId, String fileId, ImageUploadNormalizer.NormalizedImage image) throws IOException {
        String filename = fileId + image.extension();
        if (s3 != null) {
            String key = projectKey(projectId, filename);
            putObject(key, image.bytes(), image.contentType());
            return stored(key, publicUrl(key), image);
        }
        Path base = Paths.get(properties.localBasePath() == null || properties.localBasePath().isBlank() ? "./data/storage" : properties.localBasePath());
        Path dir = base.resolve("projects").resolve(projectId);
        Files.createDirectories(dir);
        Path target = dir.resolve(filename).normalize();
        Files.write(target, image.bytes());
        String url = "/api/public/files/projects/" + projectId + "/" + target.getFileName();
        return stored(target.toString(), url, image);
    }

    public StoredFile saveGeneratedProjectImage(String projectId, String filename, byte[] bytes, String contentType) throws IOException {
        if (s3 != null) {
            String key = projectKey(projectId, filename);
            putObject(key, bytes, contentType);
            return new StoredFile(key, publicUrl(key), filename, contentType, bytes.length, null, null, formatFromContentType(contentType), false);
        }
        Path target = resolvePublicProjectFile(projectId, filename);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return new StoredFile(target.toString(), "/api/public/files/projects/" + projectId + "/" + filename, filename, contentType, bytes.length, null, null, formatFromContentType(contentType), false);
    }

    public StoredFile saveGeneratedVideo(String taskId, Path source, String contentType) throws IOException {
        String filename = sanitize(taskId) + ".mp4";
        long fileSize = Files.size(source);
        if (s3 != null) {
            String key = "videos/" + filename;
            putObject(key, source, contentType);
            return new StoredFile(key, publicUrl(key), filename, contentType, fileSize, null, null, "mp4", false);
        }
        Path target = resolvePublicVideoFile(filename);
        Files.createDirectories(target.getParent());
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new StoredFile(
            target.toString(), "/api/public/files/videos/" + filename,
            filename, contentType, fileSize, null, null, "mp4", false
        );
    }

    public StoredFile saveUserAvatar(String userId, String fileId, MultipartFile file) throws IOException {
        ImageUploadNormalizer.NormalizedImage image = imageNormalizer.normalize(file, 2L * 1024L * 1024L, ImageUploadNormalizer.DEFAULT_MAX_PIXELS);
        String filename = fileId + image.extension();
        if (s3 != null) {
            String key = "avatars/" + sanitize(userId) + "/" + sanitize(filename);
            putObject(key, image.bytes(), image.contentType());
            return stored(key, publicUrl(key), image);
        }
        Path base = Paths.get(properties.localBasePath() == null || properties.localBasePath().isBlank() ? "./data/storage" : properties.localBasePath());
        Path dir = base.resolve("avatars").resolve(userId);
        Files.createDirectories(dir);
        Path target = dir.resolve(filename).normalize();
        Files.write(target, image.bytes());
        return stored(target.toString(), "/api/public/files/avatars/" + userId + "/" + target.getFileName(), image);
    }

    public StoredFile saveBenefitImage(String kind, String fileId, MultipartFile file) throws IOException {
        ImageUploadNormalizer.NormalizedImage image = imageNormalizer.normalize(file, 3L * 1024L * 1024L, ImageUploadNormalizer.DEFAULT_MAX_PIXELS);
        String filename = sanitize(fileId + image.extension());
        String group = sanitize(kind == null || kind.isBlank() ? "community" : kind);
        if (s3 != null) {
            String key = "benefits/" + group + "/" + filename;
            putObject(key, image.bytes(), image.contentType());
            return stored(key, publicUrl(key), image);
        }
        Path base = Paths.get(properties.localBasePath() == null || properties.localBasePath().isBlank() ? "./data/storage" : properties.localBasePath());
        Path dir = base.resolve("benefits").resolve(group);
        Files.createDirectories(dir);
        Path target = dir.resolve(filename).normalize();
        Files.write(target, image.bytes());
        return stored(target.toString(), "/api/public/files/benefits/" + group + "/" + target.getFileName(), image);
    }

    public Path resolvePublicProjectFile(String projectId, String filename) {
        Path base = Paths.get(properties.localBasePath() == null || properties.localBasePath().isBlank() ? "./data/storage" : properties.localBasePath());
        return base.resolve("projects").resolve(sanitize(projectId)).resolve(sanitize(filename)).normalize();
    }

    public Path resolvePublicAvatarFile(String userId, String filename) {
        Path base = Paths.get(properties.localBasePath() == null || properties.localBasePath().isBlank() ? "./data/storage" : properties.localBasePath());
        return base.resolve("avatars").resolve(sanitize(userId)).resolve(sanitize(filename)).normalize();
    }

    public Path resolvePublicBenefitFile(String kind, String filename) {
        Path base = Paths.get(properties.localBasePath() == null || properties.localBasePath().isBlank() ? "./data/storage" : properties.localBasePath());
        return base.resolve("benefits").resolve(sanitize(kind)).resolve(sanitize(filename)).normalize();
    }

    public Path resolvePublicVideoFile(String filename) {
        Path base = Paths.get(properties.localBasePath() == null || properties.localBasePath().isBlank() ? "./data/storage" : properties.localBasePath());
        return base.resolve("videos").resolve(sanitize(filename)).normalize();
    }

    public PublicFile publicProjectFile(String projectId, String filename) throws IOException {
        return publicFile(projectKey(projectId, filename), resolvePublicProjectFile(projectId, filename));
    }

    public PublicFile publicAvatarFile(String userId, String filename) throws IOException {
        return publicFile("avatars/" + sanitize(userId) + "/" + sanitize(filename), resolvePublicAvatarFile(userId, filename));
    }

    public PublicFile publicBenefitFile(String kind, String filename) throws IOException {
        return publicFile("benefits/" + sanitize(kind == null || kind.isBlank() ? "community" : kind) + "/" + sanitize(filename), resolvePublicBenefitFile(kind, filename));
    }

    public PublicFile publicVideoFile(String filename) throws IOException {
        return publicFile("videos/" + sanitize(filename), resolvePublicVideoFile(filename));
    }

    public byte[] readStoredBytes(String storagePath) throws IOException {
        if (storagePath == null || storagePath.isBlank()) {
            throw new IOException("Stored file path is empty");
        }
        if (s3 != null) {
            try (ResponseInputStream<GetObjectResponse> stream = s3.getObject(GetObjectRequest.builder()
                .bucket(properties.minioBucket())
                .key(sanitize(storagePath))
                .build())) {
                return stream.readAllBytes();
            }
        }

        Path base = Paths.get(
            properties.localBasePath() == null || properties.localBasePath().isBlank()
                ? "./data/storage"
                : properties.localBasePath()
        ).toAbsolutePath().normalize();
        Path candidate = Paths.get(storagePath);
        Path target = (candidate.isAbsolute() ? candidate : candidate.toAbsolutePath()).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target)) {
            throw new IOException("Stored file is unavailable");
        }
        return Files.readAllBytes(target);
    }

    private PublicFile publicFile(String key, Path localPath) throws IOException {
        if (s3 != null) {
            try {
                ResponseInputStream<GetObjectResponse> stream = s3.getObject(GetObjectRequest.builder()
                    .bucket(properties.minioBucket())
                    .key(sanitize(key))
                    .build());
                return new PublicFile(new InputStreamResource(stream), stream.response().contentType());
            } catch (S3Exception exception) {
                if (exception.statusCode() == 404) {
                    return null;
                }
                throw exception;
            }
        }
        if (!Files.exists(localPath) || !Files.isRegularFile(localPath)) {
            return null;
        }
        return new PublicFile(new FileSystemResource(localPath), Files.probeContentType(localPath));
    }

    private void putObject(String key, byte[] bytes, String contentType) {
        ensureBucket();
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(properties.minioBucket())
            .key(key)
            .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
            .contentLength((long) bytes.length)
            .build();
        s3.putObject(request, RequestBody.fromBytes(bytes));
    }

    private void putObject(String key, Path source, String contentType) throws IOException {
        ensureBucket();
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(properties.minioBucket())
            .key(key)
            .contentType(contentType == null || contentType.isBlank() ? "video/mp4" : contentType)
            .contentLength(Files.size(source))
            .build();
        s3.putObject(request, RequestBody.fromFile(source));
    }

    private void ensureBucket() {
        if (bucketReady || s3 == null) {
            return;
        }
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(properties.minioBucket()).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                s3.createBucket(CreateBucketRequest.builder().bucket(properties.minioBucket()).build());
            } else {
                throw ex;
            }
        }
        if (properties.publicRead()) {
            String policy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": "*",
                      "Action": ["s3:GetObject"],
                      "Resource": ["arn:aws:s3:::%s/*"]
                    }
                  ]
                }
                """.formatted(properties.minioBucket());
            s3.putBucketPolicy(PutBucketPolicyRequest.builder().bucket(properties.minioBucket()).policy(policy).build());
        }
        bucketReady = true;
    }

    private String projectKey(String projectId, String filename) {
        return "projects/" + sanitize(projectId) + "/" + sanitize(filename);
    }

    private String publicUrl(String key) {
        String sanitized = sanitize(key);
        if (sanitized.startsWith("projects/") || sanitized.startsWith("avatars/") || sanitized.startsWith("benefits/")) {
            return "/api/public/files/" + sanitized;
        }
        String base = properties.minioPublicBaseUrl();
        if (base == null || base.isBlank()) {
            base = properties.minioEndpoint() + "/" + properties.minioBucket();
        }
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return normalized + "/" + sanitized;
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replace("\\", "/").replaceAll("^/+", "").replaceAll("\\.\\.", "");
    }

    private StoredFile stored(String path, String url, ImageUploadNormalizer.NormalizedImage image) {
        return new StoredFile(path, url, image.filename(), image.contentType(), image.fileSize(), image.width(), image.height(), image.format(), image.converted());
    }

    private String formatFromContentType(String contentType) {
        if ("image/jpeg".equalsIgnoreCase(contentType)) return "jpeg";
        if ("image/png".equalsIgnoreCase(contentType)) return "png";
        if ("image/webp".equalsIgnoreCase(contentType)) return "webp";
        return "";
    }

    public record StoredFile(
        String path,
        String url,
        String filename,
        String contentType,
        long fileSize,
        Integer width,
        Integer height,
        String format,
        boolean converted
    ) {}

    public record PublicFile(Resource resource, String contentType) {}
}
