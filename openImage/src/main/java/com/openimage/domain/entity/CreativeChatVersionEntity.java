package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("creative_chat_versions")
public class CreativeChatVersionEntity {
    private String id;
    private String chatId;
    private String userId;
    private Integer versionNo;
    private String userMessageId;
    private String assistantMessageId;
    private String projectId;
    private String taskId;
    private String userRequest;
    private String finalPrompt;
    private String style;
    private String size;
    private String referenceUrl;
    private String status;
    private String resultUrl;
    private String safeError;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
