package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.ModelConfigCredentialResolver;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class ModelCapabilityService {

    private final ModelProviderRegistry providerRegistry;
    private final ModelProviderMetadataService providerMetadataService;
    private final ModelCapabilitiesCodec capabilitiesCodec;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final ModelConfigCredentialResolver credentialResolver;

    @Autowired
    public ModelCapabilityService(ModelProviderRegistry providerRegistry,
                                  ModelProviderMetadataService providerMetadataService,
                                  ModelCapabilitiesCodec capabilitiesCodec,
                                  AgentModelConfigMapper agentModelConfigMapper,
                                  ModelConfigCredentialResolver credentialResolver) {
        this.providerRegistry = providerRegistry;
        this.providerMetadataService = providerMetadataService;
        this.capabilitiesCodec = capabilitiesCodec;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.credentialResolver = credentialResolver;
    }

    public ModelCapabilityService(ModelProviderRegistry providerRegistry,
                                  ModelCapabilitiesCodec capabilitiesCodec,
                                  AgentModelConfigMapper agentModelConfigMapper,
                                  ModelConfigCredentialResolver credentialResolver) {
        this(
                providerRegistry,
                new ModelProviderMetadataService(null, providerRegistry, new com.fasterxml.jackson.databind.ObjectMapper()),
                capabilitiesCodec,
                agentModelConfigMapper,
                credentialResolver
        );
    }

    public List<String> resolveCapabilities(AgentModelConfig config) {
        if (config == null) {
            return List.of();
        }
        List<String> stored = capabilitiesCodec.parse(config.getCapabilities());
        if (!stored.isEmpty()) {
            return stored;
        }
        return providerMetadataService.get(config.getProvider()).capabilities();
    }

    public List<String> normalizeCapabilities(String provider, List<String> requested) {
        List<String> capabilities = requested == null || requested.isEmpty()
                ? providerMetadataService.get(provider).capabilities()
                : requested.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (capabilities.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "capabilities cannot be empty");
        }
        for (String capability : capabilities) {
            if ("VISION_INPUT".equalsIgnoreCase(capability)) {
                continue;
            }
            boolean supported = providerMetadataService.get(provider).capabilities().stream()
                    .anyMatch(item -> item.equalsIgnoreCase(capability));
            if (!supported) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "provider " + provider + " does not support capability " + capability);
            }
        }
        return capabilities;
    }

    public AgentModelConfig resolveModelConfigForTool(AiTool tool) {
        if (tool == null) {
            return null;
        }
        if (tool.getModelConfigId() != null) {
            AgentModelConfig bound = agentModelConfigMapper.findActiveById(tool.getModelConfigId());
            if (bound != null) {
                return bound;
            }
        }
        String requiredCapability = resolveRequiredCapability(tool);
        return agentModelConfigMapper.findAllActive().stream()
                .filter(config -> Boolean.TRUE.equals(config.getEnabled()))
                .filter(config -> resolveCapabilities(config).stream()
                        .anyMatch(capability -> capability.equalsIgnoreCase(requiredCapability)))
                .sorted(Comparator
                        .comparing((AgentModelConfig config) -> Boolean.TRUE.equals(config.getDefault()) ? 0 : 1)
                        .thenComparing(AgentModelConfig::getId, Comparator.reverseOrder()))
                .findFirst()
                .orElse(null);
    }

    public AgentModelConfig resolveModelConfigForTool(AiTool tool, Long requestedModelConfigId) {
        if (requestedModelConfigId == null) {
            return resolveModelConfigForTool(tool);
        }
        AgentModelConfig selected = agentModelConfigMapper.findActiveById(requestedModelConfigId);
        if (selected == null || Boolean.FALSE.equals(selected.getEnabled())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "model config not found or not selectable");
        }
        validateExecution(tool, selected);
        return selected;
    }

    public void validateToolModelBinding(AiTool tool) {
        if (tool == null || tool.getModelConfigId() == null) {
            return;
        }
        AgentModelConfig config = agentModelConfigMapper.findActiveById(tool.getModelConfigId());
        if (config == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "model config not found");
        }
        if (Boolean.FALSE.equals(config.getEnabled())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "bound model config is disabled: " + displayModelName(config));
        }
        String requiredCapability = resolveRequiredCapability(tool);
        if (resolveCapabilities(config).stream().noneMatch(capability -> capability.equalsIgnoreCase(requiredCapability))) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "model config does not support execution handler " + requiredCapability);
        }
    }

    public void validateToolModelBindingAvailable(AiTool tool) {
        if (tool == null || tool.getModelConfigId() == null) {
            return;
        }
        AgentModelConfig config = agentModelConfigMapper.findActiveById(tool.getModelConfigId());
        if (config == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "model config not found");
        }
        if (Boolean.FALSE.equals(config.getEnabled())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "bound model config is disabled: " + displayModelName(config));
        }
    }

    public void validateExecution(AiTool tool, AgentModelConfig modelConfig) {
        if (tool == null) {
            return;
        }
        if (modelConfig == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "no enabled model config supports " + resolveRequiredCapability(tool));
        }
        if (Boolean.FALSE.equals(modelConfig.getEnabled())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "bound model config is disabled: " + displayModelName(modelConfig));
        }
        String requiredCapability = resolveRequiredCapability(tool);
        List<String> configCapabilities = resolveCapabilities(modelConfig);
        boolean matched = configCapabilities.stream()
                .anyMatch(capability -> capability.equalsIgnoreCase(requiredCapability));
        if (!matched) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "model config does not support execution handler " + requiredCapability);
        }
        if (!providerMetadataService.get(modelConfig.getProvider()).workerReady()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "provider " + modelConfig.getProvider() + " is configured but worker executor is not ready yet");
        }
        AgentModelConfig executionConfig = credentialResolver.resolveForExecution(modelConfig);
        if (requiresApiKey(requiredCapability, executionConfig) && !hasExecutableSecret(executionConfig.getApiKey())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "bound model config has no API key: " + displayModelName(modelConfig)
                            + ". 请在管理端「统一 API 设置」为对应厂商账户填入有效 API Key"
                            + "（当前工具绑定或自动匹配到的模型配置缺少可用密钥）");
        }
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

    private boolean requiresApiKey(String capability, AgentModelConfig config) {
        if (config == null) {
            return true;
        }
        String provider = config.getProvider() == null ? "" : config.getProvider().trim().toLowerCase(Locale.ROOT);
        if ("mock".equals(provider)) {
            return false;
        }
        if ("kling_video".equals(provider) && hasKlingAccessSecretPair(config.getExtraAuthJson())) {
            return false;
        }
        return "IMAGE_GENERATION".equalsIgnoreCase(capability)
                || "VIDEO_GENERATION".equalsIgnoreCase(capability)
                || "TEXT_TO_SPEECH".equalsIgnoreCase(capability)
                || "SPEECH_TO_TEXT".equalsIgnoreCase(capability)
                || "MUSIC_GENERATION".equalsIgnoreCase(capability)
                || "DIGITAL_HUMAN".equalsIgnoreCase(capability);
    }

    private boolean hasExecutableSecret(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("replace-with-")) {
            return false;
        }
        return !isObviousPlaceholderSecret(trimmed);
    }

    private boolean isObviousPlaceholderSecret(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        return "123456".equals(lowered)
                || "test".equals(lowered)
                || "changeme".equals(lowered)
                || lowered.matches("^x+$");
    }

    private boolean hasKlingAccessSecretPair(String extraAuthJson) {
        return extraAuthJson != null
                && extraAuthJson.contains("access")
                && extraAuthJson.contains("secret");
    }

    private String displayModelName(AgentModelConfig config) {
        if (config == null) {
            return "unknown";
        }
        if (config.getDisplayName() != null && !config.getDisplayName().isBlank()) {
            return config.getDisplayName();
        }
        return config.getModelName() == null ? "unknown" : config.getModelName();
    }
}
