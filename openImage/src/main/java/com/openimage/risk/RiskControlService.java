package com.openimage.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.RiskEventEntity;
import com.openimage.domain.entity.SystemSettingEntity;
import com.openimage.domain.entity.UserEntity;
import com.openimage.domain.mapper.RiskEventMapper;
import com.openimage.domain.mapper.SystemSettingMapper;
import com.openimage.domain.mapper.UserMapper;
import com.openimage.modules.auth.email.TencentSesEmailService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class RiskControlService {
    public static final String BLOCKED_MESSAGE = "内容可能触发平台风控，本次生成已停止，未消耗积分。请修改描述后重试，如有疑问请联系管理员。";
    public static final String BANNED_MESSAGE = "账号因多次触发风控已被限制使用，请联系管理员解禁。";
    private static final String SETTING_KEY = "risk_control";
    private static final String USER_INPUT_SOURCE = "user_input";
    private static final String DEFAULT_CONFIG = """
        {
          "enabled": true,
          "ban_threshold": 5,
          "block_levels": ["high", "medium"],
          "warn_levels": ["low"],
          "keywords": {
            "high": ["色情", "成人内容", "裸露", "全裸", "裸体", "露点", "性爱", "性交", "性行为", "援交", "卖淫", "未成年裸露", "儿童色情", "幼女裸", "萝莉裸", "血腥", "肢解", "尸体", "自残", "自杀", "虐待", "毒品", "吸毒", "贩毒", "大麻", "冰毒", "海洛因", "可卡因", "枪支", "手枪", "步枪", "炸弹", "爆炸物", "制爆", "杀人", "身份证", "护照", "银行卡", "信用卡", "验证码", "密码", "私钥", "纳粹", "恐怖主义", "极端组织"],
            "medium": ["仿牌", "高仿", "复刻", "原单", "尾单", "莆田", "山寨", "假冒", "侵权", "logo复刻", "商标复刻", "大牌同款", "明星真人", "名人脸", "网红真人", "换脸", "AI换脸", "deepfake", "迪士尼", "漫威", "宝可梦", "皮卡丘", "Hello Kitty", "奥特曼", "Nike", "Adidas", "Gucci", "LV", "Chanel", "Dior", "Prada", "Hermes", "政治敏感", "领导人", "宗教攻击", "医疗功效", "治愈", "根治", "减肥神药", "壮阳"],
            "low": ["性感", "泳装", "内衣", "比基尼", "儿童", "小孩", "宝宝", "刀", "军事", "战争", "品牌同款", "大牌风", "奢侈品风"]
          }
        }
        """;
    private static final List<String> PROVIDER_RISK_WORDS = List.of("risk control", "moderation", "keyword", "policy", "sensitive");
    private static final List<NegationMarker> NEGATION_MARKERS = List.of(
        new NegationMarker("do not include", 120),
        new NegationMarker("must not include", 120),
        new NegationMarker("should not include", 120),
        new NegationMarker("should not show", 120),
        new NegationMarker("must not show", 120),
        new NegationMarker("不要出现", 96),
        new NegationMarker("不得出现", 96),
        new NegationMarker("不能出现", 96),
        new NegationMarker("不可出现", 96),
        new NegationMarker("禁止出现", 96),
        new NegationMarker("严禁出现", 96),
        new NegationMarker("不要包含", 96),
        new NegationMarker("不得包含", 96),
        new NegationMarker("不能包含", 96),
        new NegationMarker("不可包含", 96),
        new NegationMarker("不应包含", 96),
        new NegationMarker("避免", 96),
        new NegationMarker("规避", 96),
        new NegationMarker("杜绝", 96),
        new NegationMarker("排除", 96),
        new NegationMarker("去除", 96),
        new NegationMarker("移除", 96),
        new NegationMarker("不要", 96),
        new NegationMarker("不得", 96),
        new NegationMarker("禁止", 96),
        new NegationMarker("严禁", 96),
        new NegationMarker("without", 120),
        new NegationMarker("avoid", 120),
        new NegationMarker("exclude", 120),
        new NegationMarker("no ", 80),
        new NegationMarker("不含", 24),
        new NegationMarker("无任何", 16),
        new NegationMarker("无", 4)
    );
    private static final List<String> NEGATION_SCOPE_BREAKS = List.of(
        "但是", "不过", "然而", "反而", "其实", "实际", "却", "但", "but", "however", "instead", "yet"
    );
    private static final List<String> POSITIVE_DIRECTIVES = List.of(
        "生成", "加入", "包含", "呈现", "展示", "表现", "需要", "想要", "制作", "画出", "营造", "使用", "保留", "突出", "增强", "带有",
        "generate", "include", "show", "depict", "add", "keep", "emphasize", "with"
    );
    private static final List<String> PARTIAL_NEGATION_QUALIFIERS = List.of(
        "太", "过于", "过度", "非常", "稍微", "一点", "少量", "轻微", "适度", "too", "slightly", "some"
    );
    private static final List<String> POSTFIX_NEGATION_MARKERS = List.of(
        "不要出现", "不得出现", "不能出现", "不可出现", "禁止出现", "严禁出现", "不应出现", "不要包含", "不得包含", "需避免", "应避免",
        "must not appear", "should not appear", "do not include", "must not include"
    );

    private final UserMapper users;
    private final RiskEventMapper riskEvents;
    private final SystemSettingMapper settings;
    private final ObjectMapper json;
    private final TencentSesEmailService email;
    private final TransactionTemplate requiresNew;

    public RiskControlService(
        UserMapper users,
        RiskEventMapper riskEvents,
        SystemSettingMapper settings,
        ObjectMapper json,
        TencentSesEmailService email,
        PlatformTransactionManager transactionManager
    ) {
        this.users = users;
        this.riskEvents = riskEvents;
        this.settings = settings;
        this.json = json;
        this.email = email;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void ensureAllowed(String userId) {
        UserEntity user = user(userId);
        if (user != null && "banned".equalsIgnoreCase(user.getStatus())) {
            throw new RiskControlException(BANNED_MESSAGE);
        }
    }

    public void checkBeforeProvider(String userId, String projectId, String taskId, String feature, String text, String source) {
        UserEntity user = user(userId);
        if (user == null) {
            return;
        }
        if ("banned".equalsIgnoreCase(user.getStatus())) {
            throw new RiskControlException(BANNED_MESSAGE);
        }
        String normalizedSource = clean(source, "unknown").toLowerCase(Locale.ROOT);
        if (!USER_INPUT_SOURCE.equals(normalizedSource)) {
            return;
        }
        RiskConfig config = config();
        if (!config.enabled()) {
            return;
        }
        Hit hit = firstHit(config, text);
        if (hit == null) {
            return;
        }
        boolean blocked = config.blockLevels().contains(hit.level());
        boolean warned = config.warnLevels().contains(hit.level());
        if (!blocked && !warned) {
            return;
        }
        RecordResult result = record(user.getId(), projectId, taskId, feature, hit.level(), hit.keyword(), text, normalizedSource, blocked ? "blocked" : "warned", config.banThreshold());
        sendWarning(user.getEmail(), feature, hit.level(), hit.keyword(), result.count(), config.banThreshold());
        if (result.banned()) {
            sendBan(user.getEmail(), result.count(), config.banThreshold());
            throw new RiskControlException(BANNED_MESSAGE);
        }
        if (blocked) {
            throw new RiskControlException(BLOCKED_MESSAGE);
        }
    }

    public void recordProviderError(String userId, String projectId, String taskId, String feature, String errorMessage) {
        UserEntity user = user(userId);
        RiskConfig config = config();
        if (user == null || !config.enabled()) {
            return;
        }
        String keyword = providerRiskKeyword(errorMessage);
        if (keyword.isBlank()) {
            return;
        }
        String normalizedTaskId = clean(taskId, "");
        if (!normalizedTaskId.isBlank()) {
            long existing = riskEvents.selectCount(new LambdaQueryWrapper<RiskEventEntity>()
                .eq(RiskEventEntity::getDeleted, 0)
                .eq(RiskEventEntity::getUserId, user.getId())
                .eq(RiskEventEntity::getTaskId, normalizedTaskId)
                .eq(RiskEventEntity::getSource, "provider_error"));
            if (existing > 0) {
                return;
            }
        }
        RecordResult result = record(user.getId(), projectId, taskId, feature, "medium", keyword, snippet(errorMessage, keyword), "provider_error", "recorded", config.banThreshold());
        sendWarning(user.getEmail(), feature, "medium", keyword, result.count(), config.banThreshold());
        if (result.banned()) {
            sendBan(user.getEmail(), result.count(), config.banThreshold());
        }
    }

    private RecordResult record(String userId, String projectId, String taskId, String feature, String level, String keyword, String matchedText, String source, String action, int threshold) {
        return requiresNew.execute(status -> {
            RiskEventEntity row = new RiskEventEntity();
            row.setId("risk_" + UUID.randomUUID().toString().replace("-", ""));
            row.setUserId(userId);
            row.setProjectId(blankToNull(projectId));
            row.setTaskId(blankToNull(taskId));
            row.setFeature(clean(feature, "unknown"));
            row.setRiskLevel(clean(level, "medium"));
            row.setKeyword(truncate(clean(keyword, ""), 120));
            row.setMatchedText(truncate(matchedText, 8000));
            row.setSource(clean(source, "unknown"));
            row.setAction(clean(action, "recorded"));
            row.setCreatedAt(OffsetDateTime.now());
            row.setDeleted(0);
            riskEvents.insert(row);

            long count = riskEvents.selectCount(new LambdaQueryWrapper<RiskEventEntity>()
                .eq(RiskEventEntity::getDeleted, 0)
                .eq(RiskEventEntity::getUserId, userId));
            boolean banned = false;
            if (count >= threshold) {
                UserEntity locked = users.lockById(userId);
                if (locked != null && locked.getDeleted() == 0 && !"banned".equalsIgnoreCase(locked.getStatus())) {
                    locked.setStatus("banned");
                    locked.setUpdatedAt(OffsetDateTime.now());
                    users.updateById(locked);
                    banned = true;
                }
            }
            return new RecordResult(count, banned);
        });
    }

    private UserEntity user(String userId) {
        String id = clean(userId, "");
        return id.isBlank() ? null : users.selectById(id);
    }

    private RiskConfig config() {
        try {
            SystemSettingEntity row = settings.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
                .eq(SystemSettingEntity::getDeleted, 0)
                .eq(SystemSettingEntity::getSettingKey, SETTING_KEY));
            JsonNode root = json.readTree(row == null ? DEFAULT_CONFIG : row.getSettingValue());
            return new RiskConfig(
                !root.has("enabled") || root.path("enabled").asBoolean(true),
                Math.max(1, root.path("ban_threshold").asInt(5)),
                list(root.path("block_levels")),
                list(root.path("warn_levels")),
                keywords(root.path("keywords"))
            );
        } catch (Exception ignored) {
            try {
                JsonNode root = json.readTree(DEFAULT_CONFIG);
                return new RiskConfig(true, 5, list(root.path("block_levels")), list(root.path("warn_levels")), keywords(root.path("keywords")));
            } catch (Exception impossible) {
                return new RiskConfig(true, 5, List.of("high", "medium"), List.of("low"), Map.of());
            }
        }
    }

    private Hit firstHit(RiskConfig config, String raw) {
        String text = normalize(raw);
        if (text.isBlank()) {
            return null;
        }
        for (String level : List.of("high", "medium", "low")) {
            for (String keyword : config.keywords().getOrDefault(level, List.of())) {
                String normalizedKeyword = normalize(keyword);
                if (!normalizedKeyword.isBlank() && matchesKeyword(text, normalizedKeyword)) {
                    return new Hit(level, keyword, snippet(raw, keyword));
                }
            }
        }
        return null;
    }

    private boolean matchesKeyword(String text, String keyword) {
        Pattern pattern = keyword.chars().allMatch(ch -> ch < 128 && (Character.isLetterOrDigit(ch) || Character.isWhitespace(ch) || ch == '-' || ch == '_'))
            ? Pattern.compile("(?<![a-z0-9])" + Pattern.quote(keyword) + "(?![a-z0-9])")
            : Pattern.compile(Pattern.quote(keyword));
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (!isNegativeConstraint(text, matcher.start(), matcher.end())) {
                return true;
            }
        }
        return false;
    }

    private boolean isNegativeConstraint(String text, int matchStart, int matchEnd) {
        String after = text.substring(matchEnd, Math.min(text.length(), matchEnd + 32));
        if (startsWithAny(after.stripLeading(), POSTFIX_NEGATION_MARKERS)) {
            return true;
        }

        int scopeStart = negativeScopeStart(text, matchStart);
        String prefix = text.substring(scopeStart, matchStart);
        NegationMatch negation = latestNegation(prefix);
        if (negation == null) {
            return false;
        }
        String beforeMarker = prefix.substring(Math.max(0, negation.index() - 6), negation.index());
        if (endsWithAny(beforeMarker, List.of("不", "无需", "不用", "不要", "not "))) {
            return false;
        }
        String between = prefix.substring(negation.index() + negation.marker().value().length());
        if (containsAny(between, POSITIVE_DIRECTIVES)
            || containsAny(between, PARTIAL_NEGATION_QUALIFIERS)
            || containsAny(between, List.of("不避免", "无需避免", "不用避免", "不要避免", "不禁止"))) {
            return false;
        }

        String immediateAfter = afterUntilBoundary(after, 24);
        return !containsAny(immediateAfter, List.of(
            "要突出", "需突出", "要加入", "需加入", "要展示", "需展示", "要呈现", "需呈现", "要增强", "需增强",
            "should be shown", "must be shown", "include it", "show it", "emphasize it"
        ));
    }

    private int negativeScopeStart(String text, int endExclusive) {
        int start = 0;
        String prefix = text.substring(0, endExclusive);
        for (int index = 0; index < prefix.length(); index++) {
            char ch = prefix.charAt(index);
            if (ch == '\n' || ch == '\r' || ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == ';' || ch == '；') {
                start = index + 1;
            }
        }
        for (String boundary : NEGATION_SCOPE_BREAKS) {
            int index = prefix.lastIndexOf(boundary);
            if (index >= 0) {
                start = Math.max(start, index + boundary.length());
            }
        }
        return start;
    }

    private NegationMatch latestNegation(String prefix) {
        NegationMatch latest = null;
        for (NegationMarker marker : NEGATION_MARKERS) {
            int index = prefix.lastIndexOf(marker.value());
            if (index < 0 || prefix.length() - index - marker.value().length() > marker.maxDistance()) {
                continue;
            }
            if (latest == null || index > latest.index()) {
                latest = new NegationMatch(marker, index);
            }
        }
        return latest;
    }

    private String afterUntilBoundary(String text, int limit) {
        int end = Math.min(text.length(), limit);
        for (int index = 0; index < end; index++) {
            char ch = text.charAt(index);
            if (ch == '\n' || ch == '\r' || ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == ';' || ch == '；' || ch == ',' || ch == '，') {
                end = index;
                break;
            }
        }
        return text.substring(0, end);
    }

    private boolean startsWithAny(String value, List<String> candidates) {
        for (String candidate : candidates) {
            if (value.startsWith(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean endsWithAny(String value, List<String> candidates) {
        for (String candidate : candidates) {
            if (value.endsWith(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String value, List<String> candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, List<String>> keywords(JsonNode node) {
        return Map.of(
            "high", list(node.path("high")),
            "medium", list(node.path("medium")),
            "low", list(node.path("low"))
        );
    }

    private List<String> list(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            });
        }
        return values;
    }

    private String providerRiskKeyword(String message) {
        String lower = clean(message, "").toLowerCase(Locale.ROOT);
        for (String word : PROVIDER_RISK_WORDS) {
            if (lower.contains(word)) {
                return word;
            }
        }
        return "";
    }

    private void sendWarning(String emailValue, String feature, String level, String keyword, long count, int threshold) {
        if (emailValue == null || emailValue.isBlank()) {
            return;
        }
        email.sendPlainText(emailValue, "PicPilot 风控提醒", """
            你的请求内容可能触发平台风控，本次生成已停止或已记录。
            触发模块：%s
            触发等级：%s
            触发关键词：%s
            累计次数：%s/%s
            如你认为是误判，请联系管理员处理。
            """.formatted(clean(feature, "unknown"), level, keyword, count, threshold), notificationVariables(count, threshold));
    }

    private void sendBan(String emailValue, long count, int threshold) {
        if (emailValue == null || emailValue.isBlank()) {
            return;
        }
        email.sendPlainText(emailValue, "PicPilot 账号已被风控限制", """
            你的账号因多次触发平台风控已被暂时限制使用。
            累计次数：%s/%s
            请联系管理员解禁。
            """.formatted(count, threshold), notificationVariables(count, threshold));
    }

    private Map<String, String> notificationVariables(long count, int threshold) {
        String time = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return Map.of(
            "count", String.valueOf(count),
            "threshold", String.valueOf(threshold),
            "time", time
        );
    }

    private String normalize(String value) {
        return clean(value, "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String snippet(String raw, String keyword) {
        String text = clean(raw, "");
        if (text.isBlank()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String needle = clean(keyword, "").toLowerCase(Locale.ROOT);
        int index = needle.isBlank() ? -1 : lower.indexOf(needle);
        if (index < 0) {
            return truncate(text, 500);
        }
        int start = Math.max(0, index - 120);
        int end = Math.min(text.length(), index + Math.max(needle.length(), 1) + 120);
        return text.substring(start, end);
    }

    private String clean(String value, String fallback) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private String blankToNull(String value) {
        String cleaned = clean(value, "");
        return cleaned.isBlank() ? null : cleaned;
    }

    private String truncate(String value, int limit) {
        String cleaned = clean(value, "");
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }

    private record RiskConfig(boolean enabled, int banThreshold, List<String> blockLevels, List<String> warnLevels, Map<String, List<String>> keywords) {}
    private record Hit(String level, String keyword, String snippet) {}
    private record RecordResult(long count, boolean banned) {}
    private record NegationMarker(String value, int maxDistance) {}
    private record NegationMatch(NegationMarker marker, int index) {}
}
