package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("prompt_templates")
public class PromptTemplateEntity {
    private String id;
    private String name;
    private String scene;
    private Integer version;
    private String content;
    private Boolean enabled;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
