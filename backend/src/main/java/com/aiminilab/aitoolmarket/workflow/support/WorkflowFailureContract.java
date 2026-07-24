package com.aiminilab.aitoolmarket.workflow.support;

import com.aiminilab.aitoolmarket.common.error.ErrorDefinitionRegistry;
import com.aiminilab.aitoolmarket.common.error.ErrorMessageSanitizer;
import org.slf4j.MDC;

import java.util.Locale;

public record WorkflowFailureContract(
        String errorCode,
        String userMessage,
        String developerMessage,
        String failureTraceId
) {

    private static final String DEFAULT_ERROR_CODE = "WORKFLOW_FAILED";
    private static final String DEFAULT_USER_MESSAGE = "工作流执行失败，请稍后重试";

    public static WorkflowFailureContract from(String errorCode,
                                               String legacyErrorMessage,
                                               String explicitDeveloperMessage) {
        String normalizedCode = normalizeErrorCode(errorCode);
        String developerSource = explicitDeveloperMessage == null || explicitDeveloperMessage.isBlank()
                ? legacyErrorMessage
                : explicitDeveloperMessage;
        return new WorkflowFailureContract(
                normalizedCode,
                userMessage(normalizedCode),
                ErrorMessageSanitizer.sanitizeDeveloperMessage(
                        developerSource,
                        "Workflow execution failed"
                ),
                currentTraceId()
        );
    }

    public static WorkflowFailureContract from(String errorCode, String legacyErrorMessage) {
        return from(errorCode, legacyErrorMessage, null);
    }

    public static String userMessage(String errorCode) {
        String normalizedCode = normalizeErrorCode(errorCode);
        return ErrorMessageSanitizer.sanitizeUserMessage(
                controlledUserMessage(normalizedCode),
                DEFAULT_USER_MESSAGE
        );
    }

    private static String controlledUserMessage(String errorCode) {
        return ErrorDefinitionRegistry.find(errorCode)
                .map(definition -> definition.defaultUserMessage())
                .orElseGet(() -> switch (errorCode) {
                    case "MODEL_RISK_CONTROL_REJECTED", "AGENT_SECURITY_REJECTED" -> "请求未通过安全检查";
                    case "MODEL_TIMEOUT", "MODEL_004", "WORKFLOW_TIMEOUT", "ATTEMPT_LEASE_EXPIRED" ->
                            "工作流执行超时，请稍后重试";
                    case "MODEL_RATE_LIMITED", "AGENT_RATE_LIMITED" -> "请求过于频繁，请稍后重试";
                    case "INVALID_TASK_PARAMS", "PROMPT_VARIABLE_MISSING" -> "任务参数有误，请检查后重试";
                    case "WORKFLOW_CANCELLED" -> "工作流已取消";
                    default -> DEFAULT_USER_MESSAGE;
                });
    }

    private static String normalizeErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return DEFAULT_ERROR_CODE;
        }
        String normalized = errorCode.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")) {
            return DEFAULT_ERROR_CODE;
        }
        return normalized;
    }

    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        String normalized = traceId.strip();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }
}
