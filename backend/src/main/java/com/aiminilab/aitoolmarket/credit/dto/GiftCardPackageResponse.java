package com.aiminilab.aitoolmarket.credit.dto;

import com.aiminilab.aitoolmarket.credit.entity.GiftCardPackage;
import com.aiminilab.aitoolmarket.credit.support.MembershipTier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public record GiftCardPackageResponse(
        Long id,
        String packageCode,
        String packageName,
        Integer credits,
        BigDecimal priceAmount,
        String currency,
        String cardTheme,
        String cardType,
        String requiredMemberTier,
        BigDecimal listPriceAmount,
        BigDecimal discountRate
) {
    private static final BigDecimal LIST_PRICE_PER_CREDIT = new BigDecimal("0.020");

    public static GiftCardPackageResponse from(GiftCardPackage pkg) {
        BigDecimal listPriceAmount = listPriceAmount(pkg.getCredits());
        return new GiftCardPackageResponse(
                pkg.getId(),
                pkg.getPackageCode(),
                pkg.getPackageName(),
                pkg.getCredits(),
                pkg.getPriceAmount(),
                pkg.getCurrency(),
                pkg.getCardTheme(),
                normalizeCardType(pkg.getCardType()),
                normalizeRequiredMemberTier(pkg.getRequiredMemberTier()),
                listPriceAmount,
                discountRate(pkg.getPriceAmount(), listPriceAmount)
        );
    }

    private static BigDecimal listPriceAmount(Integer credits) {
        if (credits == null || credits <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return LIST_PRICE_PER_CREDIT.multiply(BigDecimal.valueOf(credits)).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal discountRate(BigDecimal priceAmount, BigDecimal listPriceAmount) {
        if (priceAmount == null || listPriceAmount.signum() <= 0) {
            return null;
        }
        return priceAmount.divide(listPriceAmount, 4, RoundingMode.HALF_UP);
    }

    private static String normalizeCardType(String cardType) {
        return cardType == null || cardType.isBlank()
                ? "CREDIT"
                : cardType.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeRequiredMemberTier(String requiredMemberTier) {
        return MembershipTier.fromCode(requiredMemberTier)
                .map(MembershipTier::code)
                .orElse(null);
    }
}
