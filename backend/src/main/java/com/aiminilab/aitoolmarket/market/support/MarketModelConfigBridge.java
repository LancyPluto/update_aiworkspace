package com.aiminilab.aitoolmarket.market.support;

import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.ModelConfigCredentialResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class MarketModelConfigBridge {

    private final ModelCapabilitiesCodec capabilitiesCodec;
    private final ModelConfigCredentialResolver credentialResolver;

    public MarketModelConfigBridge(ObjectMapper objectMapper,
                                   ModelConfigCredentialResolver credentialResolver) {
        this.capabilitiesCodec = new ModelCapabilitiesCodec(objectMapper);
        this.credentialResolver = credentialResolver;
    }

    public AgentModelConfigRequest toRequest(AgentModelConfig config) {
        AgentModelConfig resolved = credentialResolver.resolveForExecution(config);
        return new AgentModelConfigRequest(
                resolved.getVendorAccountId(),
                config.getRoutingPoolId(),
                resolved.getDisplayName(),
                config.getConfigCode(),
                resolved.getProvider(),
                resolved.getModelName(),
                resolved.getBaseUrl(),
                resolved.getApiKey(),
                null,
                resolved.getExtraAuthJson(),
                resolved.getExecutionTask(),
                resolved.getExecutionOptionsJson(),
                resolved.getRequestSchemaJson(),
                resolved.getRequestMappingJson(),
                resolved.getResponseMappingJson(),
                resolved.getApiContractVersion(),
                resolved.getContractStatus(),
                resolved.getContractVerifiedAt(),
                resolved.getMinimaxGroupId(),
                resolved.getConsoleUrl(),
                resolved.getBalanceUrl(),
                resolved.getDocsUrl(),
                resolved.getTimeoutSeconds(),
                null,
                null,
                resolved.getInputTokenPricePer1k(),
                resolved.getOutputTokenPricePer1k(),
                resolved.getInputTokenPricePer1m(),
                resolved.getOutputTokenPricePer1m(),
                resolved.getBillingUnit(),
                resolved.getUnitPrice(),
                resolved.getEnabled(),
                resolved.getAgentEnabled(),
                resolved.getDefault(),
                capabilitiesCodec.parse(resolved.getCapabilities()),
                null,
                null
        );
    }
}
