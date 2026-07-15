package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentContextSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

public record AdminAgentRunContextSnapshotResponse(
        Long snapshotId, Long sessionId, Long workspaceId, Long userId, String userMessage,
        List<AdminAgentFileSnapshotResponse> agentFiles, String strategy, Integer historyMessageCount,
        Integer fileChunkCount, Integer memoryItemCount, Integer estimatedInputTokens,
        String payloadSha256, JsonNode snapshot
) {
    public static AdminAgentRunContextSnapshotResponse from(AgentContextSnapshot item, ObjectMapper mapper) {
        if (item == null) return null;
        JsonNode payload;
        try { payload = item.getSnapshotJson() == null ? mapper.nullNode() : mapper.readTree(item.getSnapshotJson()); }
        catch (Exception ignored) { payload = mapper.nullNode(); }
        List<AdminAgentFileSnapshotResponse> files = new ArrayList<>();
        for (JsonNode file : payload.path("includedFiles")) {
            files.add(new AdminAgentFileSnapshotResponse(nullableLong(file.path("id")), file.path("filename").asText(""),
                    file.path("contentType").asText(null), file.path("status").asText(""), null));
        }
        return new AdminAgentRunContextSnapshotResponse(item.getId(), item.getSessionId(), item.getWorkspaceId(), item.getUserId(),
                payload.path("userMessage").asText(""), files, item.getStrategy(), item.getHistoryMessageCount(),
                item.getFileChunkCount(), item.getMemoryItemCount(), item.getEstimatedInputTokens(), item.getPayloadSha256(), payload);
    }

    private static Long nullableLong(JsonNode node) { return node == null || !node.canConvertToLong() ? null : node.asLong(); }
}
