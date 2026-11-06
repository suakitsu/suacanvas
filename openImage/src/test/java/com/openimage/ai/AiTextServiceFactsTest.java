package com.openimage.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.ProductProjectEntity;
import com.openimage.domain.mapper.AiTextCallLogMapper;
import com.openimage.domain.mapper.PromptTemplateMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiTextServiceFactsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PromptTemplateMapper promptTemplates = mock(PromptTemplateMapper.class);
    private final AiRuntimeConfigService runtimeConfig = mock(AiRuntimeConfigService.class);
    private final AiTextCallAuditService textAudit = new AiTextCallAuditService(mock(AiTextCallLogMapper.class));

    @Test
    void mainImageSuppressesTextColorHintsAndUsesMainImageColorPolicy() throws Exception {
        AiTextService service = new AiTextService(json, promptTemplates, runtimeConfig, textAudit);

        ProductProjectEntity project = new ProductProjectEntity();
        project.setId("prod_test");
        project.setBrand("PicPilot");
        project.setProductName("Lolita Rabbit Bag");
        project.setCountry("US");
        project.setLanguage("en-US");
        project.setConfirmedFacts("{\"main_image_uploaded\":false,\"color_hints\":[\"Gray\"],\"color_policy\":\"legacy\"}");

        Map<String, Object> originalInfo = new LinkedHashMap<>();
        originalInfo.put("description", "写实休闲户外上身实拍，背景浅虚化绿植街道；Color: Green");
        originalInfo.put("visual_prompt", "greenery street background");

        List<Map<String, Object>> images = List.of(Map.of("imageType", "main"));

        Map<String, Object> facts = service.rebuildConfirmedFacts(project, originalInfo, images);
        assertEquals(Boolean.TRUE, facts.get("main_image_uploaded"));
        assertTrue(((List<?>) facts.get("color_hints")).isEmpty());
        assertTrue(String.valueOf(facts.get("color_policy")).contains("source of truth"));
        assertTrue(String.valueOf(facts.get("color_policy")).contains("Scene/background color words do not describe the product color"));

        project.setConfirmedFacts(json.writeValueAsString(facts));
        Map<String, Object> copy = Map.of(
            "visual_prompt", "greenery street background"
        );

        String prompt = service.buildImagePrompt(project, copy, new AiTextService.ImageBriefSeed(
            "main",
            "1 白底主图",
            6,
            "main_image_uploaded,color_policy,color_hints",
            "1024x1024",
            "1024x1024"
        ));

        assertTrue(prompt.contains("\"main_image_uploaded\": true") || prompt.contains("\"main_image_uploaded\":true"));
        assertTrue(prompt.contains("Preserve the product color exactly as shown in the reference image"));
        assertTrue(prompt.contains("cutout, background replacement, or cleanup"));
        assertFalse(prompt.contains("\"color_hints\" : [ \"Green\" ]"));
    }

    @Test
    void extractsOnlyExplicitColorFieldsWhenNoMainImageExists() throws Exception {
        AiTextService service = new AiTextService(json, promptTemplates, runtimeConfig, textAudit);

        ProductProjectEntity project = new ProductProjectEntity();
        project.setId("prod_no_main");
        project.setBrand("PicPilot");
        project.setProductName("Sun Protection Jacket");
        project.setCountry("US");
        project.setLanguage("en-US");
        project.setConfirmedFacts("{}");

        Map<String, Object> originalInfo = new LinkedHashMap<>();
        originalInfo.put("description", "Background with greenery street. Product color: White, Gray");
        originalInfo.put("visual_prompt", "greenery street background");

        Map<String, Object> facts = service.rebuildConfirmedFacts(project, originalInfo, List.of());
        assertEquals(Boolean.FALSE, facts.get("main_image_uploaded"));
        assertTrue(List.copyOf((List<?>) facts.get("color_hints")).containsAll(List.of("White", "Gray")));
        assertFalse(List.copyOf((List<?>) facts.get("color_hints")).contains("Green"));
    }

    @Test
    void mainReferenceColorOverridesWrongCopyColorInImagePrompt() throws Exception {
        AiTextService service = new AiTextService(json, promptTemplates, runtimeConfig, textAudit);
        ProductProjectEntity project = new ProductProjectEntity();
        project.setId("prod_color_conflict");
        project.setBrand("TB");
        project.setProductName("TB white sun protection hoodie jacket");
        project.setCountry("US");
        project.setLanguage("en-US");
        project.setConfirmedFacts("""
            {"main_image_uploaded":true,"color_policy":"The uploaded main product image is the source of truth. Preserve the exact product color from the main reference image."}
            """);

        Map<String, Object> copy = Map.of(
            "title", "TB Sun Protection Hoodie Jacket, Green",
            "description", "Color: Green",
            "bullets", List.of("Lightweight sun protection hoodie")
        );

        String snapshot = service.buildImagePrompt(project, copy, new AiTextService.ImageBriefSeed(
            "main",
            "Main image",
            6,
            "main image",
            "1024x1024",
            "1024x1024"
        ));
        Map<String, Object> prompt = json.readValue(snapshot, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        String modelPrompt = String.valueOf(prompt.get("modelPrompt"));

        assertTrue(modelPrompt.contains("- Product: TB white sun protection hoodie jacket"));
        assertFalse(modelPrompt.contains("- Product: TB Sun Protection Hoodie Jacket, Green"));
        assertTrue(modelPrompt.contains("ignore the copy color and follow the main reference image"));
    }

    @Test
    void suggestsDomesticSellingPointsWithFallbackWhenTextAiIsDisabled() {
        when(runtimeConfig.textProviderChain()).thenReturn(List.of());
        when(runtimeConfig.effectiveText()).thenReturn(new AiRuntimeConfigService.EffectiveTextConfig(false, "deepseek", "", "", "", 45, ""));
        AiTextService service = new AiTextService(json, promptTemplates, runtimeConfig, textAudit);

        List<String> suggestions = service.suggestDomesticSellingPoints(
            "Lolita 兔子玩偶包",
            "小红书",
            "主图",
            "",
            "Lolita 爱好者",
            "zh-CN"
        );

        assertTrue(suggestions.size() >= 5);
        assertTrue(suggestions.size() <= 8);
        assertTrue(suggestions.contains("出街好搭"));
        assertTrue(suggestions.contains("拍照更出片"));
        assertFalse(String.join("", suggestions).contains("最"));
        assertFalse(String.join("", suggestions).contains("Lolita"));
        assertFalse(suggestions.contains("软萌"));
    }

    @Test
    void compilesReferencePreserveIntentAndExactVisibleCopy() throws Exception {
        AiTextService service = new AiTextService(json, promptTemplates, runtimeConfig, textAudit);
        ProductProjectEntity project = new ProductProjectEntity();
        project.setId("prod_intent");
        project.setProductName("白色短袖");
        project.setBrand("PicPilot");
        project.setLanguage("zh-CN");
        project.setConfirmedFacts("""
            {"intent":"poster_layout","text_mode":"ai_short_text","reference_policy":"preserve","reference_count":1}
            """);

        String snapshot = service.buildSingleImagePrompt(project, "1688 详情页模块，标题为：“轻盈收纳”", "creative", "自由创作图", "1024x1024", "1024x1024", "国内电商 / 1688 / 详情页模块");

        assertTrue(snapshot.contains("Treat uploaded images as optional supporting visual inputs"));
        assertTrue(snapshot.contains("Create an ecommerce commercial layout"));
        assertTrue(snapshot.contains("Add visible text only where the user explicitly requests it."));
    }

    @Test
    void creativeCompilerKeepsExplicitReplacementAndIncludesUserPromptOnce() throws Exception {
        AiTextService service = new AiTextService(json, promptTemplates, runtimeConfig, textAudit);
        ProductProjectEntity project = creativeProject("prod_replace", "对话生图");
        String userPrompt = "以第一张参考图作为构图、摆放角度和场景基准，严格保持拍摄角度、透视关系、光线方向和背景不变；仅将画面中的原产品替换为第二张参考图中的商品本体。第二张参考图是产品身份、真实形状、颜色、材质和比例的最高优先级。不要添加品牌文字、价格、二维码、水印或平台 UI；不要生成任何随机文字。";
        project.setConfirmedFacts(json.writeValueAsString(Map.of(
            "mode", "creative",
            "prompt", userPrompt,
            "intent", "background_edit",
            "text_mode", "ai_short_text",
            "reference_policy", "preserve",
            "reference_count", 2
        )));

        Map<String, Object> snapshot = snapshot(service.buildSingleImagePrompt(
            project, userPrompt, "creative", "自由创作图", "2048x2048", "2048x2048", "Agent 自动判断"
        ));
        String modelPrompt = String.valueOf(snapshot.get("modelPrompt"));

        assertEquals(1, countOccurrences(modelPrompt, userPrompt));
        assertFalse(String.valueOf(snapshot.get("brief")).contains(userPrompt));
        assertTrue(modelPrompt.contains("Perform the user's explicit subject replacement exactly."));
        assertTrue(modelPrompt.contains("do not preserve the original object"));
        assertFalse(modelPrompt.contains("Reference image is the source of truth. Do not replace the product."));
        assertFalse(modelPrompt.contains("Only modify the requested background"));
        assertTrue(modelPrompt.contains("Add no new visible text."));
    }

    @Test
    void creativeCompilerPreservesPackagingTextWithoutAddingPosterCopy() throws Exception {
        AiTextService service = new AiTextService(json, promptTemplates, runtimeConfig, textAudit);
        ProductProjectEntity project = creativeProject("prod_packaging", "自由创作");
        String userPrompt = "电商商业竖版海报，第一张参考图中的包装盒图案、文字、比例尺寸严格保持一致，禁止修改盒子任何图文。第二张参考图只参考深蓝科技背景、构图和舞台光影。画面不要添加任何大字标语、引号文字或额外文案。";
        project.setConfirmedFacts(json.writeValueAsString(Map.of(
            "mode", "creative",
            "prompt", userPrompt,
            "intent", "background_edit",
            "text_mode", "ai_short_text",
            "reference_policy", "preserve",
            "reference_count", 2
        )));

        Map<String, Object> snapshot = snapshot(service.buildSingleImagePrompt(
            project, userPrompt, "creative", "自由创作图", "1664x2080", "1664x2080", "AI 自动判断"
        ));
        String modelPrompt = String.valueOf(snapshot.get("modelPrompt"));

        assertEquals(1, countOccurrences(modelPrompt, userPrompt));
        assertTrue(modelPrompt.contains("Follow the user's role for every specifically named asset."));
        assertTrue(modelPrompt.contains("Preserve the existing text, artwork, labels, and logo"));
        assertTrue(modelPrompt.contains("Add no new headline, slogan, promotional copy"));
        assertFalse(modelPrompt.contains("Add visible text only where the user explicitly requests it."));
    }

    @Test
    void creativeCompilerRemovesLegacyMachineIntentPollution() throws Exception {
        AiTextService service = new AiTextService(json, promptTemplates, runtimeConfig, textAudit);
        ProductProjectEntity project = creativeProject("prod_polluted", "对话生图");
        String core = "第一张参考图保留构图，原产品替换为第二张参考图中的商品，不要添加文字。";
        String polluted = core + """


            Intent and reference constraints:
            Reference image is the source of truth. Do not replace the product.
            Keep the product from the reference image unchanged. Only modify the requested background.
            Do not render readable text, logos, watermarks, labels, QR codes, or platform UI unless explicitly requested.
            """;
        project.setConfirmedFacts(json.writeValueAsString(Map.of(
            "mode", "creative",
            "prompt", polluted,
            "intent", "background_edit",
            "text_mode", "ai_short_text",
            "reference_policy", "preserve",
            "reference_count", 2
        )));

        Map<String, Object> snapshot = snapshot(service.buildSingleImagePrompt(
            project, polluted, "creative", "自由创作图", "2048x2048", "2048x2048", "Agent 自动判断"
        ));
        String modelPrompt = String.valueOf(snapshot.get("modelPrompt"));

        assertEquals(1, countOccurrences(modelPrompt, core));
        assertFalse(modelPrompt.contains("Intent and reference constraints:"));
        assertFalse(modelPrompt.contains("Reference image is the source of truth. Do not replace the product."));
        assertFalse(modelPrompt.contains("Keep the product from the reference image unchanged."));
    }

    @Test
    void creativeCompilerSupportsTextOnlyEvenWhenImagesWereUploaded() throws Exception {
        AiTextService service = new AiTextService(json, promptTemplates, runtimeConfig, textAudit);
        ProductProjectEntity project = creativeProject("prod_text_only", "自由创作");
        String userPrompt = "这些图片只是临时上传，不用参考图。直接按文字生成一把透明红色雨伞的白底商品图，不要文字。";
        project.setConfirmedFacts(json.writeValueAsString(Map.of(
            "mode", "creative",
            "prompt", userPrompt,
            "intent", "free_create",
            "text_mode", "no_text",
            "reference_policy", "none",
            "reference_count", 18
        )));

        Map<String, Object> snapshot = snapshot(service.buildSingleImagePrompt(
            project, userPrompt, "creative", "自由创作图", "1024x1024", "1024x1024", "AI 自动判断"
        ));
        String modelPrompt = String.valueOf(snapshot.get("modelPrompt"));

        assertEquals(1, countOccurrences(modelPrompt, userPrompt));
        assertTrue(modelPrompt.contains("Ignore those images and execute the text request only."));
        assertFalse(modelPrompt.contains("Preserve the designated product reference"));
        assertTrue(modelPrompt.contains("Add no new visible text."));
    }

    @Test
    void listingImagePromptWritesAuditablePromptAndInfographicRequirements() throws Exception {
        AiTextService service = new AiTextService(json, promptTemplates, runtimeConfig, textAudit);
        ProductProjectEntity project = new ProductProjectEntity();
        project.setId("prod_listing_prompt");
        project.setProductName("Travel Packing Cubes");
        project.setBrand("PicPilot");
        project.setCountry("US");
        project.setLanguage("en-US");
        project.setConfirmedFacts("""
            {"main_image_uploaded":true,"dimensions":"","description":"Package Included: 3 packing cubes","language":"en-US"}
            """);

        Map<String, Object> copy = Map.of(
            "title", "PicPilot Travel Packing Cubes for Organized Luggage",
            "bullets", List.of(
                "Space Saving: Helps separate clothes and accessories",
                "Easy Sorting: Clear organization for trips",
                "Durable Zipper: Smooth daily packing"
            )
        );

        String dimensionSnapshot = service.buildImagePrompt(project, copy, new AiTextService.ImageBriefSeed(
            "dimensions",
            "4 尺寸图",
            6,
            "dimensions",
            "1024x1024",
            "1024x1024"
        ));
        assertTrue(dimensionSnapshot.contains("\"prompt\""));
        assertTrue(dimensionSnapshot.contains("\"modelPrompt\""));
        assertTrue(dimensionSnapshot.contains("measurement arrows"));
        assertTrue(dimensionSnapshot.contains("Do not write any numeric dimensions or units"));
        assertTrue(dimensionSnapshot.contains("Visible text must be short, crisp, readable"));

        String featureSnapshot = service.buildImagePrompt(project, copy, new AiTextService.ImageBriefSeed(
            "features",
            "3 细节卖点图",
            6,
            "bullets",
            "1024x1024",
            "1024x1024"
        ));
        assertTrue(featureSnapshot.contains("3-5 product detail zoom boxes"));
        assertTrue(featureSnapshot.contains("arrow/callout line"));
        assertTrue(featureSnapshot.contains("Space Saving"));
    }

    @Test
    void listingImagePromptLocalizesGermanGenericLabels() throws Exception {
        AiTextService service = new AiTextService(json, promptTemplates, runtimeConfig, textAudit);
        ProductProjectEntity project = new ProductProjectEntity();
        project.setId("prod_de_listing_prompt");
        project.setProductName("测试款儿童保温杯");
        project.setBrand("PicPilot");
        project.setCountry("US");
        project.setLanguage("de-DE");
        project.setConfirmedFacts("""
            {"main_image_uploaded":false,"language":"de-DE","material":"Edelstahl","dimensions":"500 ml"}
            """);

        String snapshot = service.buildImagePrompt(project, Map.of(
            "title", "PicPilot Kinder Trinkflasche mit Strohhalm",
            "bullets", List.of("Leicht zu tragen: Für Schule und Reise")
        ), new AiTextService.ImageBriefSeed(
            "features",
            "3 细节卖点图",
            6,
            "bullets",
            "1024x1024",
            "1024x1024"
        ));

        assertTrue(snapshot.contains("Merkmals-Infografik"));
        assertTrue(snapshot.contains("Wichtige Merkmale"));
        assertFalse(snapshot.contains("Key Features"));
    }

    @Test
    void creativeChatXiaohongshuReferencePromptDoesNotGuessProductCategory() {
        when(runtimeConfig.textProviderChain()).thenReturn(List.of(
            new AiRuntimeConfigService.EffectiveTextConfig(true, "deepseek", "http://127.0.0.1:1", "test-key", "deepseek-test", 10, "text_test")
        ));
        AiTextService service = new AiTextService(json, promptTemplates, runtimeConfig, textAudit);

        String prompt = service.buildCreativeChatPrompt(
            List.of(),
            "讲这个作为国内小红书商品图",
            "国内电商 / 小红书 / 小红书种草图",
            "1024x1024",
            "zh-CN",
            "uploaded-reference-images",
            Map.of(
                "intent", "poster_layout",
                "text_mode", "ai_short_text",
                "reference_policy", "preserve",
                "reference_count", 1
            )
        );

        assertTrue(prompt.contains("the product shown in the reference image"));
        assertTrue(prompt.contains("Uploaded images are optional visual inputs in stable upload order"));
        assertFalse(prompt.contains("Intent and reference constraints:"));
        assertTrue(prompt.contains("小红书"));
        assertFalse(prompt.contains("护肤"));
        assertFalse(prompt.contains("美妆"));
    }

    private ProductProjectEntity creativeProject(String id, String productName) {
        ProductProjectEntity project = new ProductProjectEntity();
        project.setId(id);
        project.setProductName(productName);
        project.setBrand("AI 自动判断");
        project.setCountry("CUSTOM");
        project.setLanguage("zh-CN");
        return project;
    }

    private Map<String, Object> snapshot(String value) throws Exception {
        return json.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    private int countOccurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
