package com.aiminilab.aitoolmarket.credit.dto;

import com.aiminilab.aitoolmarket.credit.entity.GiftCardPackage;

import java.math.BigDecimal;

public record GiftCardPackageResponse(
        Long id,
        String packageCode,
        String packageName,
        Integer credits,
        BigDecimal priceAmount,
        String currency,
        String cardTheme
) {
    public static GiftCardPackageResponse from(GiftCardPackage pkg) {
        return new GiftCardPackageResponse(
                pkg.getId(),
                pkg.getPackageCode(),
                pkg.getPackageName(),
                pkg.getCredits(),
                pkg.getPriceAmount(),
                pkg.getCurrency(),
                pkg.getCardTheme()
        );
    }
}
