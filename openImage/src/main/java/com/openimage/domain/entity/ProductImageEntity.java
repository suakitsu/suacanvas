package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("product_images")
public class ProductImageEntity {
    private String id;
    private String projectId;
    private String userId;
    private String imageType;
    private String originalFilename;
    private String url;
    private String storagePath;
    private String contentType;
    private String imageFormat;
    private Long fileSize;
    private Integer width;
    private Integer height;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
