package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;

public record UpsertLangGraphCheckpointRequest(
        @NotBlank String threadId,
        String checkpointNs,
        @NotBlank String checkpointId,
        @NotBlank String checkpointSerde,
        @NotBlank String checkpointPayload,
        String metadataJson,
        String parentCheckpointId
) {}
