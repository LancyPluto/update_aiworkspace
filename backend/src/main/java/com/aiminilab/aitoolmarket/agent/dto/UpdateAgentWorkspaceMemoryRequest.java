package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateAgentWorkspaceMemoryRequest(
        @NotBlank(message = "memoryType is required")
        @Size(max = 32, message = "memoryType is too long")
        String memoryType,
        @NotBlank(message = "title is required")
        @Size(max = 160, message = "title is too long")
        String title,
        @NotBlank(message = "content is required")
        String content
) {
}
