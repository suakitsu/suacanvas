package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("recharge_plans")
public class RechargePlanEntity {
    private String id;
    private String name;
    private Integer credits;
    private Integer priceCents;
    private String currency;
    private Boolean enabled;
    private Integer sortOrder;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
