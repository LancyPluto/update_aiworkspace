package com.aiminilab.aitoolmarket.task.dto;

import java.math.BigDecimal;

public record WorkerFailedRequest(
        String errorCode,
        String errorMessage,
        String failureStage,
        Boolean providerCharged,
        BigDecimal providerCostAmount,
        String providerCostCurrency,
        String providerErrorCode,
        String providerRequestId,
        Integer promptTokens,
        Integer completionTokens,
        Integer billableUnits,
        String deliveryState,
        String retryScope,
        Integer retryAfterSeconds,
        String claimToken
) {
    public WorkerFailedRequest(String errorCode,
                               String errorMessage,
                               String failureStage,
                               Boolean providerCharged,
                               BigDecimal providerCostAmount,
                               String providerCostCurrency,
                               String providerErrorCode,
                               String providerRequestId,
                               Integer promptTokens,
                               Integer completionTokens,
                               Integer billableUnits,
                               String claimToken) {
        this(errorCode, errorMessage, failureStage, providerCharged, providerCostAmount,
                providerCostCurrency, providerErrorCode, providerRequestId, promptTokens,
                completionTokens, billableUnits, null, null, null, claimToken);
    }

    public WorkerFailedRequest(String errorCode,
                               String errorMessage,
                               String failureStage,
                               Boolean providerCharged,
                               BigDecimal providerCostAmount,
                               String providerErrorCode,
                               String providerRequestId,
                               Integer promptTokens,
                               Integer completionTokens,
                               Integer billableUnits,
                               String claimToken) {
        this(errorCode, errorMessage, failureStage, providerCharged, providerCostAmount,
                null, providerErrorCode, providerRequestId, promptTokens, completionTokens,
                billableUnits, null, null, null, claimToken);
    }
}
