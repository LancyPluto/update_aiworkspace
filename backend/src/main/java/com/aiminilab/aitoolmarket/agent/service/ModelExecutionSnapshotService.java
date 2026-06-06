package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ModelExecutionSnapshotService {

    private final AgentModelConfigService agentModelConfigService;
    private final ModelCapabilityService modelCapabilityService;
    private final ModelProviderMetadataService modelProviderMetadataService;
    private final ObjectMapper objectMapper;

    public ModelExecutionSnapshotService(AgentModelConfigService agentModelConfigService,
                                         ModelCapabilityService modelCapabilityService,
                                         ModelProviderMetadataService modelProviderMetadataService,
                                         ObjectMapper objectMapper) {
        this.agentModelConfigService = agentModelConfigService;
        this.modelCapabilityService = modelCapabilityService;
        this.modelProviderMetadataService = modelProviderMetadataService;
        this.objectMapper = objectMapper;
    }

    public ModelExecutionSnapshot create(AgentModelConfig modelConfig) {
        if (modelConfig == null) {
            return null;
        }
        AgentModelConfig executable = agentModelConfigService.resolveForExecution(modelConfig);
        List<String> capabilities = modelCapabilityService.resolveCapabilities(modelConfig);
        String metadataVersion = modelProviderMetadataService.metadataVersion(executable.getProvider());
        return ModelExecutionSnapshot.from(executable, capabilities, metadataVersion);
    }

    public String serialize(ModelExecutionSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to serialize model execution snapshot", exception);
        }
    }

    public ModelExecutionSnapshot parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ModelExecutionSnapshot.class);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to parse model execution snapshot", exception);
        }
    }
}
