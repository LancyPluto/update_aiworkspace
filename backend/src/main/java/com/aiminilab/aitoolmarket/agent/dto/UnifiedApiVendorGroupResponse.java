package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record UnifiedApiVendorGroupResponse(
        String vendorCode,
        String label,
        String iconAsset,
        List<String> supportedProviders,
        List<ModelVendorAccountResponse> accounts,
        List<UnifiedApiModelItemResponse> models
) {
}
