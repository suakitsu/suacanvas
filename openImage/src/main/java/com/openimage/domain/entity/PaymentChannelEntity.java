package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("payment_channels")
public class PaymentChannelEntity {
    private String id;
    private String name;
    private String code;
    private Boolean visible;
    private Boolean enabled;
    private Integer sortOrder;
    private String config;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
