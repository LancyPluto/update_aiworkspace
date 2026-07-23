package com.aiminilab.aitoolmarket.common.error;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.UserType;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class ErrorContractResponseFactory {

    public static final String INTERNAL_SIGNATURE_VERIFIED_ATTRIBUTE =
            ErrorContractResponseFactory.class.getName() + ".internalSignatureVerified";

    private static final String ADMIN_LOGIN_PATH = "/api/admin/v1/auth/login";

    private final ErrorContractProperties contractProperties;

    public ErrorContractResponseFactory(ErrorContractProperties contractProperties) {
        this.contractProperties = contractProperties;
    }

    public boolean isV2() {
        return contractProperties.isV2();
    }

    public ResponseEntity<Object> errorResponse(HttpServletRequest request,
                                                ErrorDefinition definition,
                                                String userMessage,
                                                String developerMessage,
                                                ErrorCode legacyErrorCode,
                                                Object legacyData,
                                                HttpStatus legacyStatus) {
        Object body = isV2()
                ? v2ErrorBody(request, definition, userMessage, developerMessage)
                : ApiResponse.fail(
                        legacyErrorCode,
                        ErrorMessageSanitizer.sanitizeUserMessage(userMessage, definition.defaultUserMessage()),
                        legacyData
                );
        HttpStatus status = isV2() ? definition.httpStatus() : legacyStatus;
        return ResponseEntity.status(status).body(body);
    }

    public Object v2ErrorBody(HttpServletRequest request,
                              ErrorDefinition definition,
                              String userMessage,
                              String developerMessage) {
        if (canReceiveDeveloperMessage(request)) {
            return new AdminErrorResponse(
                    definition.code(),
                    ErrorMessageSanitizer.sanitizeDeveloperMessage(
                            developerMessage,
                            diagnosticReference(definition.code() + " occurred", request)
                    ),
                    traceId(request)
            );
        }
        return userErrorBody(definition, userMessage, traceId(request));
    }

    public UserErrorResponse userErrorBody(ErrorDefinition definition, String userMessage, String traceId) {
        return new UserErrorResponse(
                definition.code(),
                ErrorMessageSanitizer.sanitizeUserMessage(userMessage, definition.defaultUserMessage()),
                traceId
        );
    }

    public String traceId() {
        return MDC.get("traceId");
    }

    private boolean canReceiveDeveloperMessage(HttpServletRequest request) {
        if (request == null || request.getRequestURI() == null) {
            return false;
        }
        String path = normalizePath(request.getRequestURI());
        if (path.startsWith("/api/internal/v1/")) {
            return Boolean.TRUE.equals(request.getAttribute(INTERNAL_SIGNATURE_VERIFIED_ATTRIBUTE));
        }
        if (!isAdminPath(path) || ADMIN_LOGIN_PATH.equals(path)) {
            return false;
        }
        AuthUser authUser = AuthContext.get();
        return authUser != null && UserType.ADMIN.name().equals(authUser.userType());
    }

    private boolean isAdminPath(String path) {
        return path.startsWith("/api/admin/")
                || path.startsWith("/api/admin/v1/")
                || path.startsWith("/api/admin/ai-tools")
                || path.equals("/api/admin/upload-icon");
    }

    private String normalizePath(String path) {
        return path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
    }

    private String traceId(HttpServletRequest request) {
        String currentTraceId = traceId();
        if (currentTraceId != null && !currentTraceId.isBlank()) {
            return currentTraceId;
        }
        if (request == null) {
            return null;
        }
        Object requestTraceId = request.getAttribute("traceId");
        return requestTraceId instanceof String value && !value.isBlank() ? value : null;
    }

    private String diagnosticReference(String summary, HttpServletRequest request) {
        String currentTraceId = traceId(request);
        if (currentTraceId == null || currentTraceId.isBlank()) {
            return summary + "; inspect server logs";
        }
        return summary + "; inspect server logs with traceId=" + currentTraceId;
    }
}
