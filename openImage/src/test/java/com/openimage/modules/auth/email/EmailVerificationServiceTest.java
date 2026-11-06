package com.openimage.modules.auth.email;

import com.openimage.domain.entity.EmailVerificationCodeEntity;
import com.openimage.domain.mapper.EmailVerificationCodeMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailVerificationServiceTest {
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void generatesSixDigitCodeAndStoresOnlyHash() {
        EmailVerificationCodeMapper mapper = mock(EmailVerificationCodeMapper.class);
        CapturingSender sender = new CapturingSender();
        when(mapper.selectCount(any())).thenReturn(0L);

        EmailVerificationService service = new EmailVerificationService(mapper, encoder, sender);
        service.sendCode("User@Example.com", EmailCodePurpose.REGISTER);

        ArgumentCaptor<EmailVerificationCodeEntity> captor = ArgumentCaptor.forClass(EmailVerificationCodeEntity.class);
        verify(mapper).insert(captor.capture());
        EmailVerificationCodeEntity stored = captor.getValue();
        assertThat(sender.code).matches("\\d{6}");
        assertThat(stored.getCodeHash()).isNotBlank().doesNotContain(sender.code);
        assertThat(stored.getEmailHash()).hasSize(64);
        assertThat(stored.getEmailMasked()).isEqualTo("us***@example.com");
        assertThat(stored.getExpiresAt()).isAfter(stored.getCreatedAt());
    }

    @Test
    void blocksFrequentSends() {
        EmailVerificationCodeMapper mapper = mock(EmailVerificationCodeMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        EmailVerificationService service = new EmailVerificationService(mapper, encoder, new CapturingSender());

        assertThatThrownBy(() -> service.sendCode("user@example.com", EmailCodePurpose.LOGIN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("60 秒");
    }

    @Test
    void blocksHourlyLimit() {
        EmailVerificationCodeMapper mapper = mock(EmailVerificationCodeMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L, 5L);
        EmailVerificationService service = new EmailVerificationService(mapper, encoder, new CapturingSender());

        assertThatThrownBy(() -> service.sendCode("user@example.com", EmailCodePurpose.LOGIN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("1 小时");
    }

    @Test
    void expiredCodeCannotBeUsed() {
        EmailVerificationCodeMapper mapper = mock(EmailVerificationCodeMapper.class);
        EmailVerificationCodeEntity expired = codeEntity("user@example.com", "123456");
        expired.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(mapper.selectList(any())).thenReturn(List.of(expired));
        EmailVerificationService service = new EmailVerificationService(mapper, encoder, new CapturingSender());

        assertThatThrownBy(() -> service.verifyAndConsume("user@example.com", EmailCodePurpose.REGISTER, "123456"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("验证码错误或已过期");
        assertThat(expired.getStatus()).isEqualTo("expired");
        verify(mapper).updateById(expired);
    }

    @Test
    void wrongCodeCannotBeUsed() {
        EmailVerificationCodeMapper mapper = mock(EmailVerificationCodeMapper.class);
        EmailVerificationCodeEntity active = codeEntity("user@example.com", "123456");
        when(mapper.selectList(any())).thenReturn(List.of(active));
        EmailVerificationService service = new EmailVerificationService(mapper, encoder, new CapturingSender());

        assertThatThrownBy(() -> service.verifyAndConsume("user@example.com", EmailCodePurpose.REGISTER, "000000"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("验证码错误或已过期");
        assertThat(active.getStatus()).isEqualTo("sent");
    }

    @Test
    void successfulVerificationConsumesAndInvalidatesOtherActiveCodes() {
        EmailVerificationCodeMapper mapper = mock(EmailVerificationCodeMapper.class);
        EmailVerificationCodeEntity active = codeEntity("user@example.com", "123456");
        EmailVerificationCodeEntity other = codeEntity("user@example.com", "654321");
        other.setId("evc_other");
        when(mapper.selectList(any())).thenReturn(List.of(active), List.of(other));
        EmailVerificationService service = new EmailVerificationService(mapper, encoder, new CapturingSender());

        service.verifyAndConsume("user@example.com", EmailCodePurpose.REGISTER, "123456");

        assertThat(active.getStatus()).isEqualTo("consumed");
        assertThat(active.getConsumedAt()).isNotNull();
        assertThat(other.getStatus()).isEqualTo("invalidated");
        verify(mapper).updateById(active);
        verify(mapper).updateById(other);
    }

    private EmailVerificationCodeEntity codeEntity(String email, String code) {
        EmailVerificationCodeEntity entity = new EmailVerificationCodeEntity();
        entity.setId("evc_test");
        entity.setEmailHash(EmailVerificationService.emailHash(email));
        entity.setPurpose(EmailCodePurpose.REGISTER.value());
        entity.setCodeHash(encoder.encode(EmailVerificationService.verificationPayload(email, EmailCodePurpose.REGISTER, code)));
        entity.setStatus("sent");
        entity.setExpiresAt(OffsetDateTime.now().plusMinutes(10));
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setDeleted(0);
        return entity;
    }

    private static class CapturingSender implements EmailCodeSender {
        private String code;

        @Override
        public SendResult sendVerificationCode(String email, EmailCodePurpose purpose, String code) {
            this.code = code;
            return new SendResult("test", "msg_test");
        }
    }
}
