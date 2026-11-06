package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.openimage.persistence.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "ai_worker_instances", autoResultMap = true)
public class AiWorkerInstanceEntity {
    @TableId("worker_id")
    private String workerId;
    private String tokenId;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String capabilities;
    private Integer maxConcurrency;
    private Integer activeTasks;
    private OffsetDateTime lastHeartbeatAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
