package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("creative_chats")
public class CreativeChatEntity {
    private String id;
    private String userId;
    private String title;
    private String style;
    private String size;
    private String language;
    private String referenceUrl;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
