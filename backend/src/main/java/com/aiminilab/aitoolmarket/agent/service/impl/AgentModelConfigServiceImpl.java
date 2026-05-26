package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderDefinition;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class AgentModelConfigServiceImpl implements AgentModelConfigService {

    private static final String BILLING_UNIT_TOKEN_PER_M = "TOKEN_PER_M";
    private static final String BILLING_UNIT_PER_CALL = "PER_CALL";
    private static final String BILLING_UNIT_IMAGE_TOKEN = "IMAGE_TOKEN";
    private static final BigDecimal TOKEN_UNIT_SCALE = BigDecimal.valueOf(1000);
    private static final String TEST_STRATEGY_ACCEPT_ONLY = "accept_only";

    private final AgentModelConfigMapper agentModelConfigMapper;
    private final AgentServiceClient agentServiceClient;
    private final ModelProviderRegistry providerRegistry;
    private final ModelCapabilityService modelCapabilityService;
    private final ModelCapabilitiesCodec capabilitiesCodec;

    public AgentModelConfigServiceImpl(AgentModelConfigMapper agentModelConfigMapper,
                                       AgentServiceClient agentServiceClient,
                                       ModelProviderRegistry providerRegistry,
                                       ModelCapabilityService modelCapabilityService,
                                       ModelCapabilitiesCodec capabilitiesCodec) {
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.agentServiceClient = agentServiceClient;
        this.providerRegistry = providerRegistry;
        this.modelCapabilityService = modelCapabilityService;
        this.capabilitiesCodec = capabilitiesCodec;
    }

    @Override
    public AgentModelConfigResponse adminGet() {
        return toResponse(findOrDefault());
    }

    @Override
    public List<AgentModelConfigResponse> adminList() {
        List<AgentModelConfig> configs = agentModelConfigMapper.findAllActive();
        if (configs.isEmpty()) {
            return List.of(toResponse(findOrDefault()));
        }
        return configs.stream().map(this::toResponse).toList();
    }

    @Override
    public List<AgentModelConfigResponse> agentSelectableList() {
        List<AgentModelConfig> configs = agentModelConfigMapper.findAgentEnabled();
        if (!configs.isEmpty()) {
            return configs.stream().map(this::toResponse).toList();
        }
        AgentModelConfig fallback = findOrDefault();
        if (Boolean.FALSE.equals(fallback.getEnabled()) || Boolean.FALSE.equals(fallback.getAgentEnabled())) {
            return List.of();
        }
        return List.of(toResponse(fallback));
    }

    @Override
    @Transactional
    public AgentModelConfigResponse adminCreate(AgentModelConfigRequest request) {
        validate(request);
        ensureConfigCodeAvailable(request.configCode(), null);
        LocalDateTime now = LocalDateTime.now();
        AgentModelConfig config = applyRequest(new AgentModelConfig(), request, null, now);
        config.setCreatedAt(now);
        agentModelConfigMapper.insertConfig(config);
        if (Boolean.TRUE.equals(config.getDefault())) {
            agentModelConfigMapper.clearDefaultExcept(config.getId());
        }
        return toResponse(config);
    }

    @Override
    @Transactional
    public AgentModelConfigResponse adminUpdate(Long id, AgentModelConfigRequest request) {
        validate(request);
        AgentModelConfig existing = findActiveOrThrow(id);
        ensureConfigCodeAvailable(request.configCode(), existing.getId());
        AgentModelConfig config = applyRequest(existing, request, existing, LocalDateTime.now());
        agentModelConfigMapper.updateConfig(config);
        if (Boolean.TRUE.equals(config.getDefault())) {
            agentModelConfigMapper.clearDefaultExcept(config.getId());
        }
        return toResponse(config);
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
        return toResponse(existing);
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
        String previousProvider = existing != null ? existing.getProvider() : null;
        config.setDisplayName(blankToNull(request.displayName()));
        config.setConfigCode(blankToNull(request.configCode()));
        String providerTrimmed = request.provider().trim();
        config.setProvider(providerTrimmed);
        config.setModelName(request.modelName().trim());
        config.setBaseUrl(blankToNull(request.baseUrl()));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            config.setApiKey(request.apiKey().trim());
        } else if (existing != null) {
            config.setApiKey(existing.getApiKey());
        } else {
            config.setApiKey("");
        }
        if (request.extraAuthJson() != null && !request.extraAuthJson().isBlank()) {
            config.setExtraAuthJson(request.extraAuthJson().trim());
        } else if (existing != null) {
            config.setExtraAuthJson(existing.getExtraAuthJson());
        } else {
            config.setExtraAuthJson(null);
        }
        config.setMinimaxGroupId(blankToNull(request.minimaxGroupId()));
        config.setConsoleUrl(blankToNull(request.consoleUrl()));
        config.setBalanceUrl(blankToNull(request.balanceUrl()));
        config.setDocsUrl(blankToNull(request.docsUrl()));
        config.setTimeoutSeconds(request.timeoutSeconds() == null ? 60 : request.timeoutSeconds());
        BigDecimal inputPricePer1m = resolveTokenPricePer1m(request.inputTokenPricePer1m(), request.inputTokenPricePer1k());
        BigDecimal outputPricePer1m = resolveTokenPricePer1m(request.outputTokenPricePer1m(), request.outputTokenPricePer1k());
        config.setInputTokenPricePer1m(inputPricePer1m);
        config.setOutputTokenPricePer1m(outputPricePer1m);
        config.setInputTokenPricePer1k(inputPricePer1m.divide(TOKEN_UNIT_SCALE));
        config.setOutputTokenPricePer1k(outputPricePer1m.divide(TOKEN_UNIT_SCALE));
        config.setBillingUnit(resolveBillingUnit(request.billingUnit(), request.provider()));
        config.setUnitPrice(nonNegativeMoney(request.unitPrice()));
        boolean providerChanged = previousProvider != null
                && !previousProvider.trim().equalsIgnoreCase(providerTrimmed);
        List<String> capabilities;
        if (request.capabilities() != null && !request.capabilities().isEmpty()) {
            capabilities = modelCapabilityService.normalizeCapabilities(providerTrimmed, request.capabilities());
        } else if (!providerChanged && existing != null && existing.getCapabilities() != null && !existing.getCapabilities().isBlank()) {
            capabilities = capabilitiesCodec.parse(existing.getCapabilities());
            if (capabilities.isEmpty()) {
                capabilities = modelCapabilityService.normalizeCapabilities(providerTrimmed, List.of());
            }
        } else {
            capabilities = modelCapabilityService.normalizeCapabilities(providerTrimmed, List.of());
        }
        config.setCapabilities(capabilitiesCodec.serialize(capabilities));
        config.setEnabled(request.enabled() == null || request.enabled());
        config.setAgentEnabled(request.agentEnabled() == null ? Boolean.TRUE : request.agentEnabled());
        config.setDefault(request.isDefault() != null && request.isDefault());
        config.setUpdatedAt(now);
        return config;
    }

    @Override
    public InternalAgentModelConfigResponse internalGet() {
        List<AgentModelConfig> agentConfigs = agentModelConfigMapper.findAgentEnabled();
        return InternalAgentModelConfigResponse.from(agentConfigs.isEmpty() ? findOrDefault() : agentConfigs.get(0));
    }

    @Override
    public InternalAgentModelConfigResponse internalGet(Long modelConfigId) {
        if (modelConfigId == null) {
            return internalGet();
        }
        AgentModelConfig config = agentModelConfigMapper.findAgentEnabledById(modelConfigId);
        if (config == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Agent model config not found or not enabled for Agent");
        }
        return InternalAgentModelConfigResponse.from(config);
    }

    @Override
    public AgentModelConfigTestResponse adminTest(AgentModelConfigRequest request) {
        validate(request);
        AgentModelConfig existing = agentModelConfigMapper.findLatest();
        AgentModelConfigRequest merged = mergeSecretFields(request, existing);
        ModelProviderDefinition provider = providerRegistry.findByCode(merged.provider())
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "unsupported model provider"));
        if (TEST_STRATEGY_ACCEPT_ONLY.equalsIgnoreCase(provider.testStrategy())) {
            return new AgentModelConfigTestResponse(
                    true,
                    merged.provider(),
                    merged.modelName(),
                    0L,
                    provider.description().isBlank()
                            ? "provider config accepted; worker will validate at runtime"
                            : provider.description(),
                    ""
            );
        }
        try {
            return agentServiceClient.testModelConfig(merged);
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
        fallback.setExtraAuthJson(null);
        fallback.setMinimaxGroupId(null);
        fallback.setConsoleUrl(null);
        fallback.setBalanceUrl(null);
        fallback.setDocsUrl(null);
        fallback.setTimeoutSeconds(60);
        fallback.setInputTokenPricePer1k(BigDecimal.ZERO);
        fallback.setOutputTokenPricePer1k(BigDecimal.ZERO);
        fallback.setInputTokenPricePer1m(BigDecimal.ZERO);
        fallback.setOutputTokenPricePer1m(BigDecimal.ZERO);
        fallback.setBillingUnit(BILLING_UNIT_TOKEN_PER_M);
        fallback.setUnitPrice(BigDecimal.ZERO);
        fallback.setCapabilities(capabilitiesCodec.serialize(providerRegistry.defaultCapabilities("mock")));
        fallback.setEnabled(true);
        fallback.setAgentEnabled(true);
        fallback.setDefault(true);
        fallback.setCreatedAt(now);
        fallback.setUpdatedAt(now);
        return fallback;
    }

    private AgentModelConfigResponse toResponse(AgentModelConfig config) {
        return AgentModelConfigResponse.from(config, capabilitiesCodec);
    }

    private void validate(AgentModelConfigRequest request) {
        String provider = request.provider() == null ? "" : request.provider().trim();
        if (!providerRegistry.isSupported(provider)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported model provider");
        }
        if (request.modelName() == null || request.modelName().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "modelName is required");
        }
        if (isNegative(request.inputTokenPricePer1k()) || isNegative(request.outputTokenPricePer1k())
                || isNegative(request.inputTokenPricePer1m()) || isNegative(request.outputTokenPricePer1m())
                || isNegative(request.unitPrice())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "token price must be non-negative");
        }
        String billingUnit = resolveBillingUnit(request.billingUnit(), provider);
        if (!Set.of(BILLING_UNIT_TOKEN_PER_M, BILLING_UNIT_PER_CALL, BILLING_UNIT_IMAGE_TOKEN).contains(billingUnit)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported billing unit");
        }
        if (request.extraAuthJson() != null && !request.extraAuthJson().isBlank()) {
            try {
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(request.extraAuthJson());
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "extraAuthJson must be valid JSON");
            }
        }
    }

    private void ensureConfigCodeAvailable(String configCode, Long excludeId) {
        String normalized = blankToNull(configCode);
        if (normalized == null) {
            return;
        }
        if (agentModelConfigMapper.countActiveByConfigCode(normalized, excludeId) > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "configCode already exists");
        }
        agentModelConfigMapper.archiveDeletedConfigCode(normalized);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BigDecimal nonNegativeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private boolean isNegative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    private AgentModelConfigRequest mergeSecretFields(AgentModelConfigRequest request, AgentModelConfig existing) {
        boolean hasApiKey = request.apiKey() != null && !request.apiKey().isBlank();
        boolean hasExtraAuth = request.extraAuthJson() != null && !request.extraAuthJson().isBlank();
        if (existing == null || hasApiKey && hasExtraAuth) {
            return request;
        }
        return new AgentModelConfigRequest(
                request.displayName(),
                request.configCode(),
                request.provider(),
                request.modelName(),
                request.baseUrl(),
                hasApiKey ? request.apiKey() : existing.getApiKey(),
                hasExtraAuth ? request.extraAuthJson() : existing.getExtraAuthJson(),
                request.minimaxGroupId(),
                request.consoleUrl(),
                request.balanceUrl(),
                request.docsUrl(),
                request.timeoutSeconds(),
                request.inputTokenPricePer1k(),
                request.outputTokenPricePer1k(),
                request.inputTokenPricePer1m(),
                request.outputTokenPricePer1m(),
                request.billingUnit(),
                request.unitPrice(),
                request.enabled(),
                request.agentEnabled(),
                request.isDefault(),
                request.capabilities()
        );
    }

    private BigDecimal resolveTokenPricePer1m(BigDecimal pricePer1m, BigDecimal legacyPricePer1k) {
        if (pricePer1m != null) {
            return nonNegativeMoney(pricePer1m);
        }
        return nonNegativeMoney(legacyPricePer1k).multiply(TOKEN_UNIT_SCALE);
    }

    private String resolveBillingUnit(String billingUnit, String provider) {
        if (billingUnit != null && !billingUnit.isBlank()) {
            return billingUnit.trim().toUpperCase();
        }
        String defaultUnit = providerRegistry.defaultBillingUnit(provider);
        if (BILLING_UNIT_PER_CALL.equalsIgnoreCase(defaultUnit)) {
            return BILLING_UNIT_PER_CALL;
        }
        if (BILLING_UNIT_IMAGE_TOKEN.equalsIgnoreCase(defaultUnit)) {
            return BILLING_UNIT_IMAGE_TOKEN;
        }
        return BILLING_UNIT_TOKEN_PER_M;
    }

    private AgentModelConfig findActiveOrThrow(Long id) {
        AgentModelConfig config = agentModelConfigMapper.findActiveById(id);
        if (config == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "model config not found");
        }
        return config;
    }
}
