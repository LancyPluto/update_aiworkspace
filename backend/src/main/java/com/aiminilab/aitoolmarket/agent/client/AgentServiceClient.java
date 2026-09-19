package com.aiminilab.aitoolmarket.agent.client;

import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigRequest;
import com.aiminilab.aitoolmarket.agent.dto.AgentModelConfigTestResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentFileParseResult;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRouteDebugResponse;
import com.aiminilab.aitoolmarket.agent.dto.InternalAgentRunContextResponse;
import com.aiminilab.aitoolmarket.agent.dto.MarketChatCompletionRequest;
import com.aiminilab.aitoolmarket.agent.dto.MarketChatCompletionResponse;

public interface AgentServiceClient {

    void executeRun(Long runId);

    default void recoverRun(Long runId, String ownerToken) { throw new UnsupportedOperationException("Recovery unavailable"); }

    void confirmTool(Long runId, String toolCode);

    AgentFileParseResult parseFile(String filename, String contentType, byte[] content);

    AgentModelConfigTestResponse testModelConfig(AgentModelConfigRequest request);

    AdminAgentRouteDebugResponse debugRoute(InternalAgentRunContextResponse context);

    MarketChatCompletionResponse marketChatCompletion(MarketChatCompletionRequest request);
}
