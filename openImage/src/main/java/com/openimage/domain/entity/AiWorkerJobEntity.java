package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.openimage.persistence.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "ai_worker_jobs", autoResultMap = true)
public class AiWorkerJobEntity {
    @TableId("job_id")
    private String jobId;
    private String businessTaskId;
    private String providerId;
    private String capability;
    private String protocol;
    private String model;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String requestJson;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String inputFilesJson;
    private String requestHash;
    private String status;
    private String workerId;
    private String leaseToken;
    private OffsetDateTime leaseUntil;
    private OffsetDateTime leaseGraceUntil;
    private Integer attempt;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String resultJson;
    private String errorCode;
    private String errorMessage;
    private String completionIdempotencyKey;
    private String completionRequestHash;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime completedAt;
}
