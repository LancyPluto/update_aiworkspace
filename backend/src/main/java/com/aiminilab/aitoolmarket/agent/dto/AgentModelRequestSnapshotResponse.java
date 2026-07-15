package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelRequestSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

public record AgentModelRequestSnapshotResponse(
        Long id, Long runId, Integer requestSequence, String requestStage, Integer iterationNo,
        String modelProviderCode, String modelName, Integer messageCount, Integer toolCount,
        Integer estimatedInputTokens, List<String> skillCodes, JsonNode payload, String payloadSha256,
        LocalDateTime payloadExpiresAt, boolean payloadExpired, LocalDateTime createdAt
) {
    public static AgentModelRequestSnapshotResponse from(AgentModelRequestSnapshot item, JsonNode payload, List<String> skills) {
        return new AgentModelRequestSnapshotResponse(item.getId(), item.getRunId(), item.getRequestSequence(),
                item.getRequestStage(), item.getIterationNo(), item.getModelProviderCode(), item.getModelName(),
                item.getMessageCount(), item.getToolCount(), item.getEstimatedInputTokens(), skills, payload,
                item.getPayloadSha256(), item.getPayloadExpiresAt(), item.getPayloadJson() == null, item.getCreatedAt());
    }
}
