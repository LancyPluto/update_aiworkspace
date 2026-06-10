package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record ModelOptionGroupResponse(
        String vendorCode,
        String vendorName,
        String iconUrl,
        List<ModelOptionItemResponse> models
) {
}
