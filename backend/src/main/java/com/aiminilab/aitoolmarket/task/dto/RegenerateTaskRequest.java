package com.aiminilab.aitoolmarket.task.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record RegenerateTaskRequest(
        @NotNull JsonNode params,
        String clientRequestId
) {
}
