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
        String claimToken,
        String userMessage,
        String developerMessage,
        String failureTraceId
) {
    public WorkerFailedRequest {
        if (errorCode != null && "MODEL_TIMEOUT".equalsIgnoreCase(errorCode.trim())) {
            errorCode = "MODEL_004";
        }
    }

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
                               String deliveryState,
                               String retryScope,
                               Integer retryAfterSeconds,
                               String claimToken) {
        this(errorCode, errorMessage, failureStage, providerCharged, providerCostAmount,
                providerCostCurrency, providerErrorCode, providerRequestId, promptTokens,
                completionTokens, billableUnits, deliveryState, retryScope, retryAfterSeconds,
                claimToken, null, null, null);
    }

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
                completionTokens, billableUnits, null, null, null, claimToken,
                null, null, null);
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
                billableUnits, null, null, null, claimToken, null, null, null);
    }
}
