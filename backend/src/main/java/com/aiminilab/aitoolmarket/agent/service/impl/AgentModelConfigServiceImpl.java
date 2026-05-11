package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

@Service
public class AgentModelConfigServiceImpl implements AgentModelConfigService {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("mock", "openai_compatible", "minimax");

    private final AgentModelConfigMapper agentModelConfigMapper;
    private final AgentServiceClient agentServiceClient;

    public AgentModelConfigServiceImpl(AgentModelConfigMapper agentModelConfigMapper, AgentServiceClient agentServiceClient) {
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.agentServiceClient = agentServiceClient;
    }

    @Override
    public AgentModelConfigResponse adminGet() {
        return AgentModelConfigResponse.from(findOrDefault());
    }

    @Override
    @Transactional
    public AgentModelConfigResponse adminSave(AgentModelConfigRequest request) {
        validate(request);
        LocalDateTime now = LocalDateTime.now();
        AgentModelConfig existing = agentModelConfigMapper.findLatest();
        AgentModelConfig config = existing == null ? new AgentModelConfig() : existing;
        config.setProvider(request.provider().trim());
        config.setModelName(request.modelName().trim());
        config.setBaseUrl(blankToNull(request.baseUrl()));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            config.setApiKey(request.apiKey().trim());
        } else if (existing == null) {
            config.setApiKey("");
        }
        config.setMinimaxGroupId(blankToNull(request.minimaxGroupId()));
        config.setTimeoutSeconds(request.timeoutSeconds() == null ? 60 : request.timeoutSeconds());
        config.setEnabled(request.enabled() == null || request.enabled());
        config.setUpdatedAt(now);
        if (existing == null) {
            config.setCreatedAt(now);
            agentModelConfigMapper.insertConfig(config);
        } else {
            agentModelConfigMapper.updateConfig(config);
        }
        return AgentModelConfigResponse.from(config);
    }

    @Override
    public InternalAgentModelConfigResponse internalGet() {
        return InternalAgentModelConfigResponse.from(findOrDefault());
    }

    @Override
    public AgentModelConfigTestResponse adminTest(AgentModelConfigRequest request) {
        validate(request);
        return agentServiceClient.testModelConfig(request);
    }

    private AgentModelConfig findOrDefault() {
        AgentModelConfig config = agentModelConfigMapper.findLatest();
        if (config != null) {
            return config;
        }
        LocalDateTime now = LocalDateTime.now();
        AgentModelConfig fallback = new AgentModelConfig();
        fallback.setId(0L);
        fallback.setProvider("mock");
        fallback.setModelName("mock");
        fallback.setBaseUrl(null);
        fallback.setApiKey("");
        fallback.setMinimaxGroupId(null);
        fallback.setTimeoutSeconds(60);
        fallback.setEnabled(true);
        fallback.setCreatedAt(now);
        fallback.setUpdatedAt(now);
        return fallback;
    }

    private void validate(AgentModelConfigRequest request) {
        String provider = request.provider() == null ? "" : request.provider().trim();
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported model provider");
        }
        if (request.modelName() == null || request.modelName().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "modelName is required");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
