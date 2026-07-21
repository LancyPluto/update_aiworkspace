package com.aiminilab.aitoolmarket.credit.dto;

import com.aiminilab.aitoolmarket.credit.entity.CreditRechargePackage;
import com.aiminilab.aitoolmarket.credit.support.MembershipTier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record RechargePackageResponse(
        Long id,
        String packageCode,
        String packageName,
        Integer credits,
        BigDecimal priceAmount,
        String currency,
        Integer validityDays,
        List<String> benefits,
        Boolean recommended,
        String tierCode,
        String billingCycle,
        BigDecimal listPriceAmount,
        BigDecimal discountRate,
        BigDecimal cycleDiscountRate
) {
    private static final TypeReference<List<String>> BENEFITS_TYPE = new TypeReference<>() {};
    private static final BigDecimal LIST_PRICE_PER_CREDIT = new BigDecimal("0.020");

    public static RechargePackageResponse from(CreditRechargePackage item, ObjectMapper objectMapper) {
        return from(item, objectMapper, Map.of());
    }

    public static RechargePackageResponse from(CreditRechargePackage item,
                                               ObjectMapper objectMapper,
                                               Map<String, BigDecimal> monthlyPriceByTier) {
        PackageMetadata metadata = PackageMetadata.from(item.getPackageCode());
        BigDecimal listPriceAmount = listPriceAmount(item.getCredits());
        return new RechargePackageResponse(
                item.getId(),
                item.getPackageCode(),
                item.getPackageName(),
                item.getCredits(),
                item.getPriceAmount(),
                item.getCurrency(),
                item.getValidityDays(),
                parseBenefits(item.getBenefitsJson(), objectMapper),
                Boolean.TRUE.equals(item.getRecommended()),
                metadata == null ? null : metadata.tierCode(),
                metadata == null ? null : metadata.billingCycle(),
                listPriceAmount,
                discountRate(item.getPriceAmount(), listPriceAmount),
                cycleDiscountRate(item.getPriceAmount(), metadata, monthlyPriceByTier)
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

    private static BigDecimal cycleDiscountRate(BigDecimal priceAmount,
                                                PackageMetadata metadata,
                                                Map<String, BigDecimal> monthlyPriceByTier) {
        if (priceAmount == null || metadata == null) {
            return null;
        }
        if (metadata.months() == 1) {
            return BigDecimal.ONE.setScale(4);
        }
        BigDecimal monthlyPrice = monthlyPriceByTier.get(metadata.tierCode());
        if (monthlyPrice == null || monthlyPrice.signum() <= 0) {
            return null;
        }
        BigDecimal comparablePrice = monthlyPrice.multiply(BigDecimal.valueOf(metadata.months()));
        return priceAmount.divide(comparablePrice, 4, RoundingMode.HALF_UP);
    }

    private static List<String> parseBenefits(String benefitsJson, ObjectMapper objectMapper) {
        if (benefitsJson == null || benefitsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(benefitsJson, BENEFITS_TYPE);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private record PackageMetadata(String tierCode, String billingCycle, int months) {
        private static PackageMetadata from(String packageCode) {
            if (packageCode == null || packageCode.isBlank()) {
                return null;
            }
            String normalized = packageCode.trim().toLowerCase(Locale.ROOT);
            int separator = normalized.indexOf('_');
            if (separator <= 0 || separator == normalized.length() - 1) {
                return null;
            }
            String billingCycle = normalized.substring(0, separator);
            String tierCode = normalized.substring(separator + 1);
            if (MembershipTier.fromCode(tierCode).isEmpty()) {
                return null;
            }
            int months = switch (billingCycle) {
                case "monthly" -> 1;
                case "quarterly" -> 3;
                case "yearly" -> 12;
                default -> 0;
            };
            return months == 0 ? null : new PackageMetadata(tierCode, billingCycle, months);
        }
    }
}
