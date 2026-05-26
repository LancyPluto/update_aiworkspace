package com.aiminilab.aitoolmarket.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BillingOverviewResponse(
        long todayPromptTokens,
        long todayCompletionTokens,
        long todayTotalTokens,
        BigDecimal todayCostAmount,
        long todayChargedCredits,
        long todayUsageCount,
        List<ModelCostPoint> modelCosts,
        List<UserCostPoint> userCosts,
        List<ModalityCostPoint> modalityCosts,
        List<DailyCostPoint> dailyCosts
) {
    public record ModelCostPoint(
            String provider,
            String modelName,
            long totalTokens,
            BigDecimal costAmount,
            long chargedCredits
    ) {
    }

    public record UserCostPoint(
            Long userId,
            long totalTokens,
            BigDecimal costAmount,
            long chargedCredits,
            long usageCount
    ) {
    }

    public record ModalityCostPoint(
            String modality,
            long totalTokens,
            BigDecimal costAmount,
            long chargedCredits,
            long usageCount
    ) {
    }

    public record DailyCostPoint(
            LocalDate usageDate,
            long totalTokens,
            BigDecimal costAmount,
            long chargedCredits,
            long usageCount
    ) {
    }
}
