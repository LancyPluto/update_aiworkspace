package com.aiminilab.aitoolmarket.market.dto;

import java.util.Map;

public record CapabilityDto(
        String type,
        Map<String, Object> config
) {
}
