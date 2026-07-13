package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentRunAuditReview;
import java.time.LocalDateTime;

public record AgentRunAuditReviewResponse(Long id, Long runId, String expectedToolCode, String finalCategory,
                                          String reviewNote, Long reviewedBy, LocalDateTime updatedAt) {
    public static AgentRunAuditReviewResponse from(AgentRunAuditReview item) {
        if (item == null) return null;
        return new AgentRunAuditReviewResponse(item.getId(), item.getRunId(), item.getExpectedToolCode(),
                item.getFinalCategory(), item.getReviewNote(), item.getReviewedBy(), item.getUpdatedAt());
    }
}
