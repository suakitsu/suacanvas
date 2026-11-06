package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("station_messages")
public class StationMessageEntity {
    private String id;
    private String title;
    private String content;
    private String audience;
    private String publishedBy;
    private OffsetDateTime publishedAt;
    private OffsetDateTime expiresAt;
    private Boolean enabled;
    private Integer deleted;
}
