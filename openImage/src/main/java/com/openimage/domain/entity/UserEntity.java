package com.openimage.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("users")
public class UserEntity {
    private String id;
    private String email;
    private String passwordHash;
    private String nickname;
    private String role;
    private String status;
    private Integer credits;
    private String uiLanguage;
    private String theme;
    private String avatarUrl;
    private String githubId;
    private String githubLogin;
    private String authProvider;
    private String registerIp;
    private String lastRequestIp;
    private OffsetDateTime lastRequestAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @TableField("deleted")
    private Integer deleted;
}
