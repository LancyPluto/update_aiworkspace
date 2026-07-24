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
            case "MODEL_005" -> "部分参考图片可能包含真人或隐私内容，未通过模型安全检查，请更换后重试";
            case "MODEL_AUTH_FAILED", "MODEL_CREDIT_INSUFFICIENT" -> "模型服务暂不可用，请稍后重试";
            case "MODEL_CAPABILITY_DISABLED" -> "当前模型暂不可用，请稍后重试";
            case "MODEL_RATE_LIMITED" -> "请求过于频繁，请稍后重试";
            case "MODEL_TIMEOUT", "MODEL_004" -> "模型响应超时，请稍后重试";
            case "MODEL_CALL_FAILED" -> "模型调用失败，请稍后重试";
            case "MODEL_OUTPUT_EMPTY" -> "模型未返回有效结果，请换一种描述重试";
            case "MEDIA_PERSIST_FAILED" -> "媒体文件保存失败，请稍后重试";
            case "WORKER_INTERNAL_ERROR" -> "系统内部错误，请稍后重试";
            case "STALE_TASK_TIMEOUT", "ATTEMPT_LEASE_EXPIRED" -> "任务处理超时，请稍后重试";
            case "INVALID_TASK_PARAMS", "PROMPT_VARIABLE_MISSING" -> "任务参数有误，请检查后重试";
            default -> "任务执行失败，请稍后重试";
        };
    }
}
