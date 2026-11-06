package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("email_verification_codes")
public class EmailVerificationCodeEntity {
    private String id;
    private String emailHash;
    private String emailMasked;
    private String purpose;
    private String codeHash;
    private String status;
    private String provider;
    private String providerMessageId;
    private String failureReason;
    private OffsetDateTime expiresAt;
    private OffsetDateTime consumedAt;
    private OffsetDateTime sentAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;
}
