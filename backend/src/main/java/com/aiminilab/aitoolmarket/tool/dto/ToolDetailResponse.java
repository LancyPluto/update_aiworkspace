package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import com.aiminilab.aitoolmarket.tool.support.ToolFrontendStyleConfig;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDetailResponse(
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
        Long defaultModelConfigId,
        List<ToolSupportedModelResponse> supportedModels,
        List<ToolFieldResponse> fields,
        ToolFrontendStyleConfig frontendStyle,
        // 平台级集成块；标准任务工具的 integrationMode 为 STANDARD_TASK。
        // 前端入口判断、跳转、插件渲染均以本字段为准。
        ToolIntegrationView integration,
        // 过渡兼容字段；新代码请读取 integration().extension()（PPT 插件返回 PptWorkflow）。
        PptWorkflow workflow
) {
    public static ToolDetailResponse of(ToolSummaryResponse summary, List<ToolFieldResponse> fields) {
        return of(summary, fields, null, null, summary.modelConfigId(), List.of());
    }

    public static ToolDetailResponse of(ToolSummaryResponse summary,
                                        List<ToolFieldResponse> fields,
                                        ToolIntegrationView integration) {
        PptWorkflow legacy = integration != null && integration.extension() instanceof PptWorkflow w ? w : null;
        return of(summary, fields, integration, legacy, summary.modelConfigId(), List.of());
    }

    public static ToolDetailResponse of(ToolSummaryResponse summary,
                                        List<ToolFieldResponse> fields,
                                        ToolIntegrationView integration,
                                        PptWorkflow legacyWorkflow) {
        return of(summary, fields, integration, legacyWorkflow, summary.modelConfigId(), List.of());
    }

    public static ToolDetailResponse of(ToolSummaryResponse summary,
                                        List<ToolFieldResponse> fields,
                                        ToolIntegrationView integration,
                                        PptWorkflow legacyWorkflow,
                                        Long defaultModelConfigId,
                                        List<ToolSupportedModelResponse> supportedModels) {
        return new ToolDetailResponse(
                summary.id(),
                summary.toolCode(),
                summary.toolName(),
                summary.categoryId(),
                summary.categoryCode(),
                summary.categoryName(),
                summary.description(),
                summary.coverUrl(),
                summary.toolType(),
                summary.inputModality(),
                summary.outputModality(),
                summary.toolKind(),
                summary.configNote(),
                summary.status(),
                summary.estimatedCreditCost(),
                summary.variableCreditPricing(),
                summary.modelConfigId(),
                summary.modelConfigName(),
                summary.modelName(),
                summary.executionHandler(),
                summary.requiredModelCapabilities(),
                summary.executionMode(),
                summary.billingMode(),
                summary.agentSurfaceEnabled(),
                summary.workflowConfigured(),
                summary.workflowExecutionEnabled(),
                summary.publishedWorkflowVersionId(),
                summary.workflowUsable(),
                defaultModelConfigId,
                supportedModels == null ? List.of() : supportedModels,
                fields,
                summary.frontendStyle(),
                integration,
                legacyWorkflow
        );
    }
}
