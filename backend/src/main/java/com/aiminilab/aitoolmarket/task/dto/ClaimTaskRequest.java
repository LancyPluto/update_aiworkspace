package com.aiminilab.aitoolmarket.task.dto;

public record ClaimTaskRequest(
        String workerId,
        String claimToken
) {
}
