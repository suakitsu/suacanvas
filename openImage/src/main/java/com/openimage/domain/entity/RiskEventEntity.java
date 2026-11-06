package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("risk_events")
public class RiskEventEntity {
    private String id;
    private String userId;
    private String projectId;
    private String taskId;
    private String feature;
    private String riskLevel;
    private String keyword;
    private String matchedText;
    private String source;
    private String action;
    private OffsetDateTime createdAt;
    private Integer deleted;
}
