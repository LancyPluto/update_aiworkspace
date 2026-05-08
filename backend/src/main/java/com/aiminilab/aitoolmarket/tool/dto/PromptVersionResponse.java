package com.aiminilab.aitoolmarket.tool.dto;

import java.time.LocalDateTime;

public record PromptVersionResponse(
        Long id,
        Long promptId,
        String versionNo,
        String systemPrompt,
        String userPromptTemplate,
        String outputFormat,
        String status,
        LocalDateTime createdAt,
        LocalDateTime publishedAt
) {
}
