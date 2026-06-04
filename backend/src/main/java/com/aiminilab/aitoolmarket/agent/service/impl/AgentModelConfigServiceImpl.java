package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderDefinition;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentModelConfigService;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.ModelConfigCredentialResolver;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private final ModelVendorAccountMapper vendorAccountMapper;
    private final AgentServiceClient agentServiceClient;
    private final ModelProviderRegistry providerRegistry;
    private final ModelCapabilityService modelCapabilityService;
    private final ModelCapabilitiesCodec capabilitiesCodec;
    private final ModelConfigCredentialResolver credentialResolver;
    private final VendorCodeResolver vendorCodeResolver;
    private final ObjectMapper objectMapper;

    public AgentModelConfigServiceImpl(AgentModelConfigMapper agentModelConfigMapper,
                                       ModelVendorAccountMapper vendorAccountMapper,
                                       AgentServiceClient agentServiceClient,
                                       ModelProviderRegistry providerRegistry,
                                       ModelCapabilityService modelCapabilityService,
                                       ModelCapabilitiesCodec capabilitiesCodec,
                                       ModelConfigCredentialResolver credentialResolver,
                                       VendorCodeResolver vendorCodeResolver,
                                       ObjectMapper objectMapper) {
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.vendorAccountMapper = vendorAccountMapper;
        this.agentServiceClient = agentServiceClient;
        this.providerRegistry = providerRegistry;
        this.modelCapabilityService = modelCapabilityService;
        this.capabilitiesCodec = capabilitiesCodec;
        this.credentialResolver = credentialResolver;
        this.vendorCodeResolver = vendorCodeResolver;
        this.objectMapper = objectMapper;
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
        List<AgentModelConfig> configs = agentVisibleConfigs();
        if (!configs.isEmpty()) {
            return configs.stream()
                    .map(config -> toResponse(config, isChatSelectableForAgent(config)))
                    .toList();
        }
        AgentModelConfig fallback = credentialResolver.resolveForExecution(findOrDefault());
        if (Boolean.FALSE.equals(fallback.getEnabled()) || Boolean.FALSE.equals(fallback.getAgentEnabled())) {
            return List.of();
        }
        return List.of(toResponse(fallback, isChatSelectableForAgent(fallback)));
    }

    @Override
    @Transactional
    public AgentModelConfigResponse adminCreate(AgentModelConfigRequest request) {
        validate(request);
        ensureConfigCodeAvailable(request.configCode(), null);
        validateEnabledModelAccount(request);
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
        validateEnabledModelAccount(request);
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
        config.setVendorAccountId(request.vendorAccountId());
        config.setDisplayName(blankToNull(request.displayName()));
        config.setConfigCode(blankToNull(request.configCode()));
        String providerTrimmed = request.provider().trim();
        config.setProvider(providerTrimmed);
        config.setModelName(request.modelName().trim());
        config.setBaseUrl(blankToNull(request.baseUrl()));
        config.setExtraAuthJson(mergeExtraAuthJson(request, existing));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            config.setApiKey(request.apiKey().trim());
        } else if (Boolean.TRUE.equals(request.clearApiKey())
                || ("kling_video".equalsIgnoreCase(providerTrimmed) && shouldClearKlingApiKey(config.getExtraAuthJson(), existing))) {
            config.setApiKey("");
        } else if (existing != null) {
            config.setApiKey(existing.getApiKey());
        } else {
            config.setApiKey("");
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
        boolean enabled = request.enabled() == null || request.enabled();
        config.setEnabled(enabled);
        config.setAgentEnabled(enabled && (request.agentEnabled() == null ? Boolean.TRUE : request.agentEnabled()));
        config.setDefault(request.isDefault() != null && request.isDefault());
        config.setUpdatedAt(now);
        return config;
    }

    @Override
    public InternalAgentModelConfigResponse internalGet() {
        List<AgentModelConfig> agentConfigs = executableAgentConfigs();
        AgentModelConfig config = agentConfigs.isEmpty() ? findOrDefault() : agentConfigs.get(0);
        return InternalAgentModelConfigResponse.from(credentialResolver.resolveForExecution(config));
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
        AgentModelConfig executable = credentialResolver.resolveForExecution(config);
        if (!isExecutableForAgent(executable)) {
            return internalGet();
        }
        return InternalAgentModelConfigResponse.from(executable);
    }

    @Override
    public AgentModelConfigTestResponse adminTestById(Long id) {
        AgentModelConfig existing = findActiveOrThrow(id);
        try {
            AgentModelConfigTestResponse response = adminTest(toTestRequest(existing));
            recordModelConnectivityTest(existing, response);
            if (!Boolean.TRUE.equals(response.success())) {
                disableModelForFailedConnectivity(existing);
            } else {
                markVendorAccountHealthy(existing);
            }
            return response;
        } catch (RuntimeException exception) {
            disableModelForFailedConnectivity(existing);
            throw exception;
        }
    }

    private void disableModelForFailedConnectivity(AgentModelConfig config) {
        config.setEnabled(false);
        config.setAgentEnabled(false);
        config.setDefault(false);
        config.setUpdatedAt(LocalDateTime.now());
        agentModelConfigMapper.updateConfig(config);
    }

    private void recordModelConnectivityTest(AgentModelConfig config, AgentModelConfigTestResponse response) {
        config.setLastTestSuccess(Boolean.TRUE.equals(response.success()));
        String message = response.message() == null ? "" : response.message();
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        config.setLastTestMessage(message);
        config.setLastTestAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        agentModelConfigMapper.updateConnectivityTest(config);
    }

    private void markVendorAccountHealthy(AgentModelConfig config) {
        if (config.getVendorAccountId() == null) {
            return;
        }
        ModelVendorAccount account = vendorAccountMapper.findActiveById(config.getVendorAccountId());
        if (account == null) {
            return;
        }
        account.setHealthStatus("OK");
        account.setBalanceErrorMessage(null);
        account.setUpdatedAt(LocalDateTime.now());
        vendorAccountMapper.updateAccount(account);
    }

    @Override
    public AgentModelConfigTestResponse adminTest(AgentModelConfigRequest request) {
        validate(request);
        AgentModelConfig existing = findExistingForTest(request);
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

    private AgentModelConfigRequest toTestRequest(AgentModelConfig config) {
        List<String> capabilities = capabilitiesCodec.parse(config.getCapabilities());
        return new AgentModelConfigRequest(
                config.getVendorAccountId(),
                config.getDisplayName(),
                config.getConfigCode(),
                config.getProvider(),
                config.getModelName(),
                config.getBaseUrl(),
                null,
                false,
                null,
                config.getMinimaxGroupId(),
                config.getConsoleUrl(),
                config.getBalanceUrl(),
                config.getDocsUrl(),
                config.getTimeoutSeconds(),
                null,
                null,
                config.getInputTokenPricePer1k(),
                config.getOutputTokenPricePer1k(),
                config.getInputTokenPricePer1m(),
                config.getOutputTokenPricePer1m(),
                config.getBillingUnit(),
                config.getUnitPrice(),
                config.getEnabled(),
                config.getAgentEnabled(),
                config.getDefault(),
                capabilities.isEmpty() ? null : capabilities
        );
    }

    private AgentModelConfig findExistingForTest(AgentModelConfigRequest request) {
        String configCode = blankToNull(request.configCode());
        if (configCode != null) {
            AgentModelConfig byCode = agentModelConfigMapper.findActiveByConfigCode(configCode);
            if (byCode != null) {
                return byCode;
            }
        }
        String provider = blankToNull(request.provider());
        String modelName = blankToNull(request.modelName());
        String baseUrl = blankToNull(request.baseUrl());
        if (provider != null && modelName != null) {
            AgentModelConfig byIdentity = agentModelConfigMapper.findAllActive().stream()
                    .filter(item -> sameText(item.getProvider(), provider))
                    .filter(item -> sameText(item.getModelName(), modelName))
                    .filter(item -> baseUrl == null || sameText(item.getBaseUrl(), baseUrl))
                    .findFirst()
                    .orElse(null);
            if (byIdentity != null) {
                return byIdentity;
            }
        }
        return null;
    }

    private static String modelConfigTestFailureMessage(IllegalStateException exception) {
        String detail = rootMessage(exception);
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

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message == null ? "" : message;
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
        return toResponse(config, isChatSelectableForAgent(config));
    }

    private AgentModelConfigResponse toResponse(AgentModelConfig config, boolean chatSelectable) {
        String vendorAccountName = null;
        if (config.getVendorAccountId() != null) {
            ModelVendorAccount account = vendorAccountMapper.findActiveById(config.getVendorAccountId());
            if (account != null) {
                vendorAccountName = account.getAccountName();
            }
        }
        String channelCode = vendorCodeResolver.resolveVendorCode(config);
        return AgentModelConfigResponse.from(
                config,
                capabilitiesCodec,
                vendorAccountName,
                channelCode,
                vendorCodeResolver.vendorLabel(channelCode),
                vendorCodeResolver.vendorIconAsset(channelCode),
                chatSelectable
        );
    }

    private List<AgentModelConfig> executableAgentConfigs() {
        return agentModelConfigMapper.findAgentEnabled()
                .stream()
                .map(credentialResolver::resolveForExecution)
                .filter(this::isExecutableForAgent)
                .toList();
    }

    private List<AgentModelConfig> agentVisibleConfigs() {
        return agentModelConfigMapper.findAgentEnabled()
                .stream()
                .map(credentialResolver::resolveForExecution)
                .filter(this::isVisibleInAgentPicker)
                .toList();
    }

    private boolean isVisibleInAgentPicker(AgentModelConfig config) {
        if (config == null || Boolean.FALSE.equals(config.getEnabled()) || Boolean.FALSE.equals(config.getAgentEnabled())) {
            return false;
        }
        if (isKnownNonChatEndpoint(config.getBaseUrl(), config.getProvider(), config.getModelName())) {
            return false;
        }
        if (!hasHealthyEnabledAccount(config)) {
            return false;
        }
        return hasExecutableSecret(config.getApiKey()) || hasKlingAccessSecretPair(config.getExtraAuthJson());
    }

    private boolean isChatSelectableForAgent(AgentModelConfig config) {
        return isExecutableForAgent(config);
    }

    private boolean isExecutableForAgent(AgentModelConfig config) {
        if (config == null || Boolean.FALSE.equals(config.getEnabled()) || Boolean.FALSE.equals(config.getAgentEnabled())) {
            return false;
        }
        String provider = config.getProvider() == null ? "" : config.getProvider().trim().toLowerCase();
        if ("mock".equals(provider)) {
            return true;
        }
        if (!capabilitiesCodec.parse(config.getCapabilities()).contains("TEXT_GENERATION")) {
            return false;
        }
        if (isKnownNonChatEndpoint(config.getBaseUrl(), config.getProvider(), config.getModelName())) {
            return false;
        }
        if (!hasHealthyEnabledAccount(config)) {
            return false;
        }
        return hasExecutableSecret(config.getApiKey()) || hasKlingAccessSecretPair(config.getExtraAuthJson());
    }

    private boolean hasHealthyEnabledAccount(AgentModelConfig config) {
        if (config.getVendorAccountId() == null) {
            return true;
        }
        ModelVendorAccount account = vendorAccountMapper.findActiveById(config.getVendorAccountId());
        if (account == null || Boolean.FALSE.equals(account.getEnabled())) {
            return false;
        }
        String health = account.getHealthStatus() == null ? "" : account.getHealthStatus().trim();
        return "OK".equalsIgnoreCase(health);
    }

    private boolean isKnownNonChatEndpoint(String baseUrl, String provider, String modelName) {
        String text = String.join(" ",
                baseUrl == null ? "" : baseUrl,
                provider == null ? "" : provider,
                modelName == null ? "" : modelName).toLowerCase();
        return text.contains("mineru.net")
                || text.contains("mineru")
                || text.contains("pdf")
                || text.contains("ocr");
    }

    private boolean hasExecutableSecret(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.startsWith("replace-with-");
    }

    @Override
    public AgentModelConfig resolveForExecution(AgentModelConfig config) {
        return credentialResolver.resolveForExecution(config);
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
                objectMapper.readTree(request.extraAuthJson());
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "extraAuthJson must be valid JSON");
            }
        }
        if (request.vendorAccountId() != null) {
            ModelVendorAccount account = vendorAccountMapper.findActiveById(request.vendorAccountId());
            if (account == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "vendor account not found");
            }
        }
    }

    private void validateEnabledModelAccount(AgentModelConfigRequest request) {
        boolean enabled = request.enabled() == null || request.enabled();
        boolean agentEnabled = request.agentEnabled() == null || request.agentEnabled();
        if (!enabled && !agentEnabled) {
            return;
        }
        if (request.vendorAccountId() == null) {
            return;
        }
        ModelVendorAccount account = vendorAccountMapper.findActiveById(request.vendorAccountId());
        if (account != null) {
            validateAccountReadyForEnabledModel(account);
        }
    }

    private void validateAccountReadyForEnabledModel(ModelVendorAccount account) {
        if (Boolean.FALSE.equals(account.getEnabled())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "vendor account must be enabled before enabling this model");
        }
        String health = account.getHealthStatus() == null ? "" : account.getHealthStatus().trim();
        if (!"OK".equalsIgnoreCase(health)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "vendor account connectivity test must pass before enabling this model");
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

    private boolean sameText(String left, String right) {
        String l = left == null ? "" : left.trim();
        String r = right == null ? "" : right.trim();
        return l.equalsIgnoreCase(r);
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
            return mergeFromVendorAccount(request, existing);
        }
        AgentModelConfigRequest merged = new AgentModelConfigRequest(
                request.vendorAccountId() != null ? request.vendorAccountId() : (existing == null ? null : existing.getVendorAccountId()),
                request.displayName(),
                request.configCode(),
                request.provider(),
                request.modelName(),
                request.baseUrl(),
                hasApiKey ? request.apiKey() : existing.getApiKey(),
                request.clearApiKey(),
                hasExtraAuth ? request.extraAuthJson() : existing.getExtraAuthJson(),
                request.minimaxGroupId(),
                request.consoleUrl(),
                request.balanceUrl(),
                request.docsUrl(),
                request.timeoutSeconds(),
                request.connectTimeoutSeconds(),
                request.readTimeoutSeconds(),
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
        return mergeFromVendorAccount(merged, existing);
    }

    private AgentModelConfigRequest mergeFromVendorAccount(AgentModelConfigRequest request, AgentModelConfig existing) {
        Long accountId = request.vendorAccountId();
        if (accountId == null && existing != null) {
            accountId = existing.getVendorAccountId();
        }
        if (accountId == null) {
            return request;
        }
        ModelVendorAccount account = vendorAccountMapper.findActiveById(accountId);
        if (account == null) {
            return request;
        }
        boolean hasApiKey = request.apiKey() != null && !request.apiKey().isBlank();
        boolean hasExtraAuth = request.extraAuthJson() != null && !request.extraAuthJson().isBlank();
        boolean hasBaseUrl = request.baseUrl() != null && !request.baseUrl().isBlank();
        return new AgentModelConfigRequest(
                accountId,
                request.displayName(),
                request.configCode(),
                request.provider(),
                request.modelName(),
                hasBaseUrl ? request.baseUrl() : (existing != null && existing.getBaseUrl() != null && !existing.getBaseUrl().isBlank()
                        ? existing.getBaseUrl() : account.getBaseUrl()),
                hasApiKey ? request.apiKey() : (existing != null && existing.getApiKey() != null && !existing.getApiKey().isBlank()
                        ? existing.getApiKey() : account.getApiKey()),
                request.clearApiKey(),
                hasExtraAuth ? request.extraAuthJson() : (existing != null && existing.getExtraAuthJson() != null && !existing.getExtraAuthJson().isBlank()
                        ? existing.getExtraAuthJson() : account.getExtraAuthJson()),
                request.minimaxGroupId(),
                request.consoleUrl(),
                request.balanceUrl(),
                request.docsUrl(),
                request.timeoutSeconds(),
                request.connectTimeoutSeconds(),
                request.readTimeoutSeconds(),
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

    private String mergeExtraAuthJson(AgentModelConfigRequest request, AgentModelConfig existing) {
        ObjectNode node = objectMapper.createObjectNode();
        if (existing != null && existing.getExtraAuthJson() != null && !existing.getExtraAuthJson().isBlank()) {
            mergeObject(node, existing.getExtraAuthJson());
        }
        if (request.extraAuthJson() != null && !request.extraAuthJson().isBlank()) {
            mergeObject(node, request.extraAuthJson());
        }
        if (request.connectTimeoutSeconds() != null) {
            node.put("connectTimeoutSeconds", request.connectTimeoutSeconds());
        }
        if (request.readTimeoutSeconds() != null) {
            node.put("readTimeoutSeconds", request.readTimeoutSeconds());
        }
        return node.isEmpty() ? null : node.toString();
    }

    private void mergeObject(ObjectNode target, String json) {
        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (parsed != null && parsed.isObject()) {
                target.setAll((ObjectNode) parsed);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "extraAuthJson must be valid JSON");
        }
    }

    private boolean shouldClearKlingApiKey(String extraAuthJson, AgentModelConfig existing) {
        if (!hasKlingAccessSecretPair(extraAuthJson)) {
            return false;
        }
        if (existing == null || existing.getApiKey() == null) {
            return true;
        }
        String previous = existing.getApiKey().trim();
        return previous.isEmpty() || !previous.contains(".") || previous.length() <= 8;
    }

    private boolean hasKlingAccessSecretPair(String extraAuthJson) {
        if (extraAuthJson == null || extraAuthJson.isBlank()) {
            return false;
        }
        try {
            JsonNode parsed = objectMapper.readTree(extraAuthJson);
            if (parsed == null || !parsed.isObject()) {
                return false;
            }
            String accessKey = textValue(parsed.get("accessKey"), parsed.get("access_key"));
            String secretKey = textValue(parsed.get("secretKey"), parsed.get("secret_key"));
            return accessKey != null && secretKey != null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return false;
        }
    }

    private String textValue(JsonNode primary, JsonNode fallback) {
        JsonNode node = primary != null && primary.isTextual() && !primary.asText().isBlank()
                ? primary
                : fallback;
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isBlank() || value.startsWith("replace-with-") ? null : value;
    }

    private AgentModelConfig findActiveOrThrow(Long id) {
        AgentModelConfig config = agentModelConfigMapper.findActiveById(id);
        if (config == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "model config not found");
        }
        return config;
    }
}
