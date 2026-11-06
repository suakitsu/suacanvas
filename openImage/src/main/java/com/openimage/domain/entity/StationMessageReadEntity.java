package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("station_message_reads")
public class StationMessageReadEntity {
    private String id;
    private String messageId;
    private String userId;
    private OffsetDateTime readAt;
    private Integer deleted;
}
