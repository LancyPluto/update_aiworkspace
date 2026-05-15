package com.aiminilab.aitoolmarket.admin.dto;

import java.math.BigDecimal;
import java.util.List;

public record BillingOverviewResponse(
        long todayPromptTokens,
        long todayCompletionTokens,
        long todayTotalTokens,
        BigDecimal todayCostAmount,
        long todayChargedCredits,
        long todayUsageCount,
        List<ModelCostPoint> modelCosts
) {
    public record ModelCostPoint(
            String provider,
            String modelName,
            long totalTokens,
            BigDecimal costAmount,
            long chargedCredits
    ) {
    }
}
