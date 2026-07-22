package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.support.ImageGenerationParameterResolver;

import java.util.List;

public record ModelOptionItemResponse(
        Long id,
        String displayName,
        List<String> capabilities,
        ImageGenerationParametersResponse imageParameters,
        Boolean isDefault
) {
    public static ModelOptionItemResponse from(AgentModelConfig config,
                                               String vendorCode,
                                               String vendorName,
                                               List<String> capabilities,
                                               ImageGenerationParameterResolver imageParameterResolver) {
        return new ModelOptionItemResponse(
                config.getId(),
                publicDisplayName(config, vendorName),
                capabilities == null ? List.of() : List.copyOf(capabilities),
                imageParameterResolver.resolve(config),
                Boolean.TRUE.equals(config.getDefault())
        );
    }

    private static String publicDisplayName(AgentModelConfig config, String vendorName) {
        if (config.getDisplayName() != null && !config.getDisplayName().isBlank()) {
            return config.getDisplayName().trim();
        }
        String safeVendorName = vendorName == null || vendorName.isBlank() ? "其他" : vendorName.trim();
        return safeVendorName + " 模型 " + config.getId();
    }
}
