package com.aiminilab.aitoolmarket.tool.dto;

import jakarta.validation.constraints.NotBlank;

public record CreatePromptRequest(
        @NotBlank String promptCode,
        @NotBlank String promptName
) {
}
