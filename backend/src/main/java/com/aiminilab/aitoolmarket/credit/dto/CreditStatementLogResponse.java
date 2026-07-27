package com.aiminilab.aitoolmarket.credit.dto;

import java.time.LocalDateTime;

public record CreditStatementLogResponse(
        Long id,
        Long userId,
        String sourceType,
        Long sourceRef,
        String logType,
        Long amount,
        String reason,
        LocalDateTime createdAt,
        String taskNo,
        String toolName,
        Long agentRunId,
        String agentIntent,
        Long workflowRunId,
        Long workflowId,
        String workflowName,
        String workflowNodeId,
        String workflowStepName,
        String modelName,
        String provider
) {
}
