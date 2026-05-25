package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;

import java.util.List;

public record ToolDetailResponse(
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
        List<ToolFieldResponse> fields,
        // 平台级集成块；标准任务工具的 integrationMode 为 STANDARD_TASK。
        // 前端入口判断、跳转、插件渲染均以本字段为准。
        ToolIntegrationView integration,
        // 过渡兼容字段；新代码请读取 integration().extension()（PPT 插件返回 PptWorkflow）。
        PptWorkflow workflow
) {
    public static ToolDetailResponse of(ToolSummaryResponse summary, List<ToolFieldResponse> fields) {
        return of(summary, fields, null, null);
    }

    public static ToolDetailResponse of(ToolSummaryResponse summary,
                                        List<ToolFieldResponse> fields,
                                        ToolIntegrationView integration) {
        PptWorkflow legacy = integration != null && integration.extension() instanceof PptWorkflow w ? w : null;
        return of(summary, fields, integration, legacy);
    }

    public static ToolDetailResponse of(ToolSummaryResponse summary,
                                        List<ToolFieldResponse> fields,
                                        ToolIntegrationView integration,
                                        PptWorkflow legacyWorkflow) {
        return new ToolDetailResponse(
                summary.id(),
                summary.toolCode(),
                summary.toolName(),
                summary.categoryId(),
                summary.categoryName(),
                summary.description(),
                summary.coverUrl(),
                summary.toolType(),
                summary.inputModality(),
                summary.outputModality(),
                summary.configNote(),
                summary.status(),
                summary.estimatedCreditCost(),
                summary.modelConfigId(),
                summary.modelConfigName(),
                summary.modelName(),
                summary.executionHandler(),
                fields,
                integration,
                legacyWorkflow
        );
    }
}
