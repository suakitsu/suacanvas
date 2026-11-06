package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("image_type_configs")
public class ImageTypeConfigEntity {
    private String id;
    private String imageType;
    private String imageName;
    private Boolean enabled;
    private Integer creditsCost;
    private String defaultModel;
    private String fallbackModel;
    private String defaultSize;
    private String requiredFields;
    private Integer sortOrder;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
