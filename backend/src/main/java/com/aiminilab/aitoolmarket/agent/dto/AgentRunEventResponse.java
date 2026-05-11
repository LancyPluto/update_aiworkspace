package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentRunEvent;

import java.time.LocalDateTime;

public record AgentRunEventResponse(
        Long id,
        Long runId,
        String eventType,
        String eventText,
        String eventJson,
        LocalDateTime createdAt
) {
    public static AgentRunEventResponse from(AgentRunEvent event) {
        return new AgentRunEventResponse(
                event.getId(),
                event.getRunId(),
                event.getEventType(),
                event.getEventText(),
                event.getEventJson(),
                event.getCreatedAt()
        );
    }
}
