package com.openimage.modules.project;

import com.openimage.ai.AiImageSizes;

final class ImageCreditPricing {
    static final int ONE_K_CREDITS = 6;
    static final int TWO_K_CREDITS = 24;
    static final int FOUR_K_CREDITS = 30;

    private ImageCreditPricing() {}

    static int creditsForSize(String size) {
        AiImageSizes.Size parsed = AiImageSizes.parse(size);
        if (parsed == null) {
            return ONE_K_CREDITS;
        }
        int max = Math.max(parsed.width(), parsed.height());
        if (max >= 2800) {
            return FOUR_K_CREDITS;
        }
        if (max >= 1800) {
            return TWO_K_CREDITS;
        }
        return ONE_K_CREDITS;
    }
}
