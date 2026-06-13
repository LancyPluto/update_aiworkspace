package com.aiminilab.aitoolmarket.agent.dto;

public record AgentGraphCheckpointResponse(
        Long runId,
        String checkpointJson
) {
}
