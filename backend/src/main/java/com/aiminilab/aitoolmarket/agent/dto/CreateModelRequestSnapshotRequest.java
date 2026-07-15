package com.aiminilab.aitoolmarket.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record CreateModelRequestSnapshotRequest(
        @NotNull @Min(1) Integer requestSequence,
        @NotBlank String requestStage,
        Integer iterationNo,
        String modelProviderCode,
        String modelName,
        @Min(0) Integer messageCount,
        @Min(0) Integer toolCount,
        @Min(0) Integer estimatedInputTokens,
        List<String> skillCodes,
        @NotNull JsonNode payload
) {}
