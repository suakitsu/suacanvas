package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("ai_worker_tokens")
public class AiWorkerTokenEntity {
    private String id;
    private String name;
    private String tokenPrefix;
    private String tokenHash;
    private Boolean enabled;
    private OffsetDateTime lastUsedAt;
    private OffsetDateTime revokedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
