package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderDefinition;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;

@Component
public class ModelConfigCredentialResolver {

    private final ModelVendorAccountMapper vendorAccountMapper;
    private final ModelProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    public ModelConfigCredentialResolver(ModelVendorAccountMapper vendorAccountMapper,
                                         ModelProviderRegistry providerRegistry,
                                         ObjectMapper objectMapper) {
        this.vendorAccountMapper = vendorAccountMapper;
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper;
    }

    public AgentModelConfig resolveForExecution(AgentModelConfig config) {
        if (config == null || config.getVendorAccountId() == null) {
            return config;
        }
        ModelVendorAccount account = vendorAccountMapper.findActiveById(config.getVendorAccountId());
        if (account == null || Boolean.FALSE.equals(account.getEnabled())) {
            return config;
        }
        AgentModelConfig merged = copyShallow(config);
        if (isBlank(merged.getBaseUrl()) && !isBlank(account.getBaseUrl())) {
            merged.setBaseUrl(account.getBaseUrl());
        }

        // A bound vendor account is the source of truth for credentials.
        // Model rows may still contain legacy keys from before account binding;
        // those must not override a newly edited vendor account.
        if (!isBlank(account.getApiKey())) {
            merged.setApiKey(account.getApiKey());
        }
        // Shallow-merge extraAuthJson: vendor account keys (auth/region/endpoints) override
        // the model row, but model-private keys (e.g. allowedSizes, capabilities, taskMode)
        // survive. Proxy routing is intentionally model-over-account so a single model can
        // opt in/out of a provider's default network path.
        String mergedExtraAuth = mergeExtraAuthJson(merged.getExtraAuthJson(), account.getExtraAuthJson());
        if (mergedExtraAuth != null) {
            merged.setExtraAuthJson(mergedExtraAuth);
        }

        if (isBlank(merged.getApiKey()) && !isBlank(merged.getExtraAuthJson())) {
            String apiKey = extractApiKeyFromExtraAuth(merged.getExtraAuthJson());
            if (!isBlank(apiKey)) {
                merged.setApiKey(apiKey);
            }
        }
        if (isBlank(merged.getConsoleUrl()) && !isBlank(account.getConsoleUrl())) {
            merged.setConsoleUrl(account.getConsoleUrl());
        }
        if (isBlank(merged.getBalanceUrl()) && !isBlank(account.getBalanceUrl())) {
            merged.setBalanceUrl(account.getBalanceUrl());
        }
        if (isBlank(merged.getBaseUrl())) {
            providerRegistry.findByCode(merged.getProvider())
                    .map(ModelProviderDefinition::defaultBaseUrl)
                    .filter(url -> !isBlank(url))
                    .ifPresent(merged::setBaseUrl);
        }
        return merged;
    }

    private static AgentModelConfig copyShallow(AgentModelConfig source) {
        AgentModelConfig copy = new AgentModelConfig();
        copy.setId(source.getId());
        copy.setVendorAccountId(source.getVendorAccountId());
        copy.setRoutingPoolId(source.getRoutingPoolId());
        copy.setDisplayName(source.getDisplayName());
        copy.setConfigCode(source.getConfigCode());
        copy.setProvider(source.getProvider());
        copy.setModelName(source.getModelName());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setApiKey(source.getApiKey());
        copy.setExtraAuthJson(source.getExtraAuthJson());
        copy.setExecutionTask(source.getExecutionTask());
        copy.setExecutionOptionsJson(source.getExecutionOptionsJson());
        copy.setRequestSchemaJson(source.getRequestSchemaJson());
        copy.setRequestMappingJson(source.getRequestMappingJson());
        copy.setResponseMappingJson(source.getResponseMappingJson());
        copy.setApiContractVersion(source.getApiContractVersion());
        copy.setContractStatus(source.getContractStatus());
        copy.setContractVerifiedAt(source.getContractVerifiedAt());
        copy.setMinimaxGroupId(source.getMinimaxGroupId());
        copy.setConsoleUrl(source.getConsoleUrl());
        copy.setBalanceUrl(source.getBalanceUrl());
        copy.setDocsUrl(source.getDocsUrl());
        copy.setTimeoutSeconds(source.getTimeoutSeconds());
        copy.setInputTokenPricePer1k(source.getInputTokenPricePer1k());
        copy.setOutputTokenPricePer1k(source.getOutputTokenPricePer1k());
        copy.setInputTokenPricePer1m(source.getInputTokenPricePer1m());
        copy.setOutputTokenPricePer1m(source.getOutputTokenPricePer1m());
        copy.setBillingUnit(source.getBillingUnit());
        copy.setUnitPrice(source.getUnitPrice());
        copy.setCapabilities(source.getCapabilities());
        copy.setEnabled(source.getEnabled());
        copy.setAgentEnabled(source.getAgentEnabled());
        copy.setDefault(source.getDefault());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    /**
     * Shallow-merge model and vendor account extraAuthJson with account keys winning on
     * conflicts. Returns null when both inputs are blank (caller keeps existing value);
     * returns account value verbatim when either side is not a JSON object so we never
     * regress the legacy behavior.
     */
    private String mergeExtraAuthJson(String modelExtraAuthJson, String accountExtraAuthJson) {
        boolean modelBlank = isBlank(modelExtraAuthJson);
        boolean accountBlank = isBlank(accountExtraAuthJson);
        if (modelBlank && accountBlank) {
            return null;
        }
        if (modelBlank) {
            return accountExtraAuthJson;
        }
        if (accountBlank) {
            return modelExtraAuthJson;
        }
        try {
            JsonNode modelNode = objectMapper.readTree(modelExtraAuthJson);
            JsonNode accountNode = objectMapper.readTree(accountExtraAuthJson);
            if (!modelNode.isObject() || !accountNode.isObject()) {
                return accountExtraAuthJson;
            }
            ObjectNode result = ((ObjectNode) modelNode).deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = accountNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                result.set(entry.getKey(), entry.getValue());
            }
            copyProxyOverride(modelNode, result, "proxyMode");
            copyProxyUrlOverride(modelNode, result);
            return objectMapper.writeValueAsString(result);
        } catch (Exception ignored) {
            return accountExtraAuthJson;
        }
    }

    private void copyProxyOverride(JsonNode modelNode, ObjectNode result, String key) {
        JsonNode value = modelNode.get(key);
        if (value != null && !value.isNull()) {
            result.set(key, value);
        }
    }

    private void copyProxyUrlOverride(JsonNode modelNode, ObjectNode result) {
        JsonNode value = modelNode.get("proxyUrl");
        if (value != null && !value.isNull() && !value.asText("").isBlank()) {
            result.set("proxyUrl", value);
        }
    }

    private String extractApiKeyFromExtraAuth(String extraAuthJson) {
        try {
            JsonNode root = objectMapper.readTree(extraAuthJson);
            for (String field : new String[]{"apiKey", "api_key", "key", "token", "accessToken", "access_token"}) {
                JsonNode value = root.path(field);
                if (value.isTextual() && !value.asText().isBlank()) {
                    return value.asText().trim();
                }
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
