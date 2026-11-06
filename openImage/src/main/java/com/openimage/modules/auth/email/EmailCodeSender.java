package com.openimage.modules.auth.email;

public interface EmailCodeSender {
    SendResult sendVerificationCode(String email, EmailCodePurpose purpose, String code);

    record SendResult(String provider, String messageId) {}
}
