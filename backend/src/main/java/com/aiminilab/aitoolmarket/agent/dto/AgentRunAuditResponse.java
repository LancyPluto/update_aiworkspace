package com.aiminilab.aitoolmarket.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record AgentRunAuditResponse(
        Long runId,
        Long contextSnapshotId,
        JsonNode inputSnapshot,
        boolean inputSnapshotExpired,
        Diagnosis diagnosis,
        AgentRunAuditReviewResponse review,
        long modelRequestCount,
        List<EvidenceEvent> disclosureEvents,
        List<EvidenceEvent> skillEvents
) {
    public record Diagnosis(String category, String summary, List<String> evidence) {}
    public record EvidenceEvent(Long id, String eventType, JsonNode payload, String createdAt) {}
}
