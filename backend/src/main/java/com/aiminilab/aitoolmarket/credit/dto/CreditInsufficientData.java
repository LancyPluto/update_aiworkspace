package com.aiminilab.aitoolmarket.credit.dto;

public record CreditInsufficientData(
        int availableCredits,
        int requiredCredits,
        String toolCode
) {
    public static CreditInsufficientData of(int availableCredits, int requiredCredits) {
        return new CreditInsufficientData(availableCredits, requiredCredits, null);
    }

    public static CreditInsufficientData of(int availableCredits, int requiredCredits, String toolCode) {
        return new CreditInsufficientData(availableCredits, requiredCredits, toolCode);
    }
}
