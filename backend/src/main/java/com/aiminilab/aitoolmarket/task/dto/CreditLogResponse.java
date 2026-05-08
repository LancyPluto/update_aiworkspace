package com.aiminilab.aitoolmarket.task.dto;

import java.time.LocalDateTime;

public record CreditLogResponse(
        Long id,
        String logType,
        Integer amount,
        Integer balanceBefore,
        Integer balanceAfter,
        String reason,
        LocalDateTime createdAt
) {
}
