package com.aiminilab.aitoolmarket.task.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record WorkerSuccessRequest(
        @NotBlank String resourceType,
        @NotBlank String contentText,
        Integer promptTokens,
        Integer completionTokens,
        Integer billableUnits,
        BigDecimal providerCostAmount,
        String providerCostCurrency,
        String providerRequestId,
        String claimToken
) {
    public WorkerSuccessRequest(String resourceType,
                                String contentText,
                                Integer promptTokens,
                                Integer completionTokens,
                                Integer billableUnits,
                                String claimToken) {
        this(resourceType, contentText, promptTokens, completionTokens, billableUnits,
                null, null, null, claimToken);
    }
}
