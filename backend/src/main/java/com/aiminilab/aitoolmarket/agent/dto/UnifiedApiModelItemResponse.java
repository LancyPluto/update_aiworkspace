package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;

import java.util.List;

public record UnifiedApiModelItemResponse(
        Long id,
        Long vendorAccountId,
        String vendorAccountName,
        String displayName,
        String configCode,
        String provider,
        String modelName,
        List<String> capabilities,
        Boolean enabled,
        Boolean agentEnabled,
        Boolean isDefault,
        String healthStatus
) {
    public static UnifiedApiModelItemResponse from(AgentModelConfig config,
                                                   String vendorAccountName,
                                                   ModelCapabilitiesCodec codec) {
        return new UnifiedApiModelItemResponse(
                config.getId(),
                config.getVendorAccountId(),
                vendorAccountName,
                config.getDisplayName(),
                config.getConfigCode(),
                config.getProvider(),
                config.getModelName(),
                codec.parse(config.getCapabilities()),
                config.getEnabled(),
                config.getAgentEnabled(),
                config.getDefault(),
                Boolean.FALSE.equals(config.getEnabled()) ? "DISABLED" : "OK"
        );
    }
}
