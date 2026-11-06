package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("redemption_codes")
public class RedemptionCodeEntity {
    private String id;
    private String code;
    private Integer credits;
    private Integer maxUses;
    private Integer usedCount;
    private OffsetDateTime expiresAt;
    private Boolean enabled;
    private String benefitType;
    private String batchId;
    private String batchName;
    private Boolean batchExclusive;
    private String status;
    private String note;
    private String createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime disabledAt;
    private Integer deleted;
}
