package com.openimage.ai.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openimage.domain.entity.AiWorkerTokenEntity;
import com.openimage.domain.mapper.AiWorkerTokenMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@EnableConfigurationProperties(AiWorkerProperties.class)
public class AiWorkerTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AiWorkerTokenMapper tokens;
    private final AiWorkerProperties properties;

    public AiWorkerTokenService(AiWorkerTokenMapper tokens, AiWorkerProperties properties) {
        this.tokens = tokens;
        this.properties = properties;
    }

    public AiWorkerPrincipal authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AiWorkerProtocolException(HttpStatus.UNAUTHORIZED, "WORKER_TOKEN_MISSING", "Worker token is required");
        }
        AiWorkerTokenEntity token = tokens.findActiveByHash(hash(rawToken));
        if (token == null) {
            throw new AiWorkerProtocolException(HttpStatus.UNAUTHORIZED, "WORKER_TOKEN_INVALID", "Worker token is invalid or revoked");
        }
        tokens.touch(token.getId());
        return new AiWorkerPrincipal(token.getId(), token.getName(), token.getTokenPrefix());
    }

    @Transactional
    public CreatedToken create(String name) {
        String rawToken = generateToken();
        OffsetDateTime now = OffsetDateTime.now();
        AiWorkerTokenEntity row = new AiWorkerTokenEntity();
        row.setId("awt_" + id());
        row.setName(cleanName(name));
        row.setTokenPrefix(prefix(rawToken));
        row.setTokenHash(hash(rawToken));
        row.setEnabled(true);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setDeleted(0);
        tokens.insert(row);
        return new CreatedToken(rawToken, adminView(row));
    }

    @Transactional
    public CreatedToken regenerate(String tokenId) {
        AiWorkerTokenEntity row = required(tokenId);
        String rawToken = generateToken();
        OffsetDateTime now = OffsetDateTime.now();
        row.setTokenPrefix(prefix(rawToken));
        row.setTokenHash(hash(rawToken));
        row.setEnabled(true);
        row.setRevokedAt(null);
        row.setUpdatedAt(now);
        tokens.updateById(row);
        return new CreatedToken(rawToken, adminView(row));
    }

    @Transactional
    public Map<String, Object> revoke(String tokenId) {
        AiWorkerTokenEntity row = required(tokenId);
        OffsetDateTime now = OffsetDateTime.now();
        row.setEnabled(false);
        row.setRevokedAt(now);
        row.setUpdatedAt(now);
        tokens.updateById(row);
        return adminView(row);
    }

    public List<Map<String, Object>> adminViews() {
        return tokens.selectList(new LambdaQueryWrapper<AiWorkerTokenEntity>()
                .eq(AiWorkerTokenEntity::getDeleted, 0)
                .orderByDesc(AiWorkerTokenEntity::getCreatedAt))
            .stream()
            .map(this::adminView)
            .toList();
    }

    private AiWorkerTokenEntity required(String tokenId) {
        AiWorkerTokenEntity row = tokens.selectById(tokenId);
        if (row == null || row.getDeleted() == null || row.getDeleted() != 0) {
            throw new IllegalArgumentException("Worker Token 不存在");
        }
        return row;
    }

    private Map<String, Object> adminView(AiWorkerTokenEntity row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.getId());
        view.put("name", row.getName());
        view.put("token_prefix", row.getTokenPrefix());
        view.put("enabled", Boolean.TRUE.equals(row.getEnabled()) && row.getRevokedAt() == null);
        view.put("last_used_at", row.getLastUsedAt());
        view.put("revoked_at", row.getRevokedAt());
        view.put("created_at", row.getCreatedAt());
        return view;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "ppw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        String pepper = properties.tokenPepperValue();
        if (pepper.length() < 32) {
            throw new IllegalStateException("AI Worker token pepper must contain at least 32 characters");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(pepper.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hashed);
        } catch (Exception exception) {
            throw new IllegalStateException("AI Worker token hashing failed", exception);
        }
    }

    private String prefix(String token) {
        return token.substring(0, Math.min(token.length(), 12));
    }

    private String cleanName(String value) {
        String cleaned = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (cleaned.isBlank()) {
            return "PicPilot Local Worker";
        }
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    private String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public record CreatedToken(String token, Map<String, Object> workerToken) {
    }
}
