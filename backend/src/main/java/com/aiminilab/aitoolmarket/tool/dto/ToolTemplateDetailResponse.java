package com.aiminilab.aitoolmarket.tool.dto;

import com.aiminilab.aitoolmarket.tool.entity.ToolTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public record ToolTemplateDetailResponse(
        Long id,
        String templateCode,
        String templateName,
        String toolType,
        String executionHandler,
        String inputModality,
        String outputModality,
        String configNote,
        String defaultSystemPrompt,
        String defaultUserPromptTemplate,
        String defaultOutputFormat,
        String handlerConfigJson,
        Long suggestedModelConfigId,
        String status,
        Integer sortOrder,
        Boolean systemTemplate,
        List<ToolFieldResponse> fields
) {
    public static ToolTemplateDetailResponse of(ToolTemplate template, List<ToolFieldResponse> fields) {
        return new ToolTemplateDetailResponse(
                template.getId(),
                template.getTemplateCode(),
                template.getTemplateName(),
                template.getToolType(),
                template.getExecutionHandler(),
                template.getInputModality(),
                template.getOutputModality(),
                template.getConfigNote(),
                template.getDefaultSystemPrompt(),
                template.getDefaultUserPromptTemplate(),
                template.getDefaultOutputFormat(),
                template.getHandlerConfigJson(),
                template.getSuggestedModelConfigId(),
                template.getStatus(),
                template.getSortOrder(),
                Boolean.TRUE.equals(template.getSystemTemplate()),
                fields
        );
    }

    public static ToolFieldResponse fieldFromTemplateField(
            com.aiminilab.aitoolmarket.tool.entity.ToolTemplateField field,
            ObjectMapper objectMapper) {
        return ToolFieldResponse.fromTemplateField(field, objectMapper);
    }
}
