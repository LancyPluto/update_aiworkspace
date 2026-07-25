package com.aiminilab.aitoolmarket.ppt.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.ppt.service.PptPlatformModelBindingService;

public record PptModelBindingView(
        boolean platformManaged,
        ModelView textModel,
        ModelView imageModel
) {
    public static PptModelBindingView from(PptPlatformModelBindingService.ResolvedBinding binding) {
        return new PptModelBindingView(
                true,
                ModelView.from(binding.textModel()),
                ModelView.from(binding.imageModel())
        );
    }

    public record ModelView(Long modelConfigId, String displayName, String provider, String modelName) {
        static ModelView from(AgentModelConfig config) {
            String displayName = config.getDisplayName() == null || config.getDisplayName().isBlank()
                    ? config.getModelName()
                    : config.getDisplayName();
            return new ModelView(config.getId(), displayName, config.getProvider(), config.getModelName());
        }
    }
}
