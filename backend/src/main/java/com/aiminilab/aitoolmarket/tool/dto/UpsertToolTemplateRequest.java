package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record UpsertToolTemplateRequest(
        @NotBlank String templateCode,
        @NotBlank String templateName,
        @NotBlank String toolType,
        @NotBlank String executionHandler,
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
        @NotEmpty @Valid List<ToolFieldRequest> fields
) {
}
