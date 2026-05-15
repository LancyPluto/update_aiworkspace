package com.aiminilab.aitoolmarket.agent.dto;

public record InternalPendingToolContextResponse(
        Long id,
        Long runId,
        Long sessionId,
        Long userId,
        String selectedToolCode,
        String candidateToolCodesJson,
        String collectedArgumentsJson,
        String missingArgumentsJson,
        String clarifyingQuestion,
        Boolean confirmationRequired,
        String status
) {
}
