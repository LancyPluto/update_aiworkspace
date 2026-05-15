package com.aiminilab.aitoolmarket.commerce.dto;

public record UpsertNodeRequest(
        Long poolId,
        String nodeCode,
        String displayLabel,
        String providerProtocol,
        String baseUrl,
        String apiKey,
        String modelName,
        Integer timeoutSeconds,
        Integer maxConcurrency,
        Integer hourlyLimit,
        Integer dailyLimit,
        String status,
        String healthStatus,
        String note
) {
}
