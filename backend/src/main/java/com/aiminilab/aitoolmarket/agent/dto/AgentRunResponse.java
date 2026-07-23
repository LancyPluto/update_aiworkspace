package com.aiminilab.aitoolmarket.agent.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentRun;
import com.aiminilab.aitoolmarket.agent.support.AgentFailureMessage;
import com.aiminilab.aitoolmarket.common.error.ErrorMessageSanitizer;

import java.time.LocalDateTime;

public record AgentRunResponse(
        Long id,
        Long sessionId,
        String status,
        String intent,
        Long modelConfigId,
        String modelProviderCode,
        String modelName,
        Integer estimatedCredits,
        Integer consumedCredits,
        String errorCode,
        String errorMessage,
        String failureTraceId,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgentRunResponse from(AgentRun run) {
        return new AgentRunResponse(
                run.getId(),
                run.getSessionId(),
                run.getStatus(),
                run.getIntent(),
                run.getModelConfigId(),
                run.getModelProviderCode(),
                run.getModelName(),
                run.getEstimatedCredits(),
                run.getConsumedCredits(),
                run.getErrorCode(),
                userErrorMessage(run),
                run.getFailureTraceId(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getCreatedAt(),
                run.getUpdatedAt()
        );
    }

    public static AgentRunResponse fromAdmin(AgentRun run) {
        return new AgentRunResponse(
                run.getId(),
                run.getSessionId(),
                run.getStatus(),
                run.getIntent(),
                run.getModelConfigId(),
                run.getModelProviderCode(),
                run.getModelName(),
                run.getEstimatedCredits(),
                run.getConsumedCredits(),
                run.getErrorCode(),
                developerErrorMessage(run),
                run.getFailureTraceId(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getCreatedAt(),
                run.getUpdatedAt()
        );
    }

    private static String userErrorMessage(AgentRun run) {
        if (!hasFailure(run)) {
            return null;
        }
        String candidate = run.getUserMessage();
        String controlled = candidate == null || candidate.isBlank()
                ? AgentFailureMessage.userMessage(run.getErrorCode())
                : candidate;
        return ErrorMessageSanitizer.sanitizeUserMessage(
                controlled,
                AgentFailureMessage.userMessage(run.getErrorCode())
        );
    }

    private static String developerErrorMessage(AgentRun run) {
        if (!hasFailure(run)) {
            return null;
        }
        String candidate = run.getDeveloperMessage();
        if (candidate == null || candidate.isBlank()) {
            candidate = run.getErrorMessage();
        }
        return ErrorMessageSanitizer.sanitizeDeveloperMessage(candidate, "Agent run failed");
    }

    private static boolean hasFailure(AgentRun run) {
        return (run.getErrorCode() != null && !run.getErrorCode().isBlank())
                || (run.getErrorMessage() != null && !run.getErrorMessage().isBlank())
                || (run.getUserMessage() != null && !run.getUserMessage().isBlank())
                || (run.getDeveloperMessage() != null && !run.getDeveloperMessage().isBlank());
    }
}
