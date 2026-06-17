package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.balance.VendorBalanceRefreshService;
import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderDefinition;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.dto.DiscoveredModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountDiscoveryResponse;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountRequest;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountResponse;
import com.aiminilab.aitoolmarket.agent.dto.ModelVendorAccountTestResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelVendorAccountService;
import com.aiminilab.aitoolmarket.agent.support.ModelCapabilitiesCodec;
import com.aiminilab.aitoolmarket.agent.support.VendorCodeResolver;
import com.aiminilab.aitoolmarket.agent.support.VolcengineEndpointSupport;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ModelVendorAccountServiceImpl implements ModelVendorAccountService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelVendorAccountServiceImpl.class);

    private static final Set<String> BALANCE_MODES = Set.of("MANUAL", "REST_API", "NONE", "INFERRED");

    /** 厂商账户连通测试时，优先用可探测的 provider，避免误选 minimax_music 等 agent-service 不支持的模型。 */
    private static final Map<String, List<String>> ACCOUNT_TEST_PROVIDER_PRIORITY = Map.of(
            "minimax", List.of("minimax", "anthropic_compatible", "minimax_speech", "minimax_music"),
            "siliconflow", List.of("siliconflow_images", "siliconflow_speech", "openai_compatible"),
            "volcengine", List.of("volcengine_images", "seedance", "openai_compatible"),
            "kling", List.of("kling_video", "openai_compatible")
    );

    private static final int DISCOVER_TIMEOUT_SECONDS = 30;
    private final ModelVendorAccountMapper vendorAccountMapper;
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final VendorCodeResolver vendorCodeResolver;
    private final ModelProviderRegistry providerRegistry;
    private final AgentServiceClient agentServiceClient;
    private final VendorBalanceRefreshService balanceRefreshService;
    private final ModelCapabilitiesCodec capabilitiesCodec;
    private final ObjectMapper objectMapper;

    public ModelVendorAccountServiceImpl(ModelVendorAccountMapper vendorAccountMapper,
                                         AgentModelConfigMapper agentModelConfigMapper,
                                         VendorCodeResolver vendorCodeResolver,
                                         ModelProviderRegistry providerRegistry,
                                         AgentServiceClient agentServiceClient,
                                         VendorBalanceRefreshService balanceRefreshService,
                                         ModelCapabilitiesCodec capabilitiesCodec,
                                         ObjectMapper objectMapper) {
        this.vendorAccountMapper = vendorAccountMapper;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.vendorCodeResolver = vendorCodeResolver;
        this.providerRegistry = providerRegistry;
        this.agentServiceClient = agentServiceClient;
        this.balanceRefreshService = balanceRefreshService;
        this.capabilitiesCodec = capabilitiesCodec;
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
        AgentModelConfig linkedForTest = selectLinkedModelForAccountTest(account);
        String providerCode = linkedForTest != null && providerRegistry.isSupported(linkedForTest.getProvider())
                ? linkedForTest.getProvider().trim().toLowerCase(Locale.ROOT)
                : resolveTestProvider(account.getVendorCode());
        ModelProviderDefinition provider = providerRegistry.findByCode(providerCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "unsupported model provider for test"));
        if ("accept_only".equalsIgnoreCase(provider.testStrategy())) {
            return testAcceptOnlyVendorAccount(account, providerCode, provider);
        }
        if (requiresMediaGatewayProbe(provider)) {
            return testMediaGatewayVendorAccount(account, providerCode, provider);
        }
        AgentModelConfigRequest testRequest = accountTestRequest(account, providerCode, provider, linkedForTest);
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
            if (!success) {
                LOGGER.warn(
                        "Vendor account connectivity test failed: accountId={}, vendorCode={}, providerCode={}, modelName={}, stage=agent_service_test, message={}",
                        account.getId(),
                        account.getVendorCode(),
                        testRequest.provider(),
                        testRequest.modelName(),
                        message
                );
            }
        } catch (IllegalStateException exception) {
            success = false;
            message = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? "连接失败"
                    : exception.getMessage();
            latencyMs = null;
            account.setHealthStatus("ERROR");
            account.setBalanceErrorMessage(message);
            LOGGER.warn(
                    "Vendor account connectivity test error: accountId={}, vendorCode={}, providerCode={}, modelName={}, stage=agent_service_test, message={}",
                    account.getId(),
                    account.getVendorCode(),
                    testRequest.provider(),
                    testRequest.modelName(),
                    message,
                    exception
            );
        }
        account.setUpdatedAt(LocalDateTime.now());
        vendorAccountMapper.updateAccount(account);
        if (success) {
            enableLinkedModelConfigs(account);
        }
        return new ModelVendorAccountTestResponse(
                success,
                message,
                latencyMs,
                testRequest.provider(),
                testRequest.modelName(),
                toResponse(account)
        );
    }

    private AgentModelConfig selectLinkedModelForAccountTest(ModelVendorAccount account) {
        List<AgentModelConfig> linked = agentModelConfigMapper.findActiveByVendorAccountId(account.getId());
        if (linked.isEmpty()) {
            return null;
        }
        String vendor = account.getVendorCode() == null ? "" : account.getVendorCode().trim().toLowerCase(Locale.ROOT);
        List<String> priority = ACCOUNT_TEST_PROVIDER_PRIORITY.getOrDefault(vendor, List.of());
        for (String provider : priority) {
            AgentModelConfig enabled = linked.stream()
                    .filter(config -> provider.equalsIgnoreCase(config.getProvider()))
                    .filter(config -> Boolean.TRUE.equals(config.getEnabled()))
                    .findFirst()
                    .orElse(null);
            if (enabled != null) {
                return enabled;
            }
        }
        for (String provider : priority) {
            AgentModelConfig match = linked.stream()
                    .filter(config -> provider.equalsIgnoreCase(config.getProvider()))
                    .findFirst()
                    .orElse(null);
            if (match != null) {
                return match;
            }
        }
        return linked.stream()
                .filter(config -> config.getProvider() != null && providerRegistry.isSupported(config.getProvider()))
                .findFirst()
                .orElse(null);
    }

    @Override
    @Transactional
    public ModelVendorAccountDiscoveryResponse adminDiscoverModels(Long id) {
        ModelVendorAccount account = findActiveOrThrow(id);
        if (account.getBaseUrl() == null || account.getBaseUrl().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "vendor account baseUrl is required for model discovery");
        }
        if ((account.getApiKey() == null || account.getApiKey().isBlank())
                && (account.getExtraAuthJson() == null || account.getExtraAuthJson().isBlank())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "vendor account api key is required for model discovery");
        }

        List<String> modelNames = discoverRemoteModelNames(account);
        int imported = 0;
        int updated = 0;
        int skipped = 0;
        List<DiscoveredModelConfigResponse> models = new ArrayList<>();
        for (String modelName : modelNames) {
            String capability = inferCapability(modelName);
            String provider = providerForCapability(account, modelName, capability);
            if (provider == null) {
                skipped++;
                continue;
            }
            AgentModelConfig existing = agentModelConfigMapper.findActiveByVendorAccountAndModelName(account.getId(), modelName);
            AgentModelConfig saved = upsertDiscoveredModel(account, existing, modelName, provider, capability);
            if (existing == null) {
                imported++;
            } else {
                updated++;
            }
            models.add(DiscoveredModelConfigResponse.from(saved, capabilitiesCodec));
        }
        return new ModelVendorAccountDiscoveryResponse(
                imported,
                updated,
                skipped,
                modelNames.size(),
                "同步完成",
                models
        );
    }

    private List<String> discoverRemoteModelNames(ModelVendorAccount account) {
        try {
            HttpRequest request = HttpRequest.newBuilder(modelsEndpoint(account.getBaseUrl()))
                    .timeout(java.time.Duration.ofSeconds(DISCOVER_TIMEOUT_SECONDS))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + resolveApiKey(account))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.MODEL_CALL_FAILED,
                        "model discovery failed: HTTP " + response.statusCode());
            }
            return parseModelNames(response.body());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.MODEL_CALL_FAILED,
                    "model discovery failed: " + rootMessage(exception));
        }
    }

    private URI modelsEndpoint(String baseUrl) {
        return com.aiminilab.aitoolmarket.agent.support.OpenAiCompatibleModelsEndpoint.toUri(baseUrl);
    }

    private List<String> parseModelNames(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode listNode = root.isArray() ? root : firstArray(root.get("data"), root.get("models"));
            if (listNode == null || !listNode.isArray()) {
                throw new BusinessException(ErrorCode.MODEL_CALL_FAILED, "model discovery response has no model list");
            }
            LinkedHashSet<String> names = new LinkedHashSet<>();
            for (JsonNode item : listNode) {
                String name = null;
                if (item.isTextual()) {
                    name = item.asText();
                } else if (item.isObject()) {
                    name = textValue(item.get("id"), item.get("name"));
                }
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
            return List.copyOf(names);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.MODEL_CALL_FAILED,
                    "model discovery response cannot be parsed");
        }
    }

    private JsonNode firstArray(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private AgentModelConfig upsertDiscoveredModel(ModelVendorAccount account,
                                                   AgentModelConfig existing,
                                                   String modelName,
                                                   String provider,
                                                   String capability) {
        LocalDateTime now = LocalDateTime.now();
        AgentModelConfig config = existing == null ? new AgentModelConfig() : existing;
        config.setVendorAccountId(account.getId());
        if (config.getDisplayName() == null || config.getDisplayName().isBlank()) {
            config.setDisplayName(modelName);
        }
        if (config.getConfigCode() == null || config.getConfigCode().isBlank()) {
            config.setConfigCode(discoveredConfigCode(account.getId(), modelName));
        }
        config.setProvider(provider);
        config.setModelName(modelName);
        config.setBaseUrl(blankToNull(config.getBaseUrl()));
        if (config.getApiKey() == null) {
            config.setApiKey("");
        }
        String extraAuthJson = blankToNull(config.getExtraAuthJson());
        config.setExtraAuthJson(extraAuthJson == null ? defaultExtraAuthJson(provider, modelName) : extraAuthJson);
        if (config.getDocsUrl() == null || config.getDocsUrl().isBlank()) {
            config.setDocsUrl(defaultDocsUrl(provider, modelName));
        }
        if (config.getTimeoutSeconds() == null) {
            config.setTimeoutSeconds(defaultTimeoutSeconds(provider));
        }
        if (config.getInputTokenPricePer1m() == null) {
            config.setInputTokenPricePer1m(BigDecimal.ZERO);
        }
        if (config.getOutputTokenPricePer1m() == null) {
            config.setOutputTokenPricePer1m(BigDecimal.ZERO);
        }
        config.setInputTokenPricePer1k(config.getInputTokenPricePer1m().divide(BigDecimal.valueOf(1000)));
        config.setOutputTokenPricePer1k(config.getOutputTokenPricePer1m().divide(BigDecimal.valueOf(1000)));
        config.setBillingUnit(providerRegistry.defaultBillingUnit(provider));
        config.setUnitPrice(config.getUnitPrice() == null ? BigDecimal.ZERO : config.getUnitPrice());
        config.setCapabilities(capabilitiesCodec.serialize(List.of(capability)));
        config.setEnabled(config.getEnabled() == null || config.getEnabled());
        config.setAgentEnabled(config.getAgentEnabled() == null || config.getAgentEnabled());
        config.setDefault(Boolean.TRUE.equals(config.getDefault()));
        config.setUpdatedAt(now);
        if (existing == null) {
            config.setCreatedAt(now);
            agentModelConfigMapper.insertConfig(config);
        } else {
            agentModelConfigMapper.updateConfig(config);
        }
        return config;
    }

    private String discoveredConfigCode(Long accountId, String modelName) {
        String slug = modelName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.isBlank()) {
            slug = "model";
        }
        String prefix = "acct_" + accountId + "_";
        int maxSlug = Math.max(1, 64 - prefix.length());
        if (slug.length() > maxSlug) {
            slug = slug.substring(0, maxSlug);
        }
        return prefix + slug;
    }

    private String inferCapability(String modelName) {
        String text = modelName == null ? "" : modelName.toLowerCase(Locale.ROOT);
        if (text.contains("image") || text.contains("img") || text.contains("flux")
                || text.contains("stable-diffusion") || text.contains("seedream")) {
            return "IMAGE_GENERATION";
        }
        if (text.contains("video") || text.contains("kling") || text.contains("wan")
                || text.contains("sora") || text.contains("veo") || text.contains("seedance")
                || text.contains("hailuo")) {
            return "VIDEO_GENERATION";
        }
        return "TEXT_GENERATION";
    }

    private String providerForCapability(ModelVendorAccount account, String modelName, String capability) {
        if (isAgnesModelOrAccount(account, modelName)) {
            return switch (capability) {
                case "IMAGE_GENERATION" -> "agnes_images";
                case "VIDEO_GENERATION" -> "agnes_video";
                default -> "agnes_chat";
            };
        }
        return switch (capability) {
            case "IMAGE_GENERATION" -> "openai_images_gateway";
            case "VIDEO_GENERATION" -> "worker_video";
            default -> "openai_compatible";
        };
    }

    private boolean isAgnesModelOrAccount(ModelVendorAccount account, String modelName) {
        String vendorCode = account == null || account.getVendorCode() == null
                ? ""
                : account.getVendorCode().trim().toLowerCase(Locale.ROOT);
        String baseUrl = account == null || account.getBaseUrl() == null
                ? ""
                : account.getBaseUrl().trim().toLowerCase(Locale.ROOT);
        String model = modelName == null ? "" : modelName.trim().toLowerCase(Locale.ROOT);
        return "agnes".equals(vendorCode)
                || baseUrl.contains("agnes-ai.com")
                || baseUrl.contains("apihub.agnes-ai.com")
                || model.startsWith("agnes-");
    }

    private String defaultExtraAuthJson(String provider, String modelName) {
        if ("agnes_images".equals(provider)) {
            return """
                    {"responseFormatLocation":"extra_body","imageInputMode":"jsonImageArray","endpointPath":"/images/generations","readTimeoutSeconds":600}
                    """.trim();
        }
        if ("agnes_video".equals(provider)) {
            return """
                    {"createEndpointPath":"/v1/videos","resultEndpointPath":"/agnesapi","resultQueryMode":"videoIdQuery","defaultNumFrames":121,"defaultFrameRate":24,"timeoutSeconds":900}
                    """.trim();
        }
        return null;
    }

    private String defaultDocsUrl(String provider, String modelName) {
        if ("agnes_images".equals(provider)) {
            String normalized = modelName == null ? "" : modelName.trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("2.0")) {
                return "https://agnes-ai.com/doc/agnes-image-20-flash";
            }
            return "https://agnes-ai.com/doc/agnes-image-21-flash";
        }
        if ("agnes_video".equals(provider)) {
            return "https://agnes-ai.com/doc/agnes-video-v20";
        }
        if ("agnes_chat".equals(provider)) {
            String normalized = modelName == null ? "" : modelName.trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("1.5")) {
                return "https://agnes-ai.com/doc/agnes-15-flash";
            }
            return "https://agnes-ai.com/doc/agnes-20-flash";
        }
        return null;
    }

    private int defaultTimeoutSeconds(String provider) {
        if ("agnes_video".equals(provider)) {
            return 300;
        }
        if ("agnes_images".equals(provider)) {
            return 300;
        }
        return 60;
    }

    private String resolveApiKey(ModelVendorAccount account) {
        if (account.getApiKey() != null && !account.getApiKey().isBlank()) {
            return account.getApiKey().trim();
        }
        if (account.getExtraAuthJson() == null || account.getExtraAuthJson().isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(account.getExtraAuthJson());
            String value = textValue(root.get("apiKey"), root.get("api_key"));
            if (value != null) {
                return value;
            }
            value = textValue(root.get("token"), root.get("accessToken"));
            return value == null ? "" : value;
        } catch (Exception ignored) {
            return "";
        }
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

    private AgentModelConfigRequest accountTestRequest(ModelVendorAccount account,
                                                       String providerCode,
                                                       ModelProviderDefinition provider,
                                                       AgentModelConfig linked) {
        if (linked != null) {
            return normalizeProviderBaseUrl(new AgentModelConfigRequest(
                    account.getId(),
                    linked.getDisplayName() == null || linked.getDisplayName().isBlank()
                            ? account.getAccountName()
                            : linked.getDisplayName(),
                    "vendor_account_test",
                    linked.getProvider(),
                    linked.getModelName(),
                    linked.getBaseUrl() == null || linked.getBaseUrl().isBlank() ? account.getBaseUrl() : linked.getBaseUrl(),
                    account.getApiKey(),
                    null,
                    account.getExtraAuthJson(),
                    linked.getMinimaxGroupId(),
                    account.getConsoleUrl(),
                    account.getBalanceUrl(),
                    linked.getDocsUrl(),
                    linked.getTimeoutSeconds(),
                    null,
                    null,
                    linked.getInputTokenPricePer1k(),
                    linked.getOutputTokenPricePer1k(),
                    linked.getInputTokenPricePer1m(),
                    linked.getOutputTokenPricePer1m(),
                    linked.getBillingUnit(),
                    linked.getUnitPrice(),
                    true,
                    false,
                    false,
                    linked.getCapabilities() == null || linked.getCapabilities().isBlank()
                            ? provider.capabilities()
                            : java.util.Arrays.stream(linked.getCapabilities().split(","))
                            .map(String::trim)
                            .filter(value -> !value.isBlank())
                            .toList()
            ));
        }
        return normalizeProviderBaseUrl(new AgentModelConfigRequest(
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
        ));
    }

    private AgentModelConfigRequest normalizeProviderBaseUrl(AgentModelConfigRequest request) {
        String normalizedBaseUrl = VolcengineEndpointSupport.normalizeProviderBaseUrl(request.provider(), request.baseUrl());
        if (sameText(normalizedBaseUrl, request.baseUrl())) {
            return request;
        }
        return new AgentModelConfigRequest(
                request.vendorAccountId(),
                request.displayName(),
                request.configCode(),
                request.provider(),
                request.modelName(),
                normalizedBaseUrl,
                request.apiKey(),
                request.clearApiKey(),
                request.extraAuthJson(),
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

    private boolean requiresMediaGatewayProbe(ModelProviderDefinition provider) {
        if (provider == null) {
            return false;
        }
        String protocol = provider.providerProtocol();
        if (protocol != null && "openai_images".equalsIgnoreCase(protocol.trim())) {
            return true;
        }
        List<String> capabilities = provider.capabilities() == null ? List.of() : provider.capabilities();
        return capabilities.stream().anyMatch(capability ->
                "IMAGE_GENERATION".equalsIgnoreCase(capability)
                        || "VIDEO_GENERATION".equalsIgnoreCase(capability)
                        || "MUSIC_GENERATION".equalsIgnoreCase(capability));
    }

    private ModelVendorAccountTestResponse testMediaGatewayVendorAccount(ModelVendorAccount account,
                                                                         String providerCode,
                                                                         ModelProviderDefinition provider) {
        long startedAt = System.currentTimeMillis();
        if (!hasCredential(account)) {
            account.setHealthStatus("ERROR");
            account.setBalanceErrorMessage("账号凭据未配置");
            account.setUpdatedAt(LocalDateTime.now());
            vendorAccountMapper.updateAccount(account);
            return new ModelVendorAccountTestResponse(
                    false,
                    "账号凭据未配置，请在厂商账户中填写 API Key 或额外鉴权 JSON",
                    null,
                    providerCode,
                    provider.defaultModel(),
                    toResponse(account)
            );
        }
        String baseUrl = blankToNull(account.getBaseUrl());
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = provider.defaultBaseUrl();
        }
        baseUrl = VolcengineEndpointSupport.normalizeProviderBaseUrl(providerCode, baseUrl);
        MediaGatewayProbeResult probe = probeMediaGateway(baseUrl, account.getApiKey());
        long latencyMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        boolean success = probe.success();
        String message = probe.message();
        account.setHealthStatus(success ? "OK" : "ERROR");
        account.setBalanceErrorMessage(success ? null : message);
        account.setUpdatedAt(LocalDateTime.now());
        vendorAccountMapper.updateAccount(account);
        if (success) {
            enableLinkedModelConfigs(account);
        } else {
            LOGGER.warn(
                    "Vendor account media gateway probe failed: accountId={}, vendorCode={}, providerCode={}, baseUrl={}, message={}",
                    account.getId(),
                    account.getVendorCode(),
                    providerCode,
                    baseUrl,
                    message
            );
        }
        return new ModelVendorAccountTestResponse(
                success,
                message,
                latencyMs,
                providerCode,
                provider.defaultModel(),
                toResponse(account)
        );
    }

    private MediaGatewayProbeResult probeMediaGateway(String baseUrl, String apiKey) {
        String probeUrl = com.aiminilab.aitoolmarket.agent.support.OpenAiCompatibleModelsEndpoint.resolve(baseUrl);
        try {
            java.net.http.HttpClient client = com.aiminilab.aitoolmarket.agent.support.OutboundHttpClientFactory
                    .create(java.time.Duration.ofSeconds(8));
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(probeUrl))
                    .timeout(java.time.Duration.ofSeconds(12))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                return new MediaGatewayProbeResult(false, "API Key 无效或权限不足（HTTP " + status + "）");
            }
            if (status >= 200 && status < 500) {
                return new MediaGatewayProbeResult(true, "网关鉴权通过（HTTP " + status + "）");
            }
            return new MediaGatewayProbeResult(false, "网关不可达（HTTP " + status + "）");
        } catch (Exception exception) {
            String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return new MediaGatewayProbeResult(false, "网关连接失败：" + detail);
        }
    }

    private record MediaGatewayProbeResult(boolean success, String message) {
    }

    private ModelVendorAccountTestResponse testAcceptOnlyVendorAccount(ModelVendorAccount account,
                                                                       String providerCode,
                                                                       ModelProviderDefinition provider) {
        if (!hasCredential(account)) {
            account.setHealthStatus("ERROR");
            account.setBalanceErrorMessage("账号凭据未配置");
            account.setUpdatedAt(LocalDateTime.now());
            vendorAccountMapper.updateAccount(account);
            return new ModelVendorAccountTestResponse(
                    false,
                    "账号凭据未配置，请在厂商账户中填写 API Key 或额外鉴权 JSON",
                    null,
                    providerCode,
                    provider.defaultModel(),
                    toResponse(account)
            );
        }
        long started = System.currentTimeMillis();
        String message = "凭证已保存（accept-only 策略不发起网络探测）";
        Long latencyMs = Math.max(0L, System.currentTimeMillis() - started);
        account.setHealthStatus("OK");
        account.setBalanceErrorMessage(null);
        account.setUpdatedAt(LocalDateTime.now());
        vendorAccountMapper.updateAccount(account);
        enableLinkedModelConfigs(account);
        return new ModelVendorAccountTestResponse(
                true,
                message,
                latencyMs,
                providerCode,
                provider.defaultModel(),
                toResponse(account)
        );
    }

    private void enableLinkedModelConfigs(ModelVendorAccount account) {
        if (account == null || account.getId() == null) {
            return;
        }
        int updated = agentModelConfigMapper.enableByVendorAccountId(account.getId());
        if (updated > 0) {
            LOGGER.info("Re-enabled {} model config(s) linked to vendor account {}", updated, account.getId());
        }
    }

    private VendorEndpointProbeResult probeVendorEndpoint(String baseUrl) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String probeUrl = normalized.endsWith("/v1") ? normalized + "/models" : normalized;
        try {
            java.net.http.HttpClient client = com.aiminilab.aitoolmarket.agent.support.OutboundHttpClientFactory
                    .create(java.time.Duration.ofSeconds(8));
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
        if (account.getBalanceStatus() == null || account.getBalanceStatus().isBlank()) {
            account.setBalanceStatus("UNKNOWN");
        }
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
        boolean keepsExistingKey = existing != null
                && !Boolean.TRUE.equals(request.clearApiKey())
                && existing.getApiKey() != null
                && !existing.getApiKey().isBlank();
        boolean keepsExistingExtra = existing != null
                && !Boolean.TRUE.equals(request.clearExtraAuthJson())
                && existing.getExtraAuthJson() != null
                && !existing.getExtraAuthJson().isBlank();
        if (!hasKey && !hasExtra && !keepsExistingKey && !keepsExistingExtra) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请填写 API Key 或 extraAuthJson");
        }
    }

    private boolean hasCredential(ModelVendorAccount account) {
        return account != null
                && ((account.getApiKey() != null && !account.getApiKey().isBlank())
                || (account.getExtraAuthJson() != null && !account.getExtraAuthJson().isBlank()));
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

    private boolean sameText(String left, String right) {
        String l = left == null ? "" : left.trim();
        String r = right == null ? "" : right.trim();
        return l.equalsIgnoreCase(r);
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
        String normalized = vendorCode == null ? "" : vendorCode.trim().toLowerCase(Locale.ROOT);
        if (providerRegistry.isSupported(normalized)) {
            return normalized;
        }
        return switch (normalized) {
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
