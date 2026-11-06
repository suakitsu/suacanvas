package com.openimage.modules.auth.email;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openimage.domain.entity.EmailVerificationCodeEntity;
import com.openimage.domain.mapper.EmailVerificationCodeMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class EmailVerificationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern CODE_PATTERN = Pattern.compile("^\\d{6}$");
    private static final int CODE_TTL_MINUTES = 10;

    private final EmailVerificationCodeMapper codes;
    private final PasswordEncoder passwordEncoder;
    private final EmailCodeSender sender;

    public EmailVerificationService(EmailVerificationCodeMapper codes, PasswordEncoder passwordEncoder, EmailCodeSender sender) {
        this.codes = codes;
        this.passwordEncoder = passwordEncoder;
        this.sender = sender;
    }

    public Map<String, Object> sendCode(String emailValue, EmailCodePurpose purpose) {
        String email = normalizeEmail(emailValue);
        validateEmail(email);
        String emailHash = emailHash(email);
        enforceRateLimit(emailHash);

        OffsetDateTime now = OffsetDateTime.now();
        String code = nextCode();
        EmailVerificationCodeEntity entity = new EmailVerificationCodeEntity();
        entity.setId("evc_" + uuid());
        entity.setEmailHash(emailHash);
        entity.setEmailMasked(maskEmail(email));
        entity.setPurpose(purpose.value());
        entity.setCodeHash(passwordEncoder.encode(verificationPayload(email, purpose, code)));
        entity.setStatus("pending");
        entity.setProvider("tencent_ses");
        entity.setExpiresAt(now.plusMinutes(CODE_TTL_MINUTES));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setDeleted(0);
        codes.insert(entity);

        try {
            EmailCodeSender.SendResult result = sender.sendVerificationCode(email, purpose, code);
            entity.setStatus("sent");
            entity.setProvider(result.provider() == null || result.provider().isBlank() ? "tencent_ses" : result.provider());
            entity.setProviderMessageId(limit(result.messageId(), 255));
            entity.setSentAt(now);
            entity.setUpdatedAt(OffsetDateTime.now());
            codes.updateById(entity);
            return Map.of(
                "sent", true,
                "email_masked", entity.getEmailMasked(),
                "purpose", purpose.value(),
                "expires_in_seconds", CODE_TTL_MINUTES * 60,
                "cooldown_seconds", 60
            );
        } catch (EmailCodeSendException exception) {
            entity.setStatus("failed");
            entity.setFailureReason("provider_failed");
            entity.setUpdatedAt(OffsetDateTime.now());
            codes.updateById(entity);
            throw exception;
        }
    }

    public void verifyAndConsume(String emailValue, EmailCodePurpose purpose, String codeValue) {
        String email = normalizeEmail(emailValue);
        validateEmail(email);
        String code = codeValue == null ? "" : codeValue.trim();
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        String emailHash = emailHash(email);
        OffsetDateTime now = OffsetDateTime.now();
        List<EmailVerificationCodeEntity> candidates = codes.selectList(new LambdaQueryWrapper<EmailVerificationCodeEntity>()
            .eq(EmailVerificationCodeEntity::getDeleted, 0)
            .eq(EmailVerificationCodeEntity::getEmailHash, emailHash)
            .eq(EmailVerificationCodeEntity::getPurpose, purpose.value())
            .eq(EmailVerificationCodeEntity::getStatus, "sent")
            .isNull(EmailVerificationCodeEntity::getConsumedAt)
            .orderByDesc(EmailVerificationCodeEntity::getCreatedAt)
            .last("limit 10"));
        for (EmailVerificationCodeEntity candidate : candidates) {
            if (candidate.getExpiresAt() == null || candidate.getExpiresAt().isBefore(now)) {
                expire(candidate);
                continue;
            }
            if (passwordEncoder.matches(verificationPayload(email, purpose, code), candidate.getCodeHash())) {
                consume(candidate);
                invalidateOtherActiveCodes(emailHash, purpose, candidate.getId());
                return;
            }
        }
        throw new IllegalArgumentException("验证码错误或已过期");
    }

    private void enforceRateLimit(String emailHash) {
        OffsetDateTime now = OffsetDateTime.now();
        long lastMinute = sentCountSince(emailHash, now.minusSeconds(60));
        if (lastMinute > 0) {
            throw new IllegalArgumentException("验证码发送过于频繁，请 60 秒后再试");
        }
        long lastHour = sentCountSince(emailHash, now.minusHours(1));
        if (lastHour >= 5) {
            throw new IllegalArgumentException("该邮箱 1 小时内验证码发送次数已达上限，请稍后再试");
        }
        long lastDay = sentCountSince(emailHash, now.minusDays(1));
        if (lastDay >= 20) {
            throw new IllegalArgumentException("该邮箱今日验证码发送次数已达上限，请明天再试");
        }
    }

    private long sentCountSince(String emailHash, OffsetDateTime since) {
        return codes.selectCount(new LambdaQueryWrapper<EmailVerificationCodeEntity>()
            .eq(EmailVerificationCodeEntity::getDeleted, 0)
            .eq(EmailVerificationCodeEntity::getEmailHash, emailHash)
            .isNotNull(EmailVerificationCodeEntity::getSentAt)
            .ge(EmailVerificationCodeEntity::getSentAt, since));
    }

    private void consume(EmailVerificationCodeEntity entity) {
        entity.setStatus("consumed");
        entity.setConsumedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        codes.updateById(entity);
    }

    private void expire(EmailVerificationCodeEntity entity) {
        entity.setStatus("expired");
        entity.setUpdatedAt(OffsetDateTime.now());
        codes.updateById(entity);
    }

    private void invalidateOtherActiveCodes(String emailHash, EmailCodePurpose purpose, String consumedId) {
        List<EmailVerificationCodeEntity> active = codes.selectList(new LambdaQueryWrapper<EmailVerificationCodeEntity>()
            .eq(EmailVerificationCodeEntity::getDeleted, 0)
            .eq(EmailVerificationCodeEntity::getEmailHash, emailHash)
            .eq(EmailVerificationCodeEntity::getPurpose, purpose.value())
            .eq(EmailVerificationCodeEntity::getStatus, "sent")
            .isNull(EmailVerificationCodeEntity::getConsumedAt)
            .ne(EmailVerificationCodeEntity::getId, consumedId)
            .last("limit 20"));
        OffsetDateTime now = OffsetDateTime.now();
        for (EmailVerificationCodeEntity item : active) {
            item.setStatus("invalidated");
            item.setUpdatedAt(now);
            codes.updateById(item);
        }
    }

    private void validateEmail(String email) {
        if (email.isBlank() || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("请输入正确邮箱");
        }
    }

    private String nextCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    static String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    static String verificationPayload(String email, EmailCodePurpose purpose, String code) {
        return purpose.value() + "\n" + normalizeEmail(email) + "\n" + code;
    }

    static String emailHash(String email) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalizeEmail(email).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Email hash failed", exception);
        }
    }

    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "-";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }

    private String limit(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
