package com.openimage.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CreativePromptPolicy {
    private static final String GENERATED_INTENT_MARKER = "Intent and reference constraints:";
    private static final List<String> GENERATED_INTENT_EVIDENCE = List.of(
        "Reference image is the source of truth.",
        "Keep the product from the reference image unchanged.",
        "Use the reference image as the source of truth for the main subject.",
        "Create an ecommerce commercial layout, but the referenced product remains the main subject",
        "Visible text is allowed only when explicitly provided by the user.",
        "Do not render readable text, logos, watermarks, labels, QR codes, or platform UI unless explicitly requested."
    );
    private static final List<String> REPLACEMENT_ACTIONS = List.of(
        "替换为", "替换成", "换成", "换为", "换掉", "取代为"
    );
    private static final Pattern ENGLISH_REPLACEMENT = Pattern.compile(
        "(?is)\\b(?:replace|swap)\\b.{0,120}\\b(?:with|for)\\b"
    );
    private static final Pattern AFFIRMATIVE_VISIBLE_TEXT_ZH = Pattern.compile(
        "(?:添加|加入|写上|显示|呈现|放置|排版|渲染|印上)[^。；;\\n]{0,18}(?:文字|标题|标语|文案|标签|贴纸|字样|logo|标识)"
    );
    private static final Pattern AFFIRMATIVE_VISIBLE_TEXT_EN = Pattern.compile(
        "(?is)\\b(?:add|render|display|include|place|write|show)\\b.{0,40}\\b(?:text|title|headline|slogan|copy|label|sticker|wording|logo)\\b"
    );
    private static final Pattern EXACT_VISIBLE_TEXT_ZH = Pattern.compile(
        "(?:文字|标题|标语|文案|标签|贴纸|字样)\\s*(?:为|是|写|用|：|:)[^。；;\\n]{1,80}"
    );
    private static final Pattern NO_NEW_VISIBLE_TEXT_ZH = Pattern.compile(
        "(?:不要|不得|禁止|避免|无需|不需要|不可|不能|无)[^。；;\\n]{0,20}(?:添加|生成|出现|加入|放置|呈现|显示|渲染|大字|标题|标语|文案|文字|字样|标签|贴纸|logo|标识|水印)"
    );
    private static final Pattern NO_NEW_VISIBLE_TEXT_EN = Pattern.compile(
        "(?is)\\b(?:do not|don't|never|without|no)\\b.{0,40}\\b(?:add|render|generate|include|show|new|extra|random)?\\s*(?:text|title|headline|slogan|copy|label|sticker|wording|logo|watermark)\\b"
    );
    private static final Pattern PRESERVE_EXISTING_TEXT_ZH = Pattern.compile(
        "(?:保留|保持|沿用|不得修改|不要修改|禁止修改|不能修改)[^。；;\\n]{0,40}(?:包装|盒子|标签|文字|图案|图文|logo|标识)"
            + "|(?:包装|盒子|标签|文字|图案|图文|logo|标识)[^。；;\\n]{0,40}(?:保留|保持|一致|不变|不得修改|不要修改|禁止修改|不能修改)"
    );
    private static final Pattern PRESERVE_EXISTING_TEXT_EN = Pattern.compile(
        "(?is)\\b(?:preserve|keep|retain|do not change|do not modify)\\b.{0,60}\\b(?:packaging|box|label|text|artwork|graphics|logo|markings)\\b"
            + "|\\b(?:packaging|box|label|text|artwork|graphics|logo|markings)\\b.{0,60}\\b(?:unchanged|identical|intact|the same)\\b"
    );
    private static final Pattern IGNORE_ALL_REFERENCES_ZH = Pattern.compile(
        "(?:不用|不需要|无需|不要使用|不使用|不参考|不要参考|不采用|忽略所有|忽略全部)[^。；;\\n]{0,12}(?:上传的?|提供的?|这些|全部|所有)?(?:参考)?(?:图|图片|照片|素材)(?!中|上|里|内|的)"
            + "|(?:上传的?|提供的?|这些)[^。；;\\n]{0,8}(?:图|图片|照片|素材)[^。；;\\n]{0,12}(?:不作为参考|不要参考|仅作展示|只是展示)"
    );
    private static final Pattern IGNORE_ALL_REFERENCES_EN = Pattern.compile(
        "(?is)\\b(?:do not|don't|never)\\s+(?:use|reference|consider)\\b.{0,30}\\b(?:uploaded\\s+)?(?:images?|photos?|references?|assets?)\\b"
            + "|\\b(?:uploaded\\s+)?(?:images?|photos?|assets?)\\b.{0,30}\\b(?:are not references|for display only|should be ignored)\\b"
    );
    private static final Pattern NUMERIC_REFERENCE_INDEX = Pattern.compile(
        "(?i)(?:第\\s*(\\d{1,3})\\s*张|(?:参考图|图片|图)\\s*[#号]?\\s*(\\d{1,3})|(?:image|reference|photo)\\s*#?\\s*(\\d{1,3}))"
    );
    private static final Pattern CHINESE_REFERENCE_INDEX = Pattern.compile(
        "(?:第\\s*([零一二两三四五六七八九十百]+)\\s*张|(?:参考图|图片|图)\\s*([零一二两三四五六七八九十百]+))"
    );

    private CreativePromptPolicy() {
    }

    public enum VisibleTextIntent {
        NO_NEW_TEXT,
        PRESERVE_EXISTING_TEXT,
        ADD_EXACT_TEXT
    }

    public static String cleanUserPrompt(String value) {
        String prompt = value == null ? "" : value.trim();
        if (prompt.isBlank()) {
            return "";
        }
        int markerIndex = prompt.lastIndexOf(GENERATED_INTENT_MARKER);
        if (markerIndex < 0) {
            return prompt;
        }
        String suffix = prompt.substring(markerIndex + GENERATED_INTENT_MARKER.length()).trim();
        boolean generated = GENERATED_INTENT_EVIDENCE.stream().anyMatch(suffix::contains);
        if (!generated) {
            return prompt;
        }
        return prompt.substring(0, markerIndex).stripTrailing();
    }

    public static boolean requestsReferenceReplacement(String value) {
        String prompt = cleanUserPrompt(value);
        if (prompt.isBlank() || ignoresAllReferences(prompt) || !mentionsReference(prompt)) {
            return false;
        }
        for (String action : REPLACEMENT_ACTIONS) {
            int from = 0;
            int index;
            while ((index = prompt.indexOf(action, from)) >= 0) {
                if (!isNegatedInClause(prompt, index)) {
                    return true;
                }
                from = index + action.length();
            }
        }
        Matcher english = ENGLISH_REPLACEMENT.matcher(prompt);
        while (english.find()) {
            if (!isNegatedInClause(prompt, english.start())) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasExplicitReferenceRoles(String value) {
        String prompt = cleanUserPrompt(value).toLowerCase(Locale.ROOT);
        if (prompt.isBlank()) {
            return false;
        }
        boolean roles = containsAny(
            prompt,
            "构图", "摆放", "角度", "场景", "背景", "风格", "主体", "产品", "商品", "身份", "替换", "负责", "作为", "用于", "只参考",
            "composition", "layout", "angle", "scene", "background", "style", "subject", "product", "identity", "replace", "use as", "only use"
        );
        boolean indexedRoles = referencedImageIndexes(prompt).size() >= 2 && roles;
        boolean namedRoles = containsAny(prompt, "商品图", "产品图", "主体图", "product image", "subject image")
            && containsAny(prompt, "场景图", "背景图", "风格图", "构图图", "style image", "scene image", "background image")
            && roles;
        return indexedRoles || namedRoles;
    }

    public static boolean ignoresAllReferences(String value) {
        String prompt = cleanUserPrompt(value);
        if (prompt.isBlank()) {
            return false;
        }
        return IGNORE_ALL_REFERENCES_ZH.matcher(prompt).find() || IGNORE_ALL_REFERENCES_EN.matcher(prompt).find();
    }

    public static List<Integer> referencedImageIndexes(String value) {
        String prompt = cleanUserPrompt(value);
        if (prompt.isBlank()) {
            return List.of();
        }
        Set<Integer> indexes = new LinkedHashSet<>();
        Matcher numeric = NUMERIC_REFERENCE_INDEX.matcher(prompt);
        while (numeric.find()) {
            for (int group = 1; group <= numeric.groupCount(); group++) {
                String matched = numeric.group(group);
                if (matched != null && !matched.isBlank()) {
                    addPositiveIndex(indexes, parsePositiveInt(matched));
                    break;
                }
            }
        }
        Matcher chinese = CHINESE_REFERENCE_INDEX.matcher(prompt);
        while (chinese.find()) {
            String matched = chinese.group(1) == null ? chinese.group(2) : chinese.group(1);
            addPositiveIndex(indexes, chineseNumber(matched));
        }
        addNamedEnglishIndex(indexes, prompt, "first image", "first reference", "first photo", 1);
        addNamedEnglishIndex(indexes, prompt, "second image", "second reference", "second photo", 2);
        addNamedEnglishIndex(indexes, prompt, "third image", "third reference", "third photo", 3);
        return new ArrayList<>(indexes);
    }

    public static VisibleTextIntent visibleTextIntent(String value) {
        String prompt = cleanUserPrompt(value);
        if (prompt.contains("文字层 JSON:") || prompt.contains("中文字层规则") || prompt.contains("系统后期叠加")) {
            return VisibleTextIntent.ADD_EXACT_TEXT;
        }
        if (hasAffirmativeVisibleTextInstruction(prompt)) {
            return VisibleTextIntent.ADD_EXACT_TEXT;
        }
        if (PRESERVE_EXISTING_TEXT_ZH.matcher(prompt).find() || PRESERVE_EXISTING_TEXT_EN.matcher(prompt).find()) {
            return VisibleTextIntent.PRESERVE_EXISTING_TEXT;
        }
        if (NO_NEW_VISIBLE_TEXT_ZH.matcher(prompt).find() || NO_NEW_VISIBLE_TEXT_EN.matcher(prompt).find()) {
            return VisibleTextIntent.NO_NEW_TEXT;
        }
        return VisibleTextIntent.NO_NEW_TEXT;
    }

    private static boolean hasAffirmativeVisibleTextInstruction(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        if (hasNonNegatedMatch(prompt, AFFIRMATIVE_VISIBLE_TEXT_ZH)
            || hasNonNegatedMatch(prompt, AFFIRMATIVE_VISIBLE_TEXT_EN)) {
            return true;
        }
        Matcher exact = EXACT_VISIBLE_TEXT_ZH.matcher(prompt);
        while (exact.find()) {
            if (!isNegatedInClause(prompt, exact.start())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonNegatedMatch(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            if (!isNegatedInClause(value, matcher.start())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNegatedInClause(String value, int actionIndex) {
        int start = actionIndex - 1;
        while (start >= 0 && "，,。；;\n\r.!?".indexOf(value.charAt(start)) < 0) {
            start--;
        }
        String prefix = value.substring(start + 1, actionIndex).toLowerCase(Locale.ROOT);
        return containsAny(prefix, "不要", "不得", "禁止", "避免", "不可", "不能", "别", "do not", "don't", "never", "without");
    }

    private static boolean mentionsReference(String value) {
        String prompt = value.toLowerCase(Locale.ROOT);
        return !referencedImageIndexes(prompt).isEmpty() || containsAny(
            prompt,
            "参考图", "上传图", "商品图", "产品图", "主体图", "场景图", "背景图", "风格图", "素材图",
            "reference image", "reference photo", "uploaded image", "product image", "subject image", "scene image", "background image", "style image"
        );
    }

    private static int parsePositiveInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int chineseNumber(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        if ("十".equals(value)) {
            return 10;
        }
        int hundredIndex = value.indexOf('百');
        if (hundredIndex >= 0) {
            int hundreds = chineseDigit(value.substring(0, hundredIndex));
            String rest = value.substring(hundredIndex + 1);
            return Math.max(1, hundreds) * 100 + chineseNumber(rest);
        }
        int tenIndex = value.indexOf('十');
        if (tenIndex >= 0) {
            int tens = tenIndex == 0 ? 1 : chineseDigit(value.substring(0, tenIndex));
            int ones = tenIndex == value.length() - 1 ? 0 : chineseDigit(value.substring(tenIndex + 1));
            return Math.max(1, tens) * 10 + Math.max(0, ones);
        }
        return chineseDigit(value);
    }

    private static int chineseDigit(String value) {
        return switch (value) {
            case "零" -> 0;
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            default -> 0;
        };
    }

    private static void addPositiveIndex(Set<Integer> indexes, int value) {
        if (value > 0) {
            indexes.add(value);
        }
    }

    private static void addNamedEnglishIndex(Set<Integer> indexes, String value, String first, String second, String third, int index) {
        if (containsAny(value.toLowerCase(Locale.ROOT), first, second, third)) {
            indexes.add(index);
        }
    }

    private static boolean containsAny(String value, String... needles) {
        String text = value == null ? "" : value;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
