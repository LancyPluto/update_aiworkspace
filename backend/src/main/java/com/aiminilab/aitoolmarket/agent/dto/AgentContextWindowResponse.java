package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentContextSnapshot;

public record AgentContextWindowResponse(
        Long snapshotId,
        String strategy,
        Integer maxHistoryMessages,
        Integer historyMessageCount,
        Integer fileCount,
        Integer fileChunkCount,
        Integer memoryItemCount,
        Integer estimatedInputTokens,
        String snapshotJson
) {
    public static AgentContextWindowResponse from(AgentContextSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new AgentContextWindowResponse(
                snapshot.getId(),
                snapshot.getStrategy(),
                snapshot.getMaxHistoryMessages(),
                snapshot.getHistoryMessageCount(),
                snapshot.getFileCount(),
                snapshot.getFileChunkCount(),
                snapshot.getMemoryItemCount(),
                snapshot.getEstimatedInputTokens(),
                snapshot.getSnapshotJson()
        );
    }
}
