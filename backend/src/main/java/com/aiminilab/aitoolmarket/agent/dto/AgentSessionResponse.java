package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentSession;

import java.time.LocalDateTime;

public record AgentSessionResponse(
        Long id,
        Long workspaceId,
        String title,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgentSessionResponse from(AgentSession session) {
        return new AgentSessionResponse(
                session.getId(),
                session.getWorkspaceId(),
                session.getTitle(),
                session.getStatus(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }
}
