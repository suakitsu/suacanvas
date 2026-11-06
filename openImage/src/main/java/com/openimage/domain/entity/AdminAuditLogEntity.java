package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.openimage.persistence.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "admin_audit_logs", autoResultMap = true)
public class AdminAuditLogEntity {
    private String id;
    private String adminId;
    private String action;
    private String targetType;
    private String targetId;
    private String summary;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String beforeJson;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String afterJson;
    private OffsetDateTime createdAt;
    private Integer deleted;
}
