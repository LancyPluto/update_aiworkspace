package com.aiminilab.aitoolmarket.workflow.dto;

public record WorkflowToolSummaryResponse(
        Long id,
        String toolCode,
        String toolName,
        String description,
        String categoryName,
        String coverUrl,
        String status,
        Integer estimatedCreditCost,
        Integer minimumRequiredCredits,
        Boolean variableCreditPricing,
        String adapterKey
) {
}
