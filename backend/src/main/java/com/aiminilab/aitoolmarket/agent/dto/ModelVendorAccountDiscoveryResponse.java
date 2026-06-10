package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record ModelVendorAccountDiscoveryResponse(
        int imported,
        int updated,
        int skipped,
        int discovered,
        String message,
        List<DiscoveredModelConfigResponse> models
) {
}
