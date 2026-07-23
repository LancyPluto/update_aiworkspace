package com.aiminilab.aitoolmarket.common.error;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;

public final class LegacyErrorCodeMapper {

    private LegacyErrorCodeMapper() {
    }

    public static ErrorDefinition fromLegacy(ErrorCode errorCode) {
        return switch (errorCode) {
            case SUCCESS -> throw new IllegalArgumentException("SUCCESS is not an error code");
            case PARAM_ERROR -> ApiErrors.INVALID_ARGUMENT;
            case NOT_FOUND -> ApiErrors.RESOURCE_NOT_FOUND;
            case UNAUTHORIZED, ADMIN_UNAUTHORIZED -> AuthErrors.CREDENTIALS_MISSING;
            case FORBIDDEN, ADMIN_FORBIDDEN -> AuthErrors.ACCESS_DENIED;
            case TOOL_NOT_FOUND -> ToolErrors.TOOL_NOT_FOUND;
            case TOOL_OFFLINE -> ToolErrors.TOOL_STATE_CONFLICT;
            case WORKFLOW_RUNTIME_BLOCKED -> LegacyErrorDefinitions.WORKFLOW_RUNTIME_BLOCKED;
            case CREDIT_NOT_ENOUGH -> LegacyErrorDefinitions.CREDIT_NOT_ENOUGH;
            case IDEMPOTENCY_CONFLICT -> TaskErrors.IDEMPOTENCY_CONFLICT;
            case MEMBERSHIP_ACTIVE -> LegacyErrorDefinitions.MEMBERSHIP_ACTIVE;
            case MEMBERSHIP_ORDER_PENDING -> LegacyErrorDefinitions.MEMBERSHIP_ORDER_PENDING;
            case TASK_NOT_FOUND -> TaskErrors.TASK_NOT_FOUND;
            case TASK_STATUS_INVALID -> TaskErrors.TASK_STATE_CONFLICT;
            case AGENT_SESSION_NOT_FOUND -> LegacyErrorDefinitions.AGENT_SESSION_NOT_FOUND;
            case AGENT_RUN_NOT_FOUND -> LegacyErrorDefinitions.AGENT_RUN_NOT_FOUND;
            case AGENT_RUN_NOT_CANCELLABLE -> LegacyErrorDefinitions.AGENT_RUN_NOT_CANCELLABLE;
            case AGENT_RATE_LIMITED -> LegacyErrorDefinitions.AGENT_RATE_LIMITED;
            case AGENT_ACTIVE_RUN_LIMIT -> LegacyErrorDefinitions.AGENT_ACTIVE_RUN_LIMIT;
            case AGENT_TOOL_NOT_AVAILABLE -> LegacyErrorDefinitions.AGENT_TOOL_NOT_AVAILABLE;
            case AGENT_CREDIT_NOT_ENOUGH -> LegacyErrorDefinitions.AGENT_CREDIT_NOT_ENOUGH;
            case AGENT_RUN_BUDGET_EXCEEDED -> LegacyErrorDefinitions.AGENT_RUN_BUDGET_EXCEEDED;
            case AGENT_TOOL_CALL_LIMIT -> LegacyErrorDefinitions.AGENT_TOOL_CALL_LIMIT;
            case AGENT_MODEL_CALL_LIMIT -> LegacyErrorDefinitions.AGENT_MODEL_CALL_LIMIT;
            case AGENT_SECURITY_REJECTED -> LegacyErrorDefinitions.AGENT_SECURITY_REJECTED;
            case AGENT_RUN_NOT_REGENERATABLE -> LegacyErrorDefinitions.AGENT_RUN_NOT_REGENERATABLE;
            case AGENT_MESSAGE_NOT_EDITABLE -> LegacyErrorDefinitions.AGENT_MESSAGE_NOT_EDITABLE;
            case AGENT_MESSAGE_NOT_FOUND -> LegacyErrorDefinitions.AGENT_MESSAGE_NOT_FOUND;
            case AGENT_ACTIVE_RUN_EXISTS -> LegacyErrorDefinitions.AGENT_ACTIVE_RUN_EXISTS;
            case AGENT_USE_REGENERATE_PATH -> LegacyErrorDefinitions.AGENT_USE_REGENERATE_PATH;
            case MODEL_CALL_FAILED -> ModelErrors.PROVIDER_CALL_FAILED;
            case SESSION_NOT_FOUND -> LegacyErrorDefinitions.SESSION_NOT_FOUND;
            case FILE_TYPE_NOT_ALLOWED -> ApiErrors.MEDIA_TYPE_NOT_SUPPORTED;
            case FILE_SIZE_EXCEEDED -> ApiErrors.UPLOAD_TOO_LARGE;
            case PPT_PROJECT_NOT_FOUND -> LegacyErrorDefinitions.PPT_PROJECT_NOT_FOUND;
            case PPT_STEP_DISABLED -> LegacyErrorDefinitions.PPT_STEP_DISABLED;
            case PPT_ENGINE_ERROR -> LegacyErrorDefinitions.PPT_ENGINE_ERROR;
            case PPT_TASK_FAILED -> LegacyErrorDefinitions.PPT_TASK_FAILED;
            case PPT_EXPORT_FAILED -> LegacyErrorDefinitions.PPT_EXPORT_FAILED;
            case SYSTEM_ERROR -> SystemErrors.INTERNAL_ERROR;
        };
    }

    public static ErrorCode toLegacy(ErrorDefinition definition) {
        if (definition instanceof LegacyErrorDefinitions legacyDefinition) {
            return legacyDefinition.legacyErrorCode();
        }
        if (definition instanceof ApiErrors apiError) {
            return switch (apiError) {
                case UPLOAD_TOO_LARGE -> ErrorCode.FILE_SIZE_EXCEEDED;
                case MEDIA_TYPE_NOT_SUPPORTED -> ErrorCode.FILE_TYPE_NOT_ALLOWED;
                case RESOURCE_NOT_FOUND -> ErrorCode.NOT_FOUND;
                default -> ErrorCode.PARAM_ERROR;
            };
        }
        if (definition instanceof AuthErrors authError) {
            return authError == AuthErrors.ACCESS_DENIED ? ErrorCode.FORBIDDEN : ErrorCode.UNAUTHORIZED;
        }
        if (definition instanceof ToolErrors toolError) {
            return toolError == ToolErrors.TOOL_NOT_FOUND ? ErrorCode.TOOL_NOT_FOUND : ErrorCode.TOOL_OFFLINE;
        }
        if (definition instanceof TaskErrors taskError) {
            return switch (taskError) {
                case TASK_NOT_FOUND -> ErrorCode.TASK_NOT_FOUND;
                case TASK_STATE_CONFLICT -> ErrorCode.TASK_STATUS_INVALID;
                case IDEMPOTENCY_CONFLICT -> ErrorCode.IDEMPOTENCY_CONFLICT;
            };
        }
        if (definition instanceof ModelErrors) {
            return ErrorCode.MODEL_CALL_FAILED;
        }
        if (definition instanceof PayErrors) {
            return ErrorCode.SYSTEM_ERROR;
        }
        return ErrorCode.SYSTEM_ERROR;
    }
}
