package com.aiminilab.aitoolmarket.agent.config;

import java.util.List;

public record ModelProviderDefinition(
        String code,
        String label,
        List<String> capabilities,
        String defaultBaseUrl,
        String defaultModel,
        String billingDefault,
        String testStrategy,
        boolean workerReady,
        String description
) {
}
