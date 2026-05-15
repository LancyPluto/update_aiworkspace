package com.aiminilab.aitoolmarket.commerce.service;

public record ModelGatewayResult(
        String content,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer estimatedCostCents,
        String responseMetadataJson
) {
}
