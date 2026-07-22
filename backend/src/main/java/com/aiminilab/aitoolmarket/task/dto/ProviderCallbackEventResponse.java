package com.aiminilab.aitoolmarket.task.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record ProviderCallbackEventResponse(
        Long eventId,
        Long taskId,
        String providerCode,
        String providerTaskId,
        String callbackType,
        Integer providerStatusCode,
        JsonNode payload,
        LocalDateTime receivedAt
) {
}
