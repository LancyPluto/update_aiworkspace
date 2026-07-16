package com.aiminilab.aitoolmarket.credit.dto;

import java.math.BigDecimal;

public record PricingRuleSnapshot(
        String paramKey,
        String ruleType,
        String matchOp,
        String matchValue,
        BigDecimal factor,
        Integer extraCredits,
        Integer priority
) {
}
