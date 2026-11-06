package com.openimage.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public final class AiImageCostCalculator {
    private static final BigDecimal DEFAULT_APIMART_CREDIT_CNY_RATE = new BigDecimal("0.679110");
    private static final BigDecimal APIMART_1K_CREDITS = new BigDecimal("0.085");
    private static final BigDecimal APIMART_2K_CREDITS = new BigDecimal("0.140");
    private static final BigDecimal APIMART_4K_CREDITS = new BigDecimal("0.210");

    private AiImageCostCalculator() {}

    public static BigDecimal cost(String provider, String baseUrl, String model, String size, BigDecimal fallback) {
        String text = ((provider == null ? "" : provider) + " " + (baseUrl == null ? "" : baseUrl) + " " + (model == null ? "" : model)).toLowerCase(Locale.ROOT);
        String tier = tier(size);
        BigDecimal cost = null;
        if (text.contains("local-cliproxy-image")) {
            cost = AiRuntimeConfigService.LOCAL_IMAGE_COST_CNY;
        } else if (text.contains("rua") || text.contains("rua.chat")) {
            cost = new BigDecimal("0.020");
        } else if (text.contains("xin-zhi-zhu") || text.contains("xinzhizhu") || text.contains("xinzhi")) {
            cost = new BigDecimal("0.020");
        } else if (text.contains("yunfei") || text.contains("img.yunfei.best")) {
            cost = "1k".equals(tier) ? new BigDecimal("0.010") : new BigDecimal("0.100");
        } else if (text.contains("hfsyapi")) {
            cost = "1k".equals(tier) ? new BigDecimal("0.030") : new BigDecimal("0.200");
        } else if (text.contains("aishuch") || text.contains("apimart")) {
            cost = apimartCreditsToCny(apimartTierCredits(size));
        }
        if (cost == null) {
            cost = fallback == null ? BigDecimal.ZERO : fallback;
        }
        return cost.setScale(6, RoundingMode.HALF_UP);
    }

    public static BigDecimal apimartCreditsToCny(BigDecimal credits) {
        if (credits == null || credits.signum() < 0) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        return credits.multiply(apimartCreditCnyRate()).setScale(6, RoundingMode.HALF_UP);
    }

    public static BigDecimal apimartUsdToCny(BigDecimal usd) {
        if (usd == null || usd.signum() < 0) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        return usd.multiply(apimartUsdCnyRate()).setScale(6, RoundingMode.HALF_UP);
    }

    public static BigDecimal apimartTierCredits(String size) {
        return switch (tier(size)) {
            case "4k" -> APIMART_4K_CREDITS;
            case "2k" -> APIMART_2K_CREDITS;
            default -> APIMART_1K_CREDITS;
        };
    }

    public static BigDecimal apimartCreditCnyRate() {
        String configured = System.getProperty("openimage.apimart.credit-cny-rate");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("APIMART_CREDIT_CNY_RATE");
        }
        if (configured != null && !configured.isBlank()) {
            try {
                BigDecimal value = new BigDecimal(configured.trim());
                if (value.signum() > 0 && value.compareTo(BigDecimal.TEN) <= 0) {
                    return value.setScale(6, RoundingMode.HALF_UP);
                }
            } catch (NumberFormatException ignored) {
                // Invalid overrides fall back to the reviewed default rate.
            }
        }
        return DEFAULT_APIMART_CREDIT_CNY_RATE;
    }

    public static BigDecimal apimartUsdCnyRate() {
        String configured = System.getProperty("openimage.apimart.usd-cny-rate");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("APIMART_USD_CNY_RATE");
        }
        if (configured != null && !configured.isBlank()) {
            try {
                BigDecimal value = new BigDecimal(configured.trim());
                if (value.signum() > 0 && value.compareTo(new BigDecimal("20")) <= 0) {
                    return value.setScale(6, RoundingMode.HALF_UP);
                }
            } catch (NumberFormatException ignored) {
                // Invalid overrides fall back to the reviewed credit conversion.
            }
        }
        return apimartCreditCnyRate().multiply(BigDecimal.TEN).setScale(6, RoundingMode.HALF_UP);
    }

    public static String tier(String size) {
        if (size == null || size.isBlank()) {
            return "1k";
        }
        String normalized = size.toLowerCase(Locale.ROOT);
        if (normalized.contains("4k")) {
            return "4k";
        }
        if (normalized.contains("2k")) {
            return "2k";
        }
        int max = maxDimension(normalized);
        if (max >= 2800) {
            return "4k";
        }
        if (max >= 1800) {
            return "2k";
        }
        return "1k";
    }

    private static int maxDimension(String value) {
        int max = 0;
        for (String part : value.split("[^0-9]+")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
                // Ignore non-pixel fragments.
            }
        }
        return max;
    }
}
