package com.openimage.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;

@Service
public class JwtService {
    private final SecurityProperties properties;
    private final SecretKey key;

    public JwtService(SecurityProperties properties) {
        this.properties = properties;
        String secret = properties.jwtSecret();
        String normalized = secret == null ? "" : secret.toLowerCase(Locale.ROOT);
        if (secret == null || secret.isBlank()
                || secret.getBytes(StandardCharsets.UTF_8).length < 32
                || normalized.contains("change-me") || normalized.contains("changeme")
                || normalized.contains("replace-me")) {
            throw new IllegalStateException("JWT_SECRET must be a private random value of at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(CurrentUser user) {
        return issue(user, properties.accessTokenMinutes());
    }

    public String issue(CurrentUser user, long minutes) {
        Instant now = Instant.now();
        long ttl = Math.max(1, minutes);
        return Jwts.builder().subject(user.id()).claim("email", user.email()).claim("role", user.role())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(ttl * 60)))
                .signWith(key).compact();
    }

    public CurrentUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new CurrentUser(claims.getSubject(), claims.get("email", String.class), claims.get("role", String.class));
    }
}
