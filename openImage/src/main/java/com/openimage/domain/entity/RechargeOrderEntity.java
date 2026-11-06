package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("recharge_orders")
public class RechargeOrderEntity {
    private String id;
    private String userId;
    private String planId;
    private String channelCode;
    private Integer amountCents;
    private Integer credits;
    private String currency;
    private String status;
    private String remark;
    private String externalTradeNo;
    private OffsetDateTime paidAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
