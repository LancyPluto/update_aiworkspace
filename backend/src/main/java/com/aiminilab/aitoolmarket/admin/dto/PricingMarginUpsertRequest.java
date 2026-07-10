package com.aiminilab.aitoolmarket.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record PricingMarginUpsertRequest(
        Long id,
        @NotBlank String scopeType,
        Long scopeRef,
        BigDecimal markupRatio,
        Integer minCredits,
        Integer imageEstimateInputTokens,
        Integer imageEstimateOutputTokens,
        Boolean enabled,
        String remark
) {
}
