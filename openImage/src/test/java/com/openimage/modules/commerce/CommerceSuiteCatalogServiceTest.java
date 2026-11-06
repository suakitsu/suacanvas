package com.openimage.modules.commerce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.mapper.SystemSettingMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CommerceSuiteCatalogServiceTest {
    private final CommerceSuiteCatalogService catalog = new CommerceSuiteCatalogService(mock(SystemSettingMapper.class), new ObjectMapper());

    @Test
    void onlyMainAndPromoSlotsKeepPriceText() {
        CommerceSuiteDtos.ProductInfo info = new CommerceSuiteDtos.ProductInfo(
            "Apple AirPods 4 无线蓝牙耳机",
            "耳机",
            "Apple",
            List.of("苹果生态适配", "轻巧佩戴", "日常长时间听音"),
            "白色亮面塑料",
            "USB-C 充电盒",
            "通勤用户",
            "办公室和通勤",
            "1099",
            "新品",
            "CN",
            "zh-CN"
        );

        assertEquals("1099", priceTextFor("main_image", "商品主图", info));
        assertEquals("1099", priceTextFor("promo", "促销氛围图", info));

        List<String> noPriceSlots = List.of(
            "selling_point",
            "detail",
            "model_image",
            "detail_closeup",
            "detail_function",
            "sku_display",
            "specification",
            "packaging",
            "scenario",
            "crowd",
            "comparison",
            "trust",
            "social_cover"
        );
        for (String slotCode : noPriceSlots) {
            CommerceSuiteDtos.ImageSlot slot = slot(slotCode, slotCode);
            Map<String, Object> textLayer = textLayerFor(slot, info);

            assertEquals("", textLayer.get("price_text"), slotCode + " should not carry price text");
            assertTrue(String.valueOf(textLayer.get("text_strategy")).contains("no price"), slotCode + " should tell AI not to render price");
        }
    }

    @Test
    void tiktokShopUsesGallerySlotsInsteadOfLiveOrGenericPromoSlots() {
        CommerceSuiteDtos.Catalog value = catalog.catalog();
        CommerceSuiteDtos.PlatformPreset tiktok = value.platforms().stream()
            .filter(item -> "tiktok_shop".equals(item.code()))
            .findFirst()
            .orElseThrow();

        assertEquals(
            List.of("main_image", "model_image", "scenario", "detail", "detail_closeup", "detail_function", "sku_display"),
            tiktok.defaultSlots()
        );
    }

    @Test
    void visibleTextPlanDoesNotUseInternalSlotNamesAsDisplayCopy() {
        CommerceSuiteDtos.ProductInfo info = new CommerceSuiteDtos.ProductInfo(
            "Apple AirPods 4 无线蓝牙耳机",
            "耳机",
            "Apple",
            List.of("苹果生态适配", "轻巧佩戴", "日常长时间听音"),
            "白色亮面塑料",
            "USB-C 充电盒",
            "通勤用户",
            "办公室和通勤",
            "1099",
            "",
            "CN",
            "zh-CN"
        );

        for (String slotCode : List.of("selling_point", "detail", "model_image", "detail_closeup", "detail_function", "sku_display", "comparison", "trust")) {
            CommerceSuiteDtos.ImageSlot slot = slot(slotCode, slotCode);
            Map<String, Object> textLayer = textLayerFor(slot, info);

            assertTrue(String.valueOf(textLayer.get("forbidden_visible_text")).contains(slotCode));
            assertTrue(!String.valueOf(textLayer.get("title")).contains(slotCode), slotCode + " title should be user/product copy, not the slot code");
            assertTrue(!String.valueOf(textLayer.get("subtitle")).contains(slotCode), slotCode + " subtitle should be user/product copy, not the slot code");
        }
    }

    @Test
    void tiktokGallerySlotsAvoidForcedTextPanels() {
        CommerceSuiteDtos.ProductInfo info = new CommerceSuiteDtos.ProductInfo(
            "Wireless Bluetooth Earbuds",
            "Consumer electronics",
            "",
            List.of("comfortable daily fit", "compact charging case", "easy phone pairing"),
            "glossy white plastic",
            "USB-C charging case",
            "commuters",
            "desk work",
            "1099",
            "New",
            "US",
            "en-US"
        );
        CommerceSuiteDtos.PlatformPreset platform = new CommerceSuiteDtos.PlatformPreset(
            "tiktok_shop",
            "TikTok Shop",
            "gallery style",
            true,
            false,
            "1K",
            "1024x1024",
            List.of("main_image"),
            "gallery style",
            "en-US",
            "en-US"
        );

        for (String slotCode : List.of("main_image", "model_image", "scenario", "sku_display")) {
            Map<String, Object> plan = planFor(platform, slot(slotCode, slotCode), info);
            Map<String, Object> textLayer = textLayer(plan);
            assertEquals("US", plan.get("target_market"));
            assertEquals("en-US", plan.get("visible_text_language"));
            assertEquals("", textLayer.get("title"), slotCode + " should not force a text title");
            assertEquals("", textLayer.get("subtitle"), slotCode + " should not force a text subtitle");
            assertEquals(List.of(), textLayer.get("selling_points"), slotCode + " should not force benefit text");
            assertEquals("", textLayer.get("price_text"), slotCode + " should not show price");
            assertEquals("none", textLayer.get("copy_density"), slotCode + " should be no-text product photography");
        }

        for (String slotCode : List.of("detail", "detail_closeup", "detail_function")) {
            Map<String, Object> textLayer = textLayerFor(platform, slot(slotCode, slotCode), info);
            assertEquals("", textLayer.get("title"), slotCode + " should not force a headline");
            assertEquals("", textLayer.get("subtitle"), slotCode + " should not force a subtitle");
            assertEquals("", textLayer.get("price_text"), slotCode + " should not show price");
            assertEquals("tiny_labels_only", textLayer.get("copy_density"), slotCode + " should only allow tiny labels");
        }
    }

    @Test
    void tiktokPromptCarriesPdpGalleryQualityRulesEvenForOldTemplates() {
        CommerceSuiteDtos.ProductInfo info = new CommerceSuiteDtos.ProductInfo(
            "White Dachshund Graphic T-Shirt",
            "Apparel",
            "",
            List.of("soft cotton feel", "front dog graphic", "casual daily wear"),
            "cotton jersey",
            "white / short sleeve",
            "US streetwear shoppers",
            "summer casual outfit",
            "",
            "",
            "US",
            "en-US"
        );
        CommerceSuiteDtos.PlatformPreset platform = new CommerceSuiteDtos.PlatformPreset(
            "tiktok_shop",
            "TikTok Shop",
            "old saved style",
            true,
            false,
            "1K",
            "1024x1024",
            List.of("main_image"),
            "old saved style",
            "en-US",
            "en-US"
        );
        CommerceSuiteDtos.ImageSlot slot = slot("main_image", "商品主图");
        CommerceSuiteDtos.TemplatePreset oldTemplate = new CommerceSuiteDtos.TemplatePreset(
            "old-tiktok-main",
            platform.code(),
            slot.code(),
            slot.name(),
            slot.description(),
            "Create a generic ecommerce image. {{slot_strategy}}",
            "",
            Map.of(),
            "1024x1024",
            true,
            1,
            Map.of()
        );

        String prompt = catalog.renderPromptTemplate(oldTemplate, platform, slot, info);

        assertTrue(prompt.contains("Runtime platform suite quality guidance"));
        assertTrue(prompt.contains("TikTok Shop PDP gallery set"));
        assertTrue(prompt.contains("pure white/light background"));
        assertTrue(prompt.contains("no added text"));
        assertTrue(prompt.contains("No live cover"));
        assertTrue(prompt.contains("do not duplicate motifs"));
        assertTrue(prompt.contains("same single front/back artwork count and placement"));
    }

    @Test
    void defaultPlatformPromptsUseDistinctSuiteDirections() {
        CommerceSuiteDtos.ProductInfo info = new CommerceSuiteDtos.ProductInfo(
            "便携收纳盒",
            "家居收纳",
            "",
            List.of("桌面更整洁", "分格收纳", "拿取方便"),
            "PP",
            "三格",
            "租房和办公用户",
            "桌面收纳",
            "",
            "",
            "CN",
            "zh-CN"
        );

        Map<String, String> expected = Map.of(
            "taobao_tmall", "Taobao/Tmall mobile PDP set",
            "pinduoduo", "Pinduoduo shelf set",
            "douyin", "Douyin ecommerce set",
            "xiaohongshu", "Xiaohongshu seeding set",
            "alibaba_1688", "1688 supplier set"
        );
        CommerceSuiteDtos.ImageSlot slot = slot("scenario", "使用场景图");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            CommerceSuiteDtos.PlatformPreset platform = catalog.catalog().platforms().stream()
                .filter(item -> entry.getKey().equals(item.code()))
                .findFirst()
                .orElseThrow();
            String prompt = catalog.renderPromptTemplate(template(platform, slot), platform, slot, info);
            assertTrue(prompt.contains(entry.getValue()), platform.code() + " should have its own suite direction");
        }
    }

    @Test
    void visibleTextPlanRemovesOrdinalLabelsAndCarriesEdgeSafetyRules() {
        CommerceSuiteDtos.ProductInfo info = new CommerceSuiteDtos.ProductInfo(
            "新鲜山竹",
            "水果",
            "",
            List.of("1. 新鲜采摘", "2、果香清甜", "1.5kg 家庭装"),
            "",
            "",
            "",
            "",
            "",
            "",
            "CN",
            "zh-CN"
        );
        CommerceSuiteDtos.ImageSlot slot = slot("selling_point", "核心卖点图");
        Map<String, Object> textLayer = textLayerFor(slot, info);
        String prompt = catalog.renderPromptTemplate(template(slot), platform(), slot, info);

        assertEquals("新鲜采摘", textLayer.get("title"));
        assertEquals("果香清甜", textLayer.get("subtitle"));
        assertEquals(List.of("1.5kg 家庭装"), textLayer.get("selling_points"));
        assertEquals(8, textLayer.get("safe_margin_percent"));
        assertTrue(prompt.contains("inner 8% safe area"));
        assertTrue(prompt.contains("1.5kg 家庭装"));
        assertTrue(!prompt.contains("1. 新鲜采摘"));
        assertTrue(!prompt.contains("2、果香清甜"));
    }

    @Test
    void photoFirstSlotsDoNotRepeatAnUnrelatedSellingPointAsAHeadline() {
        CommerceSuiteDtos.ProductInfo info = new CommerceSuiteDtos.ProductInfo(
            "新鲜山竹",
            "水果",
            "",
            List.of("新鲜采摘", "果香清甜"),
            "",
            "",
            "",
            "",
            "",
            "",
            "CN",
            "zh-CN"
        );

        for (String slotCode : List.of("detail", "detail_closeup", "model_image", "packaging", "scenario", "crowd")) {
            Map<String, Object> textLayer = textLayerFor(slot(slotCode, slotCode), info);
            assertEquals("", textLayer.get("title"), slotCode + " should remain photo-first when its own facts are absent");
            assertEquals("", textLayer.get("subtitle"), slotCode + " should not repeat a generic selling point");
            assertEquals(List.of(), textLayer.get("selling_points"), slotCode + " should not inject unrelated labels");
        }
    }

    @Test
    void humanSubjectsKeepCompleteHeadsAndDetailSlotsAvoidPartialFaces() {
        CommerceSuiteDtos.ProductInfo info = new CommerceSuiteDtos.ProductInfo(
            "白色短袖 T 恤",
            "服饰",
            "",
            List.of("胸口小标", "宽松版型"),
            "棉",
            "白色 / 短袖",
            "成年男性",
            "日常通勤",
            "",
            "",
            "CN",
            "zh-CN"
        );

        for (String slotCode : List.of("main_image", "model_image", "scenario", "crowd", "comparison")) {
            CommerceSuiteDtos.ImageSlot slot = slot(slotCode, slotCode);
            String prompt = catalog.renderPromptTemplate(template(slot), platform(), slot, info);
            Map<String, Object> textLayer = textLayerFor(slot, info);

            assertTrue(prompt.contains("entire head, all hair"), slotCode + " should preserve the complete head");
            assertTrue(prompt.contains("breathing room above the hair"), slotCode + " should reserve top margin");
            assertTrue(prompt.contains("Preserve the complete product silhouette"), slotCode + " should preserve every important subject");
            assertEquals(6, textLayer.get("subject_safe_margin_percent"));
            assertTrue(String.valueOf(textLayer.get("canvas_framing_policy")).contains("inner 8% canvas safe area"));
            assertTrue(String.valueOf(textLayer.get("human_framing_policy")).contains("Never crop through the scalp"));
        }

        for (String slotCode : List.of("detail", "detail_closeup", "detail_function")) {
            CommerceSuiteDtos.ImageSlot slot = slot(slotCode, slotCode);
            String prompt = catalog.renderPromptTemplate(template(slot), platform(), slot, info);
            assertTrue(prompt.contains("exclude the face and head entirely"), slotCode + " should avoid partial faces in a macro crop");
        }
    }

    @Test
    void oldTemplateNegativePromptGetsHumanCropProtection() {
        CommerceSuiteDtos.TemplatePreset oldTemplate = new CommerceSuiteDtos.TemplatePreset(
            "old-main",
            "taobao_tmall",
            "main_image",
            "旧商品主图",
            "",
            "Create a product hero.",
            "No watermark.",
            Map.of(),
            "1024x1024",
            true,
            1,
            Map.of()
        );

        String negativePrompt = catalog.renderNegativePrompt(oldTemplate);

        assertTrue(negativePrompt.contains("No watermark."));
        assertTrue(negativePrompt.contains("No cropped product"));
        assertTrue(negativePrompt.contains("No cropped head"));
        assertTrue(negativePrompt.contains("no cut-off hair"));
    }

    private String priceTextFor(String slotCode, String slotName, CommerceSuiteDtos.ProductInfo info) {
        return String.valueOf(textLayerFor(slot(slotCode, slotName), info).get("price_text"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> textLayerFor(CommerceSuiteDtos.ImageSlot slot, CommerceSuiteDtos.ProductInfo info) {
        return textLayerFor(platform(), slot, info);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> textLayerFor(CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ImageSlot slot, CommerceSuiteDtos.ProductInfo info) {
        return textLayer(planFor(platform, slot, info));
    }

    private Map<String, Object> planFor(CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ImageSlot slot, CommerceSuiteDtos.ProductInfo info) {
        return catalog.renderOverlayTemplate(template(platform, slot), platform, slot, info, Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> textLayer(Map<String, Object> plan) {
        return (Map<String, Object>) plan.get("text_layer");
    }

    private CommerceSuiteDtos.PlatformPreset platform() {
        return new CommerceSuiteDtos.PlatformPreset(
            "taobao_tmall",
            "淘宝/天猫",
            "品牌感、干净、质感",
            true,
            false,
            "1K",
            "1024x1024",
            List.of("main_image"),
            "品牌感、干净、质感",
            "zh-CN",
            "zh-CN"
        );
    }

    private CommerceSuiteDtos.ImageSlot slot(String code, String name) {
        return new CommerceSuiteDtos.ImageSlot(code, name, "测试图种", "测试", "1024x1024", 1);
    }

    private CommerceSuiteDtos.TemplatePreset template(CommerceSuiteDtos.ImageSlot slot) {
        return template(platform(), slot);
    }

    private CommerceSuiteDtos.TemplatePreset template(CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ImageSlot slot) {
        Map<String, Object> textLayer = new LinkedHashMap<>();
        textLayer.put("title", "{{product_name}}");
        textLayer.put("subtitle", "{{slot_name}}");
        textLayer.put("selling_points", List.of("{{selling_point_1}}", "{{selling_point_2}}", "{{selling_point_3}}"));
        textLayer.put("price_text", "{{price_text}}");
        textLayer.put("badge_text", "{{promo_text}}");
        textLayer.put("footer_text", "{{brand}}");

        Map<String, Object> overlay = new LinkedHashMap<>();
        overlay.put("enabled", true);
        overlay.put("text_layer", textLayer);

        return new CommerceSuiteDtos.TemplatePreset(
            platform.code() + "-" + slot.code(),
            platform.code(),
            slot.code(),
            slot.name(),
            slot.description(),
            "Real selling points: {{selling_points}}. {{slot_strategy}}",
            "",
            overlay,
            slot.defaultSize(),
            true,
            1,
            Map.of()
        );
    }
}
