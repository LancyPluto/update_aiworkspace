package com.aiminilab.aitoolmarket.task.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ProviderCheckpointRequest(
        @NotNull JsonNode checkpoint,
        @NotNull @Min(0) Integer expectedVersion,
        String claimToken
) {
}
