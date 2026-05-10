package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.constraints.NotBlank;

public record CreatePromptVersionRequest(
        @NotBlank String versionNo,
        String systemPrompt,
        @NotBlank String userPromptTemplate,
        String outputFormat
) {
}
