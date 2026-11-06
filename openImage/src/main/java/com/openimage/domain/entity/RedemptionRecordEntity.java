package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("redemption_records")
public class RedemptionRecordEntity {
    private String id;
    private String codeId;
    private String code;
    private String batchId;
    private String userId;
    private String userEmailMasked;
    private String userNickname;
    private Integer credits;
    private OffsetDateTime redeemedAt;
    private Integer deleted;
}
