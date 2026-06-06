package com.aiminilab.aitoolmarket.agent.config;

import java.util.List;

public record ModelProviderDefinition(
        String code,
        String label,
        List<String> capabilities,
        String defaultBaseUrl,
        String defaultModel,
        String billingDefault,
        String providerProtocol,
        String vendorKind,
        String upstreamVendor,
        String testStrategy,
        boolean workerReady,
        boolean adapterInstalled,
        String adapterKey,
        String metadataVersion,
        String authSchemaJson,
        String modelParamSchemaJson,
        String description
) {
}
