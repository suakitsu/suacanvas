package com.openimage.modules.project;

import java.util.Locale;

final class AgentImagePolicy {
    private static final ImagePlan CHAT_PLAN = new ImagePlan("闲聊", "", 0);
    private static final ImagePlan ONE_K_PLAN = new ImagePlan("1K", "1024x1024", ImageCreditPricing.ONE_K_CREDITS);
    private static final ImagePlan TWO_K_PLAN = new ImagePlan("2K", "2048x2048", ImageCreditPricing.TWO_K_CREDITS);
    private static final ImagePlan FOUR_K_PLAN = new ImagePlan("4K", "2880x2880", ImageCreditPricing.FOUR_K_CREDITS);

    private AgentImagePolicy() {}

    static Decision decide(String message, String requestedSize, boolean hasReference, boolean hasImageContext) {
        String text = normalize(message);
        if (text.isBlank()) {
            return new Decision(false, helpReply());
        }
        boolean imageAction = hasImageAction(text);
        boolean editAction = hasEditAction(text);
        boolean resolution = hasResolutionCue(text + " " + normalize(requestedSize));
        boolean visualSubject = hasVisualSubject(text);
        boolean richPrompt = looksLikeRichPrompt(text);
        boolean explicitCommand = hasExplicitImageCommand(text);

        if (isGreetingOrThanks(text)) {
            return new Decision(false, helpReply());
        }
        if (isQuestionLike(text) && !explicitCommand && !(hasImageContext && editAction)) {
            return new Decision(false, questionReply(text));
        }
        if (hasReference && (imageAction || editAction || visualSubject || richPrompt || resolution)) {
            return new Decision(true, "");
        }
        if (hasImageContext && (editAction || imageAction || resolution)) {
            return new Decision(true, "");
        }
        if ((imageAction || explicitCommand) && (visualSubject || richPrompt)) {
            return new Decision(true, "");
        }
        if ((resolution || richPrompt) && visualSubject) {
            return new Decision(true, "");
        }
        if (visualSubject && !isQuestionLike(text)) {
            return new Decision(true, "");
        }
        return new Decision(false, needsPromptReply(text));
    }

    static boolean isGenerationFollowUp(String latestUserMessage, String previousUserMessage, String previousAssistantMessage) {
        String latest = normalize(latestUserMessage);
        String previousUser = normalize(previousUserMessage);
        String previousAssistant = normalize(previousAssistantMessage);
        if (latest.isBlank() || previousUser.isBlank() || previousAssistant.isBlank()) {
            return false;
        }
        if (isGreetingOrThanks(latest) || containsAny(latest,
            "取消", "不用生成", "不要生成", "先不生成", "不生成", "不做了", "先聊", "只聊天",
            "cancel", "do not generate", "don't generate", "stop generation")) {
            return false;
        }
        boolean assistantAwaitingImageDetails = containsAny(previousAssistant,
            "请补充", "补充以下", "补充完整", "补全", "确认以下", "确认后", "告诉我", "选择一个",
            "我就可以", "即可生成", "就可以生成", "继续生成", "开始生成", "生成前",
            "please provide", "please add", "once confirmed", "then i can generate");
        boolean previousTurnWasImageRequest = hasImageAction(previousUser)
            || hasEditAction(previousUser)
            || (hasVisualSubject(previousUser) && looksLikeRichPrompt(previousUser))
            || containsAny(previousUser,
                "画面", "构图", "镜头", "角色", "主体", "服装", "配饰", "风格", "背景", "cosplay",
                "composition", "character", "outfit", "visual", "prompt");
        return assistantAwaitingImageDetails && previousTurnWasImageRequest && !isQuestionLike(latest);
    }

    static ImagePlan chatPlan() {
        return CHAT_PLAN;
    }

    static ImagePlan imagePlan(String message, String requestedSize) {
        String text = normalize(message) + " " + normalize(requestedSize);
        String compact = compact(text);
        if (compact.contains("8k")
            || compact.contains("4k")
            || text.contains("4096")
            || text.contains("3840")
            || text.contains("2880")
            || compact.contains("4320p")
            || compact.contains("2160p")
            || compact.contains("uhd")
            || compact.contains("ultrahd")
            || text.contains("ultra hd")
            || text.contains("ultra-high")
            || text.contains("ultra high")
            || text.contains("highest resolution")
            || text.contains("maximum resolution")
            || text.contains("最高分辨率")
            || text.contains("原生4k")
            || text.contains("原生 4k")
            || text.contains("四k")
            || text.contains("四 k")
            || text.contains("超高清")
            || text.contains("极清")) {
            return FOUR_K_PLAN;
        }
        if (compact.contains("2k")
            || text.contains("2048")
            || compact.contains("1440p")
            || compact.contains("qhd")
            || hasHdCue(text)
            || text.contains("high resolution")
            || text.contains("hi-res")
            || text.contains("hires")
            || text.contains("clear text")
            || text.contains("sharp text")
            || text.contains("readable text")
            || text.contains("text rendering")
            || text.contains("文字清晰")
            || text.contains("文字清楚")
            || text.contains("字体清晰")
            || text.contains("文案清晰")
            || text.contains("清晰度")
            || text.contains("高清")
            || text.contains("超清")
            || text.contains("高分辨率")
            || text.contains("大尺寸")
            || text.contains("大图")) {
            return TWO_K_PLAN;
        }
        return ONE_K_PLAN;
    }

