package com.openimage.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreativePromptPolicyTest {
    @Test
    void detectsExplicitCrossReferenceReplacementButNotNegatedReplacement() {
        assertTrue(CreativePromptPolicy.requestsReferenceReplacement(
            "以第一张参考图为构图基准，仅将原产品替换为第二张参考图中的商品本体"
        ));
        assertFalse(CreativePromptPolicy.requestsReferenceReplacement(
            "保持第一张参考图中的商品，不要将它替换为第二张参考图里的商品"
        ));
    }

    @Test
    void detectsSeparateRolesForProductAndStyleReferences() {
        assertTrue(CreativePromptPolicy.hasExplicitReferenceRoles(
            "第一张参考图负责商品包装，第二张参考图只参考海报背景、构图和光线"
        ));
        assertTrue(CreativePromptPolicy.hasExplicitReferenceRoles(
            "第3张作为商品身份，第12张负责构图，第27张只参考背景风格"
        ));
        assertEquals(
            java.util.List.of(3, 12, 27),
            CreativePromptPolicy.referencedImageIndexes("第3张作为商品身份，第12张负责构图，第27张只参考背景风格")
        );
    }

    @Test
    void supportsNoVisualInputAndUploadedImagesThatMustBeIgnored() {
        assertTrue(CreativePromptPolicy.ignoresAllReferences("图片我上传了，但不用参考图，直接按文字生成"));
        assertTrue(CreativePromptPolicy.ignoresAllReferences("这些上传图片仅作展示，不作为参考"));
        assertFalse(CreativePromptPolicy.ignoresAllReferences("不要参考图中的文字，只参考商品外形"));
    }

    @Test
    void negativeTextRequestDoesNotBecomeAnAddTextRequest() {
        assertEquals(
            CreativePromptPolicy.VisibleTextIntent.NO_NEW_TEXT,
            CreativePromptPolicy.visibleTextIntent("电商海报，不要添加任何大字标语、品牌文字、二维码或随机文字")
        );
    }

    @Test
    void packagingTextPreservationIsSeparatedFromNewCopy() {
        assertEquals(
            CreativePromptPolicy.VisibleTextIntent.PRESERVE_EXISTING_TEXT,
            CreativePromptPolicy.visibleTextIntent(
                "包装盒图案、文字严格和第一张原图保持一致，禁止修改盒子任何图文，画面不要添加大标题文案"
            )
        );
    }

    @Test
    void explicitProvidedTitleCanStillBeRendered() {
        assertEquals(
            CreativePromptPolicy.VisibleTextIntent.ADD_EXACT_TEXT,
            CreativePromptPolicy.visibleTextIntent("添加标题：“轻盈收纳”，不要添加其他文字")
        );
    }

    @Test
    void removesOnlyRecognizedMachineGeneratedIntentSuffix() {
        String core = "以第一张参考图保留构图，把产品替换为第二张参考图中的商品。";
        String polluted = core + """


            Intent and reference constraints:
            Reference image is the source of truth. Do not replace the product.
            Do not render readable text, logos, watermarks, labels, QR codes, or platform UI unless explicitly requested.
            """;

        assertEquals(core, CreativePromptPolicy.cleanUserPrompt(polluted));
        assertEquals(
            core + "\nIntent and reference constraints:\n这是用户自己写的说明。",
            CreativePromptPolicy.cleanUserPrompt(core + "\nIntent and reference constraints:\n这是用户自己写的说明。")
        );
    }
}
