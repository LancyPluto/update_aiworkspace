package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record UnifiedApiUnconfiguredVendorResponse(
        String vendorCode,
        String label,
        String iconAsset,
        List<String> supportedProviders
) {
}
