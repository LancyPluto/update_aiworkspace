package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.ModelConfigCredentialResolver;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.support.ToolModelCapabilitySupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ModelCapabilityService {

    private static final Set<String> DIGITAL_HUMAN_VIDEO_PROVIDERS = Set.of("seedance", "infinitetalk");

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
        List<String> supportedCapabilities = supportedCapabilities(config.getProvider());
        List<String> stored = capabilitiesCodec.parse(config.getCapabilities());
        if (stored.isEmpty()) {
            return supportedCapabilities;
        }
        return stored.stream()
                .filter(supportedCapabilities::contains)
                .toList();
    }

    public List<String> normalizeCapabilities(String provider, List<String> requested) {
        List<String> supportedCapabilities = supportedCapabilities(provider);
        List<String> source = requested == null || requested.isEmpty()
                ? supportedCapabilities
                : requested;
        List<String> capabilities = source.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (capabilities.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "capabilities cannot be empty");
        }
        for (String capability : capabilities) {
            if (!supportedCapabilities.contains(capability)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "provider " + provider + " does not support capability " + capability);
            }
        }
        return capabilities;
    }

    private List<String> supportedCapabilities(String provider) {
        return providerMetadataService.get(provider).capabilities().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> !"DIGITAL_HUMAN".equals(value))
                .distinct()
                .toList();
    }

    public List<String> normalizeRequiredCapabilities(List<String> requested) {
        List<String> capabilities = ToolModelCapabilitySupport.normalize(requested);
        if (capabilities.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "requiredModelCapabilities cannot be empty");
        }
        for (String capability : capabilities) {
            if (!ToolModelCapabilitySupport.isKnownCapability(capability)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR,
                        "unsupported model capability: " + capability);
            }
        }
        return capabilities;
    }

    public List<String> resolveRequiredCapabilities(AiTool tool) {
        if (tool == null) {
            return List.of();
        }
        List<String> stored = ToolModelCapabilitySupport.normalizeLegacy(
                capabilitiesCodec.parse(tool.getRequiredModelCapabilities())
        );
        return stored.isEmpty() ? ToolModelCapabilitySupport.defaultsFor(tool) : stored;
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
        List<String> requiredCapabilities = resolveRequiredCapabilities(tool);
        return agentModelConfigMapper.findAllActive().stream()
                .filter(config -> Boolean.TRUE.equals(config.getEnabled()))
                .filter(config -> supportsTool(tool, config, requiredCapabilities))
                .sorted(Comparator
                        .comparing((AgentModelConfig config) -> Boolean.TRUE.equals(config.getDefault()) ? 0 : 1)
                        .thenComparing(AgentModelConfig::getId, Comparator.reverseOrder()))
                .findFirst()
                .orElse(null);
    }

    public Map<Long, AgentModelConfig> resolveModelConfigsForTools(List<AiTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return Map.of();
        }
        List<AgentModelConfig> activeConfigs = agentModelConfigMapper.findAllActive();
        Map<Long, AgentModelConfig> configsById = activeConfigs.stream()
                .filter(config -> config.getId() != null)
                .collect(Collectors.toMap(
                        AgentModelConfig::getId,
                        Function.identity(),
                        (left, right) -> left
                ));
        Map<Long, AgentModelConfig> resolvedByToolId = new LinkedHashMap<>();
        for (AiTool tool : tools) {
            if (tool == null || tool.getId() == null) {
                continue;
            }
            AgentModelConfig resolved = resolveModelConfigFromSnapshot(tool, activeConfigs, configsById);
            if (resolved != null) {
                resolvedByToolId.put(tool.getId(), resolved);
            }
        }
        return Map.copyOf(resolvedByToolId);
    }

    private AgentModelConfig resolveModelConfigFromSnapshot(
            AiTool tool,
            List<AgentModelConfig> activeConfigs,
            Map<Long, AgentModelConfig> configsById
    ) {
        if (tool.getModelConfigId() != null) {
            AgentModelConfig bound = configsById.get(tool.getModelConfigId());
            if (bound != null) {
                return bound;
            }
        }
        List<String> requiredCapabilities = resolveRequiredCapabilities(tool);
        return activeConfigs.stream()
                .filter(config -> Boolean.TRUE.equals(config.getEnabled()))
                .filter(config -> supportsTool(tool, config, requiredCapabilities))
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
        validateToolModelCapabilities(tool, config);
    }

    public void validateToolModelCapabilities(AiTool tool, AgentModelConfig config) {
        if (tool == null) {
            return;
        }
        List<String> requiredCapabilities = resolveRequiredCapabilities(tool);
        validateModelCapabilities(config, requiredCapabilities);
        validateExecutionHandlerProvider(tool, config);
    }

    public void validateModelCapabilities(AgentModelConfig config, List<String> requiredCapabilities) {
        if (config == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "model config not found");
        }
        List<String> missingCapabilities = missingCapabilities(config, requiredCapabilities);
        if (!missingCapabilities.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "model config does not support required capabilities " + missingCapabilities);
        }
    }

    public void validateToolModelBindingAvailable(AiTool tool) {
        validateToolModelBinding(tool);
    }

    public void validateExecution(AiTool tool, AgentModelConfig modelConfig) {
        if (tool == null) {
            return;
        }
        if (modelConfig == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "no enabled model config supports " + resolveRequiredCapabilities(tool));
        }
        List<String> requiredCapabilities = resolveRequiredCapabilities(tool);
        validateToolModelCapabilities(tool, modelConfig);
        validateModelExecution(modelConfig, requiredCapabilities);
    }

    public void validateModelExecution(AgentModelConfig modelConfig, List<String> requiredCapabilities) {
        if (modelConfig == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "model config not found");
        }
        if (Boolean.FALSE.equals(modelConfig.getEnabled())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "bound model config is disabled: " + displayModelName(modelConfig));
        }
        if (!providerMetadataService.get(modelConfig.getProvider()).workerReady()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "provider " + modelConfig.getProvider() + " is configured but worker executor is not ready yet");
        }
        AgentModelConfig executionConfig = credentialResolver.resolveForExecution(modelConfig);
        boolean apiKeyRequired = ToolModelCapabilitySupport.normalizeLegacy(requiredCapabilities).stream()
                .anyMatch(capability -> requiresApiKey(capability, executionConfig));
        if (apiKeyRequired && !hasExecutableSecret(executionConfig.getApiKey())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "bound model config has no API key: " + displayModelName(modelConfig)
                            + ". 请在管理端「统一 API 设置」为对应厂商账户填入有效 API Key"
                            + "（当前工具绑定或自动匹配到的模型配置缺少可用密钥）");
        }
    }

    public String resolveRequiredCapability(AiTool tool) {
        return resolveRequiredCapabilities(tool).stream().findFirst().orElse("TEXT_GENERATION");
    }

    private boolean supportsTool(AiTool tool, AgentModelConfig config, List<String> requiredCapabilities) {
        return missingCapabilities(config, requiredCapabilities).isEmpty()
                && isExecutionHandlerProviderCompatible(tool, config);
    }

    private void validateExecutionHandlerProvider(AiTool tool, AgentModelConfig config) {
        if (isExecutionHandlerProviderCompatible(tool, config)) {
            return;
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR,
                "provider " + config.getProvider()
                        + " is not supported by execution handler DIGITAL_HUMAN; supported providers are "
                        + DIGITAL_HUMAN_VIDEO_PROVIDERS);
    }

    private boolean isExecutionHandlerProviderCompatible(AiTool tool, AgentModelConfig config) {
        if (tool == null || !"DIGITAL_HUMAN".equalsIgnoreCase(tool.getExecutionHandler())) {
            return true;
        }
        String provider = config == null || config.getProvider() == null
                ? ""
                : config.getProvider().trim().toLowerCase(Locale.ROOT);
        return isDigitalHumanVideoProvider(provider);
    }

    boolean isDigitalHumanVideoProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return DIGITAL_HUMAN_VIDEO_PROVIDERS.contains(normalized);
    }

    private List<String> missingCapabilities(AgentModelConfig config, List<String> requiredCapabilities) {
        Set<String> available = resolveCapabilities(config).stream()
                .filter(capability -> capability != null && !capability.isBlank())
                .map(capability -> capability.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        return requiredCapabilities.stream()
                .filter(capability -> !available.contains(capability))
                .toList();
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
                || "MUSIC_GENERATION".equalsIgnoreCase(capability);
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
