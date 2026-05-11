package com.aiminilab.aitoolmarket.agent.dto;

public record CreateAgentMessageResponse(
        Long sessionId,
        Long messageId,
        Long runId,
        String runStatus
) {
}
