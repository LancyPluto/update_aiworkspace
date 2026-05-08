package com.aiminilab.aitoolmarket.task.dto;

public record WorkerFailedRequest(
        String errorCode,
        String errorMessage
) {
}
