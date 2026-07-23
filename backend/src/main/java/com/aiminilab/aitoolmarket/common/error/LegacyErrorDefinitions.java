package com.aiminilab.aitoolmarket.common.error;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import org.springframework.http.HttpStatus;

public enum LegacyErrorDefinitions implements ErrorDefinition {
    WORKFLOW_RUNTIME_BLOCKED(ErrorCode.WORKFLOW_RUNTIME_BLOCKED, "WORKFLOW_001", HttpStatus.SERVICE_UNAVAILABLE, "工作流当前不可执行", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    CREDIT_NOT_ENOUGH(ErrorCode.CREDIT_NOT_ENOUGH, "CREDIT_001", HttpStatus.CONFLICT, "可用算力不足", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    MEMBERSHIP_ACTIVE(ErrorCode.MEMBERSHIP_ACTIVE, "MEMBER_001", HttpStatus.CONFLICT, "会员权益已生效", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    MEMBERSHIP_ORDER_PENDING(ErrorCode.MEMBERSHIP_ORDER_PENDING, "MEMBER_002", HttpStatus.CONFLICT, "已有待处理的会员订单", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    AGENT_SESSION_NOT_FOUND(ErrorCode.AGENT_SESSION_NOT_FOUND, "AGENT_001", HttpStatus.NOT_FOUND, "会话不存在", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    AGENT_RUN_NOT_FOUND(ErrorCode.AGENT_RUN_NOT_FOUND, "AGENT_002", HttpStatus.NOT_FOUND, "运行记录不存在", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    AGENT_RUN_NOT_CANCELLABLE(ErrorCode.AGENT_RUN_NOT_CANCELLABLE, "AGENT_003", HttpStatus.CONFLICT, "当前运行状态无法取消", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    AGENT_RATE_LIMITED(ErrorCode.AGENT_RATE_LIMITED, "AGENT_004", HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, true),
    AGENT_ACTIVE_RUN_LIMIT(ErrorCode.AGENT_ACTIVE_RUN_LIMIT, "AGENT_005", HttpStatus.TOO_MANY_REQUESTS, "当前运行任务过多，请稍后重试", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, true),
    AGENT_TOOL_NOT_AVAILABLE(ErrorCode.AGENT_TOOL_NOT_AVAILABLE, "AGENT_006", HttpStatus.CONFLICT, "所选工具当前不可用", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    AGENT_CREDIT_NOT_ENOUGH(ErrorCode.AGENT_CREDIT_NOT_ENOUGH, "AGENT_007", HttpStatus.CONFLICT, "可用算力不足", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    AGENT_RUN_BUDGET_EXCEEDED(ErrorCode.AGENT_RUN_BUDGET_EXCEEDED, "AGENT_008", HttpStatus.CONFLICT, "本次运行已达到预算上限", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    AGENT_TOOL_CALL_LIMIT(ErrorCode.AGENT_TOOL_CALL_LIMIT, "AGENT_009", HttpStatus.TOO_MANY_REQUESTS, "工具调用次数已达上限", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    AGENT_MODEL_CALL_LIMIT(ErrorCode.AGENT_MODEL_CALL_LIMIT, "AGENT_010", HttpStatus.TOO_MANY_REQUESTS, "模型调用次数已达上限", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    AGENT_SECURITY_REJECTED(ErrorCode.AGENT_SECURITY_REJECTED, "AGENT_011", HttpStatus.FORBIDDEN, "请求未通过安全检查", ErrorCategory.AUTHORIZATION, ErrorLogLevel.WARN, false),
    AGENT_RUN_NOT_REGENERATABLE(ErrorCode.AGENT_RUN_NOT_REGENERATABLE, "AGENT_012", HttpStatus.CONFLICT, "当前运行无法重新生成", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    AGENT_MESSAGE_NOT_EDITABLE(ErrorCode.AGENT_MESSAGE_NOT_EDITABLE, "AGENT_013", HttpStatus.CONFLICT, "当前消息无法编辑", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    AGENT_MESSAGE_NOT_FOUND(ErrorCode.AGENT_MESSAGE_NOT_FOUND, "AGENT_014", HttpStatus.NOT_FOUND, "消息不存在", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    AGENT_ACTIVE_RUN_EXISTS(ErrorCode.AGENT_ACTIVE_RUN_EXISTS, "AGENT_015", HttpStatus.CONFLICT, "当前已有任务正在运行", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    AGENT_USE_REGENERATE_PATH(ErrorCode.AGENT_USE_REGENERATE_PATH, "AGENT_016", HttpStatus.CONFLICT, "请使用重新生成操作", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    SESSION_NOT_FOUND(ErrorCode.SESSION_NOT_FOUND, "SESSION_001", HttpStatus.NOT_FOUND, "会话不存在", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    PPT_PROJECT_NOT_FOUND(ErrorCode.PPT_PROJECT_NOT_FOUND, "PPT_001", HttpStatus.NOT_FOUND, "PPT 项目不存在", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    PPT_STEP_DISABLED(ErrorCode.PPT_STEP_DISABLED, "PPT_002", HttpStatus.CONFLICT, "当前步骤不可用", ErrorCategory.BUSINESS, ErrorLogLevel.WARN, false),
    PPT_ENGINE_ERROR(ErrorCode.PPT_ENGINE_ERROR, "PPT_003", HttpStatus.BAD_GATEWAY, "PPT 服务暂时不可用", ErrorCategory.DEPENDENCY, ErrorLogLevel.ERROR, true),
    PPT_TASK_FAILED(ErrorCode.PPT_TASK_FAILED, "PPT_004", HttpStatus.INTERNAL_SERVER_ERROR, "PPT 任务执行失败", ErrorCategory.SYSTEM, ErrorLogLevel.ERROR, true),
    PPT_EXPORT_FAILED(ErrorCode.PPT_EXPORT_FAILED, "PPT_005", HttpStatus.INTERNAL_SERVER_ERROR, "PPT 导出失败", ErrorCategory.SYSTEM, ErrorLogLevel.ERROR, true);

    private final ErrorCode legacyErrorCode;
    private final ErrorDescriptor descriptor;

    LegacyErrorDefinitions(ErrorCode legacyErrorCode,
                           String code,
                           HttpStatus httpStatus,
                           String defaultUserMessage,
                           ErrorCategory category,
                           ErrorLogLevel logLevel,
                           boolean retryable) {
        this.legacyErrorCode = legacyErrorCode;
        this.descriptor = new ErrorDescriptor(code, httpStatus, defaultUserMessage, category, logLevel, retryable);
    }

    public ErrorCode legacyErrorCode() {
        return legacyErrorCode;
    }

    @Override
    public ErrorDescriptor descriptor() {
        return descriptor;
    }
}
