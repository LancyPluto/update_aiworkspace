package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentMessage;

import java.time.LocalDateTime;
import java.util.List;

public record AgentMessageResponse(
        Long id,
        Long sessionId,
        String role,
        String contentText,
        String contentJson,
        Long runId,
        Long parentMessageId,
        Integer branchIndex,
        Integer branchTotal,
        List<Long> branchVariantMessageIds,
        String status,
        LocalDateTime editedAt,
        LocalDateTime createdAt
) {
    public static AgentMessageResponse from(AgentMessage message) {
        return from(message, null);
    }

    public static AgentMessageResponse from(AgentMessage message, List<AgentMessage> siblings) {
        List<AgentMessage> branchSiblings = siblings == null || siblings.isEmpty() ? List.of(message) : siblings;
        List<Long> variantIds = branchSiblings.stream()
                .map(AgentMessage::getId)
                .toList();
        int branchIndex = Math.max(0, variantIds.indexOf(message.getId()));
        return new AgentMessageResponse(
                message.getId(),
                message.getSessionId(),
                message.getRole(),
                message.getContentText(),
                message.getContentJson(),
                message.getRunId(),
                message.getParentMessageId(),
                branchIndex,
                variantIds.size(),
                variantIds,
                message.getStatus() == null ? "ACTIVE" : message.getStatus(),
                message.getEditedAt(),
                message.getCreatedAt()
        );
    }
}
