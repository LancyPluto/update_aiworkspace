package com.aiminilab.aitoolmarket.task.dto;

public record RouteFailoverRequest(
        String claimToken,
        Long routeAttemptId,
        String deliveryState,
        String retryScope,
        String failureStage,
        String errorCode,
        String errorMessage,
        String providerErrorCode,
        String providerRequestId,
        Boolean providerCharged,
        Integer retryAfterSeconds
) {
}
