package com.aiminilab.aitoolmarket.ppt.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;

import java.util.List;

public record PptModelOptionsView(
        List<ModelOption> textModels,
        List<ModelOption> imageModels
) {
    public static PptModelOptionsView from(List<AgentModelConfig> textModels,
                                           List<AgentModelConfig> imageModels) {
        return new PptModelOptionsView(
                toOptions(textModels),
                toOptions(imageModels)
        );
    }

    private static List<ModelOption> toOptions(List<AgentModelConfig> models) {
        return java.util.stream.IntStream.range(0, models.size())
                .mapToObj(index -> ModelOption.from(models.get(index), index == 0))
                .toList();
    }

    public record ModelOption(
            Long modelConfigId,
            String displayName,
            String provider,
            String modelName,
            boolean recommended
    ) {
        static ModelOption from(AgentModelConfig config, boolean recommended) {
            String displayName = config.getDisplayName() == null || config.getDisplayName().isBlank()
                    ? config.getModelName()
                    : config.getDisplayName();
            return new ModelOption(
                    config.getId(),
                    displayName,
                    config.getProvider(),
                    config.getModelName(),
                    recommended
            );
        }
    }
}
