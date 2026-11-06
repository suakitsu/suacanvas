package com.openimage.ai.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

@Component
public class AiWorkerAuthenticationFilter extends OncePerRequestFilter {
    public static final String PREFIX = "/api/internal/picpilot-worker/v1";
    public static final String PRINCIPAL_ATTRIBUTE = "openimage.ai_worker_principal";

    private final AiWorkerTokenService tokens;
    private final ObjectMapper json;

    public AiWorkerAuthenticationFilter(AiWorkerTokenService tokens, ObjectMapper json) {
        this.tokens = tokens;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI() == null || !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            requireHttps(request);
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new AiWorkerProtocolException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "WORKER_TOKEN_MISSING",
                    "Authorization: Bearer <worker_token> is required"
                );
            }
            AiWorkerPrincipal principal = tokens.authenticate(authorization.substring(7).trim());
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            filterChain.doFilter(request, response);
        } catch (AiWorkerProtocolException exception) {
            writeError(response, exception);
        }
    }

    private void requireHttps(HttpServletRequest request) {
        if (request.isSecure()) {
            return;
        }
        String forwardedProto = firstHeaderValue(request.getHeader("X-Forwarded-Proto"));
        String forwarded = request.getHeader("Forwarded");
        boolean forwardedHttps = forwarded != null && forwarded.toLowerCase(Locale.ROOT).contains("proto=https");
        if (!"https".equalsIgnoreCase(forwardedProto) && !forwardedHttps) {
            throw new AiWorkerProtocolException(
                org.springframework.http.HttpStatus.UPGRADE_REQUIRED,
                "HTTPS_REQUIRED",
                "AI Worker API requires HTTPS"
            );
        }
    }

    private String firstHeaderValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int comma = value.indexOf(',');
        return (comma >= 0 ? value.substring(0, comma) : value).trim();
    }

    private void writeError(HttpServletResponse response, AiWorkerProtocolException exception) throws IOException {
        response.setStatus(exception.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), Map.of(
            "error", Map.of(
                "code", exception.code(),
                "message", exception.getMessage(),
                "retryable", exception.retryable()
            )
        ));
    }
}
