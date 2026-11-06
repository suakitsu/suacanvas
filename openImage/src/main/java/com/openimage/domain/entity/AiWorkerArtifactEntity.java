package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("ai_worker_artifacts")
public class AiWorkerArtifactEntity {
    @TableId("artifact_id")
    private String artifactId;
    private String jobId;
    private String workerId;
    private String idempotencyKey;
    private String storagePath;
    private String url;
    private String filename;
    private String contentType;
    private Long fileSize;
    private String sha256;
    private String sourceSha256;
    private Integer width;
    private Integer height;
    private String format;
    private OffsetDateTime createdAt;
    private Integer deleted;
}
