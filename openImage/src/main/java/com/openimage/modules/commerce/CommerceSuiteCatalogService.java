package com.openimage.modules.commerce;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.SystemSettingEntity;
import com.openimage.domain.mapper.SystemSettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CommerceSuiteCatalogService {
    private static final String SETTING_KEY = "commerce_suite_catalog";
    private static final String CANVAS_FRAMING_RULE = "Keep every important product, package, person, body part, label, callout, badge, and visible text entirely inside the inner 8% canvas safe area. Preserve the complete product silhouette and all readable copy; never let essential content touch or cross any image edge.";
    private static final String HUMAN_FRAMING_RULE = "If any person or model is visible, keep the entire head, all hair, face, hands, and the complete advertised garment silhouette inside the canvas with natural breathing room above the hair. Never crop through the scalp, forehead, eyes, chin, wrists, fingers, or garment hem.";
    private static final String DETAIL_HUMAN_FRAMING_RULE = "For garment macro/detail compositions, either show the complete head with breathing room or exclude the face and head entirely by framing below the shoulders; never leave a partial face or partial head at an image edge.";
    private static final String CANVAS_CROP_NEGATIVE = "No cropped product, no cut-off package, no truncated label, no clipped callout, no text crossing an edge, no essential subject touching the canvas boundary.";
    private static final String HUMAN_FRAMING_NEGATIVE = "No cropped head, no cut-off hair, no partial face, no forehead cut, no clipped chin, no cut-off hands or fingers, no awkward body crop.";

    private final SystemSettingMapper settings;
    private final ObjectMapper json;

    public CommerceSuiteCatalogService(SystemSettingMapper settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    public CommerceSuiteDtos.Catalog catalog() {
        SystemSettingEntity row = settingRow();
        if (row != null && row.getSettingValue() != null && !row.getSettingValue().isBlank()) {
            try {
                CommerceSuiteDtos.Catalog saved = json.readValue(row.getSettingValue(), CommerceSuiteDtos.Catalog.class);
                return normalize(saved);
            } catch (Exception ignored) {
                // Invalid admin JSON falls back to the built-in catalog instead of breaking user generation.
            }
        }
        return defaultCatalog();
    }

    public List<CommerceSuiteDtos.PlatformPreset> platforms() {
        return catalog().platforms();
    }

    public List<CommerceSuiteDtos.TemplatePreset> templates(String platform) {
        String code = blankToDefault(platform, "").trim();
        return catalog().templates().stream()
            .filter(template -> code.isBlank() || code.equals(template.platform()))
            .toList();
    }

    public CommerceSuiteDtos.PlatformPreset platform(String code) {
        return platforms().stream()
            .filter(item -> item.code().equals(code))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("平台模板不存在：" + code));
    }

    public CommerceSuiteDtos.ImageSlot slot(String code) {
        return catalog().slots().stream()
            .filter(item -> item.code().equals(code))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("图片槽位不存在：" + code));
    }

    public CommerceSuiteDtos.TemplatePreset template(String platform, String slot) {
        return catalog().templates().stream()
            .filter(item -> item.platform().equals(platform) && item.slot().equals(slot) && Boolean.TRUE.equals(item.enabled()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("该平台未启用图种模板：" + slot));
    }

    @Transactional
    public CommerceSuiteDtos.Catalog saveCatalog(CommerceSuiteDtos.Catalog catalog) {
        CommerceSuiteDtos.Catalog normalized = normalize(catalog);
        upsert(normalized);
        return normalized;
    }

    @Transactional
    public CommerceSuiteDtos.PlatformPreset savePlatform(String code, CommerceSuiteDtos.PlatformPreset patch) {
        CommerceSuiteDtos.Catalog current = catalog();
        List<CommerceSuiteDtos.PlatformPreset> platforms = new ArrayList<>();
        CommerceSuiteDtos.PlatformPreset saved = null;
        for (CommerceSuiteDtos.PlatformPreset item : current.platforms()) {
            if (item.code().equals(code)) {
                saved = normalizePlatform(new CommerceSuiteDtos.PlatformPreset(
                    code,
                    blankToDefault(patch.name(), item.name()),
                    blankToDefault(patch.description(), item.description()),
                    patch.enabled() == null ? item.enabled() : patch.enabled(),
                    patch.beta() == null ? item.beta() : patch.beta(),
                    blankToDefault(patch.defaultResolution(), item.defaultResolution()),
                    blankToDefault(patch.defaultSize(), item.defaultSize()),
                    patch.defaultSlots() == null || patch.defaultSlots().isEmpty() ? item.defaultSlots() : patch.defaultSlots(),
                    blankToDefault(patch.stylePrompt(), item.stylePrompt()),
                    blankToDefault(patch.language(), item.language()),
                    blankToDefault(patch.overlayLanguage(), item.overlayLanguage())
                ));
                platforms.add(saved);
            } else {
                platforms.add(item);
            }
        }
        if (saved == null) {
            saved = normalizePlatform(new CommerceSuiteDtos.PlatformPreset(
                code,
                blankToDefault(patch.name(), code),
                blankToDefault(patch.description(), ""),
                patch.enabled() == null ? Boolean.TRUE : patch.enabled(),
                patch.beta() == null ? Boolean.TRUE : patch.beta(),
                blankToDefault(patch.defaultResolution(), "1K"),
                blankToDefault(patch.defaultSize(), "1024x1024"),
                patch.defaultSlots() == null ? List.of("main_image") : patch.defaultSlots(),
                blankToDefault(patch.stylePrompt(), ""),
                blankToDefault(patch.language(), "zh-CN"),
                blankToDefault(patch.overlayLanguage(), "zh-CN")
            ));
            platforms.add(saved);
        }
        saveCatalog(new CommerceSuiteDtos.Catalog(platforms, current.slots(), current.templates(), current.costRule(), OffsetDateTime.now()));
        return saved;
    }

    @Transactional
    public CommerceSuiteDtos.TemplatePreset saveTemplate(String id, CommerceSuiteDtos.TemplatePreset patch) {
        CommerceSuiteDtos.Catalog current = catalog();
        List<CommerceSuiteDtos.TemplatePreset> templates = new ArrayList<>();
        CommerceSuiteDtos.TemplatePreset saved = null;
        for (CommerceSuiteDtos.TemplatePreset item : current.templates()) {
            if (item.id().equals(id)) {
                saved = normalizeTemplate(new CommerceSuiteDtos.TemplatePreset(
                    id,
                    blankToDefault(patch.platform(), item.platform()),
                    blankToDefault(patch.slot(), item.slot()),
                    blankToDefault(patch.name(), item.name()),
                    blankToDefault(patch.description(), item.description()),
                    blankToDefault(patch.promptTemplate(), item.promptTemplate()),
                    blankToDefault(patch.negativePrompt(), item.negativePrompt()),
                    patch.overlayTemplate() == null || patch.overlayTemplate().isEmpty() ? item.overlayTemplate() : patch.overlayTemplate(),
                    blankToDefault(patch.defaultSize(), item.defaultSize()),
                    patch.enabled() == null ? item.enabled() : patch.enabled(),
                    patch.sortOrder() == null ? item.sortOrder() : patch.sortOrder(),
                    patch.providerParams() == null ? item.providerParams() : patch.providerParams()
                ));
                templates.add(saved);
            } else {
                templates.add(item);
            }
        }
        if (saved == null) {
            saved = normalizeTemplate(new CommerceSuiteDtos.TemplatePreset(
                id,
                patch.platform(),
                patch.slot(),
                blankToDefault(patch.name(), id),
                blankToDefault(patch.description(), ""),
                blankToDefault(patch.promptTemplate(), ""),
                blankToDefault(patch.negativePrompt(), defaultNegativePrompt()),
                patch.overlayTemplate() == null ? Map.of() : patch.overlayTemplate(),
                blankToDefault(patch.defaultSize(), "1024x1024"),
                patch.enabled() == null ? Boolean.TRUE : patch.enabled(),
                patch.sortOrder() == null ? 999 : patch.sortOrder(),
                patch.providerParams() == null ? Map.of() : patch.providerParams()
            ));
            templates.add(saved);
        }
        saveCatalog(new CommerceSuiteDtos.Catalog(current.platforms(), current.slots(), templates, current.costRule(), OffsetDateTime.now()));
        return saved;
    }

    private SystemSettingEntity settingRow() {
        return settings.selectOne(new LambdaQueryWrapper<SystemSettingEntity>()
            .eq(SystemSettingEntity::getDeleted, 0)
            .eq(SystemSettingEntity::getSettingKey, SETTING_KEY)
            .last("limit 1"));
    }

    private void upsert(CommerceSuiteDtos.Catalog catalog) {
        try {
            String value = json.writeValueAsString(catalog);
            SystemSettingEntity existing = settingRow();
            OffsetDateTime now = OffsetDateTime.now();
            if (existing == null) {
                existing = new SystemSettingEntity();
                existing.setId("setting_" + uuid());
                existing.setSettingKey(SETTING_KEY);
                existing.setDescription("电商套图平台与 Prompt 模板配置");
                existing.setCreatedAt(now);
                existing.setDeleted(0);
            }
            existing.setSettingValue(value);
            existing.setUpdatedAt(now);
            if (settings.selectById(existing.getId()) == null) {
                settings.insert(existing);
            } else {
                settings.updateById(existing);
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("电商套图配置保存失败：" + exception.getMessage());
        }
    }

    private CommerceSuiteDtos.Catalog normalize(CommerceSuiteDtos.Catalog value) {
        CommerceSuiteDtos.Catalog fallback = defaultCatalog();
        if (value == null) {
            return fallback;
        }
        List<CommerceSuiteDtos.PlatformPreset> platforms = (value.platforms() == null || value.platforms().isEmpty() ? fallback.platforms() : value.platforms()).stream()
            .map(this::normalizePlatform)
            .toList();
        List<CommerceSuiteDtos.ImageSlot> slots = value.slots() == null || value.slots().isEmpty() ? fallback.slots() : value.slots();
        List<CommerceSuiteDtos.TemplatePreset> templates = (value.templates() == null || value.templates().isEmpty() ? fallback.templates() : value.templates()).stream()
            .map(this::normalizeTemplate)
            .toList();
        Map<String, Integer> costRule = value.costRule() == null || value.costRule().isEmpty() ? fallback.costRule() : value.costRule();
        return new CommerceSuiteDtos.Catalog(platforms, slots, templates, costRule, OffsetDateTime.now());
    }

    private CommerceSuiteDtos.PlatformPreset normalizePlatform(CommerceSuiteDtos.PlatformPreset item) {
        return new CommerceSuiteDtos.PlatformPreset(
            blankToDefault(item.code(), "platform"),
            blankToDefault(item.name(), blankToDefault(item.code(), "平台")),
            blankToDefault(item.description(), ""),
            item.enabled() == null ? Boolean.TRUE : item.enabled(),
            item.beta() == null ? Boolean.FALSE : item.beta(),
            blankToDefault(item.defaultResolution(), "1K"),
            blankToDefault(item.defaultSize(), "1024x1024"),
            item.defaultSlots() == null ? List.of() : item.defaultSlots(),
            blankToDefault(item.stylePrompt(), ""),
            blankToDefault(item.language(), "zh-CN"),
            blankToDefault(item.overlayLanguage(), item.language())
        );
    }

    private CommerceSuiteDtos.TemplatePreset normalizeTemplate(CommerceSuiteDtos.TemplatePreset item) {
        return new CommerceSuiteDtos.TemplatePreset(
            blankToDefault(item.id(), blankToDefault(item.platform(), "platform") + "-" + blankToDefault(item.slot(), "slot")),
            blankToDefault(item.platform(), ""),
            blankToDefault(item.slot(), ""),
            blankToDefault(item.name(), blankToDefault(item.slot(), "图种")),
            blankToDefault(item.description(), ""),
            blankToDefault(item.promptTemplate(), ""),
            blankToDefault(item.negativePrompt(), defaultNegativePrompt()),
            item.overlayTemplate() == null ? Map.of() : item.overlayTemplate(),
            blankToDefault(item.defaultSize(), "1024x1024"),
            item.enabled() == null ? Boolean.TRUE : item.enabled(),
            item.sortOrder() == null ? 0 : item.sortOrder(),
            item.providerParams() == null ? Map.of("quality", "auto", "output_format", "png") : item.providerParams()
        );
    }

    private CommerceSuiteDtos.Catalog defaultCatalog() {
        List<CommerceSuiteDtos.ImageSlot> slots = slots();
        List<CommerceSuiteDtos.PlatformPreset> platforms = platformsDefault();
        List<CommerceSuiteDtos.TemplatePreset> templates = new ArrayList<>();
        Map<String, CommerceSuiteDtos.ImageSlot> slotMap = new LinkedHashMap<>();
        for (CommerceSuiteDtos.ImageSlot slot : slots) {
            slotMap.put(slot.code(), slot);
        }
        for (CommerceSuiteDtos.PlatformPreset platform : platforms) {
            int order = 1;
            for (String slotCode : platform.defaultSlots()) {
                CommerceSuiteDtos.ImageSlot slot = slotMap.get(slotCode);
                if (slot == null) {
                    continue;
                }
                templates.add(template(platform, slot, order++));
            }
        }
        return new CommerceSuiteDtos.Catalog(
            platforms,
            slots,
            templates,
            Map.of("1K", 6, "2K", 24, "4K", 30),
            OffsetDateTime.now()
        );
    }

    private List<CommerceSuiteDtos.PlatformPreset> platformsDefault() {
        return List.of(
            platform("taobao_tmall", "淘宝/天猫", false, true, "真实货架商品图集：主图清楚、卖点自然、详情可信、场景有代入感。允许少量中文电商文案，但不能每张都是右侧大字板。", "zh-CN", "zh-CN", "1024x1024", List.of("main_image", "selling_point", "scenario", "detail", "specification", "comparison", "packaging", "trust")),
            platform("pinduoduo", "拼多多", false, true, "高识别货架图集：缩略图一眼看懂、利益点明确、颜色更直接，但只展示用户确认的价格/优惠，避免廉价模板感。", "zh-CN", "zh-CN", "1024x1024", List.of("main_image", "selling_point", "promo", "comparison", "detail", "specification", "packaging", "crowd")),
            platform("douyin", "抖音电商", false, true, "内容电商商品图集：真人/手持/场景优先，像短视频静帧和商品卡资产，不要把每张都做成直播封面或促销大字报。", "zh-CN", "zh-CN", "720x1280", List.of("social_cover", "main_image", "scenario", "selling_point", "crowd", "detail")),
            platform("xiaohongshu", "小红书", false, true, "种草型商品图集：生活方式、审美、真实开箱/使用感，文字轻、像笔记首图和正文配图，避免硬广和平台模板感。", "zh-CN", "zh-CN", "768x1024", List.of("social_cover", "scenario", "detail", "crowd", "selling_point")),
            platform("tiktok_shop", "TikTok Shop", false, true, "TikTok Shop PDP gallery：1 张清晰主图、1 张模特/手持使用图、1 张真实场景图、2-3 张细节图、按 SKU 提供静态展示。整体像真实店铺商品图库，不像直播封面、广告海报或大字报。", "en-US", "en-US", "1024x1024", List.of("main_image", "model_image", "scenario", "detail", "detail_closeup", "detail_function", "sku_display")),
            platform("jd", "京东", true, true, "品质型商品图集：白底/浅底、参数可信、细节扎实、服务信任克制，适合家电数码家居等理性决策品类。", "zh-CN", "zh-CN", "1024x1024", List.of("main_image", "white_background", "selling_point", "detail", "specification", "trust")),
            platform("alibaba_1688", "1688", true, true, "B2B 采购图集：工厂、材质、规格、包装、批量供货、结构细节清楚，像采购页而不是面向消费者的促销海报。", "zh-CN", "zh-CN", "1024x1024", List.of("main_image", "specification", "detail", "packaging", "comparison", "trust")),
            platform("vipshop", "唯品会", true, true, "品牌折扣商品图集：服饰/美妆/生活方式质感，模特和材质细节优先，促销信息克制，保持品牌零售感。", "zh-CN", "zh-CN", "832x1040", List.of("main_image", "scenario", "detail", "selling_point", "crowd", "promo"))
        );
    }

    private CommerceSuiteDtos.PlatformPreset platform(String code, String name, boolean beta, boolean enabled, String style, String language, String overlayLanguage, String size, List<String> slots) {
        return new CommerceSuiteDtos.PlatformPreset(code, name, style, enabled, beta, "1K", size, slots, style, language, overlayLanguage);
    }

    private List<CommerceSuiteDtos.ImageSlot> slots() {
        return List.of(
            slot("main_image", "商品主图", "商品主体清晰，适合搜索/货架入口", "基础", "1024x1024", 1),
            slot("white_background", "白底图", "干净白底，强调主体轮廓与真实结构", "基础", "1024x1024", 2),
            slot("selling_point", "核心卖点图", "围绕 2-3 个真实卖点组织画面", "转化", "1024x1024", 3),
            slot("scenario", "使用场景图", "真实使用环境、目标人群和代入感", "场景", "832x1040", 4),
            slot("detail", "产品细节图", "材质、结构、局部质感和做工细节", "信息", "1024x1024", 5),
            slot("model_image", "模特图", "真人佩戴、手持、上身或使用展示，突出比例和使用方式", "场景", "1024x1024", 6),
            slot("detail_closeup", "细节特写图", "局部材质、接口、纹理、做工或结构特写", "信息", "1024x1024", 7),
            slot("detail_function", "功能细节图", "围绕一个真实功能或使用动作做近景展示", "信息", "1024x1024", 8),
            slot("sku_display", "SKU 静态展示图", "按颜色、规格、套装或变体做静态展示；没有 SKU 时展示单品多角度", "基础", "1024x1024", 9),
            slot("specification", "规格参数图", "参数/尺寸/结构区域，不能编造数值", "信息", "1024x1024", 10),
            slot("comparison", "对比图", "左右或分区对比，只使用已确认优势", "转化", "1024x1024", 11),
            slot("packaging", "包装清单图", "包装、配件、发货或变体展示", "信息", "1024x1024", 12),
            slot("crowd", "目标人群图", "目标用户、穿搭/使用人群和场景情绪", "场景", "832x1040", 13),
            slot("trust", "信任背书图", "品牌、服务、工厂或品质信任氛围，不画虚假认证", "信任", "1024x1024", 14),
            slot("social_cover", "社媒封面图", "短视频/笔记首图，移动端强钩子", "内容", "720x1280", 15),
            slot("promo", "促销氛围图", "活动氛围与利益点承接，不编造价格承诺", "转化", "1024x1024", 16)
        );
    }

    private CommerceSuiteDtos.ImageSlot slot(String code, String name, String description, String category, String size, int sortOrder) {
        return new CommerceSuiteDtos.ImageSlot(code, name, description, category, size, sortOrder);
    }

    private CommerceSuiteDtos.TemplatePreset template(CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ImageSlot slot, int order) {
        String prompt = """
            Create an original ecommerce suite image for {{platform_name}}.
            Product: {{product_name}}. Category: {{category}}. Brand: {{brand}}.
            Target market/country: {{country}}. Visible text language: {{language}}.
            Material/specs: {{material}} {{specs}}. Audience: {{audience}}. Scenario: {{scenario}}.
            Real selling points: {{selling_points}}.

            Platform style:
            {{platform_style}}
            {{platform_suite_direction}}

            Slot goal:
            {{slot_name}} - {{slot_description}}.
            {{slot_strategy}}
            {{slot_quality_direction}}

            Image rules:
            - Treat reference_images as the source of truth for product structure, color, material, silhouette, pattern, and key visible details.
            - Preserve all printed artwork, logos, embroidery, labels, graphics, patterns, and visible product text from the reference exactly: same count, placement, scale, orientation, and color. Do not duplicate, tile, relocate, recolor, remove, or invent any extra product marks.
            - Generate a clean product/background image with strong mobile readability and coherent suite style.
            - Do not reuse the same right-side white information card across the suite. Vary each slot with its natural ecommerce layout: hero product, distributed callouts, macro labels, lifestyle caption, split comparison, package flatlay, or spec grid.
            - Render only the confirmed visible words from the visible text plan directly inside the AI image. If a field is empty, do not reserve blank space for it.
            - Any visible text must be in the requested visible text language and fit the target market/country.
            - Typography must be crisp, readable, platform-appropriate, and integrated with the product/photo composition; avoid stiff text boards unless the slot is a real spec/comparison layout.
            - Do not render internal slot names such as "core selling point image", "detail image", "comparison image", "product main image", or their Chinese equivalents as visible copy.
            - Do not render platform logos, marketplace UI, QR codes, watermarks, fake badges, fake prices, or unconfirmed claims.
            - Do not invent certifications, exact dimensions, compatibility, discounts, coupons, guarantees, medical effects, or package quantity.
            - Keep visual style consistent with other slots for the same platform suite.
            {{platform_text_policy}}
            """;
        Map<String, Object> overlay = defaultOverlayTemplate(platform, slot);
        return new CommerceSuiteDtos.TemplatePreset(
            platform.code() + "-" + slot.code(),
            platform.code(),
            slot.code(),
            platform.name() + " / " + slot.name(),
            slot.description(),
            prompt,
            defaultNegativePrompt(),
            overlay,
            slot.defaultSize(),
            true,
            order,
            Map.of("quality", "auto", "output_format", "png", "n", 1)
        );
    }

    private Map<String, Object> defaultOverlayTemplate(CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ImageSlot slot) {
        Map<String, Object> textLayer = new LinkedHashMap<>();
        textLayer.put("title", "{{product_name}}");
        textLayer.put("subtitle", "");
        textLayer.put("selling_points", List.of("{{selling_point_1}}", "{{selling_point_2}}", "{{selling_point_3}}"));
        textLayer.put("price_text", "{{price_text}}");
        textLayer.put("badge_text", "");
        textLayer.put("footer_text", "{{brand}}");
        textLayer.put("layout", overlayLayout(slot.code(), platform.code()));
        textLayer.put("copy_density", copyDensity(slot.code()));
        textLayer.put("font_size", "auto");
        textLayer.put("color", overlayColor(platform.code()));
        textLayer.put("position", overlayPosition(slot.code(), platform.code()));
        textLayer.put("background_style", overlayBackground(platform.code()));
        textLayer.put("composition_instruction", compositionInstruction(slot.code()));

        Map<String, Object> overlay = new LinkedHashMap<>();
        overlay.put("enabled", true);
        overlay.put("platform", platform.name());
        overlay.put("platform_code", platform.code());
        overlay.put("slot", slot.code());
        overlay.put("usage", slot.name());
        overlay.put("text_layer", textLayer);
        return overlay;
    }

    private String slotStrategy(String platformCode, String slotCode) {
        if ("tiktok_shop".equals(platformCode)) {
            return switch (slotCode) {
                case "main_image" -> "TikTok Shop main image: create a compliant product-first PDP hero on pure white or very light neutral background, product centered and large, accurate shape/color/material, realistic shadow, no added text, no badges, no model, no props that hide the product.";
                case "model_image" -> "TikTok Shop model/use image: show the product worn, held, or used by a realistic person only when it clarifies fit, scale, or usage. Keep it natural, modern, and shoppable; no headline, no creator UI, no live-stream styling.";
                case "scenario" -> "TikTok Shop scenario image: show one believable target-market lifestyle scene with the product clearly identifiable and in use. It should feel like a premium shop gallery photo, not an ad poster or video thumbnail.";
                case "detail" -> "TikTok Shop detail image: show product texture, material, workmanship, inside/outside structure, or accessory detail with a clean macro/product-photo composition. Prefer no added text; if labels are needed, use tiny factual labels only.";
                case "detail_closeup" -> "TikTok Shop close-up image: one tight macro crop of a real physical detail from the reference product, with sharp texture and no marketing panel.";
                case "detail_function" -> "TikTok Shop function detail: demonstrate one real use action or structural function visually. Do not invent features; keep the product close and clear.";
                case "sku_display" -> "TikTok Shop SKU/static display: show actual variants, colors, sizes, bundles, or multiple angles if SKU data is absent. Use a clean static grid or lineup, no price, no promo badge, no fake marketplace UI.";
                default -> "TikTok Shop gallery asset: realistic product photography, product identity first, no advertising poster layout.";
            };
        }
        if ("xiaohongshu".equals(platformCode)) {
            return switch (slotCode) {
                case "social_cover" -> "Create a tasteful Xiaohongshu note cover: lifestyle-first, editorial crop, one short natural Chinese hook only when confirmed, not a sales banner.";
                case "selling_point" -> "Show real-life benefit through scene/detail rather than a rigid infographic board; use light sticker-like labels only when useful.";
                case "scenario", "crowd" -> "Make it feel like an authentic lifestyle note photo with product presence, soft natural light, and minimal copy.";
                default -> slotStrategy(slotCode);
            };
        }
        if ("douyin".equals(platformCode)) {
            return switch (slotCode) {
                case "social_cover" -> "Create a mobile-first Douyin ecommerce cover with one concise hook and strong subject hierarchy, but avoid fake live UI or countdown graphics.";
                case "scenario", "crowd", "model_image" -> "Use creator-style realistic hands/model/use context with motion energy, product identity clear, and no heavy spec panel.";
                default -> slotStrategy(slotCode);
            };
        }
        if ("alibaba_1688".equals(platformCode)) {
            return switch (slotCode) {
                case "specification" -> "Use a procurement-friendly spec layout with only confirmed dimensions/materials/variants, clear structure, and no consumer hype.";
                case "packaging" -> "Show packaging, bundle, accessories, or shipping-ready presentation like a supplier listing, with small item tags only.";
                case "trust" -> "Show credible factory/material/process/quality-control atmosphere without fake certificates or seals.";
                default -> slotStrategy(slotCode);
            };
        }
        return slotStrategy(slotCode);
    }

    private String slotStrategy(String slotCode) {
        return switch (slotCode) {
            case "main_image" -> "Make the product the absolute hero with clean commercial composition, high thumbnail recognition, minimal copy, and no repeated side information card.";
            case "white_background" -> "Use a pure white or near-white studio setup, accurate shadow, no props, no text.";
            case "selling_point" -> "Use two or three small benefit callouts placed around the product, each tied to a real feature; do not title the image with the slot name.";
            case "scenario" -> "Place the product in a realistic daily-use scene matched to the audience and scenario, with at most one short integrated caption.";
            case "detail" -> "Use close-up macro/detail composition that reveals texture, construction, finish, and product quality, with tiny labels close to the relevant parts.";
            case "model_image" -> "Show a real model, hand model, or natural human use context matched to the product category. The product must remain the hero and should show scale, fit, or use.";
            case "detail_closeup" -> "Create a tight macro close-up of one or two physical details such as material, connector, stitching, texture, finish, control, or structural edge.";
            case "detail_function" -> "Show one confirmed function or use action in a close product-focused scene, using minimal labels only when the fact is provided.";
            case "sku_display" -> "Create a static product gallery image for SKU/variant display: colors, sizes, bundles, or multiple angles if SKU data is not provided. Keep it clean and product-only.";
            case "specification" -> "Use a measured technical layout only when real specs are provided; otherwise keep it visual and minimal. Render only confirmed numbers/specs.";
            case "comparison" -> "Create a clean left-right or before-after comparison using only confirmed differences; do not title it simply as comparison image.";
            case "packaging" -> "Show package, components, shipping/receipt feeling or variants only if provided; use small item tags instead of a full text board.";
            case "crowd" -> "Show the target audience or lifestyle context while keeping product identity clear, with at most one short caption.";
            case "trust" -> "Create trustworthy brand, service, factory, quality-control, or premium detail atmosphere without fake seals or generic trust slogans.";
            case "social_cover" -> "Create a strong mobile social cover with thumb-stopping composition and one short hook, not a product spec panel.";
            case "promo" -> "Create energetic campaign atmosphere and readable campaign text only when provided, without inventing price or discount numbers.";
            default -> "Create a clean ecommerce visual for this image slot.";
        };
    }

    private String defaultNegativePrompt() {
        return "No marketplace logo, no platform UI, no QR code, no watermark, no fake certification, no invented price, no invented discount, no random or unreadable text, no distorted product, no duplicated or invented product print/logo/pattern, no unrelated brand, no exaggerated medical or guaranteed claim. " + CANVAS_CROP_NEGATIVE + " " + HUMAN_FRAMING_NEGATIVE;
    }

    String renderNegativePrompt(CommerceSuiteDtos.TemplatePreset template) {
        String configured = blankToDefault(template == null ? null : template.negativePrompt(), defaultNegativePrompt());
        return appendRule(appendRule(configured, CANVAS_CROP_NEGATIVE), HUMAN_FRAMING_NEGATIVE);
    }

    private String appendRule(String value, String rule) {
        return value.contains(rule) ? value : value + " " + rule;
    }

    private String slotBadge(String slotCode, String platformCode) {
        if ("tiktok_shop".equals(platformCode)) {
            return switch (slotCode) {
                case "social_cover" -> "Shop Now";
                case "comparison" -> "Before / After";
                default -> "Hot Pick";
            };
        }
        return switch (slotCode) {
            case "promo" -> "活动";
            case "main_image" -> "新品";
            case "trust" -> "安心选";
            case "social_cover" -> "种草";
            default -> "";
        };
    }

    private String overlayLayout(String slotCode, String platformCode) {
        return switch (slotCode) {
            case "main_image" -> "hero_product_minimal";
            case "white_background" -> "clean_no_text";
            case "selling_point" -> "distributed_benefit_callouts";
            case "scenario", "crowd" -> "lifestyle_photo_caption";
            case "detail" -> "macro_detail_labels";
            case "model_image" -> "model_or_hand_use_photo";
            case "detail_closeup" -> "macro_closeup_labels";
            case "detail_function" -> "single_function_demo";
            case "sku_display" -> "static_variant_gallery";
            case "specification" -> "spec_grid_or_measure_lines";
            case "comparison" -> "split_comparison";
            case "packaging" -> "flatlay_item_tags";
            case "trust" -> "premium_scene_small_brand_cue";
            case "social_cover" -> "mobile_hook_overlay";
            case "promo" -> "campaign_visual";
            default -> "slot_specific_composition";
        };
    }

    private String overlayColor(String platformCode) {
        return switch (platformCode) {
            case "pinduoduo" -> "#d93025";
            case "douyin", "tiktok_shop" -> "#111827";
            case "xiaohongshu", "vipshop" -> "#b4235a";
            case "jd" -> "#1d4ed8";
            case "alibaba_1688" -> "#b45309";
            default -> "#1f2937";
        };
    }

    private String overlayPosition(String slotCode, String platformCode) {
        return switch (slotCode) {
            case "main_image" -> "corner_or_under_product";
            case "white_background" -> "none";
            case "selling_point" -> "around_product";
            case "scenario", "crowd" -> "natural_caption_area";
            case "detail" -> "near_relevant_parts";
            case "model_image" -> "natural_scene_no_panel";
            case "detail_closeup" -> "near_macro_detail";
            case "detail_function" -> "near_function_area";
            case "sku_display" -> "small_labels_beside_variants";
            case "specification" -> "bottom_grid_or_measure_lines";
            case "comparison" -> "inside_each_split_area";
            case "packaging" -> "beside_each_component";
            case "trust" -> "small_corner_or_near_product";
            case "social_cover" -> "top_or_bottom_hook";
            case "promo" -> "campaign_area";
            default -> "composition_dependent";
        };
    }

    private String overlayBackground(String platformCode) {
        return switch (platformCode) {
            case "pinduoduo" -> "small_high_contrast_labels_only";
            case "xiaohongshu" -> "light_note_caption_not_card_stack";
            case "douyin", "tiktok_shop" -> "minimal_mobile_caption_overlay";
            case "alibaba_1688" -> "small_factory_tags_not_full_panel";
            default -> "transparent_or_subtle_labels";
        };
    }

    private String copyDensity(String slotCode) {
        return switch (slotCode) {
            case "white_background" -> "none";
            case "scenario", "crowd", "social_cover", "detail", "detail_closeup", "detail_function", "model_image", "packaging", "trust", "sku_display" -> "very_low";
            case "selling_point", "comparison", "specification" -> "low";
            case "main_image", "promo" -> "medium_when_confirmed";
            default -> "low";
        };
    }

    private String compositionInstruction(String slotCode) {
        return switch (slotCode) {
            case "main_image" -> "Large product hero with safe framing; only a compact title/benefit/price block if confirmed, never a tall side spec card. " + HUMAN_FRAMING_RULE;
            case "white_background" -> "No visible text. Pure product cutout/studio image.";
            case "selling_point" -> "Small icon-like callouts around real product features; no generic heading.";
            case "scenario" -> "Lifestyle photo first; text is optional and should feel like a short scene caption. " + HUMAN_FRAMING_RULE;
            case "crowd" -> "Show audience/lifestyle; one short audience caption at most. " + HUMAN_FRAMING_RULE;
            case "detail" -> "Macro crops and tiny part labels near the product details. " + DETAIL_HUMAN_FRAMING_RULE;
            case "model_image" -> "Model/hand-use product photo; show scale and real use, no text panel. " + HUMAN_FRAMING_RULE;
            case "detail_closeup" -> "Macro physical detail; one or two tiny labels at most, no title. " + DETAIL_HUMAN_FRAMING_RULE;
            case "detail_function" -> "One function/action close-up; short label only when confirmed. " + DETAIL_HUMAN_FRAMING_RULE;
            case "sku_display" -> "Static SKU/variant display; labels only for real colors/sizes/bundles, no marketing panel.";
            case "specification" -> "Only real specs; use a compact grid or measurement arrows, not a marketing panel.";
            case "comparison" -> "Split view with labels inside each side; compare confirmed benefits only. " + HUMAN_FRAMING_RULE;
            case "packaging" -> "Flatlay or unboxing layout with small tags beside components.";
            case "trust" -> "Premium product/factory/detail atmosphere; no fake seals or official badges.";
            case "social_cover" -> "Mobile short-video cover with one strong hook.";
            case "promo" -> "Campaign visual; price/promo only when provided by the user.";
            default -> "Use a slot-specific composition and avoid repeated side panels.";
        };
    }

    public String renderPromptTemplate(CommerceSuiteDtos.TemplatePreset template, CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ImageSlot slot, CommerceSuiteDtos.ProductInfo info) {
        Map<String, String> values = values(platform, slot, info);
        String prompt = template.promptTemplate();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            prompt = prompt.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        prompt = prompt.replace("{{slot_strategy}}", slotStrategy(platform.code(), slot.code()));
        prompt = appendRuntimeQualityGuidance(prompt, platform, slot);
        if (prompt.contains("Final visible text rendering rule:")) {
            return prompt;
        }
        return prompt + "\n\n" + visibleTextRenderingRule();
    }

    public String runtimeQualityGuidance(CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ImageSlot slot) {
        String platformCode = platform == null ? "" : platform.code();
        String slotCode = slot == null ? "" : slot.code();
        return """
            Runtime platform suite quality guidance:
            - Platform visual system: %s
            - Slot-specific shot direction: %s
            - Text and compliance policy: %s
            - Product fidelity rule: preserve all printed artwork, logos, embroidery, labels, graphics, patterns, and visible product text from the reference exactly. For apparel or graphic products, keep the same single front/back artwork count and placement; do not duplicate motifs, scatter prints, invent sleeve/hem graphics, recolor artwork, or move the design.
            - Coherence rule: this image is one asset in a multi-image ecommerce set. Keep product scale logic, lighting temperature, shadow softness, lens realism, and background palette compatible with the rest of the set, while making this slot's composition meaningfully different.
            - Quality bar: realistic commercial photography or polished ecommerce layout, clear product identity, no generic stock-photo filler, no repeated blank text area, no fake platform chrome, no random AI gibberish.
            - Canvas safety rule: keep all visible copy and graphic labels inside the inner 8%% safe area. Auto-shrink or wrap long copy to at most two lines; never let glyphs, outlines, shadows, badges, or text backgrounds touch or cross an image edge.
            - Subject and copy safe-area rule: %s
            - Human framing rule: %s
            - Detail-slot person rule: %s
            - Copy-format rule: do not add ordered-list numbers or bullet prefixes to headlines, labels, or selling points. Meaningful confirmed numbers such as capacity, size, resolution, and model numbers remain unchanged.
            - Final edge check: verify the top, right, bottom, and left edges contain no clipped text, cropped head, partial face, clipped hands, or accidentally cut product silhouette. Keep the full hero product in frame unless this slot explicitly requests a macro/detail crop.
            """.formatted(
            platformSuiteDirection(platformCode),
            slotQualityDirection(platformCode, slotCode),
            platformTextPolicy(platformCode, slotCode),
            CANVAS_FRAMING_RULE,
            HUMAN_FRAMING_RULE,
            DETAIL_HUMAN_FRAMING_RULE
        ).trim();
    }

    private String appendRuntimeQualityGuidance(String prompt, CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ImageSlot slot) {
        if (prompt.contains("Runtime platform suite quality guidance:")) {
            return prompt;
        }
        return prompt + "\n\n" + runtimeQualityGuidance(platform, slot);
    }

    private String platformSuiteDirection(String platformCode) {
        return switch (platformCode) {
            case "taobao_tmall" -> "Taobao/Tmall mobile PDP set: premium Chinese marketplace photography, warm neutral or brand-color backgrounds, clear hero product, selective Chinese callouts for real benefits, detail/spec/trust modules that look designed but not templated.";
            case "pinduoduo" -> "Pinduoduo shelf set: high thumbnail clarity, brighter contrast, direct value communication, product large and readable, but no fake low-price stickers, no copied red/yellow shouting layout on every slot.";
            case "douyin" -> "Douyin ecommerce set: content-commerce stills with creator energy, realistic hands/model/lifestyle motion, short-video cover only where the slot calls for it, product remains shoppable and recognizable.";
            case "xiaohongshu" -> "Xiaohongshu seeding set: editorial lifestyle, soft daylight, authentic desk/home/outdoor scenes, tasteful composition, minimal note-like copy, no hard-sales poster blocks.";
            case "tiktok_shop" -> "TikTok Shop PDP gallery set: compliant marketplace gallery photography for overseas buyers: clean white/light main image, natural model/hand-use image, realistic lifestyle scene, macro detail images, SKU/static variant display. No live cover, no ad poster, no big text panels.";
            case "jd" -> "JD quality set: rational purchase decision visuals, crisp white/light studio base, tech/spec clarity, trustworthy detail and service atmosphere, blue/neutral restraint.";
            case "alibaba_1688" -> "1688 supplier set: B2B procurement visuals with factory/material/spec/packaging credibility, batch and structure clarity, less lifestyle gloss, more practical proof.";
            case "vipshop" -> "Vipshop brand-discount set: fashion/beauty retail polish, model styling, material texture, elegant sale mood only when promo is confirmed, premium rather than loud.";
            default -> "Platform-specific ecommerce set with realistic product identity, mobile readability, and varied slot compositions.";
        };
    }

    private String slotQualityDirection(String platformCode, String slotCode) {
        if ("tiktok_shop".equals(platformCode)) {
            return switch (slotCode) {
                case "main_image" -> "Front or three-quarter product hero, 80-90% of frame, pure white/light background, natural shadow, no added text or props.";
                case "model_image" -> "Realistic model/hand-use photo showing scale and fit; product print/shape/details must remain accurate; no text overlay.";
                case "scenario" -> "Natural everyday use scene for the selected target country; product is still the hero, not a tiny prop; no ad copy; keep reference artwork and markings exact.";
                case "detail" -> "Clean close detail of material, stitching, texture, opening, accessory, or construction, with enough context to know the product.";
                case "detail_closeup" -> "Macro crop with sharp material texture and accurate color; avoid abstract close-ups that no longer identify the product.";
                case "detail_function" -> "Visual demonstration of one real feature/action from the product facts; show hands or product mechanics only if relevant.";
                case "sku_display" -> "Static lineup or grid of variants/angles. If no SKU data exists, show front/back/side/detail angles of the same product, with identical artwork/markings preserved across every angle.";
                default -> "TikTok Shop gallery photo, clean and shoppable.";
            };
        }
        if ("taobao_tmall".equals(platformCode) || "jd".equals(platformCode)) {
            return switch (slotCode) {
                case "main_image" -> "Strong product hero with restrained title/benefit only if confirmed; product shape is more important than decorative text.";
                case "selling_point" -> "Two or three benefit callouts anchored to visible features; vary callout positions and visual hierarchy.";
                case "specification" -> "Compact spec/measurement layout using only supplied specs; if specs are missing, turn it into a visual material/structure detail instead.";
                default -> slotStrategy(slotCode);
            };
        }
        if ("pinduoduo".equals(platformCode)) {
            return switch (slotCode) {
                case "main_image" -> "Large product, simple high-contrast background, one clear benefit or confirmed promo only; do not invent price.";
                case "promo" -> "Campaign atmosphere only when promo text exists; no fake coupons, fake countdown, or fake platform UI.";
                default -> slotStrategy(slotCode);
            };
        }
        return slotStrategy(platformCode, slotCode);
    }

    private String platformTextPolicy(String platformCode, String slotCode) {
        if ("tiktok_shop".equals(platformCode)) {
            return "For TikTok Shop product gallery images, avoid added promotional text, prices, discount badges, shop UI, livestream graphics, and empty text panels. Use the requested target-country language only for confirmed tiny factual labels when the slot explicitly allows them; otherwise keep the image text-free except real product/package text from the reference.";
        }
        if ("xiaohongshu".equals(platformCode)) {
            return "Visible text should feel like natural note/sticker copy: one short Chinese hook or tiny labels only when confirmed. No prices, coupons, fake rankings, fake platform UI, or dense spec boards.";
        }
        if ("douyin".equals(platformCode)) {
            return "Only social_cover may use a short hook. Other Douyin suite images should rely on scene, model, hands, and product detail; avoid live-room UI, fake countdowns, and repeated title boards.";
        }
        if ("pinduoduo".equals(platformCode)) {
            return "Use bold but sparse Chinese text only for confirmed value points or promo fields. Price/discount appears only when the user provided it for this slot; do not create every image as a red/yellow price poster.";
        }
        if ("alibaba_1688".equals(platformCode)) {
            return "Use concise supplier-style Chinese labels for real specs/materials/package items. Do not invent MOQ, certificates, factory scale, price tiers, or delivery promises.";
        }
        return "Render only confirmed slot-appropriate copy in the requested language. Use no text for photo-first slots, small labels for detail/spec slots, and avoid repeated large text boards across the set.";
    }

    private String visibleTextRenderingRule() {
        return """
            Final visible text rendering rule:
            - If any earlier template line asks for blank text space, editable text space, or later text compositing, ignore that line.
            - The AI image model must render only non-empty confirmed title, subtitle, selling points, badge, and confirmed price/promo text directly inside the final image.
            - Only render price, discount, campaign, or badge text when the visible text plan below contains that field as non-empty for this exact image slot.
            - Do not create the same side-panel layout for every image in a suite. Use the slot's layout/composition_instruction fields.
            - Do not render internal slot labels such as 核心卖点、产品细节、规格参数图、对比图、场景图、商品主图, unless the user typed those exact words as product copy.
            - Use crisp readable typography integrated with the ecommerce layout. Do not render JSON syntax, random filler text, fake platform UI, fake prices, fake claims, QR codes, or watermarks.
            - Keep every text block and decorative label inside an inner 8% safe area on all four sides. Every glyph, outline, shadow, underline, badge, and background shape must remain fully visible; auto-shrink or wrap to at most two lines instead of touching or crossing the canvas edge.
            - Never add ordered-list numbers, bullets, or labels such as "1.", "2、", or "(3)" before visible copy. Preserve meaningful numbers only when they are part of a confirmed product fact such as 1.5L or 2K.
            - Before final output, inspect all four edges: no headline or label may be clipped, and the complete hero product must remain visible except in an intentional detail/macro slot.
            """.trim();
    }

    public Map<String, Object> renderOverlayTemplate(CommerceSuiteDtos.TemplatePreset template, CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ImageSlot slot, CommerceSuiteDtos.ProductInfo info, Map<String, Object> overlayOptions) {
        Map<String, Object> overlay = deepCopy(template.overlayTemplate());
        String text = toJson(overlay);
        for (Map.Entry<String, String> entry : values(platform, slot, info).entrySet()) {
            text = text.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        try {
            overlay = json.readValue(text, new TypeReference<>() {});
        } catch (Exception ignored) {
            overlay = new LinkedHashMap<>(template.overlayTemplate());
        }
        if (overlayOptions != null && !overlayOptions.isEmpty()) {
            overlay.put("user_options", overlayOptions);
            Object textLayer = overlay.get("text_layer");
            if (textLayer instanceof Map<?, ?> map) {
                Map<String, Object> merged = new LinkedHashMap<>();
                map.forEach((key, value) -> merged.put(String.valueOf(key), value));
                overlayOptions.forEach((key, value) -> {
                    if (value != null && !String.valueOf(value).isBlank()) {
                        merged.put(key, value);
                    }
                });
                overlay.put("text_layer", merged);
            }
        }
        overlay.put("platform", platform.name());
        overlay.put("platform_code", platform.code());
        overlay.put("target_market", blankToDefault(info == null ? "" : info.country(), platform.code()));
        overlay.put("visible_text_language", blankToDefault(info == null ? "" : info.language(), platform.language()));
        overlay.put("slot", slot.code());
        overlay.put("usage", slot.name());
        overlay.put("enabled", !"white_background".equals(slot.code()));
        overlay.put("rendered_by", "ai_image_model");
        overlay = slotSpecificVisibleTextPlan(overlay, platform, slot, info);
        return overlay;
    }

    private Map<String, Object> slotSpecificVisibleTextPlan(Map<String, Object> plan, CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ImageSlot slot, CommerceSuiteDtos.ProductInfo info) {
        Map<String, Object> shaped = new LinkedHashMap<>();
        if (plan != null) {
            shaped.putAll(plan);
        }
        Map<String, Object> layer = mapValue(shaped.get("text_layer"));
        List<String> points = cleanList(info == null ? null : info.sellingPoints());
        String slotCode = slot.code();
        String product = blankToDefault(info == null ? "" : info.productName(), "");
        String brand = blankToDefault(info == null ? "" : info.brand(), "");
        String material = blankToDefault(info == null ? "" : info.material(), "");
        String specs = blankToDefault(info == null ? "" : info.specs(), "");
        String audience = blankToDefault(info == null ? "" : info.audience(), "");
        String scenario = blankToDefault(info == null ? "" : info.scenario(), "");
        String promo = blankToDefault(info == null ? "" : info.promoText(), "");
        boolean tiktokShop = "tiktok_shop".equals(platform.code());

        String title = titleForSlot(slotCode, product, points, material, specs, audience, scenario, promo);
        String subtitle = subtitleForSlot(slotCode, points, material, specs, audience, scenario);
        if (sameCopy(title, subtitle)) {
            subtitle = "";
        }
        String finalSubtitle = subtitle;
        List<String> slotPoints = pointsForSlot(slotCode, points, material, specs, audience, scenario).stream()
            .filter(value -> !sameCopy(value, title) && !sameCopy(value, finalSubtitle))
            .toList();
        layer.put("title", title);
        layer.put("subtitle", subtitle);
        layer.put("selling_points", slotPoints);
        layer.put("price_text", !tiktokShop && priceSlot(slotCode) ? blankToDefault(info == null ? "" : info.priceText(), "") : "");
        layer.put("badge_text", tiktokShop ? "" : badgeForSlot(slotCode, platform.code(), promo));
        layer.put("footer_text", tiktokShop ? "" : footerForSlot(slotCode, brand));
        layer.put("layout", overlayLayout(slotCode, platform.code()));
        layer.put("position", overlayPosition(slotCode, platform.code()));
        layer.put("background_style", overlayBackground(platform.code()));
        layer.put("copy_density", copyDensity(slotCode));
        layer.put("text_strategy", textStrategyForSlot(slotCode));
        layer.put("composition_instruction", compositionInstruction(slotCode));
        layer.put("safe_margin_percent", 8);
        layer.put("max_title_lines", 2);
        layer.put("max_label_lines", 2);
        layer.put("edge_policy", "Keep every glyph, outline, shadow, label, badge, and graphic fully inside the canvas safe area; auto-shrink or wrap instead of clipping.");
        layer.put("subject_safe_margin_percent", 6);
        layer.put("canvas_framing_policy", CANVAS_FRAMING_RULE);
        layer.put("human_framing_policy", HUMAN_FRAMING_RULE + " " + DETAIL_HUMAN_FRAMING_RULE);
        layer.put("list_marker_policy", "Do not add ordered-list numbers or bullet prefixes to visible copy.");
        layer.put("forbidden_visible_text", forbiddenVisibleText(slot.name()));
        if (tiktokShop) {
            layer = applyTikTokGalleryTextRules(layer, slotCode, material, specs, points);
        }
        shaped.put("text_layer", layer);
        return shaped;
    }

    private Map<String, Object> applyTikTokGalleryTextRules(Map<String, Object> layer, String slotCode, String material, String specs, List<String> points) {
        Map<String, Object> shaped = new LinkedHashMap<>(layer);
        shaped.put("price_text", "");
        shaped.put("badge_text", "");
        shaped.put("footer_text", "");
        if (tiktokNoTextSlot(slotCode)) {
            shaped.put("title", "");
            shaped.put("subtitle", "");
            shaped.put("selling_points", List.of());
            shaped.put("copy_density", "none");
            shaped.put("text_strategy", "TikTok Shop gallery asset, no visible text");
            shaped.put("composition_instruction", compositionInstruction(slotCode) + " Render this as product photography with no in-image text.");
            return shaped;
        }
        if (tiktokTinyLabelSlot(slotCode)) {
            shaped.put("title", "");
            shaped.put("subtitle", "");
            List<String> labels = new ArrayList<>();
            addAll(labels, material, specs, first(points, 0));
            shaped.put("selling_points", labels.stream().filter(item -> item != null && !item.isBlank()).distinct().limit(2).toList());
            shaped.put("copy_density", "tiny_labels_only");
            shaped.put("text_strategy", "prefer no added text; at most one or two tiny physical-detail labels if confirmed, no headline, no price");
            shaped.put("composition_instruction", compositionInstruction(slotCode) + " Prefer a clean text-free product photo. If text appears, it must be tiny factual labels attached to physical product details, not a panel, headline, badge, or price.");
        }
        return shaped;
    }

    private boolean tiktokNoTextSlot(String slotCode) {
        return "main_image".equals(slotCode)
            || "model_image".equals(slotCode)
            || "scenario".equals(slotCode)
            || "sku_display".equals(slotCode);
    }

    private boolean tiktokTinyLabelSlot(String slotCode) {
        return "detail".equals(slotCode)
            || "detail_closeup".equals(slotCode)
            || "detail_function".equals(slotCode);
    }

    private Map<String, Object> mapValue(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private String titleForSlot(String slotCode, String product, List<String> points, String material, String specs, String audience, String scenario, String promo) {
        return switch (slotCode) {
            case "main_image" -> product;
            case "promo" -> firstNonBlank(promo, product);
            case "selling_point" -> firstNonBlank(first(points, 0), product);
            case "detail" -> firstNonBlank(material, specs);
            case "model_image" -> firstNonBlank(audience, scenario);
            case "detail_closeup" -> firstNonBlank(material, specs);
            case "detail_function" -> firstNonBlank(first(points, 0), specs, material);
            case "sku_display" -> firstNonBlank(specs, material, product);
            case "specification" -> firstNonBlank(specs, material);
            case "packaging" -> firstNonBlank(specs, material);
            case "scenario" -> firstNonBlank(scenario, audience);
            case "crowd" -> firstNonBlank(audience, scenario);
            case "comparison" -> firstNonBlank(first(points, 0), first(points, 1));
            case "trust" -> firstNonBlank(material, first(points, 0), product);
            case "social_cover" -> firstNonBlank(first(points, 0), scenario, product);
            case "white_background" -> "";
            default -> firstNonBlank(first(points, 0), product);
        };
    }

    private String subtitleForSlot(String slotCode, List<String> points, String material, String specs, String audience, String scenario) {
        return switch (slotCode) {
            case "main_image" -> firstNonBlank(first(points, 0), material, specs);
            case "selling_point" -> firstNonBlank(first(points, 1), material, specs);
            case "detail" -> firstNonBlank(specs);
            case "model_image" -> firstNonBlank(scenario);
            case "detail_closeup" -> firstNonBlank(specs);
            case "detail_function" -> firstNonBlank(first(points, 1), scenario);
            case "sku_display" -> "";
            case "specification" -> "";
            case "packaging" -> firstNonBlank(material);
            case "scenario" -> firstNonBlank(audience);
            case "crowd" -> firstNonBlank(scenario);
            case "comparison" -> firstNonBlank(first(points, 1), first(points, 2));
            case "trust" -> firstNonBlank(specs, first(points, 1));
            case "social_cover" -> firstNonBlank(scenario, audience);
            case "promo" -> firstNonBlank(first(points, 0), scenario);
            default -> firstNonBlank(first(points, 1), "");
        };
    }

    private List<String> pointsForSlot(String slotCode, List<String> points, String material, String specs, String audience, String scenario) {
        List<String> result = new ArrayList<>();
        switch (slotCode) {
            case "detail" -> addAll(result, material, specs);
            case "model_image" -> addAll(result, audience, scenario);
            case "detail_closeup" -> addAll(result, material, specs);
            case "detail_function" -> addAll(result, first(points, 0), specs);
            case "sku_display" -> addAll(result, specs, material);
            case "specification" -> addAll(result, specs, material);
            case "packaging" -> addAll(result, specs, material);
            case "scenario" -> addAll(result, scenario, audience);
            case "crowd" -> addAll(result, audience, scenario);
            case "comparison" -> addAll(result, first(points, 0), first(points, 1));
            case "trust" -> addAll(result, material, specs);
            case "main_image", "social_cover", "promo" -> addAll(result, first(points, 0), first(points, 1));
            default -> addAll(result, first(points, 0), first(points, 1), first(points, 2));
        }
        return result.stream().filter(item -> item != null && !item.isBlank()).distinct().limit(3).toList();
    }

    private void addAll(List<String> target, String... values) {
        for (String value : values) {
            String text = blankToDefault(value, "");
            if (!text.isBlank()) {
                target.add(text);
            }
        }
    }

    private String first(List<String> values, int index) {
        return values != null && values.size() > index ? values.get(index) : "";
    }

    private boolean priceSlot(String slotCode) {
        return "main_image".equals(slotCode) || "promo".equals(slotCode);
    }

    private String badgeForSlot(String slotCode, String platformCode, String promo) {
        if (!badgeSlot(slotCode) || blankToDefault(promo, "").isBlank()) {
            return "";
        }
        return promo;
    }

    private boolean badgeSlot(String slotCode) {
        return "main_image".equals(slotCode) || "promo".equals(slotCode) || "social_cover".equals(slotCode);
    }

    private String footerForSlot(String slotCode, String brand) {
        return ("main_image".equals(slotCode) || "trust".equals(slotCode)) ? brand : "";
    }

    private String textStrategyForSlot(String slotCode) {
        return switch (slotCode) {
            case "main_image" -> "title + one or two benefits + optional confirmed price";
            case "promo" -> "campaign text + optional confirmed price";
            case "selling_point" -> "benefit callouts only, no price";
            case "detail" -> "material/detail labels only, no price";
            case "model_image" -> "model/use photo with no price and no panel";
            case "detail_closeup" -> "macro detail labels only, no price";
            case "detail_function" -> "one function label only, no price";
            case "sku_display" -> "static SKU/variant labels only, no price";
            case "specification" -> "confirmed specs only, no price";
            case "packaging" -> "package/component labels only, no price";
            case "scenario", "crowd" -> "scene/audience caption only, no price";
            case "comparison" -> "comparison points only, no price";
            case "trust" -> "brand/trust cue only, no price";
            case "social_cover" -> "short mobile hook only, no price";
            case "white_background" -> "no visible text, no price";
            default -> "slot-specific concise ecommerce text, no price";
        };
    }

    private List<String> forbiddenVisibleText(String slotName) {
        List<String> values = new ArrayList<>();
        addAll(values,
            slotName,
            "商品主图",
            "核心卖点",
            "核心卖点图",
            "产品细节",
            "产品细节图",
            "模特图",
            "细节特写图",
            "功能细节图",
            "SKU 静态展示图",
            "SKU静态展示图",
            "规格参数图",
            "对比图",
            "场景图",
            "使用场景图",
            "信任背书图",
            "品质信任",
            "包装清单图",
            "社媒封面图"
        );
        return values.stream().distinct().toList();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String text = blankToDefault(value, "");
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private Map<String, String> values(CommerceSuiteDtos.PlatformPreset platform, CommerceSuiteDtos.ImageSlot slot, CommerceSuiteDtos.ProductInfo info) {
        List<String> sellingPoints = cleanList(info == null ? null : info.sellingPoints());
        return Map.ofEntries(
            Map.entry("platform_name", platform.name()),
            Map.entry("platform_code", platform.code()),
            Map.entry("platform_style", platform.stylePrompt()),
            Map.entry("slot_name", slot.name()),
            Map.entry("slot_description", slot.description()),
            Map.entry("product_name", blankToDefault(info == null ? "" : info.productName(), "未命名商品")),
            Map.entry("category", blankToDefault(info == null ? "" : info.category(), "")),
            Map.entry("brand", blankToDefault(info == null ? "" : info.brand(), "")),
            Map.entry("selling_points", sellingPoints.isEmpty() ? "" : String.join(" / ", sellingPoints)),
            Map.entry("selling_point_1", sellingPoints.size() > 0 ? sellingPoints.get(0) : ""),
            Map.entry("selling_point_2", sellingPoints.size() > 1 ? sellingPoints.get(1) : ""),
            Map.entry("selling_point_3", sellingPoints.size() > 2 ? sellingPoints.get(2) : ""),
            Map.entry("material", blankToDefault(info == null ? "" : info.material(), "")),
            Map.entry("specs", blankToDefault(info == null ? "" : info.specs(), "")),
            Map.entry("audience", blankToDefault(info == null ? "" : info.audience(), "")),
            Map.entry("scenario", blankToDefault(info == null ? "" : info.scenario(), "")),
            Map.entry("price_text", blankToDefault(info == null ? "" : info.priceText(), "")),
            Map.entry("promo_text", blankToDefault(info == null ? "" : info.promoText(), "")),
            Map.entry("country", blankToDefault(info == null ? "" : info.country(), platform.code())),
            Map.entry("language", blankToDefault(info == null ? "" : info.language(), platform.language())),
            Map.entry("platform_suite_direction", platformSuiteDirection(platform.code())),
            Map.entry("slot_quality_direction", slotQualityDirection(platform.code(), slot.code())),
            Map.entry("platform_text_policy", platformTextPolicy(platform.code(), slot.code()))
        );
    }

    private List<String> cleanList(List<String> values) {
        return CommerceTextNormalizer.sellingPoints(values);
    }

    private boolean sameCopy(String left, String right) {
        String leftText = blankToDefault(left, "");
        String rightText = blankToDefault(right, "");
        return !leftText.isBlank() && leftText.equalsIgnoreCase(rightText);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(json.writeValueAsString(value), new TypeReference<>() {});
        } catch (Exception ignored) {
            return new LinkedHashMap<>(value);
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
