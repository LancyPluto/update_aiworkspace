package com.aiminilab.aitoolmarket.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record PricingRuleUpsertRequest(
        Long id,
        @NotBlank String scopeType,
        Long scopeRef,
        @NotBlank String paramKey,
        String ruleType,
        String matchOp,
        String matchValue,
        BigDecimal factor,
        Integer extraCredits,
        Integer priority,
        Boolean enabled,
        String remark
) {
}
