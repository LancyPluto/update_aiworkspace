package com.aiminilab.aitoolmarket.agent.dto;

public record LangGraphCheckpointResponse(
        Long runId,
        String threadId,
        String checkpointNs,
        String checkpointSerde,
        String checkpointPayload,
        String metadataJson,
        String checkpointId,
        String parentCheckpointId,
        java.util.List<LangGraphCheckpointWriteResponse> pendingWrites
) {}
