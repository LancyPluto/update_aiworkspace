package com.aiminilab.aitoolmarket.task.dto;

import java.time.LocalDateTime;

public record ClaimTaskResponse(
        boolean claimed,
        Long taskId,
        String status,
        String claimToken,
        String workerId,
        LocalDateTime leaseUntil,
        Integer executionAttempt,
        String reason
) {
}
