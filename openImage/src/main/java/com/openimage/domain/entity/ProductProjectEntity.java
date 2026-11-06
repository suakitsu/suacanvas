package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.openimage.persistence.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "product_projects", autoResultMap = true)
public class ProductProjectEntity {
    private String id;
    private String userId;
    private String brand;
    private String productName;
    private String country;
    private String language;
    private String status;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String originalInfo;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String generatedCopy;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String confirmedFacts;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