    static int creditsForCreativeSize(String size) {
        return ImageCreditPricing.creditsForSize(size);
    }

    private static boolean hasResolutionCue(String value) {
        String text = normalize(value);
        String compact = compact(text);
        return imagePlan(value, "").creditsCost() > ONE_K_PLAN.creditsCost()
            || hasHdCue(text)
            || compact.contains("hd画质")
            || text.contains("hd quality");
    }

    private static boolean hasImageAction(String text) {
        return containsAny(text,
            "生成", "生图", "出图", "画", "绘制", "做一张", "做个", "设计", "渲染", "出一张", "来一张",
            "create", "generate", "draw", "make an image", "make a picture", "design", "render");
    }

    private static boolean hasEditAction(String text) {
        return containsAny(text,
            "修改", "改成", "换成", "调整", "重做", "重新", "优化", "放大", "提升", "变成", "替换", "去掉", "增加", "减少",
            "edit", "change", "replace", "remove", "add ", "revise", "upscale", "enhance", "rerender");
    }

    private static boolean hasExplicitImageCommand(String text) {
        return containsAny(text,
            "帮我生成", "请生成", "给我生成", "直接生成", "帮我画", "请画", "给我画", "帮我做", "请做",
            "generate an image", "create an image", "make an image", "draw an image");
    }

    private static boolean hasVisualSubject(String text) {
        return containsAny(text,
            "图", "图片", "照片", "主图", "场景", "海报", "背景", "插画", "画面", "构图", "镜头", "角色", "产品", "商品", "包", "玩偶", "兔", "模特", "人物", "女性", "男性", "女孩", "男孩", "lo娘", "lolita", "cosplay",
            "城市", "夜景", "房间", "桌面", "服装", "服饰", "配饰", "发型", "鞋", "首饰", "logo", "icon",
            "image", "picture", "photo", "poster", "scene", "composition", "character", "product", "bag", "doll", "rabbit", "model", "woman", "man", "girl", "boy", "city", "background", "illustration", "outfit", "logo");
    }

    private static boolean looksLikeRichPrompt(String text) {
        if (text.length() < 12) {
            return false;
        }
        if (containsAny(text, "，", ",", "；", ";", "、")) {
            return true;
        }
        String[] parts = text.trim().split("\\s+");
        return parts.length >= 4;
    }

    private static boolean isQuestionLike(String text) {
        return containsAny(text, "?", "？", "吗", "么", "怎么", "如何", "为什么", "多少钱", "价格", "收费", "积分", "支持", "能不能", "可以吗", "怎么办",
            "what", "how", "why", "price", "cost", "credit", "support", "can you", "could you");
    }

    private static boolean isGreetingOrThanks(String text) {
        String compact = compact(text);
        return compact.matches("^(hi|hello|hey|你好|您好|在吗|谢谢|感谢|thanks|thankyou)[!！。,.，\\s]*$");
    }

    private static String questionReply(String text) {
        if (hasResolutionCue(text)) {
            return "可以聊。当前生图只支持 1K、2K、4K 三档：HD/高清/超清会按更高清档位预估，8K 会降级为当前最高支持的 4K，并按 4K 扣 30 积分。只有你明确提交生图或修改图片时才会扣积分。";
        }
        if (containsAny(text, "积分", "价格", "收费", "多少钱", "price", "cost", "credit")) {
            return "可以聊，闲聊和咨询不扣积分。真正创建生图任务时才扣积分：1K 为 6 积分，2K 为 24 积分，4K 为 30 积分。";
        }
        return helpReply();
    }

    private static String needsPromptReply(String text) {
        if (hasResolutionCue(text)) {
            return "我看到了清晰度要求，但还缺少画面主体。请补充要生成什么内容；如果写 8K，系统会按最高支持的 4K 执行并按 30 积分计费。";
        }
        return helpReply();
    }

    private static String helpReply() {
        return "可以闲聊，不会扣积分。你可以先和我讨论风格、构图、参考图思路；当你明确说要生成或修改图片时，我才会创建生图任务并按 1K/2K/4K 对应积分扣费。";
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasHdCue(String text) {
        return text.matches("(?s).*\\bhd\\b.*") || text.contains("hd画质") || text.contains("hd 画质");
    }

    private static String compact(String value) {
        return normalize(value).replaceAll("[\\s_\\-]+", "");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    record ImagePlan(String qualityLabel, String size, int creditsCost) {}

    record Decision(boolean shouldGenerateImage, String assistantReply) {}
}
