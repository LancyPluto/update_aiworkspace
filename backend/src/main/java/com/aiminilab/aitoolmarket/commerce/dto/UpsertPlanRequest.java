package com.aiminilab.aitoolmarket.commerce.dto;

public record UpsertPlanRequest(
        String planCode,
        String planName,
        String planType,
        Integer durationDays,
        Integer priceCents,
        Integer creditAmount,
        String status,
        String description
) {
}
