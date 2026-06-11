package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.support.ImageGenerationParameterResolver;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;

import java.math.BigDecimal;
import java.util.List;

public record ModelOptionItemResponse(
        Long id,
        Long modelConfigId,
        String configCode,
        String displayName,
        String modelName,
        String provider,
        String vendorCode,
        String vendorName,
        List<String> capabilities,
        ImageGenerationParametersResponse imageParameters,
        BigDecimal unitPrice,
        String billingUnit,
        Boolean isDefault
) {
    public static ModelOptionItemResponse from(AgentModelConfig config,
                                               String vendorCode,
                                               String vendorName,
                                               ModelCapabilitiesCodec codec,
                                               ImageGenerationParameterResolver imageParameterResolver) {
        return new ModelOptionItemResponse(
                config.getId(),
                config.getId(),
                config.getConfigCode(),
                config.getDisplayName(),
                config.getModelName(),
                config.getProvider(),
                vendorCode,
                vendorName,
                codec.parse(config.getCapabilities()),
                imageParameterResolver.resolve(config),
                config.getUnitPrice(),
                config.getBillingUnit(),
                config.getDefault()
        );
    }
}
