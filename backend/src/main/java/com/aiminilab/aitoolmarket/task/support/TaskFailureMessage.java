package com.aiminilab.aitoolmarket.task.support;

public final class TaskFailureMessage {

    private TaskFailureMessage() {
    }

    public static String userFacingProgressMessage(String errorCode, String fallback) {
        if (errorCode == null) {
            return fallback;
        }
        return switch (errorCode) {
            case "MODEL_RISK_CONTROL_REJECTED" -> "您的提示词包含违禁词";
            case "MODEL_AUTH_FAILED" -> "模型认证失败，请联系管理员检查 API Key";
            case "MODEL_CREDIT_INSUFFICIENT" -> "模型账户余额不足，请联系管理员充值";
            case "MODEL_RATE_LIMITED" -> "请求过于频繁，请稍后重试";
            case "MODEL_TIMEOUT" -> "模型响应超时，请稍后重试";
            case "MODEL_CALL_FAILED" -> "模型调用失败，请稍后重试";
            case "MODEL_OUTPUT_EMPTY" -> "模型未返回有效结果，请换一种描述重试";
            case "MEDIA_PERSIST_FAILED" -> "媒体文件保存失败，请稍后重试";
            case "WORKER_INTERNAL_ERROR" -> "系统内部错误，请稍后重试";
            default -> fallback;
        };
    }
}
