package com.aiminilab.aitoolmarket.market.dto;

import com.aiminilab.aitoolmarket.market.entity.AiMarketSession;

import java.time.ZoneId;

public record SessionResponse(
        String id,
        String title,
        String toolId,
        long createdAt,
        long updatedAt
) {
    public static SessionResponse from(AiMarketSession session) {
        ZoneId zone = ZoneId.systemDefault();
        return new SessionResponse(
                session.getSessionId(),
                session.getTitle(),
                session.getToolId(),
                session.getCreatedAt().atZone(zone).toInstant().toEpochMilli(),
                session.getUpdatedAt().atZone(zone).toInstant().toEpochMilli()
        );
    }
}
