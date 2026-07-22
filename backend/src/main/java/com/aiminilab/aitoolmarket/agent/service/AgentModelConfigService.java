package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentSelectableModelResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentModelConfigResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;

import java.util.List;

public interface AgentModelConfigService {
    AgentModelConfigResponse adminGet();

    List<AgentModelConfigResponse> adminList();

    List<AgentSelectableModelResponse> agentSelectableList();

    AgentModelConfigResponse adminCreate(AgentModelConfigRequest request);

    AgentModelConfigResponse adminUpdate(Long id, AgentModelConfigRequest request);

    AgentModelConfigResponse adminSave(AgentModelConfigRequest request);

    AgentModelConfigResponse adminSetDefault(Long id);

    void adminDelete(Long id);

    AgentModelConfigTestResponse adminTest(AgentModelConfigRequest request);

    AgentModelConfigTestResponse adminTestById(Long id);

    InternalAgentModelConfigResponse internalGet();

    InternalAgentModelConfigResponse internalGet(Long modelConfigId);

    AgentModelConfig resolveForExecution(AgentModelConfig config);
}
