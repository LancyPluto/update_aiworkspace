package com.aiminilab.aitoolmarket.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BillingUsageLogResponse(
        Long id,
        String sourceType,
        Long sourceId,
        String taskNo,
        String inputModality,
        String outputModality,
        Long userId,
        Long modelConfigId,
        String provider,
        String modelName,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        BigDecimal inputTokenPricePer1k,
        BigDecimal outputTokenPricePer1k,
        BigDecimal inputTokenPricePer1m,
        BigDecimal outputTokenPricePer1m,
        String billingUnit,
        Integer billableUnits,
        BigDecimal unitPrice,
        BigDecimal costAmount,
        BigDecimal vendorCostAmount,
        String providerCostCurrency,
        String providerRequestId,
        Boolean providerCharged,
        Integer chargedCredits,
        LocalDateTime createdAt
) {
}
