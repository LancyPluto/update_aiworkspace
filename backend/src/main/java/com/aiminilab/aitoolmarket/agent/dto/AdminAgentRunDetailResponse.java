package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record AdminAgentRunDetailResponse(
        AgentRunResponse run,
        List<AgentRunEventResponse> events,
        List<AgentToolCallResponse> toolCalls
) {
}
