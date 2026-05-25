package com.aiminilab.aitoolmarket.credit.dto;

import com.aiminilab.aitoolmarket.credit.entity.CreditRechargePackage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

public record RechargePackageResponse(
        Long id,
        String packageCode,
        String packageName,
        Integer credits,
        BigDecimal priceAmount,
        String currency,
        Integer validityDays,
        List<String> benefits,
        Boolean recommended
) {
    private static final TypeReference<List<String>> BENEFITS_TYPE = new TypeReference<>() {};

    public static RechargePackageResponse from(CreditRechargePackage item, ObjectMapper objectMapper) {
        return new RechargePackageResponse(
                item.getId(),
                item.getPackageCode(),
                item.getPackageName(),
                item.getCredits(),
                item.getPriceAmount(),
                item.getCurrency(),
                item.getValidityDays(),
                parseBenefits(item.getBenefitsJson(), objectMapper),
                Boolean.TRUE.equals(item.getRecommended())
        );
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
}
