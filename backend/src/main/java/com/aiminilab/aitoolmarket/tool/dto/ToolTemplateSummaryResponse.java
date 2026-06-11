package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.entity.ToolTemplate;

public record ToolTemplateSummaryResponse(
        Long id,
        String templateCode,
        String templateName,
        String toolType,
        String executionHandler,
        String inputModality,
        String outputModality,
        String configNote,
        String handlerConfigJson,
        Long suggestedModelConfigId,
        String status,
        Integer sortOrder,
        Boolean systemTemplate
) {
    public static ToolTemplateSummaryResponse from(ToolTemplate template) {
        return new ToolTemplateSummaryResponse(
                template.getId(),
                template.getTemplateCode(),
                template.getTemplateName(),
                template.getToolType(),
                template.getExecutionHandler(),
                template.getInputModality(),
                template.getOutputModality(),
                template.getConfigNote(),
                template.getHandlerConfigJson(),
                template.getSuggestedModelConfigId(),
                template.getStatus(),
                template.getSortOrder(),
                Boolean.TRUE.equals(template.getSystemTemplate())
        );
    }
}
