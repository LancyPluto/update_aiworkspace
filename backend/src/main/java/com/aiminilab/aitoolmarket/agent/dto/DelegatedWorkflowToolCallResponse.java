package com.aiminilab.aitoolmarket.agent.dto;

public record DelegatedWorkflowToolCallResponse(
        Long taskId,
        Long runId,
        String status,
        String runUrl
) {
    public static DelegatedWorkflowToolCallResponse of(Long taskId, Long runId, String status) {
        return new DelegatedWorkflowToolCallResponse(
                taskId,
                runId,
                status,
                "/agents/runs/" + taskId
        );
    }
}
