package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record UnifiedApiOverviewResponse(
        UnifiedApiSummaryResponse summary,
        List<UnifiedApiVendorGroupResponse> vendors,
        List<UnifiedApiUnconfiguredVendorResponse> unconfiguredVendors
) {
}
