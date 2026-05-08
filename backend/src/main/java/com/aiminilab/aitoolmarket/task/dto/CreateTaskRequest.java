package com.aiminilab.aitoolmarket.task.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateTaskRequest(
        @NotBlank String toolCode,
        @NotNull JsonNode params,
        String clientRequestId
) {
}
