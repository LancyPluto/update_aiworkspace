package com.aiminilab.aitoolmarket.agent.support;

import com.aiminilab.aitoolmarket.common.error.ErrorDefinitionRegistry;

import java.util.Locale;

public final class AgentFailureMessage {

    private static final String DEFAULT_MESSAGE = "Agent 执行失败，请稍后重试";

    private AgentFailureMessage() {
    }

    public static String userMessage(String errorCode) {
        String normalized = normalize(errorCode);
        if (normalized == null) {
            return DEFAULT_MESSAGE;
        }
        return ErrorDefinitionRegistry.find(normalized)
                .map(definition -> definition.defaultUserMessage())
                .orElseGet(() -> legacyUserMessage(normalized));
    }

    private static String legacyUserMessage(String errorCode) {
        return switch (errorCode) {
            case "AGENT_RUN_STALE", "AGENT_SERVICE_NOTIFY_TIMEOUT", "MODEL_TIMEOUT", "WORKFLOW_TIMEOUT" ->
                    "执行超时，请稍后重试";
            case "AGENT_SERVICE_NOTIFY_FAILED", "MODEL_CALL_FAILED", "MODEL_AUTH_FAILED",
                    "MODEL_CREDIT_INSUFFICIENT", "MODEL_CAPABILITY_DISABLED" ->
                    "服务暂不可用，请稍后重试";
            case "AGENT_RATE_LIMITED", "AGENT_ACTIVE_RUN_LIMIT", "MODEL_RATE_LIMITED" ->
                    "请求过于频繁，请稍后重试";
            case "AGENT_CREDIT_NOT_ENOUGH" -> "可用算力不足";
            case "AGENT_RUN_BUDGET_EXCEEDED" -> "本次运行已达到预算上限";
            case "AGENT_TOOL_CALL_LIMIT" -> "工具调用次数已达到上限";
            case "AGENT_MODEL_CALL_LIMIT" -> "模型调用次数已达到上限";
            case "AGENT_SECURITY_REJECTED", "MODEL_RISK_CONTROL_REJECTED" -> "请求未通过安全检查";
            case "RUN_ALREADY_TERMINATED" -> "Agent 运行已结束";
            case "WORKFLOW_CANCELLED" -> "工作流已取消";
            case "WORKFLOW_FAILED" -> "工作流执行失败，请稍后重试";
            default -> inferredUserMessage(errorCode);
        };
    }

    private static String inferredUserMessage(String errorCode) {
        if (errorCode.contains("TIMEOUT") || errorCode.contains("LEASE_EXPIRED")) {
            return "执行超时，请稍后重试";
        }
        if (errorCode.contains("RATE_LIMIT")) {
            return "请求过于频繁，请稍后重试";
        }
        if (errorCode.contains("CANCEL")) {
            return "执行已取消";
        }
        if (errorCode.contains("INVALID") || errorCode.contains("PARAM") || errorCode.contains("ARGUMENT")) {
            return "请求参数有误，请检查后重试";
        }
        if (errorCode.contains("NOT_FOUND")) {
            return "请求的资源不存在";
        }
        return DEFAULT_MESSAGE;
    }

    private static String normalize(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return null;
        }
        return errorCode.strip().toUpperCase(Locale.ROOT);
    }
}
