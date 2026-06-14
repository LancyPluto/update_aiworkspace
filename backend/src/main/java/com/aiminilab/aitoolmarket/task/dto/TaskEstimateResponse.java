package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.credit.dto.PricingBreakdownItem;

import java.util.List;

/**
 * Authoritative estimate result. {@code variable} marks interactive workflow tools whose final
 * cost depends on per-step model usage (UI shows "算力不详").
 *
 * @param estimatedCredits user-facing credits that will be frozen on submit (0 when variable)
 * @param variable         true for workflow tools billed per step
 * @param availableCredits caller's current available balance
 * @param sufficient       whether the balance covers the estimate
 * @param breakdown        itemised contributions for UI display
 */
public record TaskEstimateResponse(
        int estimatedCredits,
        boolean variable,
        int availableCredits,
        boolean sufficient,
        List<PricingBreakdownItem> breakdown
) {
}
