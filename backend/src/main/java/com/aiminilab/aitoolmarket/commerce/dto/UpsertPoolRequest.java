package com.aiminilab.aitoolmarket.commerce.dto;

public record UpsertPoolRequest(
        String poolCode,
        String poolName,
        String provider,
        String modelName,
        String specLabel,
        String description,
        String status,
        Integer sortOrder
) {
}
