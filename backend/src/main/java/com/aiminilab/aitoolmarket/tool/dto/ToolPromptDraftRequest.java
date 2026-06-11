package com.aiminilab.aitoolmarket.tool.dto;

import java.util.List;

public record ToolPromptDraftRequest(
        Long modelConfigId,
        String toolName,
        String description,
        String toolKind,
        String coverUrl,
        List<ToolPromptDraftFieldRequest> userInputs
) {
}
