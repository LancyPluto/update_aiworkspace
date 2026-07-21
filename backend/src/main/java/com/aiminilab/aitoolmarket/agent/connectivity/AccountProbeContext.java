package com.aiminilab.aitoolmarket.agent.connectivity;

public record AccountProbeContext(
        Long accountId,
        String vendorCode,
        String baseUrl,
        String apiKey,
        String extraAuthJson,
        String proxyMode,
        String proxyUrl
) {
}
