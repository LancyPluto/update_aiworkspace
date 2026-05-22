package com.aiminilab.aitoolmarket.common.exception;

import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBusinessException(BusinessException exception) {
        log.warn("Business exception: code={}, traceId={}, message={}",
                exception.getErrorCode(), traceId(), exception.getMessage());
        return ApiResponse.fail(exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(Exception exception) {
        log.warn("Validation exception: traceId={}, message={}", traceId(), exception.getMessage());
        return ApiResponse.fail(ErrorCode.PARAM_ERROR, validationMessage(exception));
    }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleDataAccessException(DataAccessException exception) {
        Throwable root = rootCause(exception);
        log.error("Database exception: traceId={}, type={}, rootType={}, rootMessage={}",
                traceId(),
                exception.getClass().getName(),
                root.getClass().getName(),
                root.getMessage(),
                exception);
        return ApiResponse.fail(ErrorCode.SYSTEM_ERROR,
                "数据库操作失败：" + safeMessage(root) + traceHint());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception exception) {
        log.error("Unhandled exception: traceId={}", traceId(), exception);
        return ApiResponse.fail(ErrorCode.SYSTEM_ERROR,
                "系统异常：" + exception.getClass().getSimpleName() + traceHint());
    }

    private String traceId() {
        return MDC.get("traceId");
    }

    private String traceHint() {
        String traceId = traceId();
        return traceId == null || traceId.isBlank() ? "" : "，traceId=" + traceId;
    }

    private String validationMessage(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException validationException) {
            FieldError fieldError = validationException.getBindingResult().getFieldError();
            if (fieldError != null) {
                return "参数错误：" + fieldError.getField() + " " + fieldError.getDefaultMessage();
            }
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "参数错误" : "参数错误：" + message;
    }

    private Throwable rootCause(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }

    private String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) + "..." : message;
    }
}
