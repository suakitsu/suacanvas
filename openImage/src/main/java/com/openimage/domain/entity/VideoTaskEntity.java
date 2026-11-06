package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.openimage.persistence.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@TableName(value = "video_tasks", autoResultMap = true)
public class VideoTaskEntity {
    private String id;
    private String userId;
    private String idempotencyKey;
    private String title;
    private String prompt;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sourceImageUrls;
    private String aspectRatio;
    private Integer durationSeconds;
    private String requestedResolution;
    private String mode;
    private String status;
    private Integer progress;
    private String provider;
    private String model;
    private String routingReason;
    private String upstreamTaskId;
    private String resultUrl;
    private Integer creditsCost;
    private BigDecimal actualCost;
    private Integer actualWidth;
    private Integer actualHeight;
    private BigDecimal actualSeconds;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String providerAttempts;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String metadata;
    private String safeError;
    private String rawError;
    private OffsetDateTime refundedAt;
    private OffsetDateTime queuedAt;
    private OffsetDateTime submittedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer hiddenByUser;
    private Integer deleted;
}
