package com.openimage.ai;

import com.openimage.domain.entity.AiTextCallLogEntity;
import com.openimage.domain.mapper.AiTextCallLogMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class AiTextCallAuditService {
    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();

    private final AiTextCallLogMapper logs;

    public AiTextCallAuditService(AiTextCallLogMapper logs) {
        this.logs = logs;
    }

    public Scope open(String userId, String feature, String targetType, String targetId) {
        Context previous = CONTEXT.get();
        CONTEXT.set(new Context(clean(userId), clean(feature), clean(targetType), clean(targetId)));
        return () -> {
            if (previous == null) {
                CONTEXT.remove();
            } else {
                CONTEXT.set(previous);
            }
        };
    }

    public Context current() {
        Context context = CONTEXT.get();
        return context == null ? new Context("", "unknown", "", "") : context;
    }

    public void record(
        Context context,
        String provider,
        String model,
        String status,
        Integer httpStatus,
        long durationMs,
        int inputChars,
        int outputChars,
        String errorType,
        String errorMessage
    ) {
        try {
            Context safe = context == null ? current() : context;
            AiTextCallLogEntity row = new AiTextCallLogEntity();
            row.setId("txtlog_" + UUID.randomUUID().toString().replace("-", ""));
            row.setUserId(blankToNull(safe.userId()));
            row.setFeature(blankToDefault(safe.feature(), "unknown"));
            row.setTargetType(blankToNull(safe.targetType()));
            row.setTargetId(blankToNull(safe.targetId()));
            row.setProvider(clean(provider));
            row.setModel(clean(model));
            row.setStatus(blankToDefault(status, "unknown"));
            row.setHttpStatus(httpStatus);
            row.setDurationMs((int) Math.max(0, Math.min(durationMs, Integer.MAX_VALUE)));
            row.setInputChars(Math.max(0, inputChars));
            row.setOutputChars(Math.max(0, outputChars));
            row.setErrorType(blankToNull(errorType));
            row.setErrorMessage(blankToNull(truncate(errorMessage, 500)));
            row.setCreatedAt(OffsetDateTime.now());
            row.setDeleted(0);
            logs.insert(row);
        } catch (Exception ignored) {
            // Audit logs must never break user-facing AI flows.
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToDefault(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private static String blankToNull(String value) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? null : cleaned;
    }

    private static String truncate(String value, int limit) {
        String cleaned = clean(value);
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }

    public record Context(String userId, String feature, String targetType, String targetId) {}

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
