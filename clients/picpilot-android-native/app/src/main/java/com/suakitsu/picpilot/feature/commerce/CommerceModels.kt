package com.suakitsu.picpilot.feature.commerce

import androidx.compose.runtime.Immutable

enum class CommerceWizardStep(val number: Int) {
    PRODUCT(1),
    PLATFORM(2),
    REVIEW(3),
}

enum class CommerceCreationKind(val code: String, val title: String, val shortDescription: String) {
    AMAZON_LISTING(
        code = "amazon_listing",
        title = "Amazon Listing",
        shortDescription = "主图、卖点、尺寸、场景与配件图",
    ),
    AMAZON_A_PLUS(
        code = "amazon_a_plus",
        title = "Amazon A+",
        shortDescription = "品牌故事、核心卖点与细节模块",
    ),
    PLATFORM_SUITE(
        code = "platform_suite",
        title = "平台套图",
        shortDescription = "按目标平台一次规划整套商品图",
    ),
    IMAGE_TRANSLATION(
        code = "image_translation",
        title = "图片翻译",
        shortDescription = "保留版式，仅替换图片中的语言",
    ),
    DOMESTIC_ECOMMERCE(
        code = "domestic_ecommerce",
        title = "国内电商",
        shortDescription = "淘宝、京东、拼多多、抖音与小红书",
    ),
}

enum class CommercePlatform(val code: String, val title: String) {
    AMAZON("amazon", "Amazon"),
    TAOBAO_TMALL("taobao_tmall", "淘宝 / 天猫"),
    JD("jd", "京东"),
    PINDUODUO("pinduoduo", "拼多多"),
    DOUYIN("douyin", "抖音电商"),
    XIAOHONGSHU("xiaohongshu", "小红书"),
    TIKTOK_SHOP("tiktok_shop", "TikTok Shop"),
    SHOPEE("shopee", "Shopee"),
    LAZADA("lazada", "Lazada"),
    SHOPIFY("shopify", "Shopify"),
}

enum class CommerceResolution(
    val code: String,
    val title: String,
    val detail: String,
) {
    ONE_K("1k", "1K", "1024 级输出"),
    TWO_K("2k", "2K", "2048 级输出"),
    FOUR_K("4k", "4K", "高精度输出"),
}

enum class CommerceDeliverable(
    val code: String,
    val title: String,
    val description: String,
) {
    MAIN_IMAGE("main_image", "商品主图", "主体清晰，适合作为商品入口"),
    SELLING_POINT("selling_point", "核心卖点图", "聚焦一项真实卖点"),
    SCENE("scene", "使用场景图", "展示真实使用环境与人群"),
    DETAIL("detail", "产品细节图", "材质、结构与局部工艺"),
    SPECIFICATION("specification", "规格参数图", "尺寸、结构或参数区域"),
    COMPARISON("comparison", "对比图", "仅使用已确认的优势"),
    PACKAGING("packaging", "包装清单图", "包装、配件与发货内容"),
    CROWD("crowd", "目标人群图", "目标用户与生活方式"),
    BRAND_STORY("brand_story", "品牌故事模块", "统一品牌氛围与产品定位"),
    USAGE_STEPS("usage_steps", "使用流程模块", "清楚呈现操作步骤"),
    FAQ("faq", "FAQ 模块", "结构化回答高频问题"),
    TRUST("trust", "信任模块", "材质、服务与合规信息"),
}

enum class CommerceLanguage(val code: String, val title: String) {
    AUTO("auto", "自动识别"),
    ZH_CN("zh-CN", "简体中文"),
    EN("en", "English"),
    MS("ms", "Bahasa Melayu"),
    JA("ja", "日本語"),
    KO("ko", "한국어"),
    TH("th", "ไทย"),
    VI("vi", "Tiếng Việt"),
    ID("id", "Bahasa Indonesia"),
    ES("es", "Español"),
    FR("fr", "Français"),
    DE("de", "Deutsch"),
}

@Immutable
data class CommerceLanguageOption(
    val code: String,
    val title: String,
    val nativeName: String = "",
)

@Immutable
data class CommerceReferenceImage(
    val localId: String,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
) {
    val readableSize: String
        get() = when {
            sizeBytes <= 0L -> "大小未知"
            sizeBytes < 1024L -> "$sizeBytes B"
            sizeBytes < 1024L * 1024L -> "%.1f KB".format(sizeBytes / 1024.0)
            else -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
        }

    val readableDimensions: String
        get() = if (width > 0 && height > 0) "${width}×${height}" else "尺寸读取中"
}

@Immutable
data class CommerceQuote(
    val outputCount: Int,
    val creditsPerImage: Int,
    val totalCredits: Int,
    val isServerQuote: Boolean,
) {
    val sourceLabel: String
        get() = if (isServerQuote) "服务端实时价格" else "非权威价格"
}

enum class CommerceQuotePhase {
    IDLE,
    LOADING,
    READY,
    UNAVAILABLE,
    ERROR,
}

enum class CommerceTaskPhase {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
}

enum class CommerceRefundState {
    NOT_APPLICABLE,
    NOT_REPORTED,
}

@Immutable
data class CommerceTaskProgress(
    val id: String,
    val name: String,
    val phase: CommerceTaskPhase,
    val resultUrl: String = "",
    val creditsCost: Int = 0,
    val safeError: String = "",
    val refundState: CommerceRefundState = CommerceRefundState.NOT_APPLICABLE,
)

