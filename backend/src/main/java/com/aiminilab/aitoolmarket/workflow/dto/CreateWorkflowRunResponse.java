package com.aiminilab.aitoolmarket.workflow.dto;

public record CreateWorkflowRunResponse(
        Long taskId,
        Long runId,
        Long workflowVersionId,
        String status
) {

    public static CreateWorkflowRunResponse from(WorkflowRunCreated created) {
        return new CreateWorkflowRunResponse(
                created.rootTaskId(),
                created.runId(),
                created.workflowVersionId(),
                created.status()
        );
    }
}
