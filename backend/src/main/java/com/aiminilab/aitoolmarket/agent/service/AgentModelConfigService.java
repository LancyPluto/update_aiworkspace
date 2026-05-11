package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentModelConfigResponse;

public interface AgentModelConfigService {
    AgentModelConfigResponse adminGet();

    AgentModelConfigResponse adminSave(AgentModelConfigRequest request);

    AgentModelConfigTestResponse adminTest(AgentModelConfigRequest request);

    InternalAgentModelConfigResponse internalGet();
}
