package com.aiminilab.aitoolmarket.agent.client;

import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentFileParseResult;

public interface AgentServiceClient {

    void executeRun(Long runId);

    void confirmTool(Long runId, String toolCode);

    AgentFileParseResult parseFile(String filename, String contentType, byte[] content);

    AgentModelConfigTestResponse testModelConfig(AgentModelConfigRequest request);
}
