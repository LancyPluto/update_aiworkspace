package com.aiminilab.aitoolmarket.credit.dto;

import com.aiminilab.aitoolmarket.credit.entity.GiftCard;

import java.time.LocalDateTime;

public record GiftCardResponse(
        Long id,
        String cardCode,
        String packageName,
        Integer credits,
        String status,
        String cardTheme,
        LocalDateTime createdAt,
        Long giftedFromUserId,
        LocalDateTime giftedAt,
        LocalDateTime redeemedAt
) {
    public static GiftCardResponse from(GiftCard card, String packageName, String cardTheme) {
        return new GiftCardResponse(
                card.getId(),
                card.getCardCode(),
                packageName,
                card.getCredits(),
                card.getStatus(),
                cardTheme,
                card.getCreatedAt(),
                card.getGiftedFromUserId(),
                card.getGiftedAt(),
                card.getRedeemedAt()
        );
    }
}
