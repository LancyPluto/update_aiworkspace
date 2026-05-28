package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.tool.entity.AiTool;

public record AdminAgentToolAccessResponse(
        Long id,
        String toolCode,
        String toolName,
        Long categoryId,
        String categoryName,
        String description,
        String coverUrl,
        String toolType,
        String inputModality,
        String outputModality,
        String configNote,
        String status,
        Integer estimatedCreditCost,
        Long modelConfigId,
        String modelConfigName,
        String modelName,
        String executionHandler,
        Boolean agentEnabled,
        String healthStatus,
        String healthMessage,
        java.time.LocalDateTime healthCheckedAt
) {
    public static AdminAgentToolAccessResponse from(AiTool tool, boolean agentEnabled) {
        return from(tool, agentEnabled, "UNKNOWN", null, null);
    }

    public static AdminAgentToolAccessResponse from(AiTool tool,
                                                    boolean agentEnabled,
                                                    String healthStatus,
                                                    String healthMessage,
                                                    java.time.LocalDateTime healthCheckedAt) {
        return new AdminAgentToolAccessResponse(
                tool.getId(),
                tool.getToolCode(),
                tool.getToolName(),
                tool.getCategoryId(),
                tool.getCategoryName(),
                tool.getDescription(),
                tool.getCoverUrl(),
                tool.getToolType(),
                tool.getInputModality(),
                tool.getOutputModality(),
                tool.getConfigNote(),
                tool.getStatus(),
                tool.getEstimatedCreditCost(),
                tool.getModelConfigId(),
                tool.getModelConfigName(),
                tool.getModelName(),
                tool.getExecutionHandler(),
                agentEnabled,
                healthStatus == null || healthStatus.isBlank() ? "UNKNOWN" : healthStatus,
                healthMessage,
                healthCheckedAt
        );
    }
}
