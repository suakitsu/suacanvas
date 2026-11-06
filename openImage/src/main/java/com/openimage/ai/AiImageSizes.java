package com.openimage.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AiImageSizes {
    public static final String DEFAULT_SIZE = "1024x1024";
    public static final String DEFAULT_CREATIVE_SIZE = "1024x1024";
    public static final String DEFAULT_APLUS_OUTPUT_SIZE = "970x600";
    public static final String DEFAULT_LISTING_OUTPUT_SIZE = "1024x1024";

    private static final List<Size> GENERATION_SIZES = List.of(
        new Size(1024, 1024),
        new Size(1040, 832),
        new Size(720, 1280),
        new Size(1280, 720),
        new Size(1024, 768),
        new Size(1008, 672),
        new Size(832, 1040),
        new Size(768, 1024),
        new Size(672, 1008),
        new Size(1344, 576),
        new Size(2048, 2048),
        new Size(2080, 1664),
        new Size(1152, 2048),
        new Size(2048, 1152),
        new Size(2048, 1536),
        new Size(2016, 1344),
        new Size(1664, 2080),
        new Size(1536, 2048),
        new Size(1344, 2016),
        new Size(2016, 864),
        new Size(2880, 2880),
        new Size(3200, 2560),
        new Size(2160, 3840),
        new Size(3840, 2160),
        new Size(3264, 2448),
        new Size(3504, 2336),
        new Size(2560, 3200),
        new Size(2448, 3264),
        new Size(2336, 3504),
        new Size(3696, 1584),
        new Size(4096, 4096),
        new Size(4096, 3072),
        new Size(3072, 4096),
        new Size(4096, 2304),
        new Size(2304, 4096)
    );

    private static final Map<String, Size> APLUS_OUTPUT_SIZES = new LinkedHashMap<>();
    private static final Map<String, String> APLUS_LEGACY_OUTPUT_MAP = Map.of(
        "1280x720", "970x600",
        "1008x672", "970x600",
        "1024x1024", "300x300",
        "768x1024", "300x400",
        "720x1280", "150x300",
        "1344x576", "970x300",
        "1464x600", "970x300"
    );
    private static final Set<String> LISTING_OUTPUT_SIZES = Set.of("1600x1600", "1024x1024");

    static {
        APLUS_OUTPUT_SIZES.put("970x600", new Size(970, 600));
        APLUS_OUTPUT_SIZES.put("300x300", new Size(300, 300));
        APLUS_OUTPUT_SIZES.put("600x180", new Size(600, 180));
        APLUS_OUTPUT_SIZES.put("300x400", new Size(300, 400));
        APLUS_OUTPUT_SIZES.put("350x135", new Size(350, 135));
        APLUS_OUTPUT_SIZES.put("135x135", new Size(135, 135));
        APLUS_OUTPUT_SIZES.put("220x220", new Size(220, 220));
        APLUS_OUTPUT_SIZES.put("970x300", new Size(970, 300));
        APLUS_OUTPUT_SIZES.put("150x300", new Size(150, 300));
    }

    private AiImageSizes() {}

    public static String normalize(String value) {
        return normalizeGenerationSize(value, DEFAULT_SIZE);
    }

    public static String normalize(String value, String fallback) {
        return normalizeGenerationSize(value, fallback);
    }

    public static String normalizeCreativeSize(String value) {
        return normalizeCreativeSize(value, DEFAULT_CREATIVE_SIZE);
    }

    public static String normalizeCreativeSize(String value, String fallback) {
        String normalized = normalizeKey(value);
        if (isGenerationSize(normalized)) {
            return normalized;
        }
        String normalizedFallback = normalizeKey(fallback);
        return isGenerationSize(normalizedFallback) ? normalizedFallback : DEFAULT_CREATIVE_SIZE;
    }

    public static String normalizeGenerationSize(String value, String fallback) {
        String normalized = normalizeKey(value);
        if (isGenerationSize(normalized)) {
            return normalized;
        }
        Size parsed = parseSize(normalized);
        if (parsed != null) {
            return nearestGenerationSize(parsed).value();
        }
        String normalizedFallback = normalizeKey(fallback);
        if (isGenerationSize(normalizedFallback)) {
            return normalizedFallback;
        }
        Size parsedFallback = parseSize(normalizedFallback);
        return parsedFallback == null ? DEFAULT_SIZE : nearestGenerationSize(parsedFallback).value();
    }

    public static String normalizeAplusOutputSize(String value) {
        return normalizeAplusOutputSize(value, DEFAULT_APLUS_OUTPUT_SIZE);
    }

    public static String normalizeAplusOutputSize(String value, String fallback) {
        String normalized = normalizeKey(value);
        if (APLUS_OUTPUT_SIZES.containsKey(normalized)) {
            return normalized;
        }
        String legacy = APLUS_LEGACY_OUTPUT_MAP.get(normalized);
        if (legacy != null) {
            return legacy;
        }
        if ("auto".equals(normalized)) {
            return normalizeAplusOutputSize(fallback, DEFAULT_APLUS_OUTPUT_SIZE);
        }
        Size parsed = parseSize(normalized);
        if (parsed != null) {
            return nearestAplusOutputSize(parsed);
        }
        String normalizedFallback = normalizeKey(fallback);
        if (APLUS_OUTPUT_SIZES.containsKey(normalizedFallback)) {
            return normalizedFallback;
        }
        legacy = APLUS_LEGACY_OUTPUT_MAP.get(normalizedFallback);
        return legacy == null ? DEFAULT_APLUS_OUTPUT_SIZE : legacy;
    }

    public static String normalizeListingOutputSize(String value) {
        return normalizeListingOutputSize(value, DEFAULT_LISTING_OUTPUT_SIZE);
    }

    public static String normalizeListingOutputSize(String value, String fallback) {
        String normalized = normalizeKey(value);
        if (LISTING_OUTPUT_SIZES.contains(normalized)) {
            return normalized;
        }
        Size parsed = parseSize(normalized);
        if (parsed != null && parsed.width() == parsed.height()) {
            return parsed.width() >= 1200 ? "1600x1600" : "1024x1024";
        }
        String normalizedFallback = normalizeKey(fallback);
        return LISTING_OUTPUT_SIZES.contains(normalizedFallback) ? normalizedFallback : DEFAULT_LISTING_OUTPUT_SIZE;
    }

    public static String generationSizeForOutput(String outputSize) {
        Size parsed = parseSize(outputSize);
        return parsed == null ? DEFAULT_SIZE : nearestGenerationSize(parsed).value();
    }

    public static Size parse(String value) {
        return parseSize(normalizeKey(value));
    }

    public static boolean sameSize(String left, String right) {
        Size a = parse(left);
        Size b = parse(right);
        return a != null && b != null && a.width() == b.width() && a.height() == b.height();
    }

    private static boolean isGenerationSize(String value) {
        return GENERATION_SIZES.stream().anyMatch(size -> size.value().equals(value));
    }

    private static Size nearestGenerationSize(Size target) {
        return GENERATION_SIZES.stream()
            .min((left, right) -> Double.compare(aspectDistance(target, left), aspectDistance(target, right)))
            .orElse(new Size(1024, 1024));
    }

    private static String nearestAplusOutputSize(Size target) {
        return APLUS_OUTPUT_SIZES.entrySet().stream()
            .min((left, right) -> Double.compare(aspectDistance(target, left.getValue()), aspectDistance(target, right.getValue())))
            .map(Map.Entry::getKey)
            .orElse(DEFAULT_APLUS_OUTPUT_SIZE);
    }

    private static double aspectDistance(Size target, Size candidate) {
        return Math.abs(target.aspectRatio() - candidate.aspectRatio());
    }

    private static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
            .toLowerCase(Locale.ROOT)
            .replace('×', 'x')
            .replace('*', 'x')
            .replaceAll("\\s+", "");
    }

    private static Size parseSize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split("x", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            if (width <= 0 || height <= 0) {
                return null;
            }
            return new Size(width, height);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record Size(int width, int height) {
        public String value() {
            return width + "x" + height;
        }

        double aspectRatio() {
            return (double) width / (double) height;
        }
    }
}
