package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.support.AgentVisionInputSupport;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;

import java.util.List;

public record DiscoveredModelConfigResponse(
        Long id,
        String configCode,
        String provider,
        String modelName,
        List<String> capabilities
) {
    public static DiscoveredModelConfigResponse from(AgentModelConfig config, ModelCapabilitiesCodec codec) {
        return new DiscoveredModelConfigResponse(
                config.getId(),
                config.getConfigCode(),
                config.getProvider(),
                config.getModelName(),
                AgentVisionInputSupport.withInferredVisionInput(
                        config.getProvider(),
                        config.getModelName(),
                        config.getBaseUrl(),
                        codec.parse(config.getCapabilities())
                )
        );
    }
}
