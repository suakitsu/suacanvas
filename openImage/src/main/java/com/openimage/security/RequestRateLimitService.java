package com.openimage.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RequestRateLimitService {
    private static final String FAILURE_MESSAGE = "操作过于频繁，请稍后重试";

    private final Map<String, ArrayDeque<Long>> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public RequestRateLimitService() {
        this(Clock.systemUTC());
    }

    RequestRateLimitService(Clock clock) {
        this.clock = clock;
    }

    public void check(String scope, HttpServletRequest request, String subject, int limit, Duration window) {
        if (limit <= 0 || window == null || window.isZero() || window.isNegative()) {
            return;
        }
        String key = String.join("|", normalize(scope), clientIp(request), normalize(subject));
        long now = clock.millis();
        long cutoff = now - window.toMillis();
        ArrayDeque<Long> bucket = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst() < cutoff) {
                bucket.removeFirst();
            }
            if (bucket.size() >= limit) {
                throw new IllegalArgumentException(FAILURE_MESSAGE);
            }
            bucket.addLast(now);
        }
        cleanup(cutoff);
    }

    private void cleanup(long cutoff) {
        if (attempts.size() < 2048) {
            return;
        }
        Iterator<Map.Entry<String, ArrayDeque<Long>>> iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ArrayDeque<Long>> entry = iterator.next();
            ArrayDeque<Long> bucket = entry.getValue();
            synchronized (bucket) {
                while (!bucket.isEmpty() && bucket.peekFirst() < cutoff) {
                    bucket.removeFirst();
                }
                if (bucket.isEmpty()) {
                    iterator.remove();
                }
            }
        }
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "-" : normalized;
    }
}
