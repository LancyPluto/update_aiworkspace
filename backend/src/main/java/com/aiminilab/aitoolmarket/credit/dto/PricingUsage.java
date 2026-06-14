package com.aiminilab.aitoolmarket.credit.dto;

/**
 * Actual usage reported by the worker, used at settlement time. When {@code null} is passed to
 * the pricing engine the request is treated as a pre-execution estimate (predicted usage).
 */
public record PricingUsage(Integer promptTokens, Integer completionTokens, Integer billableUnits) {

    public int prompt() {
        return promptTokens == null ? 0 : Math.max(0, promptTokens);
    }

    public int completion() {
        return completionTokens == null ? 0 : Math.max(0, completionTokens);
    }

    public int units() {
        return billableUnits == null ? 0 : Math.max(0, billableUnits);
    }

    public boolean hasTokens() {
        return prompt() > 0 || completion() > 0;
    }

    public boolean hasUnits() {
        return units() > 0;
    }
}
