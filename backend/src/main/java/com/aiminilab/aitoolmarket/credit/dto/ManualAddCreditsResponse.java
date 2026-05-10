package com.aiminilab.aitoolmarket.credit.dto;

import java.time.LocalDateTime;

public record ManualAddCreditsResponse(
        Long userId,
        Integer amount,
        Integer balanceBefore,
        Integer balanceAfter,
        String reason,
        LocalDateTime createdAt
) {
}
