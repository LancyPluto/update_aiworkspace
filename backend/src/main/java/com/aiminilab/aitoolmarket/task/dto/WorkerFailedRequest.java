package com.aiminilab.aitoolmarket.task.dto;

import java.math.BigDecimal;

public record WorkerFailedRequest(
        String errorCode,
        String errorMessage,
        String failureStage,
        Boolean providerCharged,
        BigDecimal providerCostAmount,
        String providerErrorCode,
        String providerRequestId,
        Integer promptTokens,
        Integer completionTokens,
        Integer billableUnits,
        String claimToken
) {
}
