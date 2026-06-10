package com.aiminilab.aitoolmarket.tool.dto;

public record ToolPromptDraftResponse(
        String systemPrompt,
        String toolPrompt,
        Long modelConfigId,
        String modelName,
        String warning
) {
}
