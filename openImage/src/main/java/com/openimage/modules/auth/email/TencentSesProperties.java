package com.openimage.modules.auth.email;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "openimage.email.tencent-ses")
public class TencentSesProperties {
    private String secretId = "";
    private String secretKey = "";
    private String fromEmail = "";
    private Long templateId = 0L;
    private Long notificationTemplateId = 0L;
    private String notificationTemplateSubjectKey = "subject";
    private String notificationTemplateContentKey = "content";
    private String region = "ap-hongkong";
    private Integer timeoutSeconds = 10;

    public boolean configured() {
        return notBlank(secretId) && notBlank(secretKey) && notBlank(fromEmail) && templateId != null && templateId > 0;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
