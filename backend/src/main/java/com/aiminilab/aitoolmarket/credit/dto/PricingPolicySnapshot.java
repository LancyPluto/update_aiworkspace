package com.aiminilab.aitoolmarket.credit.dto;

import java.math.BigDecimal;
import java.util.List;

public record PricingPolicySnapshot(
        BigDecimal markupRatio,
        int minCredits,
        int imageEstimateInputTokens,
        int imageEstimateOutputTokens,
        int tokenEstimateInputTokens,
        int tokenEstimateOutputTokens,
        List<PricingRuleSnapshot> rules
) {
}
