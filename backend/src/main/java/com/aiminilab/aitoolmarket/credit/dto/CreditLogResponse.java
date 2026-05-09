package com.aiminilab.aitoolmarket.credit.dto;

import java.time.LocalDateTime;

public record CreditLogResponse(
        Long id,
        Long userId,
        Long taskId,
        String logType,
        Integer amount,
        Integer frozenAmount,
        Integer balanceBefore,
        Integer balanceAfter,
        Integer frozenBefore,
        Integer frozenAfter,
        String operatorType,
        Long operatorId,
        String reason,
        LocalDateTime createdAt
) {
}
