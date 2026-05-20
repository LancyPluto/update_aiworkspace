package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentMessage;

import java.time.LocalDateTime;

public record AgentMessageResponse(
        Long id,
        Long sessionId,
        String role,
        String contentText,
        String contentJson,
        Long runId,
        String status,
        LocalDateTime createdAt
) {
    public static AgentMessageResponse from(AgentMessage message) {
        return new AgentMessageResponse(
                message.getId(),
                message.getSessionId(),
                message.getRole(),
                message.getContentText(),
                message.getContentJson(),
                message.getRunId(),
                message.getStatus() == null ? "ACTIVE" : message.getStatus(),
                message.getCreatedAt()
        );
    }
}
