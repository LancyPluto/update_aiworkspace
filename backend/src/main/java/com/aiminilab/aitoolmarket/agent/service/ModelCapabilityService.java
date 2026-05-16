package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class ModelCapabilityService {

    private final ModelProviderRegistry providerRegistry;
    private final ModelCapabilitiesCodec capabilitiesCodec;
    private final AgentModelConfigMapper agentModelConfigMapper;

    public ModelCapabilityService(ModelProviderRegistry providerRegistry,
                                  ModelCapabilitiesCodec capabilitiesCodec,
                                  AgentModelConfigMapper agentModelConfigMapper) {
        this.providerRegistry = providerRegistry;
        this.capabilitiesCodec = capabilitiesCodec;
        this.agentModelConfigMapper = agentModelConfigMapper;
    }

    public List<String> resolveCapabilities(AgentModelConfig config) {
        if (config == null) {
            return List.of();
        }
        List<String> stored = capabilitiesCodec.parse(config.getCapabilities());
        if (!stored.isEmpty()) {
            return stored;
        }
        return providerRegistry.defaultCapabilities(config.getProvider());
    }

    public List<String> normalizeCapabilities(String provider, List<String> requested) {
        List<String> capabilities = requested == null || requested.isEmpty()
                ? providerRegistry.defaultCapabilities(provider)
                : requested.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (capabilities.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "capabilities cannot be empty");
        }
        for (String capability : capabilities) {
            providerRegistry.requireCapability(provider, capability);
        }
        return capabilities;
    }

    public void validateToolModelBinding(AiTool tool) {
        if (tool == null || tool.getModelConfigId() == null) {
            return;
        }
        AgentModelConfig config = agentModelConfigMapper.findActiveById(tool.getModelConfigId());
        if (config == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "model config not found");
        }
        validateExecution(tool, config);
    }

    public void validateExecution(AiTool tool, AgentModelConfig modelConfig) {
        if (tool == null || modelConfig == null) {
            return;
        }
        String requiredCapability = resolveRequiredCapability(tool);
        List<String> configCapabilities = resolveCapabilities(modelConfig);
        boolean matched = configCapabilities.stream()
                .anyMatch(capability -> capability.equalsIgnoreCase(requiredCapability));
        if (!matched) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "model config does not support execution handler " + requiredCapability);
        }
        providerRegistry.requireWorkerReady(modelConfig.getProvider());
    }

    public String resolveRequiredCapability(AiTool tool) {
        if (tool.getExecutionHandler() != null && !tool.getExecutionHandler().isBlank()) {
            return tool.getExecutionHandler().trim().toUpperCase(Locale.ROOT);
        }
        if (tool.getToolType() != null && !tool.getToolType().isBlank()) {
            return tool.getToolType().trim().toUpperCase(Locale.ROOT);
        }
        return "TEXT_GENERATION";
    }
}
