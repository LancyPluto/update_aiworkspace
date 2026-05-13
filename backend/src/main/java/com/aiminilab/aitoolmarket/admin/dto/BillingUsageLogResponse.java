package com.aiminilab.aitoolmarket.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BillingUsageLogResponse(
        Long id,
        String sourceType,
        Long sourceId,
        Long userId,
        Long modelConfigId,
        String provider,
        String modelName,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        BigDecimal inputTokenPricePer1k,
        BigDecimal outputTokenPricePer1k,
        BigDecimal costAmount,
        Integer chargedCredits,
        LocalDateTime createdAt
) {
}
