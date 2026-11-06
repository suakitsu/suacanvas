package com.openimage.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String AUTH_ERROR_ATTRIBUTE = "openimage.auth_error";

    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith("/api/internal/picpilot-worker/v1");
    }

    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String authorization = req.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                CurrentUser user = jwt.parse(authorization.substring(7));
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    user,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role()))
                ));
            } catch (Exception ignored) {
                req.setAttribute(AUTH_ERROR_ATTRIBUTE, "TOKEN_INVALID");
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, res);
    }
}
