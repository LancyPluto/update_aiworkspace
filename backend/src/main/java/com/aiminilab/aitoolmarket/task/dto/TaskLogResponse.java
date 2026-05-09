package com.aiminilab.aitoolmarket.task.dto;

import java.time.LocalDateTime;

public record TaskLogResponse(
        Long id,
        String eventType,
        String fromStatus,
        String toStatus,
        String message,
        LocalDateTime createdAt
) {
}
