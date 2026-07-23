package com.aiminilab.aitoolmarket.workflow.controller;

import com.aiminilab.aitoolmarket.common.error.ErrorContractResponseFactory;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = WorkflowRunController.class)
public class WorkflowRunApiExceptionHandler {

    private final ErrorContractResponseFactory responseFactory;

    public WorkflowRunApiExceptionHandler(ErrorContractResponseFactory responseFactory) {
        this.responseFactory = responseFactory;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException exception,
                                                           HttpServletRequest request) {
        return responseFactory.errorResponse(
                request,
                exception.getErrorDefinition(),
                exception.getErrorDefinition().defaultUserMessage(),
                exception.getDeveloperMessage(),
                exception.getErrorCode(),
                exception.getData(),
                status(exception.getErrorCode())
        );
    }

    private HttpStatus status(ErrorCode errorCode) {
        return switch (errorCode) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND, TOOL_NOT_FOUND, TOOL_OFFLINE, TASK_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT, TASK_STATUS_INVALID -> HttpStatus.CONFLICT;
            case WORKFLOW_RUNTIME_BLOCKED -> HttpStatus.SERVICE_UNAVAILABLE;
            case SYSTEM_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
