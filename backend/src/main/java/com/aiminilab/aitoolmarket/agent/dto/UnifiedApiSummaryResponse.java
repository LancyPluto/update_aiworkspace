package com.aiminilab.aitoolmarket.agent.dto;

public record UnifiedApiSummaryResponse(
        int vendorCount,
        int accountCount,
        int modelCount,
        int enabledModelCount,
        int lowBalanceCount,
        int unhealthyAccountCount
) {
}
