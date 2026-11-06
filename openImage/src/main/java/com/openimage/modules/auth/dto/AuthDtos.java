package com.openimage.modules.auth.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
        @NotBlank(message = "邮箱不能为空") @Email(message = "请输入正确邮箱") String email,
        @NotBlank(message = "密码不能为空") @Size(min = 8, max = 16, message = "密码必须为 8-16 位") @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S+$", message = "密码必须包含字母和数字，且不能包含空格") String password,
        @JsonProperty("confirm_password") @JsonAlias("confirmPassword") String confirmPassword,
        @JsonProperty("verification_code") @JsonAlias("verificationCode") String verificationCode,
        String inviteCode,
        @JsonProperty("turnstile_token") @JsonAlias("turnstileToken") String turnstileToken
    ) {}
    public record LoginRequest(@NotBlank String email, @NotBlank String password, @JsonProperty("remember_me") @JsonAlias("rememberMe") Boolean rememberMe, @JsonProperty("turnstile_token") @JsonAlias("turnstileToken") String turnstileToken) {}
    public record SendEmailCodeRequest(@NotBlank(message = "邮箱不能为空") @Email(message = "请输入正确邮箱") String email, @NotBlank(message = "验证码用途不能为空") String purpose, @JsonProperty("turnstile_token") @JsonAlias("turnstileToken") String turnstileToken) {}
    public record EmailCodeLoginRequest(@NotBlank(message = "邮箱不能为空") @Email(message = "请输入正确邮箱") String email, @NotBlank(message = "验证码不能为空") @JsonProperty("verification_code") @JsonAlias("verificationCode") String verificationCode, @JsonProperty("remember_me") @JsonAlias("rememberMe") Boolean rememberMe, @JsonProperty("turnstile_token") @JsonAlias("turnstileToken") String turnstileToken) {}
    public record ForgotPasswordRequest(@NotBlank(message = "邮箱不能为空") @Email(message = "请输入正确邮箱") String email, String note, @JsonProperty("turnstile_token") @JsonAlias("turnstileToken") String turnstileToken) {}
    public record PasswordResetConfirmRequest(
        @NotBlank(message = "邮箱不能为空") @Email(message = "请输入正确邮箱") String email,
        @NotBlank(message = "验证码不能为空") @JsonProperty("verification_code") @JsonAlias("verificationCode") String verificationCode,
        @NotBlank(message = "密码不能为空") @Size(min = 8, max = 16, message = "密码必须为 8-16 位") @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S+$", message = "密码必须包含字母和数字，且不能包含空格") String password,
        @JsonProperty("confirm_password") @JsonAlias("confirmPassword") String confirmPassword,
        @JsonProperty("turnstile_token") @JsonAlias("turnstileToken") String turnstileToken
    ) {}
    public record UpdateProfileRequest(String nickname, @JsonProperty("ui_language") @JsonAlias("uiLanguage") String uiLanguage, String theme, @JsonProperty("avatar_url") @JsonAlias("avatarUrl") String avatarUrl) {}
    public record UserProfile(String id, String emailMasked, String emailDisplay, String nickname, String role, Integer credits, String uiLanguage, String theme, String avatarUrl) {}
    public record AuthResponse(UserProfile user, String accessToken) {}
}
