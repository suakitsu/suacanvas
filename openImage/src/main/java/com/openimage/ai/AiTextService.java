package com.openimage.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.ai.worker.AiWorkerDeferredException;
import com.openimage.ai.worker.AiWorkerJobService;
import com.openimage.ai.worker.CanvasWorkerInputService;
import com.openimage.domain.entity.PromptTemplateEntity;
import com.openimage.domain.entity.ProductProjectEntity;
import com.openimage.domain.mapper.PromptTemplateMapper;
import com.openimage.risk.RiskControlService;
import com.openimage.video.VideoPromptPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiTextService {
    private static final long TEXT_PROVIDER_AUTH_CIRCUIT_MS = Duration.ofMinutes(30).toMillis();
    private static final long TEXT_PROVIDER_TRANSIENT_CIRCUIT_MS = Duration.ofMinutes(2).toMillis();
    private static final int TEXT_PROVIDER_CHAIN_TIMEOUT_SECONDS = 25;
    private static final int CANVAS_VISION_PROVIDER_CHAIN_TIMEOUT_SECONDS = 120;
    private static final Pattern GENERATED_COPY_TITLE_COLOR_SUFFIX = Pattern.compile("(?i),\\s*(white|gray|grey|navy|black|red|blue|green|pink|purple|yellow|orange|brown|beige|cream|silver|gold|khaki|ivory|rose)\\s*$");
    private static final Pattern GENERATED_COPY_COLOR_FIELD = Pattern.compile("(?i)(\\bColor\\s*:\\s*)[^\\n.;]+");

    private final ObjectMapper json;
    private final HttpClient http;
    private final PromptTemplateMapper promptTemplates;
    private final AiRuntimeConfigService runtimeConfig;
    private final AiTextCallAuditService textAudit;
    private final RiskControlService riskControl;
    private final AiWorkerJobService workerJobs;
    private final CanvasWorkerInputService canvasWorkerInputs;
    private final Map<String, Long> textProviderCircuitOpenUntil = new ConcurrentHashMap<>();

    public AiTextService(
        ObjectMapper json,
        PromptTemplateMapper promptTemplates,
        AiRuntimeConfigService runtimeConfig,
        AiTextCallAuditService textAudit
    ) {
        this(json, promptTemplates, runtimeConfig, textAudit, null, null);
    }

    public AiTextService(
        ObjectMapper json,
        PromptTemplateMapper promptTemplates,
        AiRuntimeConfigService runtimeConfig,
        AiTextCallAuditService textAudit,
        RiskControlService riskControl
    ) {
        this(json, promptTemplates, runtimeConfig, textAudit, riskControl, null);
    }

    public AiTextService(
        ObjectMapper json,
        PromptTemplateMapper promptTemplates,
        AiRuntimeConfigService runtimeConfig,
        AiTextCallAuditService textAudit,
        RiskControlService riskControl,
        AiWorkerJobService workerJobs
    ) {
        this(json, promptTemplates, runtimeConfig, textAudit, riskControl, workerJobs, null);
    }

    @Autowired
    public AiTextService(
        ObjectMapper json,
        PromptTemplateMapper promptTemplates,
        AiRuntimeConfigService runtimeConfig,
        AiTextCallAuditService textAudit,
        RiskControlService riskControl,
        AiWorkerJobService workerJobs,
        CanvasWorkerInputService canvasWorkerInputs
    ) {
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        this.promptTemplates = promptTemplates;
        this.runtimeConfig = runtimeConfig;
        this.textAudit = textAudit;
        this.riskControl = riskControl;
        this.workerJobs = workerJobs;
        this.canvasWorkerInputs = canvasWorkerInputs;
    }

    public Map<String, Object> generateListingCopy(ProductProjectEntity project, Map<String, Object> originalInfo, List<Map<String, Object>> images, List<ImageBriefSeed> seeds) {
        String productName = project.getProductName();
        String brand = blankToDefault(project.getBrand(), "PicPilot");
        String description = cleanLegacyPromptPollution(String.valueOf(originalInfo.getOrDefault("description", "")));
        String category = String.valueOf(originalInfo.getOrDefault("category", ""));
        String material = String.valueOf(originalInfo.getOrDefault("material", ""));
        String dimensions = String.valueOf(originalInfo.getOrDefault("dimensions", ""));
        Object keywords = originalInfo.getOrDefault("keywords", List.of());
        String copyTemplate = templateContent("copy/listing", templateContent("copy/title", ""));
        String briefTemplate = templateContent("image/brief", "");

        Map<String, Object> facts = rebuildConfirmedFacts(project, originalInfo, images);
        List<String> warnings = complianceWarnings(description, dimensions, facts);
        if (runtimeConfig.textProviderChain().isEmpty()) {
            throw new IllegalArgumentException("AI 文案服务已被管理员禁用或未配置密钥，请联系管理员开启后再使用");
        }

        Map<String, Object> aiCopy = Map.of();
        String aiProvider = "structured-fallback";
        String aiMode = "local-safe-copy";
        try {
            aiCopy = generateListingCopyWithDeepSeek(project, facts, copyTemplate);
            aiProvider = runtimeConfig.effectiveText().provider();
            aiMode = "listing-copy-two-pass";
        } catch (Exception exception) {
            warnings.add("AI 文案生成暂时不可用，已使用本地安全文案兜底：" + summarize(exception.getMessage(), 120));
        }

        Map<String, Object> compliance = new LinkedHashMap<>();
        compliance.put("passed", warnings.isEmpty());
        compliance.put("warnings", warnings);
        compliance.put("blocked_claims", List.of("No.1", "best", "guaranteed", "FDA/CE/UL 未提供证明时禁用"));

        Map<String, Object> copy = new LinkedHashMap<>();
        boolean hasMainImage = Boolean.TRUE.equals(facts.get("main_image_uploaded"));
        copy.put("title", listingTitleValue(aiCopy.get("title"), brand + " " + productName + " Product Listing", hasMainImage));
        copy.put("bullets", listValue(aiCopy.get("selling_point"), fallbackBullets(project, facts)));
        copy.put("description", listingDescriptionValue(aiCopy.get("product_description"), description, hasMainImage));
        copy.put("confirmed_facts_candidate", facts);
        copy.put("compliance", compliance);
        copy.put("admin_prompt_template", copyTemplate);
        copy.put("visual_prompt", textValue(originalInfo.get("visual_prompt"), ""));
        copy.put("image_briefs", seeds.stream().map(seed -> imageBrief(seed, productName, project.getLanguage(), briefTemplate)).toList());
        copy.put("ai_provider", aiProvider);
        copy.put("ai_mode", aiMode);
        return copy;
    }

    public Map<String, Object> rebuildConfirmedFacts(ProductProjectEntity project, Map<String, Object> originalInfo, List<Map<String, Object>> images) {
        Map<String, Object> fresh = confirmedFacts(project, originalInfo, images);
        Map<String, Object> existing = readJsonMap(project == null ? null : project.getConfirmedFacts());
        Map<String, Object> facts = new LinkedHashMap<>(fresh);
        if (!existing.isEmpty()) {
            facts.putAll(existing);
        }
        facts.put("main_image_uploaded", fresh.get("main_image_uploaded"));
        boolean hasMainImage = Boolean.TRUE.equals(fresh.get("main_image_uploaded"));
        facts.put("color_hints", hasMainImage ? List.of() : colorHints(existing, originalInfo, fresh));
        facts.put("color_policy", colorPolicy(Boolean.TRUE.equals(fresh.get("main_image_uploaded")), listValue(facts.get("color_hints"), List.of())));
        return facts;
    }

    private Map<String, Object> confirmedFacts(ProductProjectEntity project, Map<String, Object> originalInfo, List<Map<String, Object>> images) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("brand", blankToDefault(project.getBrand(), "PicPilot"));
        facts.put("product_name", project.getProductName());
        facts.put("target_country", project.getCountry());
        facts.put("language", project.getLanguage());
        facts.put("description", cleanLegacyPromptPollution(String.valueOf(originalInfo.getOrDefault("description", ""))));
        facts.put("category", String.valueOf(originalInfo.getOrDefault("category", "")));
        facts.put("material", String.valueOf(originalInfo.getOrDefault("material", "")));
        facts.put("dimensions", String.valueOf(originalInfo.getOrDefault("dimensions", "")));
        facts.put("keywords", originalInfo.getOrDefault("keywords", List.of()));
        facts.put("title_keywords", originalInfo.getOrDefault("title_keywords", originalInfo.getOrDefault("keywords", List.of())));
        facts.put("selling_point_keywords", originalInfo.getOrDefault("selling_point_keywords", List.of()));
        facts.put("description_keywords", originalInfo.getOrDefault("description_keywords", List.of()));
        boolean hasMainImage = images.stream().anyMatch(image -> "main".equals(normalizeImageType(String.valueOf(image.get("imageType")))));
        facts.put("main_image_uploaded", hasMainImage);
        facts.put("color_hints", hasMainImage ? List.of() : colorHints(originalInfo));
        facts.put("color_policy", colorPolicy(Boolean.TRUE.equals(facts.get("main_image_uploaded")), listValue(facts.get("color_hints"), List.of())));
        return facts;
    }

    private List<String> complianceWarnings(String description, String dimensions, Map<String, Object> facts) {
        List<String> warnings = new ArrayList<>();
        String lower = description == null ? "" : description.toLowerCase();
        String source = description == null ? "" : description;
        if (lower.contains("best") || lower.contains("no.1") || source.contains("第一") || source.contains("最")) {
            warnings.add("检测到可能夸大或绝对化表达，正式文案需改为可验证表述。");
        }
        if (!Boolean.TRUE.equals(facts.get("main_image_uploaded"))) {
            warnings.add("缺少主商品图，不能进入可靠的白底主图和商品一致性生成。");
        }
        if ((dimensions == null || dimensions.isBlank()) && !lower.contains("size") && !source.contains("尺寸")) {
            warnings.add("尺寸信息不明确，尺寸图只能生成占位说明，不能编造具体尺寸。");
        }
        return warnings;
    }

    private Map<String, Object> generateListingCopyWithDeepSeek(ProductProjectEntity project, Map<String, Object> facts, String adminTemplate) throws Exception {
        String country = blankToDefault(project.getCountry(), "US");
        String language = blankToDefault(project.getLanguage(), "en-US");
        String productInfo = productInfoText(facts);

        String firstSystem = """
            # 角色
            你是一名经验丰富的亚马逊【%s】站运营专家，擅长根据产品特性和市场需求撰写高质量的产品Listing，以吸引潜在买家并提高转化率。
            你需要根据提供的我的品牌名、产品名称、关键词（标题关键词、卖点关键词、描述关键词）、其他信息（材质、产品尺寸、适用机型型号等），生成符合亚马逊平台要求的产品文案。
            同时，你需要学习下方【知识库/后台模板】内容，以提升文案编写能力。根据【%s】和产品名称分析产品受众，并根据受众编写符合当地语言习惯的文案，输出结果可直接发布到亚马逊平台。

            ## 知识库/后台模板
            %s

            ## 技能
            ### 技能1：标题撰写
            - 标题结构：品牌 + 核心功能 + 关键词1 + 关键词2 + 产品类型 + 关键词3 + 适用于受众 + 产品型号（颜色/尺寸等）+ 其他。
            - 标题结构补充：标题描写时，合理使用 "," 进行分割，增加可读性，但是需注意单个关键词不可分隔。
            - 字符限制：确保标题必须在180~200字符，且每个单词首字母大写（介词、连词、冠词除外）。
            - 关键词嵌入：根据提供的“标题关键词”对标题的描写进行合理埋词。
            - 标题限制：形如极致、高级、超级这类的词，不可出现在标题中。

            ### 技能2：卖点撰写
            - 卖点数量：为每篇文案输出5个卖点，结合卖点关键词和产品名称进行关键词埋点。
            - 格式规范：每条卖点以总结词首字母大写输出，使用 ":" 隔开后，再针对这一个总结词描述这一卖点。
            - 字数控制：每条卖点描述字数控制在300个字符之间，每条卖点长度不超500字符。
            - 卖点要求：必须创建完整的5条，并规避Amazon违规词。

            ### 技能3：详细描述编写
            - 描述部分：包括 "Feature"、"Specification"、"Material"、"Capacity"、"Color"、"Package Included"。书写 “Feature” 时如输入了“描述关键词”需插入关键词。
            - 字数控制：产品详细描述部分的输出长度严格控制在1000~2500字符以内。
            - 描述限制："Package Included" 中杜绝衍生出未提到的产品内容。
            - 参数限制：输出的关于具体参数数据，只能体现输入的数值，不可凭空相信，不可随意推测出其他具体参数。

            ### 技能4：亚马逊A9算法与COSMO知识图谱整合
            - A9算法对齐：基于关键词数据，构建一个能最大化算法相关性的标题和描述。
            - COSMO知识图谱：利用COSMO知识图谱来优化产品描述，确保内容的相关性和准确性。
            - COSMO关系类型参考如下：
              USED_FOR_FUNC: 功能用途。
              USED_FOR_EVE: 场景/活动用途。
              USED_FOR_AUD: 目标人群。
              CAPABLE_OF: 能力。
              USED_TO: 工具性用途。
              USED_AS: 作为...使用。
              IS_A: 产品类别/身份。
              USED_ON: 时间/事件用途。
              USED_IN_LOC: 地点用途。
              USED_WITH: 协同使用。
              xIs_A: 购买者身份/人群。
              xWANT: 用户具体活动意图。

            ### 技能5：市场和受众分析
            - 市场调研：根据【%s】和产品名称，分析产品受众。
            - 本地化：根据受众编写符合当地语言习惯的文案，确保文案能够有效吸引目标市场。
            - 符合亚马逊平台：专注于亚马逊平台的产品Listing撰写，确保所有内容符合亚马逊的要求和标准。
            - 提高转化率：始终以用户体验和转化率为优先考虑。

            ## 限制
            - 语种要求：文案必须按照【%s】的本地语言习惯进行编写输出【%s】，不可输出其他语种信息，特别是中文，严禁输出。
            - 关键词要求：严格按照提供的标题关键词、卖点关键词、描述关键词，按上方编写顺序进行插入埋词。
            - 字符限制：严格遵守各个部分的字符限制。
            - 合规要求：禁止输出亚马逊【%s】的违规词，特别是：安全、保险、价值、杀虫剂、杀菌、无色无味等相关词语。
            - 虚假宣传：杜绝绝对化的主观宣传、促销与时效性用语，例如 sale。
            - 版权限制：杜绝输出涉及版权问题的文字。
            - 参数限制：不可凭空捏造，不可联想，禁止输出未提到的参数信息。
            - 品牌名书写规范：如果文案中涉及别人的品牌词，就需要在语句前面加上 "for" 或者 "compatible with"，并按对应语种输出。
            - 颜色限制：如果“主商品图是否已上传”为 yes，主图才是商品颜色依据；不要从场景、背景、关键词或用户补充信息推断商品颜色，不要在标题末尾自动追加颜色。若没有明确商品颜色字段，Color 输出 As shown 或留空。
            - 背景/场景颜色词只描述环境，不描述商品颜色。例如 greenery street background 不能推断为 Green product color。

            ## 禁止
            - 禁止输出中文，禁止输出中文，禁止输出中文。
            - 禁止在末尾加上国家和语种相关说明，例如 "UK English"。
            - 禁止输出非输入的具体参数，禁止输出非输入的具体参数，禁止输出非输入的具体参数。

            # Output Format
            只输出 JSON，不要 Markdown，不要解释，不要代码块：
            {
              "title": "",
              "selling_point": ["", "", "", "", ""],
              "product_description": {
                "Feature": [],
                "Specification": "",
                "Material": "",
                "Capacity": "",
                "Color": "",
                "Package Included": ""
              }
            }
            """.formatted(
            country,
            country,
            adminTemplate == null || adminTemplate.isBlank() ? "无额外模板，按系统默认策略执行。" : adminTemplate,
            country,
            country,
            language,
            country
        );

        List<Map<String, String>> firstMessages = List.of(
            Map.of("role", "system", "content", firstSystem),
            Map.of("role", "user", "content", "【产品信息】\n" + productInfo)
        );
        Map<String, Object> firstCopy = parseJsonObject(callDeepSeek(firstMessages, 0.35, 3200, true));

        String secondSystem = """
            # Role
            亚马逊 Listing 合规与优化专家

            # Profile
            你是一名经验丰富的亚马逊运营专家，擅长文案合规性审查、多语言校对及产品信息一致性核查。你能精准识别文案中的非目标语言字符及未经验证的产品参数，确保 Listing 内容准确、专业且符合平台规范。

            # Task
            接收用户提供的【产品信息】和【亚马逊 Listing 文案】，对比两者内容，对 Listing 文案进行审查和修改。

            # Constraints
            1. 语言一致性：最终输出的 Listing 文案必须完全为【%s】，严禁出现任何中文字符（包括中文标点符号）。
            2. 参数真实性：严禁添加【产品信息】中未提供的具体参数（如尺寸、重量、材质、型号等）。若文案中包含未提供的参数，必须删除或模糊化处理。
            3. 可读性保持：在剔除违规内容后，需调整语句结构，确保文案通顺、逻辑连贯，避免因删除内容导致语句破碎。
            4. 高度保持原文案：除上述合规性修改外，其他地方不可改动原文。
            5. 颜色真实性：如果【产品信息】显示主商品图已上传，不得从背景、场景或关键词推断商品颜色；不得保留未经显式确认的 Color: Green/White/Black 等猜测值，应改为 Color: As shown 或删除颜色字段。

            # Workflow
            1. 分析对比：仔细阅读【产品信息】与【亚马逊 Listing 文案】，标记出文案中的中文字符及未提供的具体参数。
            2. 执行修改：删除或替换所有中文字符；删除或修正所有未经验证的产品参数；润色受影响句子。
            3. 再次审查：再次检查修改后的文案是否符合所有约束条件。
            4. 中文禁止：使用 Unicode 范围检查最后输出是否存在中文；如果存在，必须继续优化直到没有中文。

            # Output Format
            只输出 JSON，不要 Markdown，不要解释，不要修改说明，结构必须为：
            {
              "title": "",
              "selling_point": ["", "", "", "", ""],
              "product_description": {
                "Feature": [],
                "Specification": "",
                "Material": "",
                "Capacity": "",
                "Color": "",
                "Package Included": ""
              }
            }
            """.formatted(language);

        List<Map<String, String>> secondMessages = List.of(
            Map.of("role", "system", "content", secondSystem),
            Map.of("role", "user", "content", "【产品信息】\n" + productInfo + "\n\n【亚马逊 Listing 文案】\n" + json.writeValueAsString(firstCopy))
        );
        Map<String, Object> reviewed = parseJsonObject(callDeepSeek(secondMessages, 0.2, 3200, true));
        return reviewed.isEmpty() ? firstCopy : reviewed;
    }

    private String productInfoText(Map<String, Object> facts) throws Exception {
        Object legacyKeywords = facts.getOrDefault("keywords", List.of());
        String titleKeywords = json.writeValueAsString(facts.getOrDefault("title_keywords", legacyKeywords));
        String sellingPointKeywords = json.writeValueAsString(facts.getOrDefault("selling_point_keywords", List.of()));
        String descriptionKeywords = json.writeValueAsString(facts.getOrDefault("description_keywords", List.of()));
        String colorHints = json.writeValueAsString(facts.getOrDefault("color_hints", List.of()));
        return """
            1、我的品牌名：%s
            2、产品名称：%s
            3、目标站点：Amazon %s
            4、输出语言：%s
            5、关键词：
            （1）标题关键词：%s
            （2）卖点关键词：%s
            （3）描述关键词：%s
            6、其他信息：
            （1）类目：%s
            （2）材质：%s
            （3）产品尺寸：%s
            （4）用户补充信息：%s
            （5）颜色线索：%s
            （6）主图颜色规则：%s
            （7）主商品图是否已上传：%s
            """.formatted(
            textValue(facts.get("brand"), ""),
            textValue(facts.get("product_name"), ""),
            textValue(facts.get("target_country"), "US"),
            textValue(facts.get("language"), "en-US"),
            titleKeywords,
            sellingPointKeywords,
            descriptionKeywords,
            textValue(facts.get("category"), ""),
            textValue(facts.get("material"), ""),
            textValue(facts.get("dimensions"), ""),
            textValue(facts.get("description"), ""),
            colorHints,
            textValue(facts.get("color_policy"), ""),
            Boolean.TRUE.equals(facts.get("main_image_uploaded")) ? "yes" : "no"
        );
    }

    private Map<String, Object> imageBrief(ImageBriefSeed seed, String productName, String language, String briefTemplate) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("image_type", seed.type());
        brief.put("image_name", listingImageSlotLabel(seed.type(), language));
        brief.put("goal", listingImageGoalForPrompt(seed.type(), language));
        brief.put("overlay_text", isChineseLanguage(language) && "main".equals(seed.type()) ? productName : suggestedOverlayForPrompt(seed.type(), language));
        brief.put("required_facts", seed.requiredFacts());
        brief.put("generation_size", seed.generationSize());
        brief.put("output_size", seed.outputSize());
        brief.put("credits_cost", seed.cost());
        brief.put("admin_prompt_template", briefTemplate);
        return brief;
    }

    private String imageGoal(String type) {
        return switch (type) {
            case "main" -> "白底主图";
            case "scenario" -> "场景图";
            case "features" -> "卖点图";
            case "dimensions" -> "尺寸图";
            case "steps" -> "步骤图";
            case "comparison" -> "对比图";
            case "package" -> "包装/变体图";
            case "aplus" -> "A+ 模块图";
            case "creative" -> "自由创作图";
            default -> "Listing 图片";
        };
    }

    private List<String> fallbackBullets(ProductProjectEntity project, Map<String, Object> facts) {
        String product = blankToDefault(project.getProductName(), "Product");
        String material = textValue(facts.get("material"), "");
        String dimensions = textValue(facts.get("dimensions"), "");
        List<String> bullets = new ArrayList<>();
        bullets.add("Product-Focused Visual Plan: Builds the listing around " + product + " using only the product facts provided by the user.");
        bullets.add("Mobile-Ready Selling Points: Keeps each image focused on one clear use case or benefit for quick browsing on Amazon.");
        bullets.add(material.isBlank() ? "Fact-Safe Material Copy: Material claims stay generic until the user provides verified material information." : "Verified Material Detail: Uses the provided material information without adding unverified specifications.");
        bullets.add(dimensions.isBlank() ? "No Invented Dimensions: Dimension visuals reserve space for measurements but do not create exact numbers until confirmed." : "Confirmed Size Reference: Dimension visuals can use the supplied size information with clear overlay space.");
        bullets.add("Compliance-Aware Output: Avoids fake certifications, guaranteed claims, medical implications, watermarks, and unreadable AI-generated text.");
        return bullets;
    }

    public String buildImagePrompt(ProductProjectEntity project, Map<String, Object> copy, ImageBriefSeed seed) throws Exception {
        Map<String, Object> confirmedFacts = readJsonMap(project.getConfirmedFacts());
        String adminPromptTemplate = templateContent("image/brief", "");
        Object visualPrompt = copy == null ? "" : copy.get("visual_prompt");
        String visualPromptText = visualPrompt instanceof String text ? text : "";

        String listingLanguage = textValue(project.getLanguage(), "en-US");
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("projectId", project.getId());
        prompt.put("productName", project.getProductName());
        prompt.put("imageType", seed.type());
        prompt.put("imageName", listingImageSlotLabel(seed.type(), listingLanguage));
        prompt.put("adminPromptTemplate", adminPromptTemplate);
        prompt.put("brief", listingImageGoalForPrompt(seed.type(), listingLanguage) + ". Use only confirmed product facts. Do not invent dimensions, materials, certifications, compatibility, prices, discounts, or model numbers.");
        if (!visualPromptText.isBlank()) {
            prompt.put("visualPromptReference", visualPromptText);
        }
        prompt.put("confirmedFacts", confirmedFacts);
        prompt.put("overlayPlan", Map.of(
            "canvas", seed.outputSize(),
            "title", suggestedOverlayForPrompt(seed.type(), listingLanguage),
            "textRenderedBy", "ai-image",
            "forbidden", List.of("unconfirmed claims", "fake certifications", "fake prices", "QR codes", "marketplace UI", "source-language text")
        ));
        prompt.put("generationSize", seed.generationSize());
        prompt.put("outputSize", seed.outputSize());
        prompt.put("postProcess", Map.of(
            "mode", "cover",
            "instruction", "Generate with safe margins because the final image will be resized/cropped by the system to the output canvas."
        ));
        prompt.put("copy", copy);
        String modelPrompt = listingImageModelPrompt(project, copy, confirmedFacts, seed, adminPromptTemplate, visualPromptText);
        prompt.put("userPrompt", listingImageUserPrompt(project, copy, seed, visualPromptText));
        prompt.put("prompt", modelPrompt);
        prompt.put("modelPrompt", modelPrompt);
        return json.writeValueAsString(prompt);
    }

    public String buildSingleImagePrompt(ProductProjectEntity project, String rawPrompt, String imageType, String imageName, String generationSize, String outputSize, String module) throws Exception {
        boolean creative = "creative".equals(imageType);
        String userPrompt = creative ? CreativePromptPolicy.cleanUserPrompt(rawPrompt) : rawPrompt;
        String adminTemplate = templateContent("aplus".equals(imageType) ? "image/aplus" : "image/creative", templateContent("image/single", ""));
        String brief = creative
            ? creativePrompt(userPrompt, module, project.getLanguage())
            : optimizePromptBestEffort(userPrompt, project.getProductName(), "Amazon A+ Content", project.getLanguage());
        Map<String, Object> confirmedFacts = readJsonMap(project.getConfirmedFacts());

        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("projectId", project.getId());
        prompt.put("productName", project.getProductName());
        prompt.put("imageType", imageType);
        prompt.put("imageName", imageName);
        prompt.put("generationSize", generationSize);
        prompt.put("outputSize", outputSize);
        prompt.put("module", module);
        prompt.put("adminPromptTemplate", adminTemplate);
        prompt.put("brief", brief);
        prompt.put("confirmedFacts", confirmedFacts);
        prompt.put("intent", textValue(confirmedFacts.get("intent"), "free_create"));
        prompt.put("text_mode", textValue(confirmedFacts.get("text_mode"), "no_text"));
        prompt.put("reference_policy", textValue(confirmedFacts.get("reference_policy"), "none"));
        prompt.put("reference_count", intValue(confirmedFacts.get("reference_count"), 0));
        prompt.put("template_id", textValue(confirmedFacts.get("template_id"), ""));
        Map<String, Object> visibleTextPlan = domesticVisibleTextPlan(userPrompt, module);
        if (!visibleTextPlan.isEmpty()) {
            prompt.put("visibleTextPlan", Map.of(
                "source", "legacy_text_layer_json",
                "rendered_by", "ai_image_model",
                "text_plan", visibleTextPlan
            ));
        }
        String intentBlock = imageIntentBlock(confirmedFacts, userPrompt);
        if (!intentBlock.isBlank()) {
            prompt.put("imageIntent", Map.of(
                "intent", textValue(confirmedFacts.get("intent"), "free_create"),
                "text_mode", textValue(confirmedFacts.get("text_mode"), "no_text"),
                "reference_policy", textValue(confirmedFacts.get("reference_policy"), "none"),
                "instruction", intentBlock
            ));
        }
        if (creative) {
            String referenceInstruction = creativeReferencePolicy(userPrompt, confirmedFacts);
            prompt.put("referencePolicy", Map.of(
                "priority", CreativePromptPolicy.ignoresAllReferences(userPrompt)
                    ? "text_only_user_request"
                    : CreativePromptPolicy.hasExplicitReferenceRoles(userPrompt) || CreativePromptPolicy.requestsReferenceReplacement(userPrompt)
                        ? "explicit_user_asset_roles"
                        : "user_intent_then_optional_visual_inputs",
                "instruction", referenceInstruction
            ));
        }
        prompt.put("overlayPlan", Map.of(
            "canvas", outputSize,
            "textRenderedBy", "ai-image",
            "forbidden", List.of("未请求的乱码文字", "未授权品牌标识", "虚假认证", "夸大功效")
        ));
        String textPolicy = visibleTextPolicy(userPrompt, creative ? "" : brief);
        prompt.put("visibleTextPolicy", textPolicy);
        prompt.put("postProcess", Map.of(
            "mode", "aplus".equals(imageType) ? "cover" : "none",
            "instruction", "aplus".equals(imageType)
                ? "Leave safe margins; the system will resize/crop this image to the selected A+ module output size."
                : "Use the selected native GPT-image-2 canvas directly."
        ));
        String modelPrompt = creative
            ? creativeImageModelPrompt(project, userPrompt, generationSize, outputSize, module, adminTemplate, confirmedFacts, intentBlock, textPolicy)
            : aplusImageModelPrompt(project, userPrompt, brief, generationSize, outputSize, module, adminTemplate, confirmedFacts);
        prompt.put("modelPrompt", creative || intentBlock.isBlank() ? modelPrompt : modelPrompt + "\n\nIntent and reference constraints:\n" + intentBlock);
        return json.writeValueAsString(prompt);
    }

    private String listingImageModelPrompt(ProductProjectEntity project, Map<String, Object> copy, Map<String, Object> confirmedFacts, ImageBriefSeed seed, String adminTemplate, String visualPrompt) throws Exception {
        Map<String, Object> copyFacts = mapValue(copy == null ? null : copy.get("confirmed_facts_candidate"));
        Map<String, Object> facts = confirmedFacts.isEmpty() ? readJsonMap(project.getConfirmedFacts()) : confirmedFacts;
        if (facts.isEmpty()) {
            facts = copyFacts;
        }
        List<String> bullets = listValue(copy == null ? null : copy.get("bullets"), List.of());
        String title = textValue(copy == null ? null : copy.get("title"), "");
        String description = summarize(textValue(copy == null ? null : copy.get("description"), ""), 1600);
        String visibleText = listingVisibleTextRequirements(seed.type(), title, bullets, facts);
        String language = textValue(project.getLanguage(), textValue(facts.get("language"), "en-US"));
        String slotLabel = listingImageSlotLabel(seed.type(), language);
        String productLabel = Boolean.TRUE.equals(facts.get("main_image_uploaded"))
            ? textValue(project.getProductName(), "")
            : (title.isBlank() ? textValue(project.getProductName(), "") : title);
        return """
            You are an Amazon ecommerce image director and compliance-focused product photographer.

            Goal:
            Create one Amazon-ready Listing image for the image slot below. Except for a strict marketplace main image, this is an ecommerce infographic asset, not a plain product photo. The image must help shoppers understand the product quickly on mobile while staying truthful to the confirmed product facts.

            Marketplace and language:
            - Target marketplace: Amazon %s
            - Listing language/context: %s

            Product identity:
            - Brand: %s
            - Product: %s
            - Image slot: %s (%s)
            - Output canvas: %s
            - Native generation canvas: %s

            Source-language guard:
            - Some user-entered source fields may contain Chinese or another non-listing language. Treat them as internal product facts only.
            - Do not render source-language text, Chinese characters, Chinese punctuation, or localized UI labels as visible image text unless that exact text is part of the allowed listing-language copy snippets.
            - For visible text, use only the generated Listing title, generated Listing bullets, confirmed target-language facts, or short generic labels in the Listing language.

            Confirmed product facts. Treat these as the authority. Do not add facts that are not present here:
            %s

            Listing copy context for visual planning:
            - Title: %s
            - Bullet selling points:
            %s
            - Product description summary: %s
            - Keyword groups for relevance, not for rendering visible text:
            %s

            User visual request, if provided:
            %s

            Required visible information layer:
            %s

            Image-specific direction:
            %s

            Composition rules:
            - Build a clear ecommerce composition with one primary subject and one visual message.
            - Keep the product shape, material cues, color, scale, and category consistent with the reference images when provided.
            - For infographic slots, render the required title, labels, arrows, callout cards, tables, or step numbers directly in the image.
            - Visible text must be short, crisp, readable, and in the listing language. Do not use long paragraphs.
            - Do not render fake badges, watermarks, QR codes, marketplace UI, review stars, random logos, or unreadable/accidental text.
            - Use realistic lighting, natural shadows, crisp edges, and accurate product proportions.
            - Keep important product content inside safe margins because the system may crop/resize to the output canvas.

            Compliance rules:
            - Do not invent exact dimensions, package quantity, materials, certifications, compatibility, awards, guarantees, medical effects, safety claims, or performance numbers.
            - Do not use competitor logos or unauthorized brand marks.
            - Do not imply discounts, time-limited sale, No.1, best, guaranteed, pesticide, sterilization, odorless/colorless, or similar restricted claims.
            - If a detail is not confirmed, communicate it visually in a generic way instead of making it up.

            Color and source-of-truth policy:
            %s

            Admin editable strategy:
            %s

            Final output:
            Generate the image only. No explanatory text.
            """.formatted(
            textValue(project.getCountry(), "US"),
            language,
            textValue(project.getBrand(), ""),
            productLabel,
            slotLabel,
            seed.type(),
            seed.outputSize(),
            seed.generationSize(),
            jsonForPrompt(facts),
            title.isBlank() ? "Not generated yet" : title,
            bulletListForPrompt(bullets),
            description.isBlank() ? "Not generated yet" : description,
            jsonForPrompt(keywordFacts(facts, copyFacts)),
            visualPrompt == null || visualPrompt.isBlank() ? "None." : visualPrompt,
            visibleText,
            imageTypeDirection(seed.type(), facts, bullets, title),
            imageColorPolicy(seed.type(), facts),
            adminTemplate == null || adminTemplate.isBlank() ? "No extra admin strategy. Follow the system rules above." : adminTemplate
        ).trim();
    }

    private String listingImageUserPrompt(ProductProjectEntity project, Map<String, Object> copy, ImageBriefSeed seed, String visualPrompt) {
        String language = textValue(project.getLanguage(), "en-US");
        String title = textValue(copy == null ? null : copy.get("title"), "");
        List<String> lines = new ArrayList<>();
        lines.add("Generate Amazon Listing image: " + listingImageSlotLabel(seed.type(), language) + " (" + seed.type() + ").");
        lines.add("Listing language: " + language + ".");
        lines.add("Product: " + blankToDefault(title, blankToDefault(project.getProductName(), "Product")) + ".");
        lines.add("Goal: " + listingImageGoalForPrompt(seed.type(), language) + ".");
        lines.add("Required facts: " + seed.requiredFacts() + ".");
        if (!isChineseLanguage(language)) {
            lines.add("Do not render Chinese text or localized UI labels as visible image text.");
        }
        if (visualPrompt != null && !visualPrompt.isBlank()) {
            lines.add("User visual request: " + visualPrompt.trim());
        }
        return String.join("\n", lines);
    }

    private String imageIntentBlock(Map<String, Object> facts, String userPrompt) {
        if (facts == null || facts.isEmpty()) {
            return "";
        }
        String intent = textValue(facts.get("intent"), "free_create");
        int referenceCount = intValue(facts.get("reference_count"), 0);
        boolean ignoreReferences = CreativePromptPolicy.ignoresAllReferences(userPrompt)
            || "none".equals(textValue(facts.get("reference_policy"), "none"));
        if (ignoreReferences) {
            return creativeReferencePolicy(userPrompt, facts)
                + "\nFollow the user's text request only. Do not infer product identity, composition, style, or facts from uploaded images.";
        }
        boolean replacement = CreativePromptPolicy.requestsReferenceReplacement(userPrompt);
        List<String> lines = new ArrayList<>();
        if (referenceCount > 0 || CreativePromptPolicy.hasExplicitReferenceRoles(userPrompt)) {
            lines.add(creativeReferencePolicy(userPrompt, facts));
        }
        if (replacement || "reference_replace".equals(intent)) {
            lines.add("Perform the user's explicit subject replacement exactly. Preserve the replacement subject from its designated identity reference and preserve composition, camera, perspective, scene, lighting, or style only from the references assigned those roles.");
        } else {
            lines.add(switch (intent) {
                case "reference_preserve" -> "Use the reference image as the source of truth for the main subject. Preserve the referenced product's category, silhouette, color, material, pattern, structure, proportions, and key visible details. Do not replace or redesign the product.";
                case "background_edit" -> "Keep the designated product reference unchanged. Only modify the requested background, environment, lighting, props, or atmosphere. Do not alter product color, shape, print, packaging artwork, seams, or material texture unless explicitly requested.";
                case "poster_layout" -> "Create an ecommerce commercial layout around the designated product reference. Preserve that product as the main subject. Use clean composition, clear hierarchy, and platform-appropriate commercial styling. Do not invent unprovided prices, discounts, certifications, badges, QR codes, marketplace UI, brand logos, or claims.";
                default -> "Follow the user's creative brief. Keep one main visual idea. Avoid watermarks, accidental logos, unreadable text, distorted product structure, distorted anatomy, and unrelated objects.";
            });
        }
        return String.join("\n", lines);
    }

    private String creativeReferencePolicy(String userPrompt, Map<String, Object> facts) {
        int referenceCount = intValue(facts == null ? null : facts.get("reference_count"), 0);
        String referencePolicy = textValue(facts == null ? null : facts.get("reference_policy"), "none");
        if (CreativePromptPolicy.ignoresAllReferences(userPrompt) || referenceCount > 0 && "none".equals(referencePolicy)) {
            return "The user explicitly chose not to use the uploaded images as visual references. Ignore those images and execute the text request only.";
        }
        if (referenceCount <= 0) {
            return "No visual input is provided. Execute the user's text request directly without inventing facts that require an image source.";
        }
        if (CreativePromptPolicy.requestsReferenceReplacement(userPrompt)) {
            return "Visual inputs are numbered by their original upload order, regardless of how many were uploaded. The user's explicit per-image role assignment and replacement instruction are authoritative. Use composition/scene assets only for layout, camera, perspective, lighting, background, and placement. Use the designated product/identity asset only for the replacement subject's category, shape, proportions, color, material, structure, and visible details. Perform the requested replacement; do not preserve the original object that the user explicitly asked to replace, and do not merge identities across assets.";
        }
        if (CreativePromptPolicy.hasExplicitReferenceRoles(userPrompt)) {
            return "Visual inputs are numbered by their original upload order, regardless of count. Follow the user's role for every specifically named asset. Preserve the subject only from assets designated as product/identity sources, and use assets designated for style, composition, scene, background, or other roles only for those roles. Do not merge subjects or let one asset override another role.";
        }
        if ("style_reference".equals(referencePolicy)) {
            return "Use uploaded visual inputs only for the requested style, composition, palette, lighting, or scene. Do not copy or substitute their subjects unless the user explicitly requests it.";
        }
        return "Treat uploaded images as optional supporting visual inputs, not as instructions that override the user's text. Use only cues relevant to the request, keep upload order stable, and do not force every uploaded item into the result. Preserve product identity only when an uploaded asset is clearly intended as the subject source; otherwise follow the text request.";
    }

    private String aplusImageModelPrompt(ProductProjectEntity project, String rawPrompt, String brief, String generationSize, String outputSize, String module, String adminTemplate, Map<String, Object> confirmedFacts) throws Exception {
        return """
            You are an Amazon A+ Content visual director and ecommerce conversion designer.

            Goal:
            Create one A+ module image that supports brand trust, product understanding, and mobile readability. The image should look like a polished Amazon A+ asset, not a generic poster.

            Product and module:
            - Brand: %s
            - Product: %s
            - Target marketplace: Amazon %s
            - Language/context: %s
            - A+ module: %s
            - Output canvas: %s
            - Native generation canvas: %s

            Product facts. Use only these facts and the reference images as truth:
            %s

            User A+ request:
            %s

            Optimized visual brief:
            %s

            A+ direction:
            - Build a premium but truthful brand-content composition with a clear visual hierarchy.
            - Use lifestyle, detail, benefit, or brand-story composition according to the selected module and user request.
            - Render only user-requested headings, icons, captions, or callouts directly in the AI image; otherwise keep the composition clean and do not invent text.
            - Do not render small paragraphs, fake icons, badges, certification seals, review stars, marketplace UI, watermarks, or unreadable/accidental text.
            - Preserve product identity, material cues, silhouette, and visible structure from references.
            - Keep subject and important details inside safe margins for the final A+ crop.

            Visible text policy:
            %s

            Compliance rules:
            - Do not invent dimensions, materials, package contents, compatibility, certifications, safety claims, medical effects, guarantees, awards, or numeric performance claims.
            - Do not include unauthorized third-party logos or copyrighted character/style references.
            - If a requested detail is not confirmed, make the scene generic and do not invent text or numbers.

            Admin editable strategy:
            %s

            Final output:
            Generate the A+ image only. No explanatory text.
            """.formatted(
            textValue(project.getBrand(), ""),
            textValue(project.getProductName(), ""),
            textValue(project.getCountry(), "US"),
            textValue(project.getLanguage(), "en-US"),
            blankToDefault(module, "A+ module"),
            outputSize,
            generationSize,
            jsonForPrompt(confirmedFacts),
            rawPrompt == null || rawPrompt.isBlank() ? "None." : rawPrompt,
            brief == null || brief.isBlank() ? "Use a clean Amazon A+ product visual with brand-safe composition." : brief,
            visibleTextPolicy(rawPrompt, brief),
            adminTemplate == null || adminTemplate.isBlank() ? "No extra admin strategy. Follow the system rules above." : adminTemplate
        ).trim();
    }

    private String creativeImageModelPrompt(
        ProductProjectEntity project,
        String userPrompt,
        String generationSize,
        String outputSize,
        String module,
        String adminTemplate,
        Map<String, Object> confirmedFacts,
        String intentBlock,
        String textPolicy
    ) {
        return """
            You are a commercial AI image director for product-focused creative visuals.

            Instruction priority:
            1. Safety and factual compliance.
            2. The user's exact request, including every affirmative instruction, negative constraint, and explicit role assigned to each reference image.
            3. Product identity and visible facts from the reference image designated by the user as the product/identity source.
            4. Admin strategy and generic quality guidance, which may supplement but never contradict higher-priority instructions.

            User request (authoritative; execute it without changing its meaning):
            %s

            Execution context:
            - Product: %s
            - Brand: %s
            - Creative style/module: %s
            - Output canvas: %s
            - Native generation canvas: %s
            - Language/context: %s
            - Reference image count: %d

            Reference and edit execution:
            %s

            Visible text policy:
            %s

            Color and source-of-truth policy:
            %s

            Admin editable strategy:
            %s

            Output rules:
            - The selected output canvas above is authoritative. User quality terms such as "8K" or "ultra HD" request high detail only; they do not change the output dimensions.
            - Keep the result commercially usable with a clear subject, coherent perspective, realistic lighting, clean edges, and accurate structure/materials.
            - Avoid unrelated objects, distorted anatomy or product structure, accidental logos, watermarks, and low-quality artifacts.
            - Resolve any conflict using the priority order above. Generate the image only, with no explanatory text.
            """.formatted(
            userPrompt == null || userPrompt.isBlank() ? "None." : userPrompt,
            textValue(project.getProductName(), ""),
            textValue(project.getBrand(), ""),
            blankToDefault(module, "creative"),
            outputSize,
            generationSize,
            textValue(project.getLanguage(), "zh-CN"),
            intValue(confirmedFacts == null ? null : confirmedFacts.get("reference_count"), 0),
            blankToDefault(intentBlock, creativeReferencePolicy(userPrompt, confirmedFacts)),
            textPolicy,
            imageColorPolicy("creative", confirmedFacts),
            adminTemplate == null || adminTemplate.isBlank() ? "No extra admin strategy. Follow the system rules above." : adminTemplate
        ).trim();
    }

    private String listingVisibleTextRequirements(String type, String title, List<String> bullets, Map<String, Object> facts) {
        List<String> captions = visibleCaptionCandidates(title, bullets);
        String dimensions = textValue(facts.get("dimensions"), "");
        boolean hasDimensions = hasConcreteDimensionText(dimensions);
        String packageInfo = packageInfo(facts);
        String compatibility = compatibilityInfo(facts);
        String copyLine = captions.isEmpty()
            ? "- Use only confirmed product facts for visible text; if no short fact is available, use generic section labels."
            : "- Allowed copy snippets: " + String.join(" | ", captions) + ".";
        String language = textValue(facts.get("language"), textValue(facts.get("target_country"), "listing language"));

        List<String> lines = new ArrayList<>();
        lines.add("- Render visible text in " + language + ".");
        if (!isChineseLanguage(language)) {
            lines.add("- Do not render Chinese characters, Chinese punctuation, Chinese UI labels, or source-language product names as visible text. If copy is missing, use target-language generic labels.");
        }
        lines.add(copyLine);
        lines.add("- Do not invent prices, discounts, certifications, exact numbers, model names, awards, QR codes, marketplace UI, or unsupported claims.");
        lines.add("- If reference images are provided, product preservation has higher priority than decorative layout.");
        switch (type) {
            case "main" -> lines.add("- Main slot: keep a clean hero product image. If visible text is used, limit it to one short title and 2-3 concise benefit labels from the allowed snippets; keep the product dominant.");
            case "dimensions" -> {
                if (hasDimensions) {
                    lines.add("- Dimension slot: render measurement arrows and labels using only this provided size/specification text: " + dimensions + ".");
                } else {
                    lines.add("- Dimension slot: render measurement arrows and measurement-position labels only, such as Length, Width, Height, Diameter, Waist, Inseam, Capacity, or other relevant body/part names. Do not write any numeric dimensions or units.");
                }
            }
            case "features" -> lines.add("- Feature slot: render 3-5 short callout labels from the allowed snippets, each connected to a detail zoom box or product detail area.");
            case "scenario" -> lines.add("- Scenario slot: render one scenario title and 1-2 short benefit captions from the allowed snippets.");
            case "steps" -> lines.add("- Steps slot: render 3-4 numbered step cards with short labels. If no installation steps are confirmed, label them as usage method or suitable scenarios, not fake installation instructions.");
            case "comparison" -> lines.add("- Comparison slot: render a comparison-style table using only confirmed advantages from the allowed snippets. If no real competitor facts were provided, do not create a VS competitor column, negative competitor shirt, x-marks, or invented drawbacks. Instead render a neutral Feature Overview / Benefits Checklist table about this product only.");
            case "package" -> {
                if (packageInfo.isBlank()) {
                    lines.add("- Package slot: render packaging, storage, or product-in-box presentation. Do not invent accessory names or quantities.");
                } else {
                    lines.add("- Package slot: render package contents using only this provided package information: " + packageInfo + ".");
                }
            }
            default -> lines.add("- Listing slot: render a concise title and information layer only when supported by confirmed facts.");
        }
        if (!compatibility.isBlank()) {
            lines.add("- Compatibility: if visually relevant, render a compatibility table or model labels using only this confirmed compatibility information: " + compatibility + ".");
        } else {
            lines.add("- Compatibility: do not invent applicable models or device names.");
        }
        return String.join("\n", lines);
    }

    private List<String> visibleCaptionCandidates(String title, List<String> bullets) {
        List<String> captions = new ArrayList<>();
        String cleanTitle = summarize(title, 90);
        if (!cleanTitle.isBlank()) {
            captions.add(cleanTitle);
        }
        if (bullets != null) {
            for (String bullet : bullets) {
                String caption = shortBulletCaption(bullet);
                if (!caption.isBlank() && captions.stream().noneMatch(existing -> existing.equalsIgnoreCase(caption))) {
                    captions.add(caption);
                }
                if (captions.size() >= 6) {
                    break;
                }
            }
        }
        return captions;
    }

    private String shortBulletCaption(String value) {
        String text = cleanLegacyPromptPollution(textValue(value, ""));
        if (text.isBlank()) {
            return "";
        }
        int colon = Math.max(text.indexOf(':'), text.indexOf('：'));
        if (colon > 0 && colon <= 42) {
            text = text.substring(0, colon).trim();
        }
        text = text.replaceAll("\\s+", " ").trim();
        return summarize(text, 54);
    }

    private boolean hasConcreteDimensionText(String value) {
        String text = textValue(value, "").toLowerCase();
        if (text.isBlank() || text.contains("please fill") || text.contains("待填写") || text.contains("未填写") || text.contains("unknown")) {
            return false;
        }
        return Pattern.compile("\\d").matcher(text).find();
    }

    private String packageInfo(Map<String, Object> facts) {
        String description = textValue(facts.get("description"), "");
        Matcher matcher = Pattern.compile("(?i)(package included|package includes|包装(?:清单|内容)?|包含|配件)\\s*[:：-]?\\s*([^\\n。；;]+)").matcher(description);
        return matcher.find() ? summarize(matcher.group(2), 140) : "";
    }

    private String compatibilityInfo(Map<String, Object> facts) {
        String description = textValue(facts.get("description"), "");
        Matcher matcher = Pattern.compile("(?i)(compatible with|fits|for\\s+[A-Z0-9]|适用(?:于|型号)?|兼容(?:型号)?)\\s*[:：-]?\\s*([^\\n。；;]+)").matcher(description);
        return matcher.find() ? summarize(matcher.group(0), 160) : "";
    }

    private String imageTypeDirection(String type, Map<String, Object> facts, List<String> bullets, String title) {
        return switch (type) {
            case "main" -> """
                Main image: create a clean Amazon hero product image on pure white or near-white background. If a reference image is provided, prefer cutout, background replacement, or clean background cleanup over fully redrawing the product from scratch. Show the complete product clearly, centered, with accurate proportions. Keep any visible text minimal and sourced from the title/bullets only; avoid props, lifestyle scenery, decorative clutter, badges, unsupported accessories, and noncompliant claims.
                """.trim();
            case "scenario" -> """
                Scenario image: show a realistic use scene matched to the confirmed audience and use case. The product must remain the hero and should be immediately identifiable. Add a clear scenario heading and short benefit caption using only confirmed copy. Use context to explain usage, not to invent new product capabilities.
                """.trim();
            case "features" -> """
                Feature image: create a finished feature infographic with 3-5 product detail zoom boxes. Each zoom box must have an arrow/callout line and a short readable label from confirmed listing copy. Do not add unconfirmed specs or claims.
                """.trim();
            case "dimensions" -> """
                Dimension image: create a finished technical size infographic. It must include measurement arrows and measurement-position labels. If concrete dimensions are confirmed, use those exact values only; otherwise use part labels only and never invent numbers or units.
                """.trim();
            case "steps" -> """
                Steps image: create a clean sequential-use infographic with 3-4 numbered cards and short readable step labels. If the product has no confirmed installation process, make it a usage-method or suitable-scenario sequence instead of inventing assembly steps.
                """.trim();
            case "comparison" -> """
                Comparison image: create a neutral comparison-style infographic. If the user provided real competitor facts, use a factual left-right table. If not, create a single-product Feature Overview / Benefits Checklist table instead. Do not invent a generic competitor product, negative competitor claims, x-mark drawbacks, test data, certifications, or unsupported superiority claims.
                """.trim();
            case "package" -> """
                Package or variant image: show confirmed package contents, color, specification, or variant information with short labels. If package contents are not confirmed, show packaging/storage presentation only and do not invent accessory names or quantities.
                """.trim();
            case "aplus" -> "A+ module image: create brand-content composition with product detail, lifestyle context, and safe overlay space.";
            default -> "Listing image: create a truthful Amazon ecommerce product visual with one clear message.";
        };
    }

    private String imageColorPolicy(String imageType, Map<String, Object> facts) {
        boolean hasMainImage = Boolean.TRUE.equals(facts.get("main_image_uploaded"));
        String hints = joinColorHints(listValue(facts.get("color_hints"), List.of()));
        if ("main".equals(imageType)) {
            return """
                - Use the uploaded main reference image as the source of truth for product color.
                - Preserve the product color exactly as shown in the reference image.
                - Do not infer or change the product color from text, scene, background, or keyword descriptions.
                - Scene/background color words do not describe the product color.
                - For a white-background main image, prefer cutout, background replacement, or cleanup over fully redrawing the product.
                - If the Listing title or description says a color that conflicts with the uploaded main reference image, ignore the copy color and follow the main reference image.
                %s
                """.formatted(hints.isBlank()
                ? "- No text color hints are provided; do not create one."
                : "- Text color hints are optional variant information only: " + hints + ".").trim();
        }
        if (hasMainImage) {
            return """
                - Use the uploaded main reference image as the source of truth for product color.
                - Preserve the product color exactly as shown in the reference image.
                - Do not infer or change the product color from text, scene, background, or keyword descriptions.
                - Scene/background color words do not describe the product color.
                - If the Listing title or description says a color that conflicts with the uploaded main reference image, ignore the copy color and follow the main reference image.
                %s
                """.formatted(hints.isBlank()
                ? "- No text color hints are provided; do not create one."
                : "- Text color hints are optional variants only: " + hints + ".").trim();
        }
        return """
            - Follow the color cues provided in the user brief.
            - If color hints are present, treat them as optional variants unless the user explicitly requests a replacement.
            %s
            """.formatted(hints.isBlank()
            ? "- No explicit color hints were provided."
            : "- Text color hints: " + hints + ".").trim();
    }

    private String bulletListForPrompt(List<String> bullets) {
        if (bullets == null || bullets.isEmpty()) {
            return "- No listing bullets generated yet.";
        }
        return String.join("\n", bullets.stream()
            .limit(5)
            .map(item -> "- " + item)
            .toList());
    }

    private Map<String, Object> keywordFacts(Map<String, Object> facts, Map<String, Object> fallback) {
        Map<String, Object> keywords = new LinkedHashMap<>();
        keywords.put("title_keywords", facts.getOrDefault("title_keywords", fallback.getOrDefault("title_keywords", fallback.getOrDefault("keywords", List.of()))));
        keywords.put("selling_point_keywords", facts.getOrDefault("selling_point_keywords", fallback.getOrDefault("selling_point_keywords", List.of())));
        keywords.put("description_keywords", facts.getOrDefault("description_keywords", fallback.getOrDefault("description_keywords", List.of())));
        return keywords;
    }

    private List<String> colorHints(Map<String, Object>... sources) {
        Set<String> hints = new LinkedHashSet<>();
        if (sources == null) {
            return List.of();
        }
        for (Map<String, Object> source : sources) {
            if (source == null || source.isEmpty()) {
                continue;
            }
            addDirectColorHints(hints, source.get("color_hints"));
            addDirectColorHints(hints, source.get("color"));
            addExplicitColorHints(hints, source.get("description"));
            addExplicitColorHints(hints, source.get("visual_prompt"));
            addExplicitColorHints(hints, source.get("aplus_prompt"));
        }
        return new ArrayList<>(hints);
    }

    private void addDirectColorHints(Set<String> hints, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                addDirectColorHints(hints, item);
            }
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return;
        }
        for (String part : text.split("[,，/、|]")) {
            String color = cleanColorHint(part);
            if (!color.isBlank()) {
                hints.add(color);
            }
        }
    }

    private void addExplicitColorHints(Set<String> hints, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                addExplicitColorHints(hints, item);
            }
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return;
        }
        Matcher matcher = COLOR_HINT_PATTERN.matcher(text);
        while (matcher.find()) {
            for (String part : matcher.group(1).split("[,，/、|]")) {
                String color = cleanColorHint(part);
                if (!color.isBlank()) {
                    hints.add(color);
                }
            }
        }
    }

    private String cleanColorHint(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("^[\\s：:，,;；]+|[\\s：:，,;；。]+$", "").trim();
    }

    private String joinColorHints(List<String> hints) {
        if (hints == null || hints.isEmpty()) {
            return "";
        }
        return String.join(", ", hints);
    }

    private String colorPolicy(boolean hasMainImage, List<String> hints) {
        String hintText = joinColorHints(hints);
        if (hasMainImage) {
            if (hintText.isBlank()) {
                return "Use the uploaded main reference image as the source of truth for product color. Preserve the product color exactly as shown in the reference image. Do not infer or change the product color from text, scene, background, or keyword descriptions. Scene/background color words do not describe the product color.";
            }
            return "Use the uploaded main reference image as the source of truth for product color. Preserve the product color exactly as shown in the reference image. Do not infer or change the product color from text, scene, background, or keyword descriptions. Scene/background color words do not describe the product color. Text color hints are optional variant information only: " + hintText + ".";
        }
        if (hintText.isBlank()) {
            return "Follow the color cues provided in the user brief. If color is not explicitly confirmed, keep the product color neutral and faithful to the reference image.";
        }
        return "Follow the color cues provided in the user brief. Text color hints are optional variant information only: " + hintText + ".";
    }

    private static final Pattern COLOR_HINT_PATTERN = Pattern.compile("(?i)(?:product\\s+color|variant\\s+color|color|商品颜色|颜色)\\s*[:：]\\s*([^\\n；;。]+)");

    private String jsonForPrompt(Object value) throws Exception {
        if (value == null) {
            return "{}";
        }
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    private Map<String, Object> mapValue(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private Map<String, Object> readJsonMap(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> domesticVisibleTextPlan(String rawPrompt, String module) {
        if (module == null || !module.contains("国内电商")) {
            return Map.of();
        }
        String overlayJson = extractOverlayJson(rawPrompt);
        if (overlayJson.isBlank()) {
            return Map.of();
        }
        return readJsonMap(overlayJson);
    }

    private String extractOverlayJson(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int marker = text.indexOf("文字层 JSON:");
        if (marker < 0) {
            return "";
        }
        int start = text.indexOf('{', marker);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    private String creativePrompt(String rawPrompt, String style, String language) {
        String raw = rawPrompt == null ? "" : rawPrompt.trim();
        if (raw.isBlank()) {
            throw new IllegalArgumentException("请输入生成提示词");
        }
        String styleText = blankToDefault(style, "自由创作");
        return """
            Creative execution context.
            Desired style/module: %s
            Output language/context: %s
            The user's request is stored separately as the authoritative instruction and is intentionally not repeated in this brief.
            """.formatted(styleText, blankToDefault(language, "zh-CN")).trim();
    }

    public String optimizePrompt(String rawPrompt, String productName, String marketplace, String language) {
        String raw = rawPrompt == null ? "" : rawPrompt.trim();
        if (raw.isBlank()) {
            throw new IllegalArgumentException("请输入需要优化的 prompt");
        }
        if (runtimeConfig.textProviderChain().isEmpty()) {
            throw new IllegalArgumentException("AI 文案服务已被管理员禁用或未配置密钥，请联系管理员开启后再使用");
        }
        return optimizePromptBestEffort(raw, productName, marketplace, language);
    }

    public Map<String, Object> generateCanvasText(Map<String, Object> requestBody, String businessTaskId) {
        Map<String, Object> body = sanitizeCanvasTextBody(requestBody);
        List<AiRuntimeConfigService.EffectiveTextConfig> configuredChain = runtimeConfig.textProviderChain();
        if (configuredChain.isEmpty()) {
            throw new IllegalArgumentException("AI 文案服务已被管理员禁用或未配置密钥，请联系管理员开启后再使用");
        }
        boolean containsImages = canvasMessagesContainImages(body.get("messages"));
        List<AiRuntimeConfigService.EffectiveTextConfig> chain = containsImages
            ? configuredChain.stream().filter(this::supportsCanvasImageMessages).toList()
            : configuredChain;
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("当前未配置支持图片理解的文案模型，请联系管理员开启后再试");
        }
        int localWaitBudget = chain.stream()
            .filter(AiRuntimeConfigService.EffectiveTextConfig::pullWorker)
            .mapToInt(AiRuntimeConfigService.EffectiveTextConfig::localWaitSeconds)
            .max()
            .orElse(0);
        int chainTimeoutSeconds = containsImages
            ? CANVAS_VISION_PROVIDER_CHAIN_TIMEOUT_SECONDS
            : TEXT_PROVIDER_CHAIN_TIMEOUT_SECONDS;
        long deadline = System.nanoTime()
            + Duration.ofSeconds(chainTimeoutSeconds + localWaitBudget).toNanos();
        Exception last = null;
        for (int providerIndex = 0; providerIndex < chain.size(); providerIndex++) {
            AiRuntimeConfigService.EffectiveTextConfig config = chain.get(providerIndex);
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                last = new IllegalStateException("Canvas text provider chain timed out");
                break;
            }
            if (isTextProviderCircuitOpen(config)) {
                last = new IllegalStateException(config.provider() + " text provider is temporarily disabled after repeated failures");
                continue;
            }
            try {
                int remainingSeconds = providerAttemptTimeoutSeconds(
                    remainingNanos,
                    chain.size() - providerIndex,
                    chainTimeoutSeconds
                );
                Map<String, Object> response = callCanvasTextProvider(
                    config,
                    body,
                    blankToDefault(businessTaskId, "canvas_text_" + java.util.UUID.randomUUID().toString().replace("-", "")),
                    remainingSeconds
                );
                closeTextProviderCircuit(config);
                return response;
            } catch (AiWorkerDeferredException exception) {
                throw exception;
            } catch (Exception exception) {
                openTextProviderCircuitIfNeeded(config, exception);
                last = exception;
            }
        }
        throw new IllegalStateException("Canvas text provider chain failed: " + summarize(last == null ? "" : last.getMessage(), 240));
    }

    private boolean canvasMessagesContainImages(Object value) {
        if (value instanceof Map<?, ?> map) {
            if ("image_url".equals(String.valueOf(map.get("type")))) return true;
            return map.values().stream().anyMatch(this::canvasMessagesContainImages);
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(this::canvasMessagesContainImages);
        }
        return false;
    }

    private boolean supportsCanvasImageMessages(AiRuntimeConfigService.EffectiveTextConfig config) {
        return config.pullWorker() || !isDeepSeekCompatible(config);
    }

    private Map<String, Object> sanitizeCanvasTextBody(Map<String, Object> source) {
        if (source == null || !(source.get("messages") instanceof List<?> messages) || messages.isEmpty()) {
            throw new IllegalArgumentException("画布文案消息不能为空");
        }
        if (messages.size() > 20) throw new IllegalArgumentException("画布文案最多携带 20 条消息");
        try {
            if (json.writeValueAsString(source).length() > 32_000_000) {
                throw new IllegalArgumentException("画布文案请求过大");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("画布文案请求格式无效");
        }
        validateCanvasMessages(messages);
        Map<String, Object> body = new LinkedHashMap<>();
        for (String key : List.of(
            "messages", "tools", "tool_choice", "temperature", "max_tokens",
            "max_completion_tokens", "reasoning_effort", "response_format"
        )) {
            if (source.containsKey(key) && source.get(key) != null) body.put(key, source.get(key));
        }
        if (body.get("tools") != null) {
            try {
                if (json.writeValueAsString(body.get("tools")).length() > 64_000) {
                    throw new IllegalArgumentException("画布工具定义过大");
                }
            } catch (IllegalArgumentException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalArgumentException("画布工具定义格式无效");
            }
        }
        body.put("stream", false);
        return body;
    }

    private void validateCanvasMessages(List<?> messages) {
        int textChars = 0;
        int imageCount = 0;
        long imageChars = 0;
        for (Object value : messages) {
            if (!(value instanceof Map<?, ?> message)) {
                throw new IllegalArgumentException("画布文案消息格式无效");
            }
            String role = String.valueOf(message.get("role"));
            if (!Set.of("system", "user", "assistant", "tool").contains(role)) {
                throw new IllegalArgumentException("画布文案消息角色无效");
            }
            Object content = message.get("content");
            if (content instanceof String text) {
                textChars += text.length();
                continue;
            }
            if (!(content instanceof List<?> parts) || parts.size() > 24) {
                throw new IllegalArgumentException("画布文案消息内容格式无效");
            }
            for (Object partValue : parts) {
                if (!(partValue instanceof Map<?, ?> part)) {
                    throw new IllegalArgumentException("画布文案消息片段格式无效");
                }
                String type = String.valueOf(part.get("type"));
                if ("text".equals(type)) {
                    textChars += String.valueOf(part.get("text") == null ? "" : part.get("text")).length();
                    continue;
                }
                if (!"image_url".equals(type)) {
                    throw new IllegalArgumentException("画布文案仅支持文本和图片消息");
                }
                Object imageValue = part.get("image_url");
                String imageUrl = imageValue instanceof Map<?, ?> imageMap
                    ? String.valueOf(imageMap.get("url") == null ? "" : imageMap.get("url"))
                    : String.valueOf(imageValue == null ? "" : imageValue);
                if (!imageUrl.matches("(?is)^data:image/(?:jpeg|jpg|png|webp);base64,[a-z0-9+/=\\r\\n]+$")) {
                    throw new IllegalArgumentException("画布图片必须是 JPG、PNG 或 WebP 的内嵌数据");
                }
                imageCount += 1;
                imageChars += imageUrl.length();
            }
        }
        if (textChars > 80_000) throw new IllegalArgumentException("画布文案文本过长");
        if (imageCount > 4) throw new IllegalArgumentException("画布文案最多携带 4 张图片");
        if (imageChars > 28_000_000L) throw new IllegalArgumentException("画布图片总大小过大");
    }

    public VideoScriptPlan optimizeVideoScript(
        String rawPrompt,
        String provider,
        String model,
        int durationSeconds,
        String aspectRatio,
        int referenceCount,
        boolean generateAudio
    ) {
        String raw = rawPrompt == null ? "" : rawPrompt.trim();
        if (raw.length() < 4) {
            throw new IllegalArgumentException("请填写至少 4 个字符的商品视频描述");
        }
        String mode = VideoPromptPolicy.modeFor(raw, provider);
        String fallback = VideoPromptPolicy.fallbackScript(
            raw, provider, durationSeconds, referenceCount, generateAudio
        );
        if (runtimeConfig.textProviderChain().isEmpty()) {
            return new VideoScriptPlan(mode, fallback, "fallback", "已按商品连续性规则整理脚本");
        }

        String modeInstruction = "storyboard".equals(mode)
            ? "输出 2 到 4 个有明确时间范围的分镜，所有时间段连续覆盖完整视频时长。允许按脚本切镜，但转场必须自然。"
            : "输出单镜头连续脚本，不得切镜、跳场景或改变商品身份。";
        String audioInstruction = generateAudio
            ? "同步规划自然环境声和商品动作音效；除非用户明确给出台词，否则不要编造口播、价格或宣传承诺。"
            : "不要规划音轨、台词或旁白。";
        String references = VideoPromptPolicy.referenceGuide(provider, referenceCount);
        try {
            List<Map<String, String>> messages = List.of(
                Map.of(
                    "role", "system",
                    "content", """
                        你是 PicPilot 的商品视频导演、分镜师和电商合规审核员。
                        你的工作是把用户的一句话需求整理成可直接提交给视频生成模型的高质量执行脚本。

                        只输出 JSON，不要 Markdown，不要解释：
                        {
                          "optimized_prompt": "最终可执行脚本",
                          "summary": "不超过30字的优化摘要"
                        }

                        强制规则：
                        1. 严格保留用户意图，不改变商品品类，不虚构尺寸、材质、功能、认证、功效、价格、折扣或品牌授权。
                        2. 参考图是商品身份、结构、比例、颜色、材质、Logo、标签、配件和数量的事实来源。
                        3. 从首帧到末帧保持同一商品，不得变形、增减部件、换色、换字、复制或替换。
                        4. 镜头运动和商品动作必须符合物理规律，画面适合真实电商投放。
                        5. 禁止无关文字、乱码、水印、二维码、平台界面和未提供的营销承诺。
                        6. 时长必须精确覆盖用户选择的总时长，不能擅自增加或缩短。
                        7. 保留用户明确要求的场景、动作、运镜、台词和声音；缺失的信息只做安全的视觉补全。
                        """
                ),
                Map.of(
                    "role", "user",
                    "content", """
                        供应商：%s
                        模型：%s
                        视频时长：%d 秒
                        画面比例：%s
                        脚本模式：%s
                        模式要求：%s
                        参考素材规则：%s
                        音频要求：%s

                        用户原始需求：
                        %s
                        """.formatted(
                        blankToDefault(provider, "video"),
                        blankToDefault(model, "default"),
                        durationSeconds,
                        blankToDefault(aspectRatio, "9:16"),
                        mode,
                        modeInstruction,
                        references.isBlank() ? "无参考素材" : references,
                        audioInstruction,
                        raw
                    )
                )
            );
            Map<String, Object> result = parseJsonObject(callDeepSeek(messages, 0.25, 1400, true));
            String optimized = cleanPromptOutput(textValue(result.get("optimized_prompt"), ""));
            if (optimized.length() < 4 || optimized.length() > 4000) {
                return new VideoScriptPlan(mode, fallback, "fallback", "已按商品连续性规则整理脚本");
            }
            String summary = summarize(textValue(result.get("summary"), "已完成智能脚本优化"), 30);
            return new VideoScriptPlan(mode, optimized, "ai", summary);
        } catch (Exception ignored) {
            return new VideoScriptPlan(mode, fallback, "fallback", "已按商品连续性规则整理脚本");
        }
    }

    public record VideoScriptPlan(
        String mode,
        String optimizedPrompt,
        String source,
        String summary
    ) {}

    public List<String> suggestDomesticSellingPoints(String productName, String platform, String usage, String specs, String audience, String language) {
        String product = blankToDefault(productName, "").trim();
        if (product.isBlank()) {
            throw new IllegalArgumentException("请先填写商品名称");
        }
        if (!runtimeConfig.textProviderChain().isEmpty()) {
            try {
                List<String> aiSuggestions = suggestDomesticSellingPointsWithAi(product, platform, usage, specs, audience, language);
                if (!aiSuggestions.isEmpty()) {
                    return aiSuggestions;
                }
            } catch (Exception ignored) {
                // Keep domestic commerce usable when the text provider is temporarily unavailable.
            }
        }
        return fallbackDomesticSellingPoints(product, platform, usage, specs, audience);
    }

    public String buildCreativeChatPrompt(List<CreativeChatTurn> turns, String latestUserMessage, String style, String size, String language, String referenceUrl) {
        return buildCreativeChatPrompt(turns, latestUserMessage, style, size, language, referenceUrl, Map.of());
    }

    public String buildCreativeChatPrompt(List<CreativeChatTurn> turns, String latestUserMessage, String style, String size, String language, String referenceUrl, Map<String, Object> imageIntentFacts) {
        String latest = latestUserMessage == null ? "" : latestUserMessage.trim();
        if (latest.isBlank()) {
            throw new IllegalArgumentException("请输入生图需求或修改意见");
        }
        if (runtimeConfig.textProviderChain().isEmpty()) {
            throw new IllegalArgumentException("AI 文案服务已被管理员禁用或未配置密钥，请联系管理员开启后再使用");
        }
        try {
            String prompt = buildCreativeChatPromptWithAi(turns, latest, style, size, language, referenceUrl);
            if (!prompt.isBlank()) {
                return CreativePromptPolicy.cleanUserPrompt(cleanPromptOutput(prompt));
            }
        } catch (Exception ignored) {
            // Keep the creative flow usable when the text provider is temporarily unstable.
        }
        return CreativePromptPolicy.cleanUserPrompt(cleanPromptOutput(creativeChatPromptFallback(turns, latest, style, size, language, referenceUrl)));
    }

    public CreativeChatPlan planCreativeChat(List<CreativeChatTurn> turns, String latestUserMessage, String requestedSize, String language, boolean hasReference, boolean hasImageContext) {
        String latest = latestUserMessage == null ? "" : latestUserMessage.trim();
        if (latest.isBlank() || runtimeConfig.textProviderChain().isEmpty()) {
            return new CreativeChatPlan("", "", "", 0);
        }
        try {
            List<Map<String, String>> messages = List.of(
                Map.of(
                    "role", "system",
                    "content", """
                        你是 PicPilot 图像 Agent 的意图规划器。判断用户当前消息是否真的要创建/修改图片，还是只是闲聊、咨询、补充想法或需要追问。

                        只输出 JSON，不要 Markdown，不要解释：
                        {
                          "intent": "chat|clarify|generate_image|edit_image",
                          "reply": "当 intent 为 chat 或 clarify 时，给用户的简短回复；generate_image/edit_image 时留空",
                          "size_hint": "1K|2K|4K|",
                          "confidence": 0.0
                        }

                        规则：
                        - 问价格、积分、能力、怎么用、是否支持、闲聊、打招呼：intent=chat，不扣积分。
                        - 只有 HD/高清/超清/8K 等清晰度词，但没有画面主体：intent=clarify，不扣积分，并追问要生成什么画面。
                        - 明确要求生成/画/做/设计图片，且有主体或场景：intent=generate_image。
                        - 已有图片上下文时，要求修改、换、重做、增强、放大、变成某种效果：intent=edit_image。
                        - 参考图存在且用户说修改/生成/场景/风格/清晰度，可视为生图或改图。
                        - 必须结合最近上下文判断。如果上一轮用户已提出生图/改图需求，助手正在追问角色、性别、年龄、服装、主题、构图、场景、颜色或尺寸等缺失字段，那么用户当前对这些字段的简短补充仍属于原生图任务：无已有图片结果时 intent=generate_image，已有图片结果时 intent=edit_image。不得因为当前消息短、没有再次出现“生成”二字而判为 chat。
                        - 用户明确说取消、不生成、先聊天或只是询问时，才终止上述意图继承。
                        - size_hint 只允许 1K、2K、4K；8K 降级为 4K；HD/高清/超清通常为 2K；明确 4K/UHD/3840/4096/8K 才是 4K。
                        - 低置信度时用 clarify。
                        """
                ),
                Map.of(
                    "role", "user",
                    "content", """
                        语言：%s
                        用户请求尺寸：%s
                        是否有参考图：%s
                        是否已有图片上下文：%s

                        最近上下文：
                        %s

                        最新用户消息：
                        %s
                        """.formatted(
                        blankToDefault(language, "zh-CN"),
                        blankToDefault(requestedSize, ""),
                        hasReference ? "yes" : "no",
                        hasImageContext ? "yes" : "no",
                        creativeChatContext(turns),
                        latest
                    )
                )
            );
            Map<String, Object> raw = parseJsonObject(callDeepSeek(messages, 0.1, 500, true));
            String intent = textValue(raw.get("intent"), "").trim().toLowerCase();
            String reply = textValue(raw.get("reply"), "").trim();
            String sizeHint = textValue(raw.get("size_hint"), "").trim().toUpperCase();
            double confidence = doubleValue(raw.get("confidence"), 0);
            if (!List.of("chat", "clarify", "generate_image", "edit_image").contains(intent)) {
                return new CreativeChatPlan("", "", "", 0);
            }
            if (!List.of("1K", "2K", "4K").contains(sizeHint)) {
                sizeHint = "";
            }
            return new CreativeChatPlan(intent, reply, sizeHint, confidence);
        } catch (Exception ignored) {
            return new CreativeChatPlan("", "", "", 0);
        }
    }

    private String buildCreativeChatPromptWithAi(List<CreativeChatTurn> turns, String latest, String style, String size, String language, String referenceUrl) throws Exception {
        String adminTemplate = templateContent("prompt/creative-chat", templateContent("prompt/optimize", ""));
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of(
            "role", "system",
            "content", """
                你是 PicPilot 的跨境电商视觉策划师、AI 图像提示词导演和商品图合规审核员。
                你要把用户在多轮聊天中的需求、修改意见、风格偏好和参考图说明整理成一段最终可交给图像模型执行的 prompt。

                必须做到：
                1. 保留最新一轮用户修改意见，并与历史上下文合并，不能改变、遗漏或反转用户明确要求；最新要求与旧要求冲突时以最新要求为准。
                2. 明确主体、场景、构图、镜头、光线、材质、背景、风格、留白和负面约束。
                3. 图片是可选视觉素材，不是默认高于用户文字的指令。可以没有图片、只有一张或有任意数量；未指定用途时只能作辅助，不能强迫所有图片进入画面。
                4. 如果用户为任意图片指定了序号、名称或角色，必须按原上传顺序分别使用：商品/主体素材负责身份，构图/场景/风格素材只负责对应角色。不得把所有图片都当成同一个主体。
                5. 用户明确说不参考上传图片时，输出必须是纯文本生图需求，不能再要求模型参考图片。
                6. 用户明确要求用某张素材中的商品替换另一张图中的原商品时，必须执行替换；不得追加“禁止替换商品”之类的相反规则。替换后保留新商品身份，同时继承用户指定的构图、角度、场景和光影。
                7. 不编造未提供的具体参数、认证、功效、兼容型号、品牌授权或促销信息。
                8. 默认不新增任何文字。只有用户明确给出要新增的准确文案时才允许上字；“海报、包装、文字、Logo”等词出现在禁止项或“保持原包装文字”中，不代表用户要求新增文字。
                9. 用户要求保留包装、标签或 Logo 原有图文时，把它们作为商品身份的一部分保留，但不得生成新的标题、标语或营销文案。
                10. 有视觉素材但没有视觉识别结果时，禁止猜商品品类；只能写“用户指定素材中的商品”，并按用户分配的角色保留相关细节。
                11. 用户提到国内电商、小红书、淘宝、天猫、京东、拼多多、1688、抖音、快手、商品图、主图、详情页、卖点图、封面或种草时，必须按对应国内电商平台风格生成商品图/海报布局。
                12. 小红书商品图要像真实种草封面/商品图；仅当用户给出准确中文标题或标签式短文案时才渲染文字。禁止虚构价格、折扣、优惠券、新品、销量、认证、平台 UI、二维码。
                13. 输出一段最终 prompt，不要解释过程，不要 Markdown，不要 JSON，也不要附加系统内部的 Intent/constraint 区块。

                管理员后台启用的 Prompt 模板/策略：
                %s
                """.formatted(adminTemplate.isBlank() ? "无额外模板，按系统默认策略执行。" : adminTemplate)
        ));
        messages.add(Map.of(
            "role", "user",
            "content", """
                输出语言/语境：%s
                画面风格：%s
                目标尺寸：%s
                参考图：%s

                历史上下文：
                %s

                最新用户需求/修改意见：
                %s

                请输出最终可执行生图 prompt。
                """.formatted(
                blankToDefault(language, "zh-CN"),
                blankToDefault(style, "自由创作"),
                blankToDefault(size, "1024x1024"),
                referenceUrl == null || referenceUrl.isBlank() ? "无" : referenceUrl,
                creativeChatContext(turns),
                latest
            )
        ));
        return callDeepSeek(messages, 0.45, 1200);
    }

    private String creativeChatPromptFallback(List<CreativeChatTurn> turns, String latest, String style, String size, String language, String referenceUrl) {
        return """
            Create one commercially usable ecommerce creative image.
            Final user request: %s
            Conversation context: %s
            Style direction: %s
            Output canvas: %s
            Language/context: %s
            Reference image policy: %s
            Composition: clear primary subject, coherent scene, strong mobile readability, integrated readable text areas only when requested, realistic lighting, sharp focus, clean background control, accurate proportions.
            Constraints: do not guess the product category from the reference image; call it "the product shown in the reference image" unless the user named the category. Follow explicit per-reference roles and explicit replacement instructions exactly. Do not invent exact dimensions, certifications, compatibility, awards, guarantees, medical or safety claims, discounts, logos, watermarks, QR codes, marketplace UI, or unreadable AI-generated text. Add visible text only when the user provides the exact wording; otherwise add no new text, while preserving existing packaging/label text when requested.
            """.formatted(
            latest,
            summarize(creativeChatContext(turns), 1400),
            blankToDefault(style, "creative ecommerce visual"),
            blankToDefault(size, "1024x1024"),
            blankToDefault(language, "zh-CN"),
            referenceUrl == null || referenceUrl.isBlank()
                ? "No reference image was provided."
                : "Uploaded images are optional visual inputs in stable upload order. Follow any roles or exclusions stated by the user; do not force every image into the output or let images override the text request. Execute any explicit subject replacement."
        );
    }

    private String creativeChatContext(List<CreativeChatTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "无历史上下文。";
        }
        return turns.stream()
            .limit(12)
            .map(turn -> "- " + blankToDefault(turn.role(), "user") + ": " + summarize(blankToDefault(turn.content(), ""), 500))
            .filter(line -> !line.endsWith(": "))
            .reduce((left, right) -> left + "\n" + right)
            .orElse("无历史上下文。");
    }

    private List<String> suggestDomesticSellingPointsWithAi(String productName, String platform, String usage, String specs, String audience, String language) throws Exception {
        String market = blankToDefault(platform, "国内电商");
        String imageUsage = blankToDefault(usage, "主图/卖点图");
        String lang = blankToDefault(language, "zh-CN");
        List<Map<String, String>> messages = List.of(
            Map.of(
                "role", "system",
                "content", """
                    你是国内电商运营和商品卖点策划专家，熟悉淘宝/天猫、1688、京东、拼多多、抖音电商、小红书图片转化表达。
                    任务：根据用户填写的商品名称、平台和图片用途，生成可放进国内电商图片表单的候选卖点。

                    重要边界：
                    - 这些卖点是“同类热销图常见诉求方向”的辅助建议，不是实时市场调研结论。
                    - 不允许编造具体尺寸、重量、材质、容量、价格、认证、功效、兼容型号、销量、排名、时效、活动优惠。
                    - 如果用户没有提供参数，只写方向型卖点，例如“容量更好装”“通勤好搭”“细节更精致”，不要写具体数字。
                    - 避免绝对化和未验证效果词，如最、第一、全网、保证、治愈、安全、无毒、医用、杀菌、永久、超、持久、大容量、舒适、高效等。
                    - 每条必须是完整的中文短卖点，优先 4-8 个中文字。
                    - 不能只输出品类词、品牌词、人群词或孤立名词，例如 Lolita、兔包、包、玩偶、少女心 这种单独词不要输出。
                    - 不能输出英文、拼音、型号、价格、平台 UI、数字标记或解释文字。
                    - 只输出真正能放进图片里的卖点短语，必须带有“更、好、感、显、搭、装、出街、通勤、拍照、出片、氛围、精致、实用、方便、省心、可爱、萌趣、甜系、百搭、吸睛、整洁、清爽”等表达中的至少一种。
                    - 严禁输出 2-3 个字的空泛词，例如“软萌”“少女心”“好看”“精致”“可爱”“甜美”。
                    - 只输出 JSON，不要 Markdown，不要解释。

                    输出格式：
                    {"selling_points":["","","","","",""]}
                    """
            ),
            Map.of(
                "role", "user",
                "content", """
                    商品名称：%s
                    平台：%s
                    图片用途：%s
                    已知规格/参数：%s
                    适用人群：%s
                    输出语言：%s

                    请给 6 条候选卖点，适合直接填入国内电商图卖点输入框。
                    """.formatted(
                    productName,
                    market,
                    imageUsage,
                    blankToDefault(specs, "未提供"),
                    blankToDefault(audience, "未提供"),
                    lang
                )
            )
        );
        String content = callDeepSeek(messages, 0.2, 420, true);
        Map<String, Object> payload = parseJsonObject(content);
        return sanitizeDomesticSellingPoints(listValue(payload.get("selling_points"), List.of()), productName, platform, usage, specs, audience);
    }

    private List<String> fallbackDomesticSellingPoints(String productName, String platform, String usage, String specs, String audience) {
        List<String> seeds = new ArrayList<>();
        String source = (productName + " " + blankToDefault(specs, "") + " " + blankToDefault(audience, "")).toLowerCase();
        if (containsAny(source, "包", "bag", "背包", "挎包", "托特", "手提")) {
            seeds.addAll(List.of("出街好搭", "造型更吸睛", "细节更精致", "通勤好搭", "拍照更出片", "拿取更顺手"));
        } else if (containsAny(source, "鞋", "靴", "拖鞋", "sneaker")) {
            seeds.addAll(List.of("日常好搭配", "版型更显利落", "通勤好搭", "出街更利落", "拍照更出片", "细节更精致"));
        } else if (containsAny(source, "衣", "裙", "裤", "衫", "外套", "lolita", "lo娘")) {
            seeds.addAll(List.of("版型更显气质", "上身更有层次", "日常拍照都好看", "细节更精致", "搭配空间更高", "出街更有氛围"));
        } else if (containsAny(source, "灯", "电器", "充电", "键盘", "耳机", "支架", "数码")) {
            seeds.addAll(List.of("使用更顺手", "桌面更清爽", "细节清晰可见", "日常场景适配", "质感更利落", "收纳更省心"));
        } else if (containsAny(source, "杯", "壶", "餐", "锅", "碗", "厨房", "收纳", "置物", "架")) {
            seeds.addAll(List.of("日常使用方便", "收纳更省心", "台面更清爽", "取用更顺手", "分区更好找", "细节更精致"));
        } else if (containsAny(source, "玩偶", "公仔", "娃", "兔", "猫", "熊")) {
            seeds.addAll(List.of("萌趣更吸睛", "拍照更出片", "送礼更有心意", "细节更可爱", "氛围感更强", "出街摆放都好看"));
        } else {
            seeds.addAll(List.of("颜值更出众", "使用更省心", "场景更百搭", "细节更精致", "送礼自用都适合", "日常更实用"));
        }

        String market = blankToDefault(platform, "");
        if (market.contains("1688")) {
            seeds.addAll(List.of("源头货更好选", "采购判断更清楚", "货盘展示更直观", "拿样参考更省心"));
        } else if (market.contains("小红书")) {
            seeds.addAll(List.of("氛围感更强", "拍照更出片", "种草感更足"));
        } else if (market.contains("抖音")) {
            seeds.addAll(List.of("三秒抓眼", "直播间更吸睛", "上镜更清楚"));
        } else if (market.contains("京东")) {
            seeds.addAll(List.of("参数展示清晰", "细节更可信", "品质感更强"));
        } else if (market.contains("拼多多")) {
            seeds.addAll(List.of("一眼看懂优势", "实用更划算", "家庭日常适合"));
        } else if (market.contains("淘宝") || market.contains("天猫")) {
            seeds.addAll(List.of("店铺视觉更统一", "新品感更强", "收藏欲更高"));
        }

        String imageUsage = blankToDefault(usage, "");
        if (imageUsage.contains("主图")) {
            seeds.addAll(List.of("主体更清晰", "缩略图更醒目"));
        } else if (imageUsage.contains("详情")) {
            seeds.addAll(List.of("细节分区更清楚", "信息层级更好读"));
        } else if (imageUsage.contains("场景")) {
            seeds.addAll(List.of("使用场景更真实", "代入感更强"));
        }
        return sanitizeDomesticSellingPoints(seeds, productName, platform, usage, specs, audience);
    }

    private List<String> sanitizeDomesticSellingPoints(List<String> values, String productName, String platform, String usage, String specs, String audience) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String sourceContext = (blankToDefault(productName, "") + " " + blankToDefault(specs, "") + " " + blankToDefault(audience, "")).toLowerCase();
        for (String value : values == null ? List.<String>of() : values) {
            String cleaned = cleanDomesticSellingPoint(value, sourceContext);
            if (!cleaned.isBlank()) {
                result.add(cleaned);
            }
            if (result.size() >= 8) {
                break;
            }
        }
        if (result.size() < 6) {
            for (String fallback : fallbackDomesticSellingPointsNoSanitize(productName, platform, usage, specs, audience)) {
                String cleaned = cleanDomesticSellingPoint(fallback, sourceContext);
                if (!cleaned.isBlank()) {
                    result.add(cleaned);
                }
                if (result.size() >= 6) {
                    break;
                }
            }
        }
        return result.stream().limit(8).toList();
    }

    private List<String> fallbackDomesticSellingPointsNoSanitize(String productName, String platform, String usage, String specs, String audience) {
        String source = (productName + " " + blankToDefault(specs, "") + " " + blankToDefault(audience, "")).toLowerCase();
        if (containsAny(source, "包", "bag", "背包", "挎包", "托特", "手提")) {
            return List.of("出街好搭", "容量更好装", "造型更吸睛", "细节更精致", "通勤约会都适合", "拿取更顺手");
        }
        if (containsAny(source, "玩偶", "公仔", "娃", "兔", "猫", "熊")) {
            return List.of("软萌更吸睛", "拍照更出片", "送礼更有心意", "细节更可爱", "陪伴感更强", "摆放背着都好看");
        }
        return List.of("颜值更出众", "使用更省心", "场景更百搭", "细节更精致", "送礼自用都适合", "日常更实用");
    }

    private String cleanDomesticSellingPoint(String value) {
        return cleanDomesticSellingPoint(value, "");
    }

    private String cleanDomesticSellingPoint(String value, String sourceContext) {
        if (value == null) {
            return "";
        }
        String cleaned = value
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("^[\\s\\-•·、，。:：0-9.]+", "")
            .replaceAll("[\"“”'`]", "")
            .trim();
        if (cleaned.isBlank()) {
            return "";
        }
        String lower = cleaned.toLowerCase();
        List<String> blocked = List.of(
            "最", "第一", "全网", "保证", "治愈", "医用", "杀菌", "无毒", "永久", "100%", "no.1", "best",
            "神器", "必备", "爆棚", "爆款", "闭眼入", "冲冲冲", "超好", "超值", "绝绝子",
            "极致", "高级", "超级", "超", "安全", "保险", "价值", "杀虫剂", "无色无味",
            "持久", "大容量", "舒适", "高效", "长效", "恒温", "耐用", "耐磨", "防水", "防滑"
        );
        for (String item : blocked) {
            cleaned = cleaned.replace(item, "");
            lower = lower.replace(item.toLowerCase(), "");
        }
        List<String> unverifiedSpecifics = List.of(
            "真皮", "纯棉", "羊毛", "蚕丝", "毛绒", "刺绣", "防水", "抗菌", "环保", "无味",
            "加厚", "轻量", "透气", "耐磨", "防滑", "可水洗", "不掉色", "不起球",
            "手感", "口感", "保温更", "冰感", "速干", "承重", "防尘", "防潮"
        );
        String context = sourceContext == null ? "" : sourceContext;
        for (String item : unverifiedSpecifics) {
            if (!context.contains(item.toLowerCase())) {
                cleaned = cleaned.replace(item, "");
                lower = lower.replace(item.toLowerCase(), "");
            }
        }
        cleaned = cleaned.replaceAll("[A-Za-z0-9]+", "");
        cleaned = cleaned.replaceAll("\\s+", "");
        cleaned = cleaned.replaceAll("[_\\-+=<>~`^*{}\\[\\]\\(\\)【】#@!$%&?/\\\\|:;,.，。！？、·“”'\"，；：]+", "");
        if (!containsChinese(cleaned)) {
            return "";
        }
        if (!containsAny(cleaned, "更", "好", "感", "型", "显", "搭", "装", "出街", "通勤", "拍照", "出片", "氛围", "精致", "实用", "方便", "省心", "可爱", "萌趣", "甜系", "百搭", "吸睛", "利落", "松弛", "轻盈", "顺手", "整洁", "清爽")) {
            return "";
        }
        if (cleaned.endsWith("更") || cleaned.endsWith("更显") || cleaned.endsWith("更有") || cleaned.endsWith("更好")) {
            return "";
        }
        if (cleaned.length() > 18) {
            cleaned = cleaned.substring(0, 18);
        }
        if (cleaned.length() < 4) {
            return "";
        }
        return cleaned.trim();
    }

    private boolean containsChinese(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String value, String... candidates) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && value.contains(candidate.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String optimizePromptBestEffort(String rawPrompt, String productName, String marketplace, String language) {
        String raw = rawPrompt == null ? "" : rawPrompt.trim();
        if (!runtimeConfig.textProviderChain().isEmpty()) {
            try {
                String optimized = optimizePromptWithDeepSeek(raw, productName, marketplace, language);
                if (!optimized.isBlank()) {
                    return cleanPromptOutput(optimized);
                }
            } catch (Exception ignored) {
                // keep prompt construction available when the upstream provider is temporarily unavailable
            }
        }
        return cleanPromptOutput(optimizePromptFallback(raw, productName, marketplace, language));
    }

    private String optimizePromptWithDeepSeek(String raw, String productName, String marketplace, String language) throws Exception {
        String product = blankToDefault(productName, "the product");
        String market = blankToDefault(marketplace, "Amazon US");
        String lang = blankToDefault(language, "zh-CN");
        String adminTemplate = templateContent("prompt/optimize", "");
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of(
            "role", "system",
            "content", """
                你是 PicPilot 的多平台电商视觉策划专家、AI 图像提示词导演和合规审核专家。
                你的任务不是套模板，也不是把所有需求都改成 Amazon 风格，而是根据商品主体、使用场景、目标平台和用户原始想法，重写成可直接交给图像模型的高质量 prompt。
                必须做到：
                1. 抓住画面主体和真实卖点，不偏题。
                2. 明确构图、场景、镜头、光线、材质、背景、视觉层级。
                3. 严格符合目标平台语境：Amazon 才使用 Listing/A+ 逻辑；TikTok Shop 使用 PDP gallery 商品图库逻辑；淘宝/天猫/京东/拼多多/1688 使用国内货架/详情页逻辑；抖音/小红书使用内容电商/种草逻辑。
                4. 不编造尺寸、认证、功效、兼容型号。
                5. 默认不要让图像模型乱生成文字；但当用户明确要求贴纸、标签、海报、封面、标题、Logo 或某些文字上图时，必须保留用户给出的原文字，并说明这些文字需要作为画面元素清晰渲染。
                6. 电商套图必须保留每个图种的分工，不要把主图、场景图、细节图、SKU 图全部写成同一种右侧文字板布局。
                7. 输出一段最终 prompt，不要解释过程，不要 Markdown。

                管理员后台启用的 Prompt 模板/策略：
                %s
                """
                .formatted(adminTemplate.isBlank() ? "无额外模板，按系统默认策略执行。" : adminTemplate)
        ));
        messages.add(Map.of(
            "role", "user",
            "content", """
                目标平台：%s
                输出语言：%s
                商品主体：%s
                用户原始想法：%s

                请输出最终可执行 prompt。
                """.formatted(market, lang, product, raw)
        ));
        return callDeepSeek(messages, 0.45, 900);
    }

    private String callDeepSeek(List<Map<String, String>> messages, double temperature, int maxTokens) throws Exception {
        return callDeepSeek(messages, temperature, maxTokens, false);
    }

    private String callDeepSeek(List<Map<String, String>> messages, double temperature, int maxTokens, boolean jsonMode) throws Exception {
        List<AiRuntimeConfigService.EffectiveTextConfig> chain = runtimeConfig.textProviderChain();
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("AI 文案服务已被管理员禁用或未配置密钥，请联系管理员开启后再使用");
        }
        AiTextCallAuditService.Context riskContext = textAudit.current();
        Exception last = null;
        int localWaitBudget = chain.stream()
            .filter(AiRuntimeConfigService.EffectiveTextConfig::pullWorker)
            .mapToInt(AiRuntimeConfigService.EffectiveTextConfig::localWaitSeconds)
            .max()
            .orElse(0);
        long deadline = System.nanoTime()
            + Duration.ofSeconds(TEXT_PROVIDER_CHAIN_TIMEOUT_SECONDS + localWaitBudget).toNanos();
        for (int providerIndex = 0; providerIndex < chain.size(); providerIndex++) {
            AiRuntimeConfigService.EffectiveTextConfig config = chain.get(providerIndex);
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                last = new IllegalStateException("Text provider chain timed out");
                break;
            }
            if (isTextProviderCircuitOpen(config)) {
                last = new IllegalStateException(config.provider() + " text provider is temporarily disabled after repeated failures");
                continue;
            }
            try {
                int remainingSeconds = providerAttemptTimeoutSeconds(
                    remainingNanos,
                    chain.size() - providerIndex
                );
                String content = callTextProvider(config, messages, temperature, maxTokens, jsonMode, remainingSeconds);
                if (!content.isBlank()) {
                    closeTextProviderCircuit(config);
                    return content;
                }
                last = new IllegalStateException(config.provider() + " returned empty content");
            } catch (AiWorkerDeferredException exception) {
                throw exception;
            } catch (Exception exception) {
                openTextProviderCircuitIfNeeded(config, exception);
                last = exception;
            }
        }
        if (riskControl != null && last != null) {
            riskControl.recordProviderError(riskContext.userId(), contextProjectId(riskContext), "", riskContext.feature(), last.getMessage());
        }
        throw new IllegalStateException("Text provider chain failed: " + summarize(last == null ? "" : last.getMessage(), 240));
    }

    static int providerAttemptTimeoutSeconds(long remainingNanos, int remainingProviders) {
        return providerAttemptTimeoutSeconds(remainingNanos, remainingProviders, TEXT_PROVIDER_CHAIN_TIMEOUT_SECONDS);
    }

    static int providerAttemptTimeoutSeconds(long remainingNanos, int remainingProviders, int maxAttemptSeconds) {
        long remainingSeconds = Math.max(1L, (remainingNanos + 999_999_999L) / 1_000_000_000L);
        int providerCount = Math.max(1, remainingProviders);
        long fairShare = (remainingSeconds + providerCount - 1L) / providerCount;
        return (int) Math.max(1L, Math.min(Math.max(1, maxAttemptSeconds), fairShare));
    }

    private String contextProjectId(AiTextCallAuditService.Context context) {
        return context != null && "project".equalsIgnoreCase(context.targetType()) ? context.targetId() : "";
    }

    private String messagesText(List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map<String, String> message : messages) {
            if (message == null) {
                continue;
            }
            message.forEach((key, value) -> out.append(key).append(": ").append(value == null ? "" : value).append('\n'));
        }
        return out.toString();
    }

    private String callTextProvider(AiRuntimeConfigService.EffectiveTextConfig config, List<Map<String, String>> messages, double temperature, int maxTokens, boolean jsonMode, int remainingSeconds) throws Exception {
        long started = System.nanoTime();
        AiTextCallAuditService.Context auditContext = textAudit.current();
        int inputChars = textInputChars(messages);
        int httpStatus = 0;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        if (isDeepSeekCompatible(config)) {
            body.put("thinking", Map.of("type", "disabled"));
        }
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        body.put("stream", false);

        if (config.pullWorker()) {
            if (workerJobs == null) {
                throw new IllegalStateException("本机文案 Worker 服务未加载");
            }
            try {
                String result = workerJobs.executeText(
                    config,
                    textBusinessTaskId(auditContext, body),
                    "openai_chat_completions",
                    body
                );
                textAudit.record(
                    auditContext,
                    config.provider(),
                    config.model(),
                    result.isBlank() ? "empty" : "success",
                    null,
                    elapsedMs(started),
                    inputChars,
                    result.length(),
                    result.isBlank() ? "empty_content" : "",
                    ""
                );
                return result;
            } catch (AiWorkerDeferredException exception) {
                textAudit.record(
                    auditContext,
                    config.provider(),
                    config.model(),
                    "queued",
                    null,
                    elapsedMs(started),
                    inputChars,
                    0,
                    "worker_deferred",
                    ""
                );
                throw exception;
            } catch (Exception exception) {
                textAudit.record(
                    auditContext,
                    config.provider(),
                    config.model(),
                    "failed",
                    null,
                    elapsedMs(started),
                    inputChars,
                    0,
                    errorType(exception),
                    exception.getMessage()
                );
                throw exception;
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(chatEndpoint(config)))
            .timeout(Duration.ofSeconds(Math.max(1, Math.min(Math.min(config.timeoutSeconds(), 90), remainingSeconds))))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.apiKey())
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            httpStatus = response.statusCode();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = config.provider() + " text request failed: " + response.statusCode() + " " + summarize(response.body(), 240);
                textAudit.record(auditContext, config.provider(), config.model(), "failed", httpStatus, elapsedMs(started), inputChars, response.body() == null ? 0 : response.body().length(), "http_" + response.statusCode(), message);
                throw new TextProviderHttpException(message, response.statusCode());
            }
            JsonNode payload = json.readTree(response.body());
            JsonNode content = payload.path("choices").path(0).path("message").path("content");
            String result = content.isMissingNode() || content.isNull() ? "" : content.asText("").trim();
            textAudit.record(auditContext, config.provider(), config.model(), result.isBlank() ? "empty" : "success", httpStatus, elapsedMs(started), inputChars, result.length(), result.isBlank() ? "empty_content" : "", "");
            return result;
        } catch (Exception exception) {
            if (httpStatus == 0) {
                textAudit.record(auditContext, config.provider(), config.model(), "failed", null, elapsedMs(started), inputChars, 0, errorType(exception), exception.getMessage());
            }
            throw exception;
        }
    }

    private Map<String, Object> callCanvasTextProvider(
        AiRuntimeConfigService.EffectiveTextConfig config,
        Map<String, Object> sourceBody,
        String businessTaskId,
        int remainingSeconds
    ) throws Exception {
        long started = System.nanoTime();
        AiTextCallAuditService.Context auditContext = textAudit.current();
        Map<String, Object> body = new LinkedHashMap<>(sourceBody);
        body.put("model", config.model());
        body.put("stream", false);
        if (isDeepSeekCompatible(config)) body.put("thinking", Map.of("type", "disabled"));
        int inputChars = canvasTextInputChars(body.get("messages"));
        int httpStatus = 0;

        if (config.pullWorker()) {
            if (workerJobs == null) throw new IllegalStateException("本机文案 Worker 服务未加载");
            try {
                if (canvasMessagesContainImages(body.get("messages"))) {
                    if (canvasWorkerInputs == null) {
                        throw new IllegalStateException("本机文案 Worker 视觉输入适配器未加载");
                    }
                    body = canvasWorkerInputs.externalize(body, businessTaskId);
                }
                Map<String, Object> response = normalizeCanvasTextResponse(
                    workerJobs.executeTextRaw(config, businessTaskId, "openai_chat_completions", body),
                    config.model()
                );
                textAudit.record(
                    auditContext,
                    config.provider(),
                    config.model(),
                    "success",
                    null,
                    elapsedMs(started),
                    inputChars,
                    json.writeValueAsString(response).length(),
                    "",
                    ""
                );
                return response;
            } catch (AiWorkerDeferredException exception) {
                textAudit.record(auditContext, config.provider(), config.model(), "queued", null, elapsedMs(started), inputChars, 0, "worker_deferred", "");
                throw exception;
            } catch (Exception exception) {
                textAudit.record(auditContext, config.provider(), config.model(), "failed", null, elapsedMs(started), inputChars, 0, errorType(exception), exception.getMessage());
                throw exception;
            }
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(chatEndpoint(config)))
            .timeout(Duration.ofSeconds(Math.max(1, Math.min(Math.min(config.timeoutSeconds(), 90), remainingSeconds))))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.apiKey())
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            httpStatus = response.statusCode();
            if (httpStatus < 200 || httpStatus >= 300) {
                String message = config.provider() + " canvas text request failed: " + httpStatus + " " + summarize(response.body(), 240);
                textAudit.record(auditContext, config.provider(), config.model(), "failed", httpStatus, elapsedMs(started), inputChars, response.body() == null ? 0 : response.body().length(), "http_" + httpStatus, message);
                throw new TextProviderHttpException(message, httpStatus);
            }
            Map<String, Object> payload = json.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
            if (payload.get("error") != null) {
                throw new IllegalStateException(config.provider() + " returned an error response");
            }
            Map<String, Object> normalized = normalizeCanvasTextResponse(payload, config.model());
            textAudit.record(auditContext, config.provider(), config.model(), "success", httpStatus, elapsedMs(started), inputChars, response.body().length(), "", "");
            return normalized;
        } catch (Exception exception) {
            if (httpStatus == 0) {
                textAudit.record(auditContext, config.provider(), config.model(), "failed", null, elapsedMs(started), inputChars, 0, errorType(exception), exception.getMessage());
            }
            throw exception;
        }
    }

    private Map<String, Object> normalizeCanvasTextResponse(Object value, String model) {
        Map<String, Object> response;
        if (value instanceof Map<?, ?> map) {
            response = json.convertValue(map, new TypeReference<Map<String, Object>>() {});
        } else if (value instanceof JsonNode node) {
            response = json.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } else {
            response = new LinkedHashMap<>();
            response.put("choices", List.of(Map.of(
                "index", 0,
                "message", Map.of("role", "assistant", "content", String.valueOf(value == null ? "" : value)),
                "finish_reason", "stop"
            )));
        }
        if (response.containsKey("choices")) return response;
        String text = textValue(response.get("text"), textValue(response.get("output_text"), ""));
        response = new LinkedHashMap<>(response);
        response.put("model", blankToDefault(textValue(response.get("model"), ""), model));
        response.put("choices", List.of(Map.of(
            "index", 0,
            "message", Map.of("role", "assistant", "content", text),
            "finish_reason", "stop"
        )));
        return response;
    }

    private int canvasTextInputChars(Object value) {
        if (value == null) return 0;
        if (value instanceof String text) {
            return text.regionMatches(true, 0, "data:image/", 0, 11) ? 0 : text.length();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                .filter(entry -> !"image_url".equals(String.valueOf(entry.getKey())))
                .mapToInt(entry -> canvasTextInputChars(entry.getValue()))
                .sum();
        }
        if (value instanceof List<?> list) {
            return list.stream().mapToInt(this::canvasTextInputChars).sum();
        }
        return 0;
    }

    private int textInputChars(List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return messages.stream()
            .mapToInt(item -> item == null ? 0 : item.values().stream().mapToInt(value -> value == null ? 0 : value.length()).sum())
            .sum();
    }

    private boolean isTextProviderCircuitOpen(AiRuntimeConfigService.EffectiveTextConfig config) {
        Long openUntil = textProviderCircuitOpenUntil.get(textProviderCircuitKey(config));
        if (openUntil == null) {
            return false;
        }
        if (openUntil <= System.currentTimeMillis()) {
            textProviderCircuitOpenUntil.remove(textProviderCircuitKey(config));
            return false;
        }
        return true;
    }

    private void openTextProviderCircuitIfNeeded(AiRuntimeConfigService.EffectiveTextConfig config, Exception exception) {
        long duration = textProviderCircuitDuration(exception);
        if (duration <= 0) {
            return;
        }
        textProviderCircuitOpenUntil.put(textProviderCircuitKey(config), System.currentTimeMillis() + duration);
    }

    private void closeTextProviderCircuit(AiRuntimeConfigService.EffectiveTextConfig config) {
        textProviderCircuitOpenUntil.remove(textProviderCircuitKey(config));
    }

    private long textProviderCircuitDuration(Exception exception) {
        if (exception instanceof TextProviderHttpException httpException) {
            int status = httpException.statusCode();
            if (status == 401 || status == 403) {
                return TEXT_PROVIDER_AUTH_CIRCUIT_MS;
            }
            if (status == 429 || status >= 500) {
                return TEXT_PROVIDER_TRANSIENT_CIRCUIT_MS;
            }
            return 0;
        }
        String type = errorType(exception);
        if ("timeout".equals(type) || "connection".equals(type)) {
            return TEXT_PROVIDER_TRANSIENT_CIRCUIT_MS;
        }
        return 0;
    }

    private String textProviderCircuitKey(AiRuntimeConfigService.EffectiveTextConfig config) {
        String id = config == null ? "" : blankToDefault(config.id(), "");
        if (!id.isBlank()) {
            return id;
        }
        if (config == null) {
            return "";
        }
        return blankToDefault(config.provider(), "") + "|" + blankToDefault(config.baseUrl(), "") + "|" + blankToDefault(config.model(), "");
    }

    private long elapsedMs(long started) {
        return Math.max(0, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    private String errorType(Exception exception) {
        if (exception == null) {
            return "unknown";
        }
        String text = (exception.getClass().getSimpleName() + " " + exception.getMessage()).toLowerCase();
        if (text.contains("timeout") || text.contains("timed out")) return "timeout";
        if (text.contains("connect")) return "connection";
        if (text.contains("json")) return "parse_error";
        return exception.getClass().getSimpleName();
    }

    private String chatEndpoint(AiRuntimeConfigService.EffectiveTextConfig config) {
        String normalized = config.baseUrl() == null ? "" : config.baseUrl().replaceAll("/+$", "");
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/chat/completions";
        }
        if (isDeepSeekCompatible(config)) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    private boolean isDeepSeekCompatible(AiRuntimeConfigService.EffectiveTextConfig config) {
        String provider = config.provider() == null ? "" : config.provider().toLowerCase();
        String model = config.model() == null ? "" : config.model().toLowerCase();
        return provider.contains("deepseek") || model.contains("deepseek");
    }

    private static class TextProviderHttpException extends IllegalStateException {
        private final int statusCode;

        private TextProviderHttpException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }

    private Map<String, Object> parseJsonObject(String value) throws Exception {
        String jsonText = extractJsonObject(value);
        if (jsonText.isBlank()) {
            return Map.of();
        }
        return json.readValue(jsonText, new TypeReference<Map<String, Object>>() {});
    }

    private String extractJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value.trim();
        cleaned = cleaned.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)\\s*```$", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned.startsWith("{") ? cleaned : "";
    }

    private String optimizePromptFallback(String raw, String productName, String marketplace, String language) {
        String market = blankToDefault(marketplace, "Amazon US");
        String product = blankToDefault(productName, "the product");
        String scene = inferScene(raw);
        String subject = product.equals(raw) ? product : product + ", based on the user's idea: " + raw;
        String composition = raw.length() > 60 ? "multi-layer commercial composition with foreground product detail, midground use context, and clean negative space" : "clean product-centered composition with one clear selling point";
        return "Create a high-converting %s ecommerce product visual for %s. Primary subject: %s. Scene strategy: %s. Composition: %s. Camera and lighting: commercial product photography, sharp focus, realistic material texture, balanced softbox lighting, accurate scale and proportions, clean background control, strong mobile-first readability. Conversion goal: make the main benefit and use case clear within three seconds while keeping the product truthful and premium. Visible text rule: if the user explicitly asks for sticker, label, poster, cover, logo, title, or visible words, render only the user-provided wording clearly as part of the AI image; otherwise do not invent text. Negative constraints: no fake certifications, no medical or guaranteed-performance claims, no unrelated logos, no distorted product structure, no watermark, no clutter, no unreadable AI-generated text."
            .formatted(market, product, subject, scene, composition);
    }

    private String inferScene(String raw) {
        String lower = raw.toLowerCase();
        if (raw.contains("儿童") || raw.contains("亲子") || lower.contains("kids")) {
            return "family-friendly lifestyle scene with safe, warm, realistic daily-use context";
        }
        if (raw.contains("户外") || raw.contains("运动") || lower.contains("outdoor") || lower.contains("sport")) {
            return "active outdoor lifestyle scene with dynamic use context and natural light";
        }
        if (raw.contains("尺寸") || lower.contains("size") || lower.contains("dimension")) {
            return "technical product layout with confirmed measurements only when supplied";
        }
        if (raw.contains("对比") || lower.contains("compare")) {
            return "comparison-oriented layout with clear left-right visual hierarchy";
        }
        if (raw.contains("高级") || raw.contains("质感") || lower.contains("premium")) {
            return "premium studio scene emphasizing material texture, finish, and brand trust";
        }
        return "realistic ecommerce scene matched to the provided product facts and target marketplace";
    }

    private String visibleTextPolicy(String userText, String optimizedText) {
        String combined = (blankToDefault(userText, "") + "\n" + blankToDefault(optimizedText, "")).trim();
        return switch (CreativePromptPolicy.visibleTextIntent(combined)) {
            case ADD_EXACT_TEXT -> "Add visible text only where the user explicitly requests it. Render only the exact user-provided wording as crisp, legible visual text, preserving its language and characters. Do not invent extra titles, claims, badges, certification seals, prices, discounts, QR codes, marketplace UI, watermarks, or unrelated logos.";
            case PRESERVE_EXISTING_TEXT -> "Preserve the existing text, artwork, labels, and logo already visible on the product or packaging reference as part of that product's identity. Do not rewrite, translate, remove, or invent those markings. Add no new headline, slogan, promotional copy, badge, price, discount, QR code, marketplace UI, watermark, or unrelated logo.";
            case NO_NEW_TEXT -> "Add no new visible text. Do not invent words, titles, slogans, labels, badges, certification seals, prices, discounts, QR codes, marketplace UI, watermarks, or unrelated logos.";
        };
    }

    private String textBusinessTaskId(
        AiTextCallAuditService.Context context,
        Map<String, Object> request
    ) {
        AiTextCallAuditService.Context safe = context == null
            ? new AiTextCallAuditService.Context("", "unknown", "", "")
            : context;
        String target = blankToDefault(safe.targetId(), "");
        String identity = String.join(
            ":",
            "text",
            blankToDefault(safe.targetType(), "request"),
            target.isBlank() ? blankToDefault(safe.userId(), "anonymous") : target,
            blankToDefault(safe.feature(), "unknown"),
            requestDigest(request).substring(0, 16)
        );
        return identity.length() <= 160 ? identity : identity.substring(0, 160);
    }

    private String requestDigest(Map<String, Object> request) {
        try {
            byte[] bytes = json.writeValueAsString(request == null ? Map.of() : request)
                .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to identify text Worker request");
        }
    }

    private String templateContent(String scene, String fallback) {
        if (scene == null || scene.isBlank()) {
            return fallback;
        }
        try {
            PromptTemplateEntity template = promptTemplates.selectOne(new LambdaQueryWrapper<PromptTemplateEntity>()
                .eq(PromptTemplateEntity::getDeleted, 0)
                .eq(PromptTemplateEntity::getEnabled, true)
                .eq(PromptTemplateEntity::getScene, scene)
                .orderByDesc(PromptTemplateEntity::getUpdatedAt)
                .last("limit 1"));
            return template == null || template.getContent() == null || template.getContent().isBlank() ? fallback : template.getContent();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String suggestedOverlay(String type, String productName) {
        return switch (type) {
            case "main" -> productName;
            case "scenario" -> "Designed for Real Use";
            case "features" -> "Key Features";
            case "dimensions" -> "Dimensions / Size";
            case "steps" -> "Easy to Use";
            case "comparison" -> "Why Choose This";
            case "package" -> "Package & Variants";
            case "aplus" -> "Brand Story / Key Benefit";
            case "creative" -> "Creative Visual";
            default -> "Listing Image";
        };
    }

    private String suggestedOverlayForPrompt(String type, String language) {
        if (isChineseLanguage(language)) {
            return switch (type) {
                case "main" -> "商品主标题";
                case "scenario" -> "真实使用场景";
                case "features" -> "核心卖点";
                case "dimensions" -> "尺寸/结构";
                case "steps" -> "使用方式";
                case "comparison" -> "优势对比";
                case "package" -> "包装/变体";
                case "aplus" -> "品牌故事/核心利益点";
                default -> "Listing 图片";
            };
        }
        if (isGermanLanguage(language)) {
            return switch (type) {
                case "main" -> "Listing-Titel";
                case "scenario" -> "Anwendungsbereich";
                case "features" -> "Wichtige Merkmale";
                case "dimensions" -> "Abmessungen / Struktur";
                case "steps" -> "Anwendung";
                case "comparison" -> "Merkmalsübersicht";
                case "package" -> "Lieferumfang / Varianten";
                case "aplus" -> "Markengeschichte / Hauptvorteil";
                default -> "Listing-Bild";
            };
        }
        if (isFrenchLanguage(language)) {
            return switch (type) {
                case "main" -> "Titre du listing";
                case "scenario" -> "Cas d'utilisation";
                case "features" -> "Caractéristiques clés";
                case "dimensions" -> "Dimensions / Structure";
                case "steps" -> "Mode d'utilisation";
                case "comparison" -> "Aperçu des caractéristiques";
                case "package" -> "Contenu / Variantes";
                case "aplus" -> "Histoire de marque / Bénéfice clé";
                default -> "Image du listing";
            };
        }
        return switch (type) {
            case "main" -> "Listing title";
            case "scenario" -> "Use Case";
            case "features" -> "Key Features";
            case "dimensions" -> "Dimensions / Structure";
            case "steps" -> "How to Use";
            case "comparison" -> "Feature Overview";
            case "package" -> "Package / Variants";
            case "aplus" -> "Brand Story / Key Benefit";
            default -> "Listing Image";
        };
    }

    private String listingImageSlotLabel(String type, String language) {
        if (isChineseLanguage(language)) {
            return imageGoal(type);
        }
        if (isGermanLanguage(language)) {
            return switch (type) {
                case "main" -> "Hauptbild";
                case "scenario" -> "Lifestyle-Anwendungsbild";
                case "features" -> "Merkmals-Infografik";
                case "dimensions" -> "Abmessungs-/Struktur-Infografik";
                case "steps" -> "Anwendungs-/Schritte-Infografik";
                case "comparison" -> "Vergleichs-/Merkmalsübersicht-Infografik";
                case "package" -> "Lieferumfang-/Variantenbild";
                case "aplus" -> "A+ Modulbild";
                case "creative" -> "Kreativbild";
                default -> "Listing-Bild";
            };
        }
        if (isFrenchLanguage(language)) {
            return switch (type) {
                case "main" -> "Image principale";
                case "scenario" -> "Image de mise en situation";
                case "features" -> "Infographie des caractéristiques";
                case "dimensions" -> "Infographie dimensions / structure";
                case "steps" -> "Infographie mode d'utilisation";
                case "comparison" -> "Infographie comparaison / aperçu";
                case "package" -> "Image contenu / variante";
                case "aplus" -> "Image module A+";
                case "creative" -> "Image créative";
                default -> "Image du listing";
            };
        }
        return switch (type) {
            case "main" -> "Main image";
            case "scenario" -> "Lifestyle scenario image";
            case "features" -> "Feature infographic";
            case "dimensions" -> "Dimensions / structure infographic";
            case "steps" -> "How-to / usage steps infographic";
            case "comparison" -> "Comparison / feature overview infographic";
            case "package" -> "Package / variant image";
            case "aplus" -> "A+ module image";
            case "creative" -> "Creative image";
            default -> "Listing image";
        };
    }

    private String listingImageGoalForPrompt(String type, String language) {
        if (isChineseLanguage(language)) {
            return imageGoal(type);
        }
        if (isGermanLanguage(language)) {
            return switch (type) {
                case "main" -> "Sauberes Amazon-Hauptbild mit dem Produkt als Mittelpunkt";
                case "scenario" -> "Lifestyle-Anwendungsbild mit einem bestätigten Nutzungsszenario";
                case "features" -> "Merkmals-Infografik mit kurzen Hinweisen aus bestätigten Verkaufspunkten";
                case "dimensions" -> "Abmessungs- oder Struktur-Infografik mit Messpfeilen und ohne erfundene Zahlen";
                case "steps" -> "Anwendungs- oder Schritte-Infografik mit kurzen nummerierten Schritten";
                case "comparison" -> "Vergleichs- oder Merkmalsübersicht nur mit bestätigten Vorteilen";
                case "package" -> "Lieferumfang, Aufbewahrung oder Varianten ohne erfundene Mengen";
                case "aplus" -> "Amazon A+ Modulbild";
                default -> "Amazon Listing Produktbild";
            };
        }
        if (isFrenchLanguage(language)) {
            return switch (type) {
                case "main" -> "Image principale Amazon claire avec le produit au centre";
                case "scenario" -> "Image de mise en situation montrant un usage confirmé";
                case "features" -> "Infographie avec courts appels basés sur les arguments confirmés";
                case "dimensions" -> "Infographie dimensions ou structure avec flèches de mesure sans chiffres inventés";
                case "steps" -> "Infographie d'utilisation avec courtes étapes numérotées";
                case "comparison" -> "Comparaison ou aperçu des caractéristiques avec avantages confirmés uniquement";
                case "package" -> "Contenu, rangement ou variantes sans quantités inventées";
                case "aplus" -> "Visuel de module Amazon A+";
                default -> "Image produit Amazon Listing";
            };
        }
        return switch (type) {
            case "main" -> "Clean Amazon main image with the product as the hero";
            case "scenario" -> "Lifestyle scenario image that communicates one confirmed use case";
            case "features" -> "Feature infographic with short callouts based on confirmed selling points";
            case "dimensions" -> "Dimensions or structure infographic with measurement arrows and no invented numbers";
            case "steps" -> "Usage or how-to infographic with short numbered steps";
            case "comparison" -> "Comparison or feature overview infographic using only confirmed advantages";
            case "package" -> "Package, storage, or variant presentation without invented quantities";
            case "aplus" -> "Amazon A+ content module visual";
            default -> "Amazon Listing product image";
        };
    }

    private boolean isChineseLanguage(String language) {
        return blankToDefault(language, "").toLowerCase().startsWith("zh");
    }

    private boolean isGermanLanguage(String language) {
        return blankToDefault(language, "").toLowerCase().startsWith("de");
    }

    private boolean isFrenchLanguage(String language) {
        return blankToDefault(language, "").toLowerCase().startsWith("fr");
    }

    private String cleanPromptOutput(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        cleaned = cleaned.replaceAll("(?s)^```(?:text|prompt)?\\s*", "").replaceAll("(?s)\\s*```$", "").trim();
        String[] markers = { "优化后的 prompt：", "优化后的 prompt:", "Optimized prompt:", "Final prompt:", "Prompt:" };
        for (String marker : markers) {
            int index = cleaned.toLowerCase().indexOf(marker.toLowerCase());
            if (index >= 0) {
                cleaned = cleaned.substring(index + marker.length()).trim();
                break;
            }
        }
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private String cleanLegacyPromptPollution(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.equals("用户暂未填写商品描述") || cleaned.equals("商品描述はまだ入力されていません") || cleaned.equalsIgnoreCase("No product description provided yet")) {
            return "";
        }
        if ((cleaned.contains("优化后的 prompt") || cleaned.contains("优化后的 Prompt")) && cleaned.contains("用户原始想法")) {
            String marker = "用户原始想法：";
            int start = cleaned.indexOf(marker);
            if (start < 0) {
                marker = "用户原始想法:";
                start = cleaned.indexOf(marker);
            }
            int end = cleaned.indexOf("优化后的", Math.max(0, start));
            if (start >= 0 && end > start) {
                String raw = cleaned.substring(start + marker.length(), end).trim();
                if (!raw.isBlank()) {
                    return raw;
                }
            }
        }
        return cleaned;
    }

    private String textValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? fallback : text;
    }

    private List<String> listValue(Object value, List<String> fallback) {
        if (value instanceof List<?> list) {
            List<String> result = list.stream()
                .map(item -> item == null ? "" : String.valueOf(item).trim())
                .filter(item -> !item.isBlank())
                .toList();
            if (!result.isEmpty()) {
                return result;
            }
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return fallback;
    }

    private String listingTitleValue(Object value, String fallback, boolean hasMainImage) {
        String title = textValue(value, fallback);
        if (!hasMainImage) {
            return title;
        }
        return GENERATED_COPY_TITLE_COLOR_SUFFIX.matcher(title).replaceFirst("").trim();
    }

    private String listingDescriptionValue(Object value, String fallback, boolean hasMainImage) {
        String description = descriptionValue(value, fallback);
        if (!hasMainImage) {
            return description;
        }
        return GENERATED_COPY_COLOR_FIELD.matcher(description).replaceAll("${1}As shown");
    }

    private String descriptionValue(Object value, String fallback) {
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            for (String key : List.of("Feature", "Specification", "Material", "Capacity", "Color", "Package Included")) {
                Object section = map.get(key);
                if (section instanceof List<?> list) {
                    List<String> lines = list.stream()
                        .map(item -> item == null ? "" : String.valueOf(item).trim())
                        .filter(item -> !item.isBlank())
                        .toList();
                    if (!lines.isEmpty()) {
                        parts.add(key + ": " + String.join(" ", lines));
                    }
                } else {
                    String text = textValue(section, "");
                    if (!text.isBlank()) {
                        parts.add(key + ": " + text);
                    }
                }
            }
            if (!parts.isEmpty()) {
                return String.join("\n", parts);
            }
        }
        return textValue(value, fallback == null || fallback.isBlank() ? "Please add product material, dimensions, use cases, and package contents." : fallback);
    }

    private String summarize(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String normalizeImageType(String value) {
        if (value == null) return "main";
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "main";
        int comma = trimmed.indexOf(',');
        return comma >= 0 ? trimmed.substring(0, comma).trim() : trimmed;
    }

    public record ImageBriefSeed(String type, String name, int cost, String requiredFacts, String generationSize, String outputSize) {}

    public record CreativeChatTurn(String role, String content) {}

    public record CreativeChatPlan(String intent, String reply, String sizeHint, double confidence) {}
}
