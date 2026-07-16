package com.aiminilab.aitoolmarket.workflow.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record CreateWorkflowRunCommand(
        Long userId,
        String toolCode,
        JsonNode input,
        String clientRequestId,
        String launchSource,
        Long agentToolCallId
) {
}
