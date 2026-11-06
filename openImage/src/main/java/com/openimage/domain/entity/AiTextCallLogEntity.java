package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("ai_text_call_logs")
public class AiTextCallLogEntity {
    private String id;
    private String userId;
    private String feature;
    private String targetType;
    private String targetId;
    private String provider;
    private String model;
    private String status;
    private Integer httpStatus;
    private Integer durationMs;
    private Integer inputChars;
    private Integer outputChars;
    private String errorType;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private Integer deleted;
}
