package com.aiminilab.aitoolmarket.task.dto;

public record WorkerProcessingRequest(
        Integer progress,
        String progressMessage,
        String claimToken
) {
}
