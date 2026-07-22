package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.support.AgentVisionInputSupport;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;

import java.util.List;

/** Public model metadata required by the agent model picker. */
public record AgentSelectableModelResponse(
        Long id,
        String displayName,
        Boolean isDefault,
        List<String> capabilities,
        Boolean chatSelectable,
        String channelCode,
        String channelLabel,
        String channelIconAsset
) {
    public static AgentSelectableModelResponse from(AgentModelConfig config,
                                                     ModelCapabilitiesCodec codec,
                                                     boolean chatSelectable,
                                                     String channelCode,
                                                     String channelLabel,
                                                     String channelIconAsset) {
        return new AgentSelectableModelResponse(
                config.getId(),
                publicDisplayName(config, channelLabel),
                config.getDefault(),
                AgentVisionInputSupport.withInferredVisionInput(
                        config.getProvider(),
                        config.getModelName(),
                        config.getBaseUrl(),
                        codec.parse(config.getCapabilities())
                ),
                chatSelectable,
                channelCode,
                channelLabel,
                channelIconAsset
        );
    }

    private static String publicDisplayName(AgentModelConfig config, String channelLabel) {
        if (config.getDisplayName() != null && !config.getDisplayName().isBlank()) {
            return config.getDisplayName().trim();
        }
        if (channelLabel != null && !channelLabel.isBlank()) {
            return config.getId() == null
                    ? channelLabel.trim() + " 模型"
                    : channelLabel.trim() + " 模型 " + config.getId();
        }
        return config.getId() == null ? "AI 模型" : "AI 模型 " + config.getId();
    }
}
