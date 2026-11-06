package com.openimage.modules.auth;

import com.openimage.common.ApiResponse;
import com.openimage.modules.auth.dto.AuthDtos;
import com.openimage.security.CurrentUser;
import com.openimage.security.IpAddressUtil;
import com.openimage.security.RequestRateLimitService;
import com.openimage.security.turnstile.TurnstileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthService svc;
    private final TurnstileService turnstile;
    private final RequestRateLimitService rateLimits;

    @Value("${openimage.features.github-login-enabled:true}")
    private boolean githubLoginEnabled;

    @Value("${openimage.oauth.github-client-id:}")
    private String githubClientId;

    @Value("${openimage.oauth.github-client-secret:}")
    private String githubClientSecret;

    @Value("${openimage.oauth.user-web-base-url:http://localhost:3000}")
    private String userWebBaseUrl;

    public AuthController(AuthService svc, TurnstileService turnstile, RequestRateLimitService rateLimits) {
        this.svc = svc;
        this.turnstile = turnstile;
        this.rateLimits = rateLimits;
    }

    @PostMapping("/auth/register")
    public ApiResponse<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request, HttpServletRequest servletRequest) {
        rateLimits.check("auth-register", servletRequest, request.email(), 10, Duration.ofHours(1));
        turnstile.verify(request.turnstileToken(), servletRequest);
        return ApiResponse.ok(svc.register(request, IpAddressUtil.clientIp(servletRequest)));
    }

    @PostMapping("/auth/login")
    public ApiResponse<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request, HttpServletRequest servletRequest) {
        rateLimits.check("auth-login", servletRequest, request.email(), 30, Duration.ofMinutes(10));
        turnstile.verify(request.turnstileToken(), servletRequest);
        return ApiResponse.ok(svc.login(request, IpAddressUtil.clientIp(servletRequest)));
    }

    @PostMapping("/auth/email-code")
    public ApiResponse<java.util.Map<String, Object>> sendEmailCode(@Valid @RequestBody AuthDtos.SendEmailCodeRequest request, HttpServletRequest servletRequest) {
        rateLimits.check("auth-email-code", servletRequest, request.email() + ":" + request.purpose(), 20, Duration.ofHours(1));
        turnstile.verify(request.turnstileToken(), servletRequest);
        return ApiResponse.ok(svc.sendEmailCode(request));
    }

    @PostMapping("/auth/login/email-code")
    public ApiResponse<AuthDtos.AuthResponse> loginWithEmailCode(@Valid @RequestBody AuthDtos.EmailCodeLoginRequest request, HttpServletRequest servletRequest) {
        rateLimits.check("auth-login-code", servletRequest, request.email(), 30, Duration.ofMinutes(10));
        turnstile.verify(request.turnstileToken(), servletRequest);
        return ApiResponse.ok(svc.loginWithEmailCode(request, IpAddressUtil.clientIp(servletRequest)));
    }

    @PostMapping("/auth/forgot-password")
    public ApiResponse<java.util.Map<String, Object>> forgotPassword(@Valid @RequestBody AuthDtos.ForgotPasswordRequest request, HttpServletRequest servletRequest) {
        rateLimits.check("auth-forgot-password", servletRequest, request.email(), 20, Duration.ofHours(1));
        turnstile.verify(request.turnstileToken(), servletRequest);
        return ApiResponse.ok(svc.requestPasswordReset(request));
    }

    @PostMapping("/auth/password-reset")
    public ApiResponse<java.util.Map<String, Object>> resetPassword(@Valid @RequestBody AuthDtos.PasswordResetConfirmRequest request, HttpServletRequest servletRequest) {
        rateLimits.check("auth-password-reset", servletRequest, request.email(), 20, Duration.ofHours(1));
        turnstile.verify(request.turnstileToken(), servletRequest);
        return ApiResponse.ok(svc.resetPassword(request));
    }

    @GetMapping("/auth/github/status")
    public ApiResponse<java.util.Map<String, Object>> githubStatus() {
        boolean enabled = githubLoginEnabled && githubClientId != null && !githubClientId.isBlank() && githubClientSecret != null && !githubClientSecret.isBlank();
        return ApiResponse.ok(java.util.Map.of("enabled", enabled));
    }

    @GetMapping("/auth/github")
    public ResponseEntity<Void> githubLogin(@RequestParam(required = false) String next) {
        if (!githubLoginEnabled) {
            return redirect(userWebBaseUrl + "/login?error=" + encode("GitHub 登录未启用"));
        }
        if (githubClientId == null || githubClientId.isBlank()) {
            return redirect(userWebBaseUrl + "/login?error=" + encode("GitHub OAuth 未配置 Client ID"));
        }
        String redirectUri = callbackUrl();
        String state = safeNext(next);
        if (state.isBlank()) {
            state = UUID.randomUUID().toString();
        }
        String url = "https://github.com/login/oauth/authorize?client_id=" + encode(githubClientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode("read:user user:email")
                + "&state=" + encode(state);
        return redirect(url);
    }

    @GetMapping("/auth/github/callback")
    public ResponseEntity<Void> githubCallback(String code, @RequestParam(required = false) String state, HttpServletRequest servletRequest) {
        if (!githubLoginEnabled || githubClientId == null || githubClientId.isBlank() || githubClientSecret == null || githubClientSecret.isBlank()) {
            return redirect(userWebBaseUrl + "/login?error=" + encode("GitHub OAuth 未完整配置"));
        }
        try {
            AuthDtos.AuthResponse auth = svc.loginOrRegisterGithub(code, githubClientId, githubClientSecret, callbackUrl(), IpAddressUtil.clientIp(servletRequest));
            String next = safeNext(state);
            String nextParam = next.isBlank() ? "" : "&next=" + encode(next);
            return redirect(userWebBaseUrl + "/auth/github/callback?token=" + encode(auth.accessToken()) + "&user=" + encode(svc.encodeProfile(auth.user())) + nextParam);
        } catch (Exception ex) {
            return redirect(userWebBaseUrl + "/login?error=" + encode("GitHub 登录失败"));
        }
    }

    @PostMapping("/auth/admin/register")
    public ApiResponse<AuthDtos.AuthResponse> adminRegister(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        throw new IllegalArgumentException("Admin registration is disabled");
    }

    @PostMapping("/auth/admin/login")
    public ApiResponse<AuthDtos.AuthResponse> adminLogin(@Valid @RequestBody AuthDtos.LoginRequest request, HttpServletRequest servletRequest) {
        rateLimits.check("admin-login", servletRequest, request.email(), 20, Duration.ofMinutes(10));
        turnstile.verify(request.turnstileToken(), servletRequest);
        return ApiResponse.ok(svc.adminLogin(request, IpAddressUtil.clientIp(servletRequest)));
    }

    @GetMapping("/me")
    public ApiResponse<AuthDtos.UserProfile> me(@AuthenticationPrincipal CurrentUser user) {
        return ApiResponse.ok(svc.profile(user));
    }

    @PatchMapping("/me")
    public ApiResponse<AuthDtos.UserProfile> updateMe(@AuthenticationPrincipal CurrentUser user, @RequestBody AuthDtos.UpdateProfileRequest request) {
        return ApiResponse.ok(svc.updateProfile(user, request));
    }

    @PostMapping("/me/avatar")
    public ApiResponse<AuthDtos.UserProfile> uploadAvatar(@AuthenticationPrincipal CurrentUser user, @RequestParam("file") MultipartFile file) throws Exception {
        return ApiResponse.ok(svc.uploadAvatar(user, file));
    }

    private String callbackUrl() {
        return userWebBaseUrl.replaceAll("/+$", "") + "/api/auth/github/callback";
    }

    private String safeNext(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.startsWith("/") && !trimmed.startsWith("//") ? trimmed : "";
    }

    private ResponseEntity<Void> redirect(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(url));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
