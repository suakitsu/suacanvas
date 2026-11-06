package com.openimage.modules.auth.email;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.ses.v20201002.SesClient;
import com.tencentcloudapi.ses.v20201002.models.SendEmailRequest;
import com.tencentcloudapi.ses.v20201002.models.SendEmailResponse;
import com.tencentcloudapi.ses.v20201002.models.Simple;
import com.tencentcloudapi.ses.v20201002.models.Template;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TencentSesEmailService implements EmailCodeSender {
    private static final Logger log = LoggerFactory.getLogger(TencentSesEmailService.class);

    private final TencentSesProperties properties;

    public TencentSesEmailService(TencentSesProperties properties) {
        this.properties = properties;
    }

    @Override
    public SendResult sendVerificationCode(String email, EmailCodePurpose purpose, String code) {
        if (!properties.configured()) {
            log.warn("Tencent SES is not configured for purpose={} email={}", purpose.value(), maskEmail(email));
            throw new EmailCodeSendException("验证码发送失败，请稍后重试");
        }
        try {
            Credential credential = new Credential(properties.getSecretId(), properties.getSecretKey());
            HttpProfile httpProfile = new HttpProfile();
            httpProfile.setEndpoint("ses.tencentcloudapi.com");
            httpProfile.setConnTimeout(Math.max(3, properties.getTimeoutSeconds()));
            ClientProfile clientProfile = new ClientProfile();
            clientProfile.setHttpProfile(httpProfile);

            SesClient client = new SesClient(credential, normalizedRegion(), clientProfile);
            Template template = new Template();
            template.setTemplateID(properties.getTemplateId());
            template.setTemplateData("{\"code\":\"" + code + "\"}");

            SendEmailRequest request = new SendEmailRequest();
            request.setFromEmailAddress(properties.getFromEmail());
            request.setSubject("PicPilot 邮箱验证码");
            request.setDestination(new String[]{email});
            request.setTemplate(template);

            SendEmailResponse response = client.SendEmail(request);
            return new SendResult("tencent_ses", response == null ? "" : response.getMessageId());
        } catch (TencentCloudSDKException exception) {
            log.warn(
                "Tencent SES verification email failed purpose={} email={} errorCode={} requestId={}",
                purpose.value(),
                maskEmail(email),
                safe(exception.getErrorCode()),
                safe(exception.getRequestId())
            );
            throw new EmailCodeSendException("验证码发送失败，请稍后重试", exception);
        } catch (RuntimeException exception) {
            log.warn(
                "Tencent SES verification email failed purpose={} email={} error={}",
                purpose.value(),
                maskEmail(email),
                exception.getClass().getSimpleName()
            );
            throw new EmailCodeSendException("验证码发送失败，请稍后重试", exception);
        }
    }

    public SendResult sendPlainText(String email, String subject, String text) {
        return sendPlainText(email, subject, text, Map.of());
    }

    public SendResult sendPlainText(String email, String subject, String text, Map<String, String> variables) {
        if (!mailConfigured()) {
            log.warn("Tencent SES is not configured for plain email subject={} email={}", subject, maskEmail(email));
            return new SendResult("tencent_ses", "");
        }
        try {
            Credential credential = new Credential(properties.getSecretId(), properties.getSecretKey());
            HttpProfile httpProfile = new HttpProfile();
            httpProfile.setEndpoint("ses.tencentcloudapi.com");
            httpProfile.setConnTimeout(Math.max(3, properties.getTimeoutSeconds()));
            ClientProfile clientProfile = new ClientProfile();
            clientProfile.setHttpProfile(httpProfile);

            SesClient client = new SesClient(credential, normalizedRegion(), clientProfile);
            SendEmailRequest request = new SendEmailRequest();
            request.setFromEmailAddress(properties.getFromEmail());
            request.setSubject(subject == null || subject.isBlank() ? "PicPilot 通知" : subject);
            request.setDestination(new String[]{email});
            if (properties.getNotificationTemplateId() != null && properties.getNotificationTemplateId() > 0) {
                Template template = new Template();
                template.setTemplateID(properties.getNotificationTemplateId());
                template.setTemplateData(templateData(subject, text, variables));
                request.setTemplate(template);
            } else {
                Simple simple = new Simple();
                simple.setText(Base64.getEncoder().encodeToString((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
                request.setSimple(simple);
            }

            SendEmailResponse response = client.SendEmail(request);
            return new SendResult("tencent_ses", response == null ? "" : response.getMessageId());
        } catch (TencentCloudSDKException exception) {
            log.warn(
                "Tencent SES plain email failed subject={} email={} errorCode={} requestId={} message={}",
                subject,
                maskEmail(email),
                safe(exception.getErrorCode()),
                safe(exception.getRequestId()),
                safe(exception.getMessage())
            );
            return new SendResult("tencent_ses", "");
        } catch (Exception exception) {
            log.warn("Tencent SES plain email failed subject={} email={} error={}", subject, maskEmail(email), exception.getClass().getSimpleName());
            return new SendResult("tencent_ses", "");
        }
    }

    private boolean mailConfigured() {
        return properties.getSecretId() != null && !properties.getSecretId().isBlank()
            && properties.getSecretKey() != null && !properties.getSecretKey().isBlank()
            && properties.getFromEmail() != null && !properties.getFromEmail().isBlank();
    }

    private String normalizedRegion() {
        return properties.getRegion() == null || properties.getRegion().isBlank() ? "ap-hongkong" : properties.getRegion().trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String templateData(String subject, String content, Map<String, String> variables) {
        String subjectKey = properties.getNotificationTemplateSubjectKey() == null || properties.getNotificationTemplateSubjectKey().isBlank()
            ? "subject"
            : properties.getNotificationTemplateSubjectKey().trim();
        String contentKey = properties.getNotificationTemplateContentKey() == null || properties.getNotificationTemplateContentKey().isBlank()
            ? "content"
            : properties.getNotificationTemplateContentKey().trim();
        Map<String, String> data = new LinkedHashMap<>();
        data.put(subjectKey, subject == null || subject.isBlank() ? "PicPilot 通知" : subject);
        data.put(contentKey, content == null ? "" : content);
        if (variables != null) {
            variables.forEach((key, value) -> {
                if (key != null && !key.isBlank()) {
                    data.put(key.trim(), value == null ? "" : value);
                }
            });
        }
        data.putIfAbsent("time", ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        mirrorDollarVariable(data, "count");
        mirrorDollarVariable(data, "threshold");
        mirrorDollarVariable(data, "time");
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append('"').append(jsonEscape(entry.getKey())).append("\":\"").append(jsonEscape(entry.getValue())).append('"');
        }
        return builder.append('}').toString();
    }

    private void mirrorDollarVariable(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value != null) {
            data.putIfAbsent("$" + key, value);
        }
    }

    private String jsonEscape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "-";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }
}
