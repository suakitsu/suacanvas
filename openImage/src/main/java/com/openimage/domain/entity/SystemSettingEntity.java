package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.openimage.persistence.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "system_settings", autoResultMap = true)
public class SystemSettingEntity {
    private String id;
    private String settingKey;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String settingValue;
    private String description;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
