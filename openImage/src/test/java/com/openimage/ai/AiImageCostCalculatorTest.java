package com.openimage.ai;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class AiImageCostCalculatorTest {
    @Test
    void localWorkerUsesConfiguredFixedCost() {
        assertThat(AiImageCostCalculator.cost(
            "local-cliproxy-image",
            "",
            "gpt-image-2",
            "1K",
            BigDecimal.ZERO
        )).isEqualByComparingTo("0.001000");
    }

    @Test
    void ruaChatUsesCurrentFixedCnyPrice() {
        BigDecimal cost = AiImageCostCalculator.cost(
            "rua.chat",
            "https://api.rua.chat",
            "gpt-image-2",
            "1600x1600",
            BigDecimal.ZERO
        );

        assertThat(cost).isEqualByComparingTo("0.020000");
    }

    @Test
    void apimartUsesPublishedCreditsPerResolutionTier() {
        assertThat(AiImageCostCalculator.apimartTierCredits("1024x1024")).isEqualByComparingTo("0.085");
        assertThat(AiImageCostCalculator.apimartTierCredits("2048x2048")).isEqualByComparingTo("0.140");
        assertThat(AiImageCostCalculator.apimartTierCredits("2480x3312@4k")).isEqualByComparingTo("0.210");
    }

    @Test
    void apimartCreditsAreConvertedToCny() {
        BigDecimal expected = new BigDecimal("0.210")
            .multiply(AiImageCostCalculator.apimartCreditCnyRate())
            .setScale(6, RoundingMode.HALF_UP);

        assertThat(AiImageCostCalculator.apimartCreditsToCny(new BigDecimal("0.210")))
            .isEqualByComparingTo(expected);
    }

    @Test
    void apimartUsdAndCreditsResolveToTheSameCnyCost() {
        assertThat(AiImageCostCalculator.apimartUsdToCny(new BigDecimal("0.021")))
            .isEqualByComparingTo(AiImageCostCalculator.apimartCreditsToCny(new BigDecimal("0.210")));
    }
}