enum class CommerceSubmissionPhase {
    IDLE,
    AWAITING_CONFIRMATION,
    VERIFYING_QUOTE,
    SUBMITTING,
    SUBMITTED,
    ERROR,
}

@Immutable
data class CommerceUiState(
    val step: CommerceWizardStep = CommerceWizardStep.PRODUCT,
    val kind: CommerceCreationKind = CommerceCreationKind.PLATFORM_SUITE,
    val productName: String = "",
    val category: String = "",
    val brand: String = "",
    val productFacts: String = "",
    val coreSellingPoints: String = "",
    val referenceImages: List<CommerceReferenceImage> = emptyList(),
    val isReadingImages: Boolean = false,
    val imageError: String? = null,
    val platform: CommercePlatform = CommercePlatform.TAOBAO_TMALL,
    val resolution: CommerceResolution = CommerceResolution.ONE_K,
    val deliverables: Set<CommerceDeliverable> = defaultSuiteDeliverables,
    val sourceLanguage: CommerceLanguage = CommerceLanguage.AUTO,
    val targetLanguageCode: String = "",
    val translationLanguages: List<CommerceLanguageOption> = emptyList(),
    val preserveLayout: Boolean = true,
    val customRequirements: String = "",
    val quote: CommerceQuote? = null,
    val quotePhase: CommerceQuotePhase = CommerceQuotePhase.IDLE,
    val quoteMessage: String? = null,
    val quoteKey: String? = null,
    val catalogUpdatedAt: String = "",
    val availablePlatformCodes: Set<String> = emptySet(),
    val availableDeliverableCodes: Set<String> = emptySet(),
    val isConfigurationLoading: Boolean = false,
    val fieldErrors: Map<String, String> = emptyMap(),
    val submissionPhase: CommerceSubmissionPhase = CommerceSubmissionPhase.IDLE,
    val submissionMessage: String? = null,
    val submittedTaskId: String? = null,
    val availableCredits: Int? = null,
    val isBalanceLoading: Boolean = false,
    val projectStatus: String? = null,
    val projectTasks: List<CommerceTaskProgress> = emptyList(),
    val projectTotalCredits: Int = 0,
    val isProjectRefreshing: Boolean = false,
    val projectTerminal: Boolean = false,
) {
    val canGoBack: Boolean get() = step != CommerceWizardStep.PRODUCT
    val isSubmitting: Boolean get() = submissionPhase == CommerceSubmissionPhase.VERIFYING_QUOTE || submissionPhase == CommerceSubmissionPhase.SUBMITTING
    val pricingReady: Boolean
        get() = quotePhase == CommerceQuotePhase.READY && quote?.isServerQuote == true && availableCredits != null

    val targetLanguageTitle: String
        get() = translationLanguages.firstOrNull { it.code == targetLanguageCode }?.title
            ?: targetLanguageCode.ifBlank { "尚未加载" }
}

@Immutable
data class CommerceSubmitRequest(
    val kind: String,
    val productName: String,
    val category: String,
    val brand: String,
    val productFacts: String,
    val coreSellingPoints: String,
    val inputImages: List<CommerceInputImage>,
    val platform: String,
    val resolution: String,
    val deliverables: List<String>,
    val sourceLanguage: String?,
    val targetLanguage: String?,
    val preserveLayout: Boolean,
    val customRequirements: String,
    val expectedOutputCount: Int,
    val quotedCredits: Int,
)

@Immutable
data class CommerceInputImage(
    val localId: String,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val order: Int,
)

sealed interface CommerceSubmissionResult {
    @Immutable
    data class Accepted(val taskId: String, val chargedCredits: Int) : CommerceSubmissionResult

    @Immutable
    data class Rejected(val userMessage: String, val retryable: Boolean) : CommerceSubmissionResult

    data object NotConnected : CommerceSubmissionResult
}

typealias CommerceSubmitter = suspend (CommerceSubmitRequest) -> CommerceSubmissionResult

internal val defaultSuiteDeliverables = setOf(
    CommerceDeliverable.MAIN_IMAGE,
    CommerceDeliverable.SELLING_POINT,
    CommerceDeliverable.SCENE,
    CommerceDeliverable.DETAIL,
    CommerceDeliverable.SPECIFICATION,
    CommerceDeliverable.COMPARISON,
    CommerceDeliverable.PACKAGING,
    CommerceDeliverable.CROWD,
)

internal val amazonListingDeliverables = setOf(
    CommerceDeliverable.MAIN_IMAGE,
    CommerceDeliverable.SELLING_POINT,
    CommerceDeliverable.SPECIFICATION,
    CommerceDeliverable.SCENE,
    CommerceDeliverable.DETAIL,
    CommerceDeliverable.COMPARISON,
    CommerceDeliverable.PACKAGING,
)

internal val amazonAPlusDeliverables = setOf(
    CommerceDeliverable.BRAND_STORY,
    CommerceDeliverable.SELLING_POINT,
    CommerceDeliverable.DETAIL,
    CommerceDeliverable.USAGE_STEPS,
    CommerceDeliverable.COMPARISON,
    CommerceDeliverable.FAQ,
    CommerceDeliverable.TRUST,
)

internal val domesticDeliverables = setOf(
    CommerceDeliverable.MAIN_IMAGE,
    CommerceDeliverable.SELLING_POINT,
    CommerceDeliverable.SCENE,
    CommerceDeliverable.DETAIL,
    CommerceDeliverable.SPECIFICATION,
    CommerceDeliverable.COMPARISON,
    CommerceDeliverable.PACKAGING,
    CommerceDeliverable.CROWD,
)
