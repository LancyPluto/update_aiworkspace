package com.aiminilab.aitoolmarket.task.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record ProviderCheckpointResponse(
        Long taskId,
        JsonNode checkpoint,
        Integer version
) {
}
