package com.openimage.modules.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.openimage.common.ApiResponse;
import com.openimage.common.BusinessTime;
import com.openimage.domain.entity.AiTextCallLogEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.mapper.AiTextCallLogMapper;
import com.openimage.domain.mapper.UserMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/text-calls")
@PreAuthorize("hasAnyRole('admin','super_admin')")
public class AdminTextCallController {
    private final AiTextCallLogMapper logs;
    private final UserMapper users;

    public AdminTextCallController(AiTextCallLogMapper logs, UserMapper users) {
        this.logs = logs;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "provider", required = false) String provider,
        @RequestParam(value = "model", required = false) String model,
        @RequestParam(value = "feature", required = false) String feature,
        @RequestParam(value = "user_keyword", required = false) String userKeyword,
        @RequestParam(value = "from", required = false) String from,
        @RequestParam(value = "to", required = false) String to,
        @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
        @RequestParam(value = "page_size", required = false, defaultValue = "20") Integer pageSize
    ) {
        int normalizedPage = Math.max(1, page == null ? 1 : page);
        int normalizedSize = Math.max(1, Math.min(100, pageSize == null ? 20 : pageSize));
        Set<String> matchingUserIds = matchingUserIds(userKeyword);
        Filters filters = new Filters(keyword, status, provider, model, feature, userKeyword, from, to, matchingUserIds);

        long total = logs.selectCount(query(filters));
        List<AiTextCallLogEntity> records = logs.selectList(query(filters)
            .orderByDesc("created_at")
            .last("limit " + normalizedSize + " offset " + ((normalizedPage - 1) * normalizedSize)));
        Map<String, UserEntity> userMap = userMap(records);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rows", records.stream().map(record -> row(record, userMap.get(record.getUserId()))).toList());
        response.put("total", total);
        response.put("page", normalizedPage);
        response.put("page_size", normalizedSize);
        response.put("total_pages", total == 0 ? 0 : (total + normalizedSize - 1) / normalizedSize);
        response.put("summary", summary(filters));
        return ApiResponse.ok(response);
    }

    private QueryWrapper<AiTextCallLogEntity> query(Filters filters) {
        QueryWrapper<AiTextCallLogEntity> query = new QueryWrapper<>();
        query.eq("deleted", 0);
        exact(query, "status", filters.status());
        exact(query, "provider", filters.provider());
        exact(query, "model", filters.model());
        exact(query, "feature", filters.feature());

        String search = lower(filters.keyword());
        if (!search.isBlank()) {
            String like = "%" + search + "%";
            query.and(wrapper -> wrapper
                .apply("lower(id) like {0}", like)
                .or().apply("lower(feature) like {0}", like)
                .or().apply("lower(coalesce(target_type, '')) like {0}", like)
                .or().apply("lower(coalesce(provider, '')) like {0}", like)
                .or().apply("lower(coalesce(model, '')) like {0}", like)
                .or().apply("lower(coalesce(error_type, '')) like {0}", like));
        }

        String userSearch = lower(filters.userKeyword());
        if (!userSearch.isBlank()) {
            String like = "%" + userSearch + "%";
            query.and(wrapper -> {
                if (!filters.matchingUserIds().isEmpty()) {
                    wrapper.in("user_id", filters.matchingUserIds()).or();
                }
                wrapper.apply("lower(coalesce(user_id, '')) like {0}", like);
            });
        }

        if (!text(filters.from()).isBlank()) {
            query.ge("created_at", BusinessTime.startOfDay(LocalDate.parse(filters.from().trim())));
        }
        if (!text(filters.to()).isBlank()) {
            query.le("created_at", BusinessTime.endOfDay(LocalDate.parse(filters.to().trim())));
        }
        return query;
    }

    private Map<String, Object> summary(Filters filters) {
        QueryWrapper<AiTextCallLogEntity> aggregate = query(filters);
        aggregate.select(
            "count(*) AS calls",
            "count(distinct user_id) AS users",
            "sum(case when status = 'success' then 1 else 0 end) AS success",
            "sum(case when status = 'failed' then 1 else 0 end) AS failed",
            "sum(case when status = 'queued' then 1 else 0 end) AS queued",
            "coalesce(round(avg(duration_ms))::bigint, 0) AS avg_duration_ms"
        );
        Map<String, Object> source = logs.selectMaps(aggregate).stream().findFirst().orElse(Map.of());
        long calls = longValue(source.get("calls"));
        long success = longValue(source.get("success"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("calls", calls);
        result.put("users", longValue(source.get("users")));
        result.put("success", success);
        result.put("failed", longValue(source.get("failed")));
        result.put("queued", longValue(source.get("queued")));
        result.put("success_rate", calls == 0
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(success * 100d / calls).setScale(2, RoundingMode.HALF_UP));
        result.put("avg_duration_ms", longValue(source.get("avg_duration_ms")));
        return result;
    }

    private Set<String> matchingUserIds(String keyword) {
        String search = lower(keyword);
        if (search.isBlank()) {
            return Set.of();
        }
        String like = "%" + search + "%";
        return users.selectList(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getDeleted, 0)
                .and(wrapper -> wrapper
                    .apply("lower(id) like {0}", like)
                    .or().apply("lower(coalesce(email, '')) like {0}", like)
                    .or().apply("lower(coalesce(nickname, '')) like {0}", like)))
            .stream()
            .map(UserEntity::getId)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toSet());
    }

    private Map<String, UserEntity> userMap(List<AiTextCallLogEntity> records) {
        Set<String> ids = records.stream()
            .map(AiTextCallLogEntity::getUserId)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.selectList(new LambdaQueryWrapper<UserEntity>().in(UserEntity::getId, ids)).stream()
            .collect(Collectors.toMap(UserEntity::getId, Function.identity(), (left, right) -> left));
    }

    private Map<String, Object> row(AiTextCallLogEntity record, UserEntity user) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", record.getId());
        row.put("user_id", text(record.getUserId()));
        row.put("user_email", user == null ? "" : text(user.getEmail()));
        row.put("user_nickname", user == null ? "" : text(user.getNickname()));
        row.put("attributed", record.getUserId() != null && !record.getUserId().isBlank());
        row.put("feature", text(record.getFeature()));
        row.put("target_type", text(record.getTargetType()));
        row.put("provider", text(record.getProvider()));
        row.put("model", text(record.getModel()));
        row.put("status", text(record.getStatus()));
        row.put("http_status", record.getHttpStatus());
        row.put("duration_ms", value(record.getDurationMs()));
        row.put("input_chars", value(record.getInputChars()));
        row.put("output_chars", value(record.getOutputChars()));
        row.put("error_type", text(record.getErrorType()));
        row.put("created_at", record.getCreatedAt());
        return row;
    }

    private void exact(QueryWrapper<AiTextCallLogEntity> query, String column, String value) {
        String cleaned = lower(value);
        if (!cleaned.isBlank()) {
            query.apply("lower(coalesce(" + column + ", '')) = {0}", cleaned);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String lower(String value) {
        return text(value).toLowerCase(java.util.Locale.ROOT);
    }

    private static int value(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private record Filters(
        String keyword,
        String status,
        String provider,
        String model,
        String feature,
        String userKeyword,
        String from,
        String to,
        Set<String> matchingUserIds
    ) {}
}
