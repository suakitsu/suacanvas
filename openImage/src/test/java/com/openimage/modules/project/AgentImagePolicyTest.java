package com.openimage.modules.project;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentImagePolicyTest {
    @Test
    void casualChatDoesNotGenerateOrCharge() {
        AgentImagePolicy.Decision decision = AgentImagePolicy.decide("你好，你是谁？", "", false, false);
        AgentImagePolicy.ImagePlan plan = decision.shouldGenerateImage()
            ? AgentImagePolicy.imagePlan("你好，你是谁？", "")
            : AgentImagePolicy.chatPlan();

        assertFalse(decision.shouldGenerateImage());
        assertEquals(0, plan.creditsCost());
    }

    @Test
    void pricingQuestionWithFourKDoesNotGenerate() {
        AgentImagePolicy.Decision decision = AgentImagePolicy.decide("你们支持 4K 吗，怎么收费？", "", false, false);

        assertFalse(decision.shouldGenerateImage());
    }

    @Test
    void eightKImageRequestDowngradesToFourKPrice() {
        AgentImagePolicy.Decision decision = AgentImagePolicy.decide("生成 8K lolita 兔子玩偶包场景图", "", false, false);
        AgentImagePolicy.ImagePlan plan = AgentImagePolicy.imagePlan("生成 8K lolita 兔子玩偶包场景图", "");

        assertTrue(decision.shouldGenerateImage());
        assertEquals("4K", plan.qualityLabel());
        assertEquals("2880x2880", plan.size());
        assertEquals(30, plan.creditsCost());
    }

    @Test
    void hdAndSuperClearImageRequestsUseTwoKPrice() {
        AgentImagePolicy.Decision hdDecision = AgentImagePolicy.decide("HD product photo of a rabbit bag", "", false, false);
        AgentImagePolicy.ImagePlan hdPlan = AgentImagePolicy.imagePlan("HD product photo of a rabbit bag", "");
        AgentImagePolicy.Decision clearDecision = AgentImagePolicy.decide("超清 lolita 兔子包场景图", "", false, false);
        AgentImagePolicy.ImagePlan clearPlan = AgentImagePolicy.imagePlan("超清 lolita 兔子包场景图", "");

        assertTrue(hdDecision.shouldGenerateImage());
        assertEquals("2K", hdPlan.qualityLabel());
        assertEquals(24, hdPlan.creditsCost());
        assertTrue(clearDecision.shouldGenerateImage());
        assertEquals("2K", clearPlan.qualityLabel());
        assertEquals(24, clearPlan.creditsCost());
    }

    @Test
    void creativeSizePriceHasServerSideFloor() {
        assertEquals(6, AgentImagePolicy.creditsForCreativeSize("1024x1024"));
        assertEquals(24, AgentImagePolicy.creditsForCreativeSize("2048x2048"));
        assertEquals(30, AgentImagePolicy.creditsForCreativeSize("2880x2880"));
    }

    @Test
    void domesticReferenceProductImageRequestGeneratesImage() {
        AgentImagePolicy.Decision decision = AgentImagePolicy.decide("讲这个作为国内小红书商品图", "", true, false);

        assertTrue(decision.shouldGenerateImage());
    }

    @Test
    void clarificationAnswerInheritsPreviousImageIntent() {
        boolean followUp = AgentImagePolicy.isGenerationFollowUp(
            "女性 成年 蓝灰金服",
            "二次元 cosplay 角色，原创人物设计，精致动漫图，画面五官清晰，服装材质细节丰富",
            "请补充角色性别与年龄感、主题职业、发型发色、服装款式、主色调、动作、场景和画面比例后，我就可以为你生成。"
        );

        assertTrue(followUp);
    }

    @Test
    void explicitCancellationDoesNotInheritImageIntent() {
        boolean followUp = AgentImagePolicy.isGenerationFollowUp(
            "先不生成了",
            "请生成一张女性 cosplay 角色图",
            "请补充服装和场景后，我就可以生成。"
        );

        assertFalse(followUp);
    }

    @Test
    void shortVisualBriefCanGenerateWithoutRepeatingCommand() {
        AgentImagePolicy.Decision decision = AgentImagePolicy.decide("女性 成年 蓝灰金服", "", false, false);

        assertTrue(decision.shouldGenerateImage());
    }
}
