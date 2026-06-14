package com.aiminilab.aitoolmarket.task.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

/**
 * Request for the authoritative pre-execution price estimate. Mirrors {@link CreateTaskRequest}
 * inputs that affect pricing so the preview matches the amount that will be frozen on submit.
 */
public record EstimateTaskRequest(
        @NotBlank String toolCode,
        JsonNode params,
        Long modelConfigId
) {
}
