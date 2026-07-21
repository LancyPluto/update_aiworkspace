package com.aiminilab.aitoolmarket.task.dto;

public record RouteFailoverResponse(
        boolean switched,
        String reason,
        Long routeAttemptId,
        ExecutionContextResponse executionContext
) {
}
