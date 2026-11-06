package com.openimage.security;

import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class UserRequestIpTrackingFilter extends OncePerRequestFilter {
    private static final long MIN_UPDATE_MILLIS = 5 * 60 * 1000L;

    private final UserMapper users;
    private final Map<String, Long> recentUpdates = new ConcurrentHashMap<>();

    public UserRequestIpTrackingFilter(UserMapper users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            Object principal = authentication == null ? null : authentication.getPrincipal();
            if (principal instanceof CurrentUser user) {
                track(user.id(), IpAddressUtil.clientIp(request));
            }
        } catch (Exception ignored) {
            // IP tracking must never block the request path.
        }
        filterChain.doFilter(request, response);
    }

    private void track(String userId, String ip) {
        if (userId == null || userId.isBlank() || ip == null || ip.isBlank()) {
            return;
        }
        String key = userId + "|" + ip;
        long now = System.currentTimeMillis();
        Long previous = recentUpdates.get(key);
        if (previous != null && now - previous < MIN_UPDATE_MILLIS) {
            return;
        }
        recentUpdates.put(key, now);
        UserEntity user = users.selectById(userId);
        if (user == null || user.getDeleted() == null || user.getDeleted() != 0) {
            return;
        }
        user.setLastRequestIp(ip);
        user.setLastRequestAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        users.updateById(user);
    }
}
