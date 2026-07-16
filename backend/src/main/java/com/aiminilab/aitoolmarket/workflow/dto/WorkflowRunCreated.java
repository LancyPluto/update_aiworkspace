package com.aiminilab.aitoolmarket.workflow.dto;

public record WorkflowRunCreated(
        Long rootTaskId,
        Long runId,
        Long workflowVersionId,
        String status
) {
}
