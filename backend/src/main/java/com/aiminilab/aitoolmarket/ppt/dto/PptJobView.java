package com.aiminilab.aitoolmarket.ppt.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record PptJobView(
        Long jobId,
        Long projectId,
        String jobType,
        String status,
        int progress,
        String progressMessage,
        String engineCode,
        String creditState,
        int reservedCredits,
        int actualCredits,
        boolean retryable,
        ErrorView error,
        JsonNode result,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime finishedAt
) {
    public record ErrorView(String code, String message) {
    }
}
