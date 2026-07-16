package com.aiminilab.aitoolmarket.workflow.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record WorkflowRunDetailResponse(
        Long taskId,
        Long runId,
        String taskNo,
        String toolCode,
        String toolName,
        String status,
        Integer progress,
        String progressMessage,
        Long currentStepId,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        String errorCode,
        String errorMessage,
        List<WorkflowRunStepResponse> steps,
        List<WorkflowArtifactResponse> artifacts,
        WorkflowCostResponse cost,
        Integer totalCredits,
        Integer reservedCredits,
        Object userAction,
        Object confirmation,
        String adapterKey,
        JsonNode adapterData,
        Long revision
) {

    public record WorkflowRunStepResponse(
            Long id,
            Long stepId,
            String stepCode,
            String stepName,
            String nodeType,
            String status,
            Integer progress,
            String progressMessage,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            String errorCode,
            String errorMessage,
            List<WorkflowArtifactResponse> artifacts,
            List<WorkflowStepChargeResponse> charges
    ) {
    }

    public record WorkflowStepChargeResponse(
            Long id,
            Long stepId,
            String status,
            Integer reservedCredits,
            Integer capturedCredits,
            Integer actualCredits
    ) {
    }

    public record WorkflowCostResponse(
            Integer totalCredits,
            Integer reservedCredits,
            Integer capturedCredits,
            Integer releasedCredits,
            List<WorkflowStepChargeResponse> charges
    ) {
    }

    public record WorkflowArtifactResponse(
            Long id,
            Long artifactId,
            Long stepId,
            String type,
            String name,
            String title,
            String url,
            String downloadUrl,
            Object content,
            Object value,
            String mimeType,
            Long size,
            String adapterKey,
            JsonNode adapterData
    ) {
    }

    public record WorkflowUserActionResponse(
            Long stepId,
            String prompt,
            List<Map<String, Object>> fields,
            List<String> allowedActions,
            String confirmationToken,
            LocalDateTime expiresAt
    ) {
    }
}
