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
import java.util.List;
import java.util.Set;

@Service
public class AgentModelConfigServiceImpl implements AgentModelConfigService {

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("mock", "openai_compatible", "anthropic_compatible", "minimax");

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
    public List<AgentModelConfigResponse> adminList() {
        List<AgentModelConfig> configs = agentModelConfigMapper.findAllActive();
        if (configs.isEmpty()) {
            return List.of(AgentModelConfigResponse.from(findOrDefault()));
        }
        return configs.stream().map(AgentModelConfigResponse::from).toList();
    }

    @Override
    @Transactional
    public AgentModelConfigResponse adminCreate(AgentModelConfigRequest request) {
        validate(request);
        LocalDateTime now = LocalDateTime.now();
        AgentModelConfig config = applyRequest(new AgentModelConfig(), request, null, now);
        config.setCreatedAt(now);
        agentModelConfigMapper.insertConfig(config);
        if (Boolean.TRUE.equals(config.getDefault())) {
            agentModelConfigMapper.clearDefaultExcept(config.getId());
        }
        return AgentModelConfigResponse.from(config);
    }

    @Override
    @Transactional
    public AgentModelConfigResponse adminUpdate(Long id, AgentModelConfigRequest request) {
        validate(request);
        AgentModelConfig existing = findActiveOrThrow(id);
        AgentModelConfig config = applyRequest(existing, request, existing, LocalDateTime.now());
        agentModelConfigMapper.updateConfig(config);
        if (Boolean.TRUE.equals(config.getDefault())) {
            agentModelConfigMapper.clearDefaultExcept(config.getId());
        }
        return AgentModelConfigResponse.from(config);
    }

    @Override
    @Transactional
    public AgentModelConfigResponse adminSave(AgentModelConfigRequest request) {
        AgentModelConfig existing = agentModelConfigMapper.findLatest();
        if (existing == null) {
            return adminCreate(request);
        }
        return adminUpdate(existing.getId(), request);
    }

    @Override
    @Transactional
    public AgentModelConfigResponse adminSetDefault(Long id) {
        AgentModelConfig existing = findActiveOrThrow(id);
        agentModelConfigMapper.setDefault(id);
        existing.setDefault(true);
        existing.setUpdatedAt(LocalDateTime.now());
        return AgentModelConfigResponse.from(existing);
    }

    @Override
    @Transactional
    public void adminDelete(Long id) {
        AgentModelConfig existing = findActiveOrThrow(id);
        if (Boolean.TRUE.equals(existing.getDefault())) {
            List<AgentModelConfig> others = agentModelConfigMapper.findAllActive().stream()
                    .filter(config -> !config.getId().equals(id))
                    .toList();
            if (!others.isEmpty()) {
                agentModelConfigMapper.setDefault(others.get(0).getId());
            }
        }
        agentModelConfigMapper.softDelete(id);
    }

    private AgentModelConfig applyRequest(AgentModelConfig config,
                                          AgentModelConfigRequest request,
                                          AgentModelConfig existing,
                                          LocalDateTime now) {
        config.setDisplayName(blankToNull(request.displayName()));
        config.setConfigCode(blankToNull(request.configCode()));
        config.setProvider(request.provider().trim());
        config.setModelName(request.modelName().trim());
        config.setBaseUrl(blankToNull(request.baseUrl()));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            config.setApiKey(request.apiKey().trim());
        } else if (existing != null) {
            config.setApiKey(existing.getApiKey());
        } else {
            config.setApiKey("");
        }
        config.setMinimaxGroupId(blankToNull(request.minimaxGroupId()));
        config.setTimeoutSeconds(request.timeoutSeconds() == null ? 60 : request.timeoutSeconds());
        config.setEnabled(request.enabled() == null || request.enabled());
        config.setDefault(request.isDefault() != null && request.isDefault());
        config.setUpdatedAt(now);
        return config;
    }

    @Override
    public InternalAgentModelConfigResponse internalGet() {
        return InternalAgentModelConfigResponse.from(findOrDefault());
    }

    @Override
    public AgentModelConfigTestResponse adminTest(AgentModelConfigRequest request) {
        validate(request);
        AgentModelConfig existing = agentModelConfigMapper.findLatest();
        try {
            return agentServiceClient.testModelConfig(mergeSecretFields(request, existing));
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.MODEL_CALL_FAILED, modelConfigTestFailureMessage(exception));
        }
    }

    private static String modelConfigTestFailureMessage(IllegalStateException exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            return "调用 agent-service 失败，请查看后端日志并确认服务与内网签名配置。";
        }
        if (detail.startsWith("Could not parse agent-service model config test response")) {
            return "agent-service 返回内容无法解析为测试结果，请核对 agent-service 版本与接口是否正常，或查看后端日志。"
                    + " 原始信息：" + detail;
        }
        return "连通性测试失败：请确认 agent-service 已启动，且后端 AGENT_SERVICE_BASE_URL 在运行环境中可解析"
                + "（Docker 内通常为 http://agent-service:8090），并与 agent-service 共用同一 INTERNAL_API_TOKEN。"
                + " 详情：" + detail;
    }

    private AgentModelConfig findOrDefault() {
        AgentModelConfig config = agentModelConfigMapper.findLatest();
        if (config != null) {
            return config;
        }
        LocalDateTime now = LocalDateTime.now();
        AgentModelConfig fallback = new AgentModelConfig();
        fallback.setId(0L);
        fallback.setDisplayName("Mock");
        fallback.setConfigCode("mock");
        fallback.setProvider("mock");
        fallback.setModelName("mock");
        fallback.setBaseUrl(null);
        fallback.setApiKey("");
        fallback.setMinimaxGroupId(null);
        fallback.setTimeoutSeconds(60);
        fallback.setEnabled(true);
        fallback.setDefault(true);
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

    private AgentModelConfigRequest mergeSecretFields(AgentModelConfigRequest request, AgentModelConfig existing) {
        if (existing == null || request.apiKey() != null && !request.apiKey().isBlank()) {
            return request;
        }
        return new AgentModelConfigRequest(
                request.displayName(),
                request.configCode(),
                request.provider(),
                request.modelName(),
                request.baseUrl(),
                existing.getApiKey(),
                request.minimaxGroupId(),
                request.timeoutSeconds(),
                request.enabled(),
                request.isDefault()
        );
    }

    private AgentModelConfig findActiveOrThrow(Long id) {
        AgentModelConfig config = agentModelConfigMapper.findActiveById(id);
        if (config == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "model config not found");
        }
        return config;
    }
}
