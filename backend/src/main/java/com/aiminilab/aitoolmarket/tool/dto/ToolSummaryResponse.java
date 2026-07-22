package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.support.ToolFrontendStyleConfig;
import com.aiminilab.aitoolmarket.tool.support.ToolKindSupport;
import com.aiminilab.aitoolmarket.tool.support.ToolModelCapabilitySupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolSummaryResponse(
        Long id,
        String toolCode,
        String toolName,
        Long categoryId,
        String categoryCode,
        String categoryName,
        String description,
        String coverUrl,
        String toolType,
        String inputModality,
        String outputModality,
        String toolKind,
        String configNote,
        String status,
        Integer estimatedCreditCost,
        Boolean variableCreditPricing,
        Long modelConfigId,
        String modelConfigName,
        String modelName,
        String executionHandler,
        List<String> requiredModelCapabilities,
        String executionMode,
        String billingMode,
        Boolean agentSurfaceEnabled,
        Boolean workflowConfigured,
        Boolean workflowExecutionEnabled,
        Long publishedWorkflowVersionId,
        Boolean workflowUsable,
        ToolFrontendStyleConfig frontendStyle
) {
    private static final ObjectMapper CAPABILITY_OBJECT_MAPPER = new ObjectMapper();

    public static ToolSummaryResponse from(AiTool tool) {
        return from(tool, tool.getEstimatedCreditCost());
    }

    public static ToolSummaryResponse from(AiTool tool, Integer estimatedCreditCost) {
        return from(tool, estimatedCreditCost, false);
    }

    public static ToolSummaryResponse from(AiTool tool, Integer estimatedCreditCost, boolean variableCreditPricing) {
        return new ToolSummaryResponse(
                tool.getId(),
                tool.getToolCode(),
                tool.getToolName(),
                tool.getCategoryId(),
                tool.getCategoryCode(),
                tool.getCategoryName(),
                tool.getDescription(),
                tool.getCoverUrl(),
                tool.getToolType(),
                tool.getInputModality(),
                tool.getOutputModality(),
                ToolKindSupport.resolve(tool),
                tool.getConfigNote(),
                tool.getStatus(),
                variableCreditPricing ? null : estimatedCreditCost,
                variableCreditPricing,
                tool.getModelConfigId(),
                tool.getModelConfigName(),
                tool.getModelName(),
                tool.getExecutionHandler(),
                ToolModelCapabilitySupport.resolve(tool, CAPABILITY_OBJECT_MAPPER),
                tool.getExecutionMode(),
                tool.getBillingMode(),
                tool.getAgentSurfaceEnabled(),
                null,
                null,
                null,
                null,
                null
        );
    }

    public ToolSummaryResponse withWorkflowState(
            boolean workflowConfigured,
            Boolean workflowExecutionEnabled,
            Long publishedWorkflowVersionId,
            boolean workflowUsable
    ) {
        return new ToolSummaryResponse(
                id,
                toolCode,
                toolName,
                categoryId,
                categoryCode,
                categoryName,
                description,
                coverUrl,
                toolType,
                inputModality,
                outputModality,
                toolKind,
                configNote,
                status,
                estimatedCreditCost,
                variableCreditPricing,
                modelConfigId,
                modelConfigName,
                modelName,
                executionHandler,
                requiredModelCapabilities,
                executionMode,
                billingMode,
                agentSurfaceEnabled,
                workflowConfigured,
                workflowExecutionEnabled,
                publishedWorkflowVersionId,
                workflowUsable,
                frontendStyle
        );
    }

    public ToolSummaryResponse withSanitizedCoverUrl(String coverUrl) {
        if (coverUrl == null ? this.coverUrl == null : coverUrl.equals(this.coverUrl)) {
            return this;
        }
        return new ToolSummaryResponse(
                id,
                toolCode,
                toolName,
                categoryId,
                categoryCode,
                categoryName,
                description,
                coverUrl,
                toolType,
                inputModality,
                outputModality,
                toolKind,
                configNote,
                status,
                estimatedCreditCost,
                variableCreditPricing,
                modelConfigId,
                modelConfigName,
                modelName,
                executionHandler,
                requiredModelCapabilities,
                executionMode,
                billingMode,
                agentSurfaceEnabled,
                workflowConfigured,
                workflowExecutionEnabled,
                publishedWorkflowVersionId,
                workflowUsable,
                frontendStyle
        );
    }
}
