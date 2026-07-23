package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentToolCall;
import com.aiminilab.aitoolmarket.agent.support.AgentFailureMessage;
import com.aiminilab.aitoolmarket.common.error.ErrorMessageSanitizer;

import java.time.LocalDateTime;

public record AgentToolCallResponse(
        Long id,
        Long runId,
        String toolCode,
        Long taskId,
        String status,
        String argumentsJson,
        String resultJson,
        String errorCode,
        String errorMessage,
        String failureTraceId,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt
) {
    public static AgentToolCallResponse from(AgentToolCall call) {
        return new AgentToolCallResponse(
                call.getId(),
                call.getRunId(),
                call.getToolCode(),
                call.getTaskId(),
                call.getStatus(),
                call.getArgumentsJson(),
                call.getResultJson(),
                call.getErrorCode(),
                userErrorMessage(call),
                call.getFailureTraceId(),
                call.getStartedAt(),
                call.getFinishedAt(),
                call.getCreatedAt()
        );
    }

    public static AgentToolCallResponse fromAdmin(AgentToolCall call) {
        return new AgentToolCallResponse(
                call.getId(),
                call.getRunId(),
                call.getToolCode(),
                call.getTaskId(),
                call.getStatus(),
                call.getArgumentsJson(),
                call.getResultJson(),
                call.getErrorCode(),
                developerErrorMessage(call),
                call.getFailureTraceId(),
                call.getStartedAt(),
                call.getFinishedAt(),
                call.getCreatedAt()
        );
    }

    private static String userErrorMessage(AgentToolCall call) {
        if (!hasFailure(call)) {
            return null;
        }
        String candidate = call.getUserMessage();
        String controlled = candidate == null || candidate.isBlank()
                ? AgentFailureMessage.userMessage(call.getErrorCode())
                : candidate;
        return ErrorMessageSanitizer.sanitizeUserMessage(
                controlled,
                AgentFailureMessage.userMessage(call.getErrorCode())
        );
    }

    private static String developerErrorMessage(AgentToolCall call) {
        if (!hasFailure(call)) {
            return null;
        }
        String candidate = call.getDeveloperMessage();
        if (candidate == null || candidate.isBlank()) {
            candidate = call.getErrorMessage();
        }
        return ErrorMessageSanitizer.sanitizeDeveloperMessage(candidate, "Agent tool call failed");
    }

    private static boolean hasFailure(AgentToolCall call) {
        return (call.getErrorCode() != null && !call.getErrorCode().isBlank())
                || (call.getErrorMessage() != null && !call.getErrorMessage().isBlank())
                || (call.getUserMessage() != null && !call.getUserMessage().isBlank())
                || (call.getDeveloperMessage() != null && !call.getDeveloperMessage().isBlank());
    }
}
