package com.openimage.modules.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.AdminAuditLogEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.mapper.AdminAuditLogMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.modules.auth.dto.AuthDtos;
import com.openimage.modules.auth.email.EmailCodePurpose;
import com.openimage.modules.auth.email.EmailVerificationService;
import com.openimage.security.CurrentUser;
import com.openimage.security.JwtService;
import com.openimage.storage.LocalStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {
    public static final String DEFAULT_ADMIN_LOGIN = "admin";
    public static final String DEFAULT_ADMIN_EMAIL = "admin@openimage.local";
    private static final long REMEMBER_ME_TOKEN_MINUTES = 15L * 24 * 60;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final UserMapper users;
    private final AdminAuditLogMapper auditLogs;
    private final PasswordEncoder enc;
    private final JwtService jwt;
    private final LocalStorageService storage;
    private final EmailVerificationService emailVerification;
    private final WebClient webClient = WebClient.builder().build();

    @Value("${openimage.security.admin-invite-code:}")
    private String adminInviteCode;

    public AuthService(UserMapper users, AdminAuditLogMapper auditLogs, PasswordEncoder enc, JwtService jwt, LocalStorageService storage, EmailVerificationService emailVerification) {
        this.users = users;
        this.auditLogs = auditLogs;
        this.enc = enc;
        this.jwt = jwt;
        this.storage = storage;
        this.emailVerification = emailVerification;
    }

    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request, String requestIp) {
        validateRegisterRequest(request);
        String email = normalizeEmail(request.email());
        if (findByEmail(email) != null) {
            throw new IllegalArgumentException("Email has been registered");
        }
        emailVerification.verifyAndConsume(email, EmailCodePurpose.REGISTER, request.verificationCode());
        return createAccount(request, "user", 0, "新用户", requestIp);
    }

    public AuthDtos.AuthResponse registerAdmin(AuthDtos.RegisterRequest request) {
        validateRegisterRequest(request);
        if (adminInviteCode == null || adminInviteCode.isBlank()) {
            throw new IllegalArgumentException("Admin registration is disabled");
        }
        if (request.inviteCode() == null || !adminInviteCode.equals(request.inviteCode())) {
            throw new IllegalArgumentException("Invalid admin invite code");
        }
        return createAccount(request, "admin", 1000, "管理员", "");
    }

    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request, String requestIp) {
        return resp(touchRequestIp(authenticate(request), requestIp), Boolean.TRUE.equals(request.rememberMe()));
    }

    public AuthDtos.AuthResponse loginWithEmailCode(AuthDtos.EmailCodeLoginRequest request, String requestIp) {
        String email = normalizeEmail(request.email());
        UserEntity user = findByEmail(email);
        if (user == null || user.getDeleted() != 0 || !"active".equals(user.getStatus())) {
            throw new IllegalArgumentException("账号不存在或不可用");
        }
        emailVerification.verifyAndConsume(email, EmailCodePurpose.LOGIN, request.verificationCode());
        return resp(touchRequestIp(user, requestIp), Boolean.TRUE.equals(request.rememberMe()));
    }

    public AuthDtos.AuthResponse adminLogin(AuthDtos.LoginRequest request, String requestIp) {
        UserEntity user = authenticate(request);
        String role = user.getRole();
        if (!"admin".equals(role) && !"super_admin".equals(role)) {
            throw new IllegalArgumentException("Admin permission required");
        }
        return resp(touchRequestIp(user, requestIp), true);
    }

    public Map<String, Object> requestPasswordReset(AuthDtos.ForgotPasswordRequest request) {
        String email = normalizeEmail(request.email());
        if (email.isBlank()) {
            throw new IllegalArgumentException("请输入正确邮箱");
        }
        UserEntity user = findByEmail(email);
        boolean accountFound = user != null && (user.getDeleted() == null || user.getDeleted() == 0);
        if (accountFound) {
            emailVerification.sendCode(email, EmailCodePurpose.RESET_PASSWORD);
        }
        Map<String, Object> payload = Map.of(
            "email_masked", maskEmail(email, null),
            "account_found", accountFound,
            "user_id", user == null ? "" : user.getId(),
            "note", request.note() == null ? "" : request.note().trim()
        );
        AdminAuditLogEntity log = new AdminAuditLogEntity();
        log.setId("audit_" + UUID.randomUUID().toString().replace("-", ""));
        log.setAdminId(null);
        log.setAction("password_reset_requested");
        log.setTargetType("user");
        log.setTargetId(user == null ? maskEmail(email, null) : user.getId());
        log.setSummary("用户提交找回密码请求：" + maskEmail(email, user == null ? null : user.getGithubLogin()));
        log.setBeforeJson(null);
        log.setAfterJson(toJson(payload));
        log.setCreatedAt(OffsetDateTime.now());
        log.setDeleted(0);
        auditLogs.insert(log);
        return Map.of("accepted", true, "message", accountFound ? "Verification code sent" : "Password reset request recorded");
    }

    public Map<String, Object> sendEmailCode(AuthDtos.SendEmailCodeRequest request) {
        String email = normalizeEmail(request.email());
        EmailCodePurpose purpose = EmailCodePurpose.from(request.purpose());
        UserEntity user = findByEmail(email);
        if (purpose == EmailCodePurpose.REGISTER && user != null && user.getDeleted() == 0) {
            throw new IllegalArgumentException("Email has been registered");
        }
        if ((purpose == EmailCodePurpose.LOGIN || purpose == EmailCodePurpose.RESET_PASSWORD) && (user == null || user.getDeleted() != 0 || !"active".equals(user.getStatus()))) {
            throw new IllegalArgumentException("账号不存在或不可用");
        }
        return emailVerification.sendCode(email, purpose);
    }

    public Map<String, Object> resetPassword(AuthDtos.PasswordResetConfirmRequest request) {
        String email = normalizeEmail(request.email());
        String password = request.password() == null ? "" : request.password();
        if (!password.equals(request.confirmPassword())) {
            throw new IllegalArgumentException("两次密码不一致");
        }
        UserEntity user = findByEmail(email);
        if (user == null || user.getDeleted() != 0 || !"active".equals(user.getStatus())) {
            throw new IllegalArgumentException("账号不存在或不可用");
        }
        emailVerification.verifyAndConsume(email, EmailCodePurpose.RESET_PASSWORD, request.verificationCode());
        user.setPasswordHash(enc.encode(password));
        user.setAuthProvider("password");
        user.setUpdatedAt(OffsetDateTime.now());
        users.updateById(user);
        return Map.of("updated", true, "email_masked", maskEmail(email, user.getGithubLogin()));
    }

    public AuthDtos.AuthResponse loginOrRegisterGithub(String code, String clientId, String clientSecret, String redirectUri) {
        return loginOrRegisterGithub(code, clientId, clientSecret, redirectUri, "");
    }

    public AuthDtos.AuthResponse loginOrRegisterGithub(String code, String clientId, String clientSecret, String redirectUri, String requestIp) {
        Map<?, ?> token = webClient.post()
                .uri("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .bodyValue(Map.of("client_id", clientId, "client_secret", clientSecret, "code", code, "redirect_uri", redirectUri))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(15))
                .block();
        String accessToken = token == null ? null : String.valueOf(token.get("access_token"));
        if (accessToken == null || accessToken.isBlank() || "null".equals(accessToken)) {
            throw new IllegalArgumentException("GitHub token exchange failed");
        }
        Map<?, ?> profile = webClient.get()
                .uri("https://api.github.com/user")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(15))
                .block();
        List<?> emails = List.of();
        try {
            emails = webClient.get()
                    .uri("https://api.github.com/user/emails")
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
        } catch (Exception ignored) {
            emails = List.of();
        }
        String login = "github";
        String id = UUID.randomUUID().toString();
        String avatarUrl = "";
        if (profile != null) {
            Object loginValue = profile.get("login");
            Object idValue = profile.get("id");
            Object avatarValue = profile.get("avatar_url");
            login = loginValue == null ? "github" : String.valueOf(loginValue);
            id = idValue == null ? id : String.valueOf(idValue);
            avatarUrl = avatarValue == null ? "" : String.valueOf(avatarValue);
        }
        String email = bestGithubEmail(emails);
        if (email.isBlank()) {
            email = "github_" + id + "@users.noreply.github.openimage.local";
        }
        UserEntity user = findByGithubId(id);
        if (user == null) {
            user = findByEmail(email);
        }
        if (user == null) {
            user = new UserEntity();
            user.setId("u_" + UUID.randomUUID().toString().replace("-", ""));
            user.setEmail(email);
            user.setPasswordHash(enc.encode(UUID.randomUUID().toString()));
            user.setNickname(login);
            user.setRole("user");
            user.setStatus("active");
            user.setCredits(0);
            user.setUiLanguage("zh-CN");
            user.setTheme("system");
            user.setAvatarUrl(avatarUrl);
            user.setGithubId(id);
            user.setGithubLogin(login);
            user.setAuthProvider("github");
            user.setRegisterIp(cleanIp(requestIp));
            user.setLastRequestIp(cleanIp(requestIp));
            user.setLastRequestAt(OffsetDateTime.now());
            user.setCreatedAt(OffsetDateTime.now());
            user.setUpdatedAt(OffsetDateTime.now());
            users.insert(user);
        } else {
            user.setGithubId(id);
            user.setGithubLogin(login);
            user.setAuthProvider("github");
            if ((user.getAvatarUrl() == null || user.getAvatarUrl().isBlank()) && !avatarUrl.isBlank()) {
                user.setAvatarUrl(avatarUrl);
            }
            if (user.getNickname() == null || user.getNickname().isBlank()) {
                user.setNickname(login);
            }
            user.setUpdatedAt(OffsetDateTime.now());
            users.updateById(user);
        }
        return resp(touchRequestIp(user, requestIp));
    }

    public String encodeProfile(AuthDtos.UserProfile profile) {
        String json = "{\"id\":\"" + profile.id() + "\",\"email_masked\":\"" + profile.emailMasked() + "\",\"email_display\":\"" + safeJson(profile.emailDisplay()) + "\",\"nickname\":\"" + safeJson(profile.nickname()) + "\",\"role\":\"" + profile.role() + "\",\"credits\":" + profile.credits() + ",\"ui_language\":\"" + profile.uiLanguage() + "\",\"theme\":\"" + profile.theme() + "\",\"avatar_url\":\"" + safeJson(profile.avatarUrl()) + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public AuthDtos.UserProfile profile(CurrentUser currentUser) {
        UserEntity user = users.selectById(currentUser.id());
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }
        return profile(user);
    }

    public AuthDtos.UserProfile updateProfile(CurrentUser currentUser, AuthDtos.UpdateProfileRequest request) {
        UserEntity user = users.selectById(currentUser.id());
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }
        String nickname = normalizeNickname(request.nickname());
        String uiLanguage = normalizeLanguage(request.uiLanguage());
        String theme = normalizeTheme(request.theme());
        user.setNickname(nickname);
        user.setUiLanguage(uiLanguage);
        user.setTheme(theme);
        if (request.avatarUrl() != null && request.avatarUrl().startsWith("http")) {
            user.setAvatarUrl(request.avatarUrl().trim());
        }
        user.setUpdatedAt(OffsetDateTime.now());
        users.updateById(user);
        return profile(user);
    }

    public AuthDtos.UserProfile uploadAvatar(CurrentUser currentUser, MultipartFile file) throws Exception {
        UserEntity user = users.selectById(currentUser.id());
        if (user == null || user.getDeleted() != 0) {
            throw new IllegalArgumentException("User not found");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择头像图片");
        }
        if (file.getSize() > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("头像不能超过 2MB");
        }
        LocalStorageService.StoredFile stored = storage.saveUserAvatar(user.getId(), "avatar_" + UUID.randomUUID().toString().replace("-", ""), file);
        user.setAvatarUrl(stored.url());
        user.setUpdatedAt(OffsetDateTime.now());
        users.updateById(user);
        return profile(user);
    }

    private AuthDtos.AuthResponse createAccount(AuthDtos.RegisterRequest request, String role, int credits, String nickname, String requestIp) {
        String email = normalizeEmail(request.email());
        if (findByEmail(email) != null) {
            throw new IllegalArgumentException("Email has been registered");
        }
        String ip = cleanIp(requestIp);
        UserEntity user = new UserEntity();
        user.setId("u_" + UUID.randomUUID().toString().replace("-", ""));
        user.setEmail(email);
        user.setPasswordHash(enc.encode(request.password()));
        user.setNickname(nickname);
        user.setRole(role);
        user.setStatus("active");
        user.setCredits(credits);
        user.setUiLanguage("zh-CN");
        user.setTheme("system");
        user.setAuthProvider("password");
        user.setRegisterIp(ip);
        user.setLastRequestIp(ip);
        user.setLastRequestAt(OffsetDateTime.now());
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        users.insert(user);
        return resp(user);
    }

    private UserEntity touchRequestIp(UserEntity user, String requestIp) {
        String ip = cleanIp(requestIp);
        if (user == null || ip.isBlank()) {
            return user;
        }
        user.setLastRequestIp(ip);
        user.setLastRequestAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        users.updateById(user);
        return user;
    }

    private String cleanIp(String value) {
        if (value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim())) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }

    private UserEntity authenticate(AuthDtos.LoginRequest request) {
        UserEntity user = findForLogin(request.email());
        String password = request.password() == null ? "" : request.password().trim();
        if (user == null || !enc.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }
        if (!"active".equals(user.getStatus())) {
            throw new IllegalArgumentException("Account is disabled");
        }
        return user;
    }

    private void validateRegisterRequest(AuthDtos.RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (email.isBlank()) {
            throw new IllegalArgumentException("请输入正确邮箱");
        }
        String password = request.password() == null ? "" : request.password();
        if (!password.equals(request.confirmPassword())) {
            throw new IllegalArgumentException("两次密码不一致");
        }
        if (request.verificationCode() == null || request.verificationCode().trim().isBlank()) {
            throw new IllegalArgumentException("请输入邮箱验证码");
        }
    }

    private String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private UserEntity findByEmail(String email) {
        return users.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getEmail, email.toLowerCase()));
    }

    private UserEntity findForLogin(String value) {
        String normalized = loginEmail(value);
        if (normalized.isBlank()) {
            return null;
        }
        UserEntity byEmail = findByEmail(normalized);
        if (byEmail != null) {
            return byEmail;
        }
        String githubLogin = normalized.startsWith("@") ? normalized.substring(1) : normalized;
        if (githubLogin.isBlank() || githubLogin.contains("@")) {
            return null;
        }
        return users.selectOne(new LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getDeleted, 0)
            .apply("lower(github_login) = {0}", githubLogin));
    }

    private UserEntity findByGithubId(String githubId) {
        if (githubId == null || githubId.isBlank()) {
            return null;
        }
        return users.selectOne(new LambdaQueryWrapper<UserEntity>()
            .eq(UserEntity::getGithubId, githubId)
            .eq(UserEntity::getDeleted, 0));
    }

    private String loginEmail(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return DEFAULT_ADMIN_LOGIN.equals(normalized) ? DEFAULT_ADMIN_EMAIL : normalized;
    }

    private AuthDtos.AuthResponse resp(UserEntity user) {
        return resp(user, false);
    }

    private AuthDtos.AuthResponse resp(UserEntity user, boolean rememberMe) {
        CurrentUser currentUser = new CurrentUser(user.getId(), user.getEmail(), user.getRole());
        String token = rememberMe ? jwt.issue(currentUser, REMEMBER_ME_TOKEN_MINUTES) : jwt.issue(currentUser);
        return new AuthDtos.AuthResponse(profile(user), token);
    }

    private AuthDtos.UserProfile profile(UserEntity user) {
        return new AuthDtos.UserProfile(
            user.getId(),
            maskEmail(user.getEmail(), user.getGithubLogin()),
            displayEmail(user),
            user.getNickname(),
            user.getRole(),
            user.getCredits(),
            user.getUiLanguage(),
            user.getTheme(),
            user.getAvatarUrl()
        );
    }

    private String normalizeNickname(String value) {
        if (value == null || value.isBlank()) {
            return "未命名用户";
        }
        String trimmed = value.trim();
        if (trimmed.length() > 40) {
            throw new IllegalArgumentException("Nickname must be within 40 characters");
        }
        return trimmed;
    }

    private String normalizeLanguage(String value) {
        if (value == null || value.isBlank()) {
            return "zh-CN";
        }
        return switch (value) {
            case "zh-CN", "en-US", "ja-JP" -> value;
            default -> throw new IllegalArgumentException("Unsupported language");
        };
    }

    private String normalizeTheme(String value) {
        if (value == null || value.isBlank()) {
            return "system";
        }
        return switch (value) {
            case "light", "dark", "system" -> value;
            default -> throw new IllegalArgumentException("Unsupported theme");
        };
    }

    private String safeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String maskEmail(String email, String githubLogin) {
        if (email != null && email.endsWith("@users.noreply.github.openimage.local")) {
            return githubLogin == null || githubLogin.isBlank() ? "GitHub 用户" : "GitHub @" + githubLogin;
        }
        if (email == null || email.isBlank()) {
            return "未绑定邮箱";
        }
        int at = email.indexOf('@');
        if (at <= 2) {
            return "***" + email.substring(at);
        }
        return email.substring(0, 2) + "***" + email.substring(at);
    }

    private String displayEmail(UserEntity user) {
        String email = user.getEmail();
        if (email != null && email.endsWith("@users.noreply.github.openimage.local")) {
            return user.getGithubLogin() == null || user.getGithubLogin().isBlank() ? "GitHub 用户" : "GitHub @" + user.getGithubLogin();
        }
        return maskEmail(email, user.getGithubLogin());
    }

    private String bestGithubEmail(List<?> emails) {
        if (emails == null) {
            return "";
        }
        for (Object item : emails) {
            if (item instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("primary")) && Boolean.TRUE.equals(map.get("verified")) && map.get("email") != null) {
                return String.valueOf(map.get("email")).toLowerCase();
            }
        }
        for (Object item : emails) {
            if (item instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("verified")) && map.get("email") != null) {
                return String.valueOf(map.get("email")).toLowerCase();
            }
        }
        return "";
    }
}
