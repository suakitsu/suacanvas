package com.openimage.modules.commerce;

import java.util.List;
import java.util.regex.Pattern;

final class CommerceTextNormalizer {
    private static final int MAX_SELLING_POINTS = 8;
    private static final Pattern LEADING_LIST_MARKER = Pattern.compile(
        "^\\s*(?:(?:\\d{1,2}\\s*[.、)）](?!\\d)|[（(]\\d{1,2}[）)]|[一二三四五六七八九十]+\\s*[.、)）])\\s*|[•●▪◦·]\\s*|[-*]\\s+)"
    );
    private static final Pattern REPEATED_WHITESPACE = Pattern.compile("\\s+");

    private CommerceTextNormalizer() {}

    static List<String> sellingPoints(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(CommerceTextNormalizer::visibleCopy)
            .filter(value -> !value.isBlank())
            .distinct()
            .limit(MAX_SELLING_POINTS)
            .toList();
    }

    static String visibleCopy(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            return "";
        }
        text = LEADING_LIST_MARKER.matcher(text).replaceFirst("");
        return REPEATED_WHITESPACE.matcher(text).replaceAll(" ").trim();
    }
}
