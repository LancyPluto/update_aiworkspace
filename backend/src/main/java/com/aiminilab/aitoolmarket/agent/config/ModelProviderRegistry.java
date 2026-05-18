package com.aiminilab.aitoolmarket.agent.config;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class ModelProviderRegistry {

    private Map<String, ModelProviderDefinition> providersByCode = Map.of();

    @PostConstruct
    void load() {
        try (InputStream input = new ClassPathResource("model-providers.yml").getInputStream()) {
            Object loaded = new Yaml().load(input);
            if (!(loaded instanceof Map<?, ?> root)) {
                throw new IllegalStateException("model-providers.yml root must be a map");
            }
            Object providersNode = root.get("providers");
            if (!(providersNode instanceof List<?> providers)) {
                throw new IllegalStateException("model-providers.yml must contain providers list");
            }
            Map<String, ModelProviderDefinition> map = new LinkedHashMap<>();
            for (Object item : providers) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                ModelProviderDefinition definition = parseDefinition(raw);
                map.put(definition.code(), definition);
            }
            this.providersByCode = Collections.unmodifiableMap(map);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load model-providers.yml", exception);
        }
    }

    public boolean isSupported(String providerCode) {
        return providerCode != null && providersByCode.containsKey(providerCode.trim());
    }

    public Optional<ModelProviderDefinition> findByCode(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providersByCode.get(providerCode.trim()));
    }

    public List<ModelProviderDefinition> listAll() {
        return List.copyOf(providersByCode.values());
    }

    public List<ModelProviderDefinition> listByCapability(String capability) {
        if (capability == null || capability.isBlank()) {
            return listAll();
        }
        String normalized = capability.trim().toUpperCase(Locale.ROOT);
        return providersByCode.values().stream()
                .filter(definition -> definition.capabilities().stream()
                        .anyMatch(item -> item.equalsIgnoreCase(normalized)))
                .collect(Collectors.toList());
    }

    public void requireCapability(String providerCode, String capability) {
        ModelProviderDefinition definition = findByCode(providerCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "unsupported model provider: " + providerCode));
        String normalized = capability == null ? "" : capability.trim().toUpperCase(Locale.ROOT);
        boolean supported = definition.capabilities().stream()
                .anyMatch(item -> item.equalsIgnoreCase(normalized));
        if (!supported) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "provider " + providerCode + " does not support capability " + normalized);
        }
    }

    public void requireWorkerReady(String providerCode) {
        ModelProviderDefinition definition = findByCode(providerCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "unsupported model provider: " + providerCode));
        if (!definition.workerReady()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "provider " + providerCode + " is configured but worker executor is not ready yet");
        }
    }

    public String defaultBillingUnit(String providerCode) {
        return findByCode(providerCode)
                .map(ModelProviderDefinition::billingDefault)
                .orElse("TOKEN_PER_M");
    }

    public List<String> defaultCapabilities(String providerCode) {
        return findByCode(providerCode)
                .map(ModelProviderDefinition::capabilities)
                .orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private static ModelProviderDefinition parseDefinition(Map<?, ?> raw) {
        String code = stringValue(raw.get("code"));
        if (code.isBlank()) {
            throw new IllegalStateException("provider code is required");
        }
        List<String> capabilities = new ArrayList<>();
        Object capabilitiesNode = raw.get("capabilities");
        if (capabilitiesNode instanceof List<?> list) {
            for (Object entry : list) {
                if (entry != null) {
                    capabilities.add(entry.toString().trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return new ModelProviderDefinition(
                code,
                stringValue(raw.get("label")),
                List.copyOf(capabilities),
                stringValue(raw.get("defaultBaseUrl")),
                stringValue(raw.get("defaultModel")),
                stringValue(raw.get("billingDefault")),
                stringValue(raw.get("testStrategy")),
                booleanValue(raw.get("workerReady"), true),
                stringValue(raw.get("description"))
        );
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }
}
