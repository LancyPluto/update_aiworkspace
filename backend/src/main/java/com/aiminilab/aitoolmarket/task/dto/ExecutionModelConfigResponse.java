package com.aiminilab.aitoolmarket.task.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.dto.ProxyPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public record ExecutionModelConfigResponse(
        Long id,
        Long vendorAccountId,
        String displayName,
        String configCode,
        String provider,
        String modelName,
        String baseUrl,
        String apiKey,
        String extraAuthJson,
        String executionTask,
        String executionOptionsJson,
        String minimaxGroupId,
        Integer timeoutSeconds,
        List<String> capabilities,
        String credentialSource,
        String credentialFingerprint,
        ProxyPolicy proxyPolicy
) {
    public static ExecutionModelConfigResponse from(AgentModelConfig config, List<String> capabilities) {
        return from(config, capabilities, null);
    }

    public static ExecutionModelConfigResponse from(AgentModelConfig config, List<String> capabilities, ProxyPolicy proxyPolicy) {
        if (config == null) {
            return null;
        }
        return new ExecutionModelConfigResponse(
                config.getId(),
                config.getVendorAccountId(),
                config.getDisplayName(),
                config.getConfigCode(),
                config.getProvider(),
                config.getModelName(),
                config.getBaseUrl(),
                config.getApiKey(),
                config.getExtraAuthJson(),
                config.getExecutionTask(),
                config.getExecutionOptionsJson(),
                config.getMinimaxGroupId(),
                config.getTimeoutSeconds(),
                capabilities == null ? List.of() : capabilities,
                credentialSource(config.getVendorAccountId(), config.getApiKey(), config.getExtraAuthJson()),
                credentialFingerprint(config.getApiKey(), config.getExtraAuthJson()),
                proxyPolicy
        );
    }

    public static ExecutionModelConfigResponse from(ModelExecutionSnapshot snapshot) {
        return from(snapshot, snapshot == null ? null : snapshot.proxyPolicy());
    }

    public static ExecutionModelConfigResponse from(ModelExecutionSnapshot snapshot, ProxyPolicy runtimeProxyPolicy) {
        if (snapshot == null) {
            return null;
        }
        return new ExecutionModelConfigResponse(
                snapshot.id(),
                snapshot.vendorAccountId(),
                snapshot.displayName(),
                snapshot.configCode(),
                snapshot.provider(),
                snapshot.modelName(),
                snapshot.baseUrl(),
                snapshot.apiKey(),
                snapshot.extraAuthJson(),
                snapshot.executionTask(),
                snapshot.executionOptionsJson(),
                snapshot.minimaxGroupId(),
                snapshot.timeoutSeconds(),
                snapshot.capabilities() == null ? List.of() : snapshot.capabilities(),
                credentialSource(snapshot.vendorAccountId(), snapshot.apiKey(), snapshot.extraAuthJson()),
                credentialFingerprint(snapshot.apiKey(), snapshot.extraAuthJson()),
                runtimeProxyPolicy
        );
    }

    private static String credentialSource(Long vendorAccountId, String apiKey, String extraAuthJson) {
        if (vendorAccountId != null && hasSecret(apiKey, extraAuthJson)) {
            return "vendor_account:" + vendorAccountId;
        }
        if (hasSecret(apiKey, extraAuthJson)) {
            return "model_config";
        }
        return "missing";
    }

    private static String credentialFingerprint(String apiKey, String extraAuthJson) {
        String secret = !isBlank(extraAuthJson) ? extraAuthJson.trim() : (!isBlank(apiKey) ? apiKey.trim() : "");
        if (secret.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < Math.min(6, hash.length); index++) {
                builder.append(String.format("%02x", hash[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            return "";
        }
    }

    private static boolean hasSecret(String apiKey, String extraAuthJson) {
        return !isBlank(apiKey) || !isBlank(extraAuthJson);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || value.trim().startsWith("replace-with-");
    }
}
