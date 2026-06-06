package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.config.ModelProviderDefinition;
import com.aiminilab.aitoolmarket.agent.config.ModelProviderRegistry;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.dto.ModelProviderResponse;
import com.aiminilab.aitoolmarket.agent.entity.ModelProviderMetadata;
import com.aiminilab.aitoolmarket.agent.mapper.ModelProviderMetadataMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ModelProviderMetadataService {

    private final ModelProviderMetadataMapper metadataMapper;
    private final ModelProviderRegistry fallbackRegistry;
    private final ObjectMapper objectMapper;

    public ModelProviderMetadataService(ModelProviderMetadataMapper metadataMapper,
                                        ModelProviderRegistry fallbackRegistry,
                                        ObjectMapper objectMapper) {
        this.metadataMapper = metadataMapper;
        this.fallbackRegistry = fallbackRegistry;
        this.objectMapper = objectMapper;
    }

    public List<ModelProviderResponse> list(String capability) {
        List<ModelProviderResponse> stored = safeEnabledMetadata().stream()
                .map(this::toResponse)
                .filter(provider -> capability == null || capability.isBlank()
                        || provider.capabilities().stream().anyMatch(cap -> cap.equalsIgnoreCase(capability.trim())))
                .toList();
        if (!stored.isEmpty()) {
            return stored;
        }
        return fallbackRegistry.listByCapability(capability).stream()
                .map(ModelProviderResponse::from)
                .toList();
    }

    public ModelProviderResponse get(String providerCode) {
        ModelProviderMetadata stored = safeFind(providerCode);
        if (stored != null) {
            return toResponse(stored);
        }
        return fallbackRegistry.findByCode(providerCode)
                .map(ModelProviderResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "model provider not found"));
    }

    public String metadataVersion(String providerCode) {
        return get(providerCode).metadataVersion();
    }

    public AgentModelConfigTestResponse acceptOnlyTest(AgentModelConfigRequest request) {
        ModelProviderResponse provider = get(request.provider());
        return new AgentModelConfigTestResponse(
                true,
                request.provider(),
                request.modelName(),
                0L,
                provider.description() == null || provider.description().isBlank()
                        ? "provider config accepted; adapter will validate at runtime"
                        : provider.description(),
                ""
        );
    }

    private List<ModelProviderMetadata> safeEnabledMetadata() {
        try {
            return metadataMapper.findEnabled();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private ModelProviderMetadata safeFind(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return null;
        }
        try {
            return metadataMapper.findEnabledByCode(providerCode.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private ModelProviderResponse toResponse(ModelProviderMetadata metadata) {
        return new ModelProviderResponse(
                metadata.getProviderCode(),
                metadata.getLabel(),
                parseCapabilities(metadata.getCapabilitiesJson()),
                nullToEmpty(metadata.getDefaultBaseUrl()),
                nullToEmpty(metadata.getDefaultModel()),
                nullToEmpty(metadata.getBillingDefault()),
                nullToEmpty(metadata.getProviderProtocol()),
                nullToEmpty(metadata.getVendorKind()),
                nullToEmpty(metadata.getUpstreamVendor()),
                nullToEmpty(metadata.getTestStrategy()),
                !Boolean.FALSE.equals(metadata.getWorkerReady()),
                !Boolean.FALSE.equals(metadata.getAdapterInstalled()),
                nullToEmpty(metadata.getAdapterKey()),
                nullToEmpty(metadata.getMetadataVersion()).isBlank() ? "db" : metadata.getMetadataVersion(),
                nullToEmpty(metadata.getAuthSchemaJson()),
                nullToEmpty(metadata.getModelParamSchemaJson()),
                nullToEmpty(metadata.getDescription())
        );
    }

    private List<String> parseCapabilities(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readerForListOf(String.class).readValue(value);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
