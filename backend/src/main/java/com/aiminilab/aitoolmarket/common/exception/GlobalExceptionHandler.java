package com.aiminilab.aitoolmarket.common.exception;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.error.ApiErrors;
import com.aiminilab.aitoolmarket.common.error.ErrorContractResponseFactory;
import com.aiminilab.aitoolmarket.common.error.ErrorDefinition;
import com.aiminilab.aitoolmarket.common.error.ErrorDefinitionRegistry;
import com.aiminilab.aitoolmarket.common.error.ErrorLogLevel;
import com.aiminilab.aitoolmarket.common.error.ErrorMessageSanitizer;
import com.aiminilab.aitoolmarket.common.error.LegacyErrorCodeMapper;
import com.aiminilab.aitoolmarket.common.error.SystemErrors;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorContractResponseFactory responseFactory;

    public GlobalExceptionHandler(ErrorContractResponseFactory responseFactory) {
        this.responseFactory = responseFactory;
        ErrorDefinitionRegistry.validate();
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Object> handleAppException(AppException exception, HttpServletRequest request) {
        logAppException(exception);
        ErrorCode legacyErrorCode = exception instanceof BusinessException businessException
                ? businessException.getErrorCode()
                : LegacyErrorCodeMapper.toLegacy(exception.getErrorDefinition());
        Object legacyData = exception instanceof BusinessException businessException
                ? businessException.getData()
                : null;
        HttpStatus legacyStatus = exception instanceof BusinessException
                ? HttpStatus.BAD_REQUEST
                : exception.getErrorDefinition().httpStatus();
        return responseFactory.errorResponse(
                request,
                exception.getErrorDefinition(),
                exception.getUserMessage(),
                exception.getDeveloperMessage(),
                legacyErrorCode,
                legacyData,
                legacyStatus
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleUnreadableMessage(HttpMessageNotReadableException exception,
                                                           HttpServletRequest request) {
        log.warn("Unreadable request body: traceId={}, type={}", traceId(), exception.getClass().getName());
        return errorResponse(
                request,
                ApiErrors.JSON_BODY_UNREADABLE,
                ApiErrors.JSON_BODY_UNREADABLE.defaultUserMessage(),
                "Request body could not be parsed as JSON; causeType=" + rootCause(exception).getClass().getName(),
                ErrorCode.PARAM_ERROR,
                null
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> handleUploadTooLarge(MaxUploadSizeExceededException exception,
                                                        HttpServletRequest request) {
        log.warn("Upload too large: traceId={}, maxUploadSize={}", traceId(), exception.getMaxUploadSize());
        return errorResponse(
                request,
                ApiErrors.UPLOAD_TOO_LARGE,
                ApiErrors.UPLOAD_TOO_LARGE.defaultUserMessage(),
                "Upload exceeded configured size limit; maxUploadSize=" + exception.getMaxUploadSize(),
                ErrorCode.FILE_SIZE_EXCEEDED,
                null
        );
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Object> handleValidationException(Exception exception, HttpServletRequest request) {
        String developerMessage = validationDeveloperMessage(exception);
        log.warn("Validation exception: traceId={}, detail={}",
                traceId(),
                ErrorMessageSanitizer.sanitizeDeveloperMessage(developerMessage, "Validation failed"));
        return errorResponse(
                request,
                ApiErrors.INVALID_ARGUMENT,
                ApiErrors.INVALID_ARGUMENT.defaultUserMessage(),
                developerMessage,
                ErrorCode.PARAM_ERROR,
                null
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception,
                                                            HttpServletRequest request) {
        log.warn("HTTP method not supported: traceId={}, method={}", traceId(), exception.getMethod());
        return errorResponse(
                request,
                ApiErrors.METHOD_NOT_ALLOWED,
                ApiErrors.METHOD_NOT_ALLOWED.defaultUserMessage(),
                "HTTP method is not supported; method=" + exception.getMethod(),
                ErrorCode.PARAM_ERROR,
                null
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Object> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception,
                                                               HttpServletRequest request) {
        log.warn("Media type not supported: traceId={}, contentType={}", traceId(), exception.getContentType());
        return errorResponse(
                request,
                ApiErrors.MEDIA_TYPE_NOT_SUPPORTED,
                ApiErrors.MEDIA_TYPE_NOT_SUPPORTED.defaultUserMessage(),
                "Request media type is not supported; contentType=" + exception.getContentType(),
                ErrorCode.FILE_TYPE_NOT_ALLOWED,
                null
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> handleNoResourceFound(NoResourceFoundException exception,
                                                         HttpServletRequest request) {
        String path = exception.getResourcePath() == null ? "" : exception.getResourcePath();
        log.warn("Resource not found: traceId={}, path={}, method={}", traceId(), path, exception.getHttpMethod());
        return errorResponse(
                request,
                ApiErrors.RESOURCE_NOT_FOUND,
                ApiErrors.RESOURCE_NOT_FOUND.defaultUserMessage(),
                "API or static resource was not found; method=" + exception.getHttpMethod() + ", path=" + path,
                ErrorCode.NOT_FOUND,
                null
        );
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Object> handleDataAccessException(DataAccessException exception,
                                                             HttpServletRequest request) {
        Throwable root = rootCause(exception);
        log.error("Database exception: traceId={}, type={}, rootType={}, rootMessage={}",
                traceId(),
                exception.getClass().getName(),
                root.getClass().getName(),
                root.getMessage(),
                exception);
        return errorResponse(
                request,
                SystemErrors.INTERNAL_ERROR,
                SystemErrors.INTERNAL_ERROR.defaultUserMessage(),
                diagnosticReference("Database operation failed"),
                ErrorCode.SYSTEM_ERROR,
                null
        );
    }

    @ExceptionHandler(ServletException.class)
    public ResponseEntity<Object> handleServletException(ServletException exception,
                                                          HttpServletRequest request) {
        Throwable root = rootCause(exception);
        log.error("Servlet exception: traceId={}, type={}, rootType={}, rootMessage={}",
                traceId(),
                exception.getClass().getName(),
                root.getClass().getName(),
                root.getMessage(),
                exception);
        return errorResponse(
                request,
                SystemErrors.INTERNAL_ERROR,
                SystemErrors.INTERNAL_ERROR.defaultUserMessage(),
                diagnosticReference("Servlet request processing failed; exceptionType=" + exception.getClass().getName()),
                ErrorCode.SYSTEM_ERROR,
                null
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleException(Exception exception, HttpServletRequest request) {
        Throwable root = rootCause(exception);
        log.error("Unhandled exception: traceId={}, type={}, rootType={}, rootMessage={}",
                traceId(),
                exception.getClass().getName(),
                root.getClass().getName(),
                root.getMessage(),
                exception);
        return errorResponse(
                request,
                SystemErrors.INTERNAL_ERROR,
                SystemErrors.INTERNAL_ERROR.defaultUserMessage(),
                diagnosticReference("Unhandled server exception; exceptionType=" + exception.getClass().getName()),
                ErrorCode.SYSTEM_ERROR,
                null
        );
    }

    private ResponseEntity<Object> errorResponse(HttpServletRequest request,
                                                 ErrorDefinition definition,
                                                 String userMessage,
                                                 String developerMessage,
                                                 ErrorCode legacyErrorCode,
                                                 Object legacyData) {
        return responseFactory.errorResponse(
                request,
                definition,
                userMessage,
                developerMessage,
                legacyErrorCode,
                legacyData,
                definition.httpStatus()
        );
    }

    private void logAppException(AppException exception) {
        ErrorDefinition definition = exception.getErrorDefinition();
        String developerMessage = ErrorMessageSanitizer.sanitizeDeveloperMessage(
                exception.getDeveloperMessage(),
                definition.code() + " occurred"
        );
        String logContext = ErrorMessageSanitizer.sanitizeDeveloperMessage(
                String.valueOf(exception.getLogContext()),
                "{}"
        );
        if (definition.logLevel() == ErrorLogLevel.INFO) {
            log.info("Application exception: errorCode={}, traceId={}, developerMessage={}, context={}",
                    definition.code(), traceId(), developerMessage, logContext);
        } else if (definition.logLevel() == ErrorLogLevel.WARN) {
            log.warn("Application exception: errorCode={}, traceId={}, developerMessage={}, context={}",
                    definition.code(), traceId(), developerMessage, logContext);
        } else {
            log.error("Application exception: errorCode={}, traceId={}, developerMessage={}, context={}",
                    definition.code(), traceId(), developerMessage, logContext, exception);
        }
    }

    private String validationDeveloperMessage(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException validationException) {
            FieldError fieldError = validationException.getBindingResult().getFieldError();
            if (fieldError != null) {
                return "Request validation failed; field=" + fieldError.getField()
                        + ", reason=" + fieldError.getDefaultMessage();
            }
        }
        if (exception instanceof MissingServletRequestParameterException missingParameterException) {
            return "Required request parameter is missing; parameter=" + missingParameterException.getParameterName()
                    + ", expectedType=" + missingParameterException.getParameterType();
        }
        if (exception instanceof MethodArgumentTypeMismatchException mismatchException) {
            String requiredType = mismatchException.getRequiredType() == null
                    ? "unknown"
                    : mismatchException.getRequiredType().getName();
            return "Request parameter type mismatch; parameter=" + mismatchException.getName()
                    + ", expectedType=" + requiredType;
        }
        return "Request validation failed; exceptionType=" + exception.getClass().getName();
    }

    private String diagnosticReference(String summary) {
        String currentTraceId = traceId();
        if (currentTraceId == null || currentTraceId.isBlank()) {
            return summary + "; inspect server logs";
        }
        return summary + "; inspect server logs with traceId=" + currentTraceId;
    }

    private String traceId() {
        return MDC.get("traceId");
    }

    private Throwable rootCause(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
