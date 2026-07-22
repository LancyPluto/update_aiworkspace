package com.aiminilab.aitoolmarket.credit.dto;

import java.time.LocalDateTime;

public record ManualAddCreditsResponse(
        Long userId,
        String operationId,
        Long operatorId,
        Integer amount,
        Integer balanceBefore,
        Integer balanceAfter,
        String reason,
        GiftCardResponse giftCard,
        LocalDateTime createdAt
) {
}
