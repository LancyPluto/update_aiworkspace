package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.support.ImageGenerationParameterResolver;

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
                                               List<String> capabilities,
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
                capabilities == null ? List.of() : List.copyOf(capabilities),
                imageParameterResolver.resolve(config),
                config.getUnitPrice(),
                config.getBillingUnit(),
                config.getDefault()
        );
    }
}
