package com.openimage.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openimage.storage")
public record StorageProperties(
    String provider,
    String localBasePath,
    String minioEndpoint,
    String minioPublicBaseUrl,
    String minioBucket,
    String minioRegion,
    String minioAccessKey,
    String minioSecretKey,
    Boolean minioPathStyleAccess,
    Boolean minioPublicRead
) {
    public boolean minioEnabled() {
        return ("minio".equalsIgnoreCase(provider) || "s3".equalsIgnoreCase(provider))
            && notBlank(minioEndpoint)
            && notBlank(minioBucket)
            && notBlank(minioAccessKey)
            && notBlank(minioSecretKey);
    }

    public String regionValue() {
        return notBlank(minioRegion) ? minioRegion : "us-east-1";
    }

    public boolean pathStyleAccess() {
        return minioPathStyleAccess == null || minioPathStyleAccess;
    }

    public boolean publicRead() {
        return minioPublicRead == null || minioPublicRead;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
