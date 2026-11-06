package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("creative_chat_messages")
public class CreativeChatMessageEntity {
    private String id;
    private String chatId;
    private String userId;
    private String role;
    private String content;
    private String projectId;
    private String taskId;
    private String resultUrl;
    private String status;
    private Integer versionNo;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
