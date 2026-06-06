package com.aiminilab.aitoolmarket.agent.provider;

import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.dto.ModelProviderResponse;

public interface ModelProviderAdapter {
    String providerCode();

    ModelProviderResponse metadata();

    AgentModelConfigTestResponse testConnection(AgentModelConfigRequest request);
}
