package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.balance.VendorBalanceRefreshService;
import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderDefinition;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountRequest;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountResponse;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountTestResponse;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorAccountService;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ModelVendorAccountServiceImpl implements ModelVendorAccountService {

    private static final Set<String> BALANCE_MODES = Set.of("MANUAL", "REST_API", "NONE", "INFERRED");
    private final ModelVendorAccountMapper vendorAccountMapper;
    private final VendorCodeResolver vendorCodeResolver;
    private final ModelProviderRegistry providerRegistry;
    private final AgentServiceClient agentServiceClient;
    private final VendorBalanceRefreshService balanceRefreshService;
    private final ObjectMapper objectMapper;

    public ModelVendorAccountServiceImpl(ModelVendorAccountMapper vendorAccountMapper,
                                         VendorCodeResolver vendorCodeResolver,
                                         ModelProviderRegistry providerRegistry,
                                         AgentServiceClient agentServiceClient,
                                         VendorBalanceRefreshService balanceRefreshService,
                                         ObjectMapper objectMapper) {
        this.vendorAccountMapper = vendorAccountMapper;
        this.vendorCodeResolver = vendorCodeResolver;
        this.providerRegistry = providerRegistry;
        this.agentServiceClient = agentServiceClient;
        this.balanceRefreshService = balanceRefreshService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ModelVendorAccountResponse> adminList(String vendorCode) {
        List<ModelVendorAccount> accounts = vendorCode == null || vendorCode.isBlank()
                ? vendorAccountMapper.findAllActive()
                : vendorAccountMapper.findActiveByVendorCode(vendorCode.trim());
        return accounts.stream().map(this::toResponse).toList();
    }

    @Override
    public ModelVendorAccountResponse adminGet(Long id) {
        return toResponse(findActiveOrThrow(id));
    }

    @Override
    @Transactional
    public ModelVendorAccountResponse adminCreate(ModelVendorAccountRequest request) {
        validate(request, null);
        LocalDateTime now = LocalDateTime.now();
        ModelVendorAccount account = applyRequest(new ModelVendorAccount(), request, null, now);
        account.setCreatedAt(now);
        vendorAccountMapper.insertAccount(account);
        if ("REST_API".equals(normalizeMode(account.getBalanceQueryMode()))) {
            balanceRefreshService.refresh(account);
            account.setUpdatedAt(LocalDateTime.now());
            vendorAccountMapper.updateAccount(account);
        }
        return toResponse(account);
    }

    @Override
    @Transactional
    public ModelVendorAccountResponse adminUpdate(Long id, ModelVendorAccountRequest request) {
        ModelVendorAccount existing = findActiveOrThrow(id);
        validate(request, existing);
        ModelVendorAccount account = applyRequest(existing, request, existing, LocalDateTime.now());
        vendorAccountMapper.updateAccount(account);
        return toResponse(account);
    }

    @Override
    @Transactional
    public void adminDelete(Long id) {
        ModelVendorAccount account = findActiveOrThrow(id);
        int modelCount = vendorAccountMapper.countActiveModelsByAccountId(id);
        if (modelCount > 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "该账户下仍有 " + modelCount + " 个模型配置，请先迁移或删除模型后再删除账户");
        }
        vendorAccountMapper.softDelete(account.getId());
    }

    @Override
    @Transactional
    public ModelVendorAccountResponse adminRefreshBalance(Long id) {
        ModelVendorAccount account = findActiveOrThrow(id);
        refreshBalance(account);
        account.setUpdatedAt(LocalDateTime.now());
        vendorAccountMapper.updateAccount(account);
        return toResponse(account);
    }

    @Override
    @Transactional
    public int adminRefreshBalanceAll() {
        return balanceRefreshService.refreshAllEnabled();
    }

    @Override
    public ModelVendorAccountTestResponse adminTest(Long id) {
        ModelVendorAccount account = findActiveOrThrow(id);
        String providerCode = resolveTestProvider(account.getVendorCode());
        ModelProviderDefinition provider = providerRegistry.findByCode(providerCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "unsupported model provider for test"));
        if ("accept_only".equalsIgnoreCase(provider.testStrategy())) {
            return testAcceptOnlyVendorAccount(account, providerCode, provider);
        }
        AgentModelConfigRequest testRequest = new AgentModelConfigRequest(
                account.getId(),
                account.getAccountName(),
                "vendor_account_test",
                providerCode,
                provider.defaultModel(),
                account.getBaseUrl(),
                account.getApiKey(),
                null,
                account.getExtraAuthJson(),
                null,
                account.getConsoleUrl(),
                account.getBalanceUrl(),
                null,
                60,
                null,
                null,
                null,
                null,
                null,
                null,
                provider.billingDefault(),
                BigDecimal.ZERO,
                true,
                false,
                false,
                provider.capabilities()
        );
        boolean success;
        String message;
        Long latencyMs;
        try {
            AgentModelConfigTestResponse result = agentServiceClient.testModelConfig(testRequest);
            success = Boolean.TRUE.equals(result.success());
            message = result.message() == null || result.message().isBlank()
                    ? (success ? "连接成功" : "连接失败")
                    : result.message();
            latencyMs = result.latencyMs();
            account.setHealthStatus(success ? "OK" : "ERROR");
            account.setBalanceErrorMessage(success ? null : message);
        } catch (IllegalStateException exception) {
            success = false;
            message = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? "连接失败"
                    : exception.getMessage();
            latencyMs = null;
            account.setHealthStatus("ERROR");
            account.setBalanceErrorMessage(message);
        }
        account.setUpdatedAt(LocalDateTime.now());
        vendorAccountMapper.updateAccount(account);
        return new ModelVendorAccountTestResponse(
                success,
                message,
                latencyMs,
                providerCode,
                provider.defaultModel(),
                toResponse(account)
        );
    }

    private ModelVendorAccountTestResponse testAcceptOnlyVendorAccount(ModelVendorAccount account,
                                                                       String providerCode,
                                                                       ModelProviderDefinition provider) {
        if (account.getApiKey() == null || account.getApiKey().isBlank()) {
            account.setHealthStatus("ERROR");
            account.setBalanceErrorMessage("API Key 未配置");
            account.setUpdatedAt(LocalDateTime.now());
            vendorAccountMapper.updateAccount(account);
            return new ModelVendorAccountTestResponse(
                    false,
                    "API Key 未配置",
                    null,
                    providerCode,
                    provider.defaultModel(),
                    toResponse(account)
            );
        }
        long started = System.currentTimeMillis();
        String baseUrl = account.getBaseUrl() == null || account.getBaseUrl().isBlank()
                ? provider.defaultBaseUrl()
                : account.getBaseUrl().trim();
        String message;
        boolean success;
        if (baseUrl == null || baseUrl.isBlank()) {
            success = true;
            message = "凭证已保存（该协议无固定 Base URL，未发起网络探测）";
        } else {
            VendorEndpointProbeResult probe = probeVendorEndpoint(baseUrl);
            success = probe.reachable();
            message = probe.message();
        }
        Long latencyMs = success ? Math.max(0L, System.currentTimeMillis() - started) : null;
        account.setHealthStatus(success ? "OK" : "ERROR");
        account.setBalanceErrorMessage(success ? null : message);
        account.setUpdatedAt(LocalDateTime.now());
        vendorAccountMapper.updateAccount(account);
        return new ModelVendorAccountTestResponse(
                success,
                message,
                latencyMs,
                providerCode,
                provider.defaultModel(),
                toResponse(account)
        );
    }

    private VendorEndpointProbeResult probeVendorEndpoint(String baseUrl) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String probeUrl = normalized.endsWith("/v1") ? normalized + "/models" : normalized;
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(8))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(probeUrl))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            java.net.http.HttpResponse<Void> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 500) {
                return new VendorEndpointProbeResult(true, "网关可达（HTTP " + status + "）");
            }
            return new VendorEndpointProbeResult(false, "网关不可达（HTTP " + status + "）");
        } catch (Exception exception) {
            String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return new VendorEndpointProbeResult(false, "网关连接失败：" + detail);
        }
    }

    private record VendorEndpointProbeResult(boolean reachable, String message) {
    }

    private void refreshBalance(ModelVendorAccount account) {
        balanceRefreshService.refresh(account);
    }

    private ModelVendorAccount applyRequest(ModelVendorAccount account,
                                            ModelVendorAccountRequest request,
                                            ModelVendorAccount existing,
                                            LocalDateTime now) {
        account.setVendorCode(request.vendorCode().trim().toLowerCase(Locale.ROOT));
        account.setAccountName(request.accountName().trim());
        account.setBaseUrl(blankToNull(request.baseUrl()));
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            account.setApiKey(request.apiKey().trim());
        } else if (Boolean.TRUE.equals(request.clearApiKey())) {
            account.setApiKey("");
        } else if (existing != null) {
            account.setApiKey(existing.getApiKey());
        }
        if (request.extraAuthJson() != null && !request.extraAuthJson().isBlank()) {
            account.setExtraAuthJson(request.extraAuthJson().trim());
        } else if (Boolean.TRUE.equals(request.clearExtraAuthJson())) {
            account.setExtraAuthJson("");
        } else if (existing != null) {
            account.setExtraAuthJson(existing.getExtraAuthJson());
        }
        account.setConsoleUrl(blankToNull(request.consoleUrl()));
        account.setBalanceUrl(blankToNull(request.balanceUrl()));
        account.setBalanceQueryMode(resolveBalanceQueryMode(request, existing));
        if (request.balanceAmount() != null) {
            account.setBalanceAmount(request.balanceAmount());
        }
        if (request.balanceCurrency() != null && !request.balanceCurrency().isBlank()) {
            account.setBalanceCurrency(request.balanceCurrency().trim());
        } else if (account.getBalanceCurrency() == null) {
            account.setBalanceCurrency("CNY");
        }
        account.setBalanceLowThreshold(request.balanceLowThreshold());
        account.setEnabled(request.enabled() == null || request.enabled());
        if (account.getHealthStatus() == null || account.getHealthStatus().isBlank()) {
            account.setHealthStatus("UNKNOWN");
        }
        account.setUpdatedAt(now);
        if ("MANUAL".equals(normalizeMode(account.getBalanceQueryMode()))) {
            balanceRefreshService.refresh(account);
        }
        return account;
    }

    private String resolveBalanceQueryMode(ModelVendorAccountRequest request, ModelVendorAccount existing) {
        if (request.balanceQueryMode() != null && !request.balanceQueryMode().isBlank()) {
            return normalizeMode(request.balanceQueryMode());
        }
        if (existing != null && existing.getBalanceQueryMode() != null) {
            return normalizeMode(existing.getBalanceQueryMode());
        }
        String vendor = request.vendorCode().trim().toLowerCase(Locale.ROOT);
        if ("deepseek".equals(vendor) || "siliconflow".equals(vendor)) {
            return "REST_API";
        }
        return "MANUAL";
    }

    private void validate(ModelVendorAccountRequest request, ModelVendorAccount existing) {
        if (request.vendorCode() == null || request.vendorCode().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "vendorCode is required");
        }
        if (request.accountName() == null || request.accountName().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "accountName is required");
        }
        String mode = normalizeMode(request.balanceQueryMode());
        if (!BALANCE_MODES.contains(mode)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported balanceQueryMode");
        }
        if (request.extraAuthJson() != null && !request.extraAuthJson().isBlank()) {
            try {
                objectMapper.readTree(request.extraAuthJson());
            } catch (JsonProcessingException exception) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "extraAuthJson must be valid JSON");
            }
        }
        boolean hasKey = request.apiKey() != null && !request.apiKey().isBlank();
        boolean hasExtra = request.extraAuthJson() != null && !request.extraAuthJson().isBlank();
        if (existing == null && !hasKey && !hasExtra) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请填写 API Key 或 extraAuthJson");
        }
    }

    private ModelVendorAccountResponse toResponse(ModelVendorAccount account) {
        int modelCount = vendorAccountMapper.countActiveModelsByAccountId(account.getId());
        return ModelVendorAccountResponse.from(
                account,
                vendorCodeResolver.vendorLabel(account.getVendorCode()),
                modelCount);
    }

    private ModelVendorAccount findActiveOrThrow(Long id) {
        ModelVendorAccount account = vendorAccountMapper.findActiveById(id);
        if (account == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "vendor account not found");
        }
        return account;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "MANUAL";
        }
        return mode.trim().toUpperCase(Locale.ROOT);
    }

    private String resolveTestProvider(String vendorCode) {
        return switch (vendorCode) {
            case "deepseek" -> "deepseek";
            case "siliconflow" -> "siliconflow_images";
            case "volcengine" -> "volcengine_images";
            case "kling" -> "kling_video";
            case "minimax" -> "minimax";
            case "openai_gateway" -> "openai_images_gateway";
            case "mock" -> "mock";
            default -> "openai_compatible";
        };
    }
}
