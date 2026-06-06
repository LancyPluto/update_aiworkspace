package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderDefinition;

import java.util.List;

public record ModelProviderResponse(
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
    public static ModelProviderResponse from(ModelProviderDefinition definition) {
        return new ModelProviderResponse(
                definition.code(),
                definition.label(),
                definition.capabilities(),
                definition.defaultBaseUrl(),
                definition.defaultModel(),
                definition.billingDefault(),
                definition.providerProtocol(),
                definition.vendorKind(),
                definition.upstreamVendor(),
                definition.testStrategy(),
                definition.workerReady(),
                definition.adapterInstalled(),
                definition.adapterKey(),
                definition.metadataVersion(),
                definition.authSchemaJson(),
                definition.modelParamSchemaJson(),
                definition.description()
        );
    }
}
