package com.aiminilab.aitoolmarket.market.support;

import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class MarketModelConfigBridge {

    private final ModelCapabilitiesCodec capabilitiesCodec;

    public MarketModelConfigBridge(ObjectMapper objectMapper) {
        this.capabilitiesCodec = new ModelCapabilitiesCodec(objectMapper);
    }

    public AgentModelConfigRequest toRequest(AgentModelConfig config) {
        return new AgentModelConfigRequest(
                config.getDisplayName(),
                config.getConfigCode(),
                config.getProvider(),
                config.getModelName(),
                config.getBaseUrl(),
                config.getApiKey(),
                config.getExtraAuthJson(),
                config.getMinimaxGroupId(),
                config.getConsoleUrl(),
                config.getBalanceUrl(),
                config.getDocsUrl(),
                config.getTimeoutSeconds(),
                config.getInputTokenPricePer1k(),
                config.getOutputTokenPricePer1k(),
                config.getInputTokenPricePer1m(),
                config.getOutputTokenPricePer1m(),
                config.getBillingUnit(),
                config.getUnitPrice(),
                config.getEnabled(),
                config.getDefault(),
                capabilitiesCodec.parse(config.getCapabilities())
        );
    }
}
