package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.openimage.persistence.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@TableName(value = "image_tasks", autoResultMap = true)
public class ImageTaskEntity {
    private String id;
    private String projectId;
    private String userId;
    private String taskSetId;
    private String imageType;
    private String status;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String promptSnapshot;
    private String resultUrl;
    private Integer creditsCost;
    private BigDecimal actualCost;
    private String provider;
    private String model;
    private String safeError;
    private String rawError;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String providerAttempts;
    private OffsetDateTime refundedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer hiddenByUser;
    private Integer deleted;
}
