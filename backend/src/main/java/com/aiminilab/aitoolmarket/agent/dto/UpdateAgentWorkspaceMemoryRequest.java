package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record UpdateAgentWorkspaceMemoryRequest(
        @NotBlank(message = "memoryType is required")
        @Size(max = 32, message = "memoryType is too long")
        String memoryType,
        @NotBlank(message = "title is required")
        @Size(max = 160, message = "title is too long")
        String title,
        @NotBlank(message = "content is required")
        String content,
        @Min(value = 0, message = "importance must be >= 0")
        @Max(value = 10, message = "importance must be <= 10")
        Integer importance,
        Double confidence,
        Boolean pinned,
        String tagsJson,
        String metadataJson,
        LocalDateTime expiresAt
) {
}
