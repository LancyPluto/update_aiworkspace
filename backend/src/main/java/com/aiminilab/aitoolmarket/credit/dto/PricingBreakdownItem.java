package com.aiminilab.aitoolmarket.credit.dto;

/**
 * One human-readable contribution to the final price, surfaced to the UI so users understand
 * why a parameter combination costs what it costs.
 */
public record PricingBreakdownItem(String label, String detail, Integer credits) {

    public static PricingBreakdownItem of(String label, String detail, Integer credits) {
        return new PricingBreakdownItem(label, detail, credits);
    }
}
