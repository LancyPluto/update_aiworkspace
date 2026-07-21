package com.aiminilab.aitoolmarket.agent.connectivity;

import java.util.List;

public record ModelProbeContext(
        Long modelConfigId,
        String provider,
        String modelName,
        List<String> capabilities,
        String baseUrl,
        String apiKey,
        String extraAuthJson
) {
    public ModelProbeContext {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
