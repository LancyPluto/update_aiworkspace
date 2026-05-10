package com.aiminilab.aitoolmarket.tool.dto;

public record PromptResponse(
        Long id,
        Long toolId,
        String promptCode,
        String promptName,
        Long activeVersionId,
        String status
) {
}
