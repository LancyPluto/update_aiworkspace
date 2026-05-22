package com.aiminilab.aitoolmarket.market.dto;

import com.aiminilab.aitoolmarket.market.entity.AiMarketMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record MessageResponse(
        String id,
        String role,
        String content,
        long timestamp,
        List<MessageAttachmentResponse> attachments,
        Map<String, Object> params
) {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public static MessageResponse from(
            AiMarketMessage message,
            List<MessageAttachmentResponse> attachments,
            ObjectMapper objectMapper
    ) {
        ZoneId zone = ZoneId.systemDefault();
        return new MessageResponse(
                message.getMessageId(),
                message.getRole(),
                message.getContent(),
                message.getCreatedAt().atZone(zone).toInstant().toEpochMilli(),
                attachments == null ? List.of() : attachments,
                parseParams(message.getParamsJson(), objectMapper)
        );
    }

    private static Map<String, Object> parseParams(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }
}
