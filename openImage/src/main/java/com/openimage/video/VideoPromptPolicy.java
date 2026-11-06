package com.openimage.video;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class VideoPromptPolicy {
    private static final Pattern SINGLE_SHOT = Pattern.compile(
        "(?i)(一镜到底|单镜头|连续镜头|不要切镜|不切镜|no\\s+cuts?|single\\s+shot|continuous\\s+shot)"
    );
    private static final Pattern STORYBOARD = Pattern.compile(
        "(?i)(分镜|镜头\\s*[一二三四五六七八九十\\d]+|切镜|转场|多场景|广告片|宣传片|带货|"
            + "\\d+\\s*[-~至–—]\\s*\\d+\\s*(?:s|秒)|shot\\s*\\d+|scene\\s*\\d+)"
    );

    private VideoPromptPolicy() {}

    public static String modeFor(String prompt, String provider) {
        if ("agnes".equalsIgnoreCase(clean(provider))) return "single_shot";
        String value = clean(prompt);
        if (SINGLE_SHOT.matcher(value).find()) return "single_shot";
        return STORYBOARD.matcher(value).find() ? "storyboard" : "single_shot";
    }

    public static String fallbackScript(
        String rawPrompt,
        String provider,
        int durationSeconds,
        int referenceCount,
        boolean generateAudio
    ) {
        String mode = modeFor(rawPrompt, provider);
        String references = referenceGuide(provider, referenceCount);
        String audio = generateAudio
            ? "音频：使用与画面自然同步的环境声和轻量产品动作音效；除非用户明确提供台词，否则不要编造口播或商品承诺。"
            : "音频：无需生成音轨。";
        if ("storyboard".equals(mode)) {
            return """
                用户创意：%s

                执行脚本：在 %d 秒内按用户给出的分镜、时间点和场景顺序执行；所有时间段必须连续覆盖完整时长。镜头之间使用自然且有因果关系的转场，始终保持同一商品的外形、比例、颜色、材质、Logo、标签、配件和数量一致。%s
                %s
                禁止虚构商品参数、认证、功效或促销信息；禁止无关文字、水印、二维码、平台界面和商品形变。
                """.formatted(clean(rawPrompt), durationSeconds, references, audio).trim();
        }
        return """
            用户创意：%s

            执行脚本：%d 秒单镜头连续商品展示。以参考图中的同一商品为唯一主体，使用平稳、物理合理的镜头运动和商品动作完成用户需求；从首帧到末帧保持商品外形、比例、颜色、材质、Logo、标签、配件和数量一致。%s
            %s
            禁止切镜、场景跳变、商品替换或形变；禁止虚构商品参数、认证、功效或促销信息；禁止无关文字、水印、二维码和平台界面。
            """.formatted(clean(rawPrompt), durationSeconds, references, audio).trim();
    }

    public static String compileForProvider(
        String prompt,
        String provider,
        int referenceCount
    ) {
        String mode = modeFor(prompt, provider);
        String continuity = """
            Product continuity requirements: keep the exact same referenced product as the subject in every frame. Preserve its identity,
            silhouette, geometry, proportions, materials, colors, logo, labels, printed text, small details, accessories and object count.
            All product details must remain temporally coherent from the first frame to the last frame.
            Avoid accidental text, misspellings, watermarks, QR codes, unrelated logos and unsupported product claims.
            """.trim();
        String motion = "storyboard".equals(mode)
            ? """
                Follow the planned shot order and timing in the script. Cuts and transitions are allowed only where the script requests them.
                Across every planned shot, keep the same product identity and use coherent camera direction, lighting and physical motion.
                """
            : """
                Use one continuous shot with physically plausible motion. No cuts, no scene replacement, no viewpoint jump and no object transformation.
                """;
        String reference = referenceGuide(provider, referenceCount);
        String suffix = "\n\n" + continuity + "\n" + motion.trim();
        if (!reference.isBlank()) suffix += "\n" + reference;
        int maxLength = "hfsy".equalsIgnoreCase(clean(provider)) ? 5000 : 4000;
        String base = clean(prompt);
        int available = Math.max(4, maxLength - suffix.length());
        if (base.length() > available) base = base.substring(0, available).trim();
        return base + suffix;
    }

    public static String referenceGuide(String provider, int referenceCount) {
        if (referenceCount <= 0) return "";
        List<String> names = new ArrayList<>();
        for (int index = 1; index <= referenceCount; index++) {
            names.add("hfsy".equalsIgnoreCase(clean(provider)) ? "@图" + index : "图片" + index);
        }
        return "参考素材：" + String.join("、", names)
            + " 按上传顺序对应；把它们作为商品身份、结构、颜色和材质的事实来源。";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
