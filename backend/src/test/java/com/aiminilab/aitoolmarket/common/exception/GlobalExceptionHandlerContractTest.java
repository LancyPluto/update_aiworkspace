package com.aiminilab.aitoolmarket.common.exception;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.UserType;
import com.aiminilab.aitoolmarket.common.error.AdminErrorResponse;
import com.aiminilab.aitoolmarket.common.error.ApiErrors;
import com.aiminilab.aitoolmarket.common.error.ErrorContractMode;
import com.aiminilab.aitoolmarket.common.error.ErrorContractProperties;
import com.aiminilab.aitoolmarket.common.error.ErrorContractResponseFactory;
import com.aiminilab.aitoolmarket.common.error.ErrorDefinitionRegistry;
import com.aiminilab.aitoolmarket.common.error.LegacyErrorCodeMapper;
import com.aiminilab.aitoolmarket.common.error.ModelErrors;
import com.aiminilab.aitoolmarket.common.error.TaskErrors;
import com.aiminilab.aitoolmarket.common.error.ToolErrors;
import com.aiminilab.aitoolmarket.common.error.UserErrorResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearTraceId() {
        MDC.remove("traceId");
        AuthContext.clear();
    }

    @Test
    void v2UserResponseContainsOnlySafeUserFields() {
        MDC.put("traceId", "trace-user-1");
        DependencyException exception = new DependencyException(
                ModelErrors.PROVIDER_CALL_FAILED,
                "provider=minimax, Authorization=Bearer secret-token, password=hunter2",
                new IllegalStateException("provider root response"),
                Map.of("providerRequestId", "provider-request-1")
        );

        ResponseEntity<Object> response = v2Handler().handleAppException(
                exception,
                request("/api/v1/tasks")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isInstanceOf(UserErrorResponse.class);
        JsonNode body = objectMapper.valueToTree(response.getBody());
        assertThat(body.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("errorCode", "userMessage", "traceId");
        assertThat(body.get("errorCode").asText()).isEqualTo("MODEL_001");
        assertThat(body.get("userMessage").asText()).isEqualTo("模型调用失败，请稍后重试");
        assertThat(body.toString()).doesNotContain("developerMessage", "minimax", "secret-token", "hunter2");
    }

    @Test
    void v2AdminResponseContainsOnlySanitizedDeveloperFields() {
        MDC.put("traceId", "trace-admin-1");
        AuthContext.set(new AuthUser(1L, "admin", UserType.ADMIN.name()));
        DependencyException exception = new DependencyException(
                ModelErrors.PROVIDER_CALL_FAILED,
                "provider=minimax, Authorization=Bearer secret-token, password=hunter2, phone=13376644413, "
                        + "payload={\"apiKey\":\"json-secret\"}, diagnostic=" + "x".repeat(2200),
                null,
                Map.of()
        );

        ResponseEntity<Object> response = v2Handler().handleAppException(
                exception,
                request("/api/admin/v1/tasks")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isInstanceOf(AdminErrorResponse.class);
        JsonNode body = objectMapper.valueToTree(response.getBody());
        assertThat(body.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("errorCode", "developerMessage", "traceId");
        assertThat(body.get("developerMessage").asText())
                .contains("provider=minimax", "[REDACTED]", "[REDACTED_PHONE]")
                .doesNotContain("secret-token", "hunter2", "json-secret", "13376644413")
                .hasSizeLessThanOrEqualTo(2000);
        assertThat(body.toString()).doesNotContain("userMessage", "message", "details", "data");
    }

    @Test
    void v2OnlyTrustedInternalRequestReceivesDeveloperFields() {
        MDC.put("traceId", "trace-internal-1");
        DependencyException exception = new DependencyException(
                ModelErrors.PROVIDER_CALL_FAILED,
                "provider=minimax, requestId=provider-request-1",
                null,
                Map.of()
        );
        MockHttpServletRequest trustedRequest = request("/api/internal/v1/tasks/1/failed");
        trustedRequest.setAttribute(
                ErrorContractResponseFactory.INTERNAL_SIGNATURE_VERIFIED_ATTRIBUTE,
                Boolean.TRUE
        );

        ResponseEntity<Object> trustedResponse = v2Handler().handleAppException(exception, trustedRequest);
        ResponseEntity<Object> untrustedResponse = v2Handler().handleAppException(
                exception,
                request("/api/internal/v1/tasks/1/failed")
        );

        assertThat(trustedResponse.getBody()).isInstanceOf(AdminErrorResponse.class);
        assertThat(untrustedResponse.getBody()).isInstanceOf(UserErrorResponse.class);
        assertThat(objectMapper.valueToTree(untrustedResponse.getBody()).toString())
                .doesNotContain("developerMessage", "minimax", "provider-request-1");
    }

    @Test
    void v2AdminLoginAlwaysUsesUserFields() {
        MDC.put("traceId", "trace-admin-login-1");
        AuthContext.set(new AuthUser(1L, "admin", UserType.ADMIN.name()));

        ResponseEntity<Object> response = v2Handler().handleAppException(
                new DependencyException(ModelErrors.PROVIDER_CALL_FAILED, "provider response contains diagnostics"),
                request("/api/admin/v1/auth/login")
        );

        assertThat(response.getBody()).isInstanceOf(UserErrorResponse.class);
        assertThat(objectMapper.valueToTree(response.getBody()).toString())
                .doesNotContain("developerMessage", "diagnostics");
    }

    @Test
    void v2AsyncDispatchFallsBackToRequestTraceId() {
        MockHttpServletRequest asyncRequest = request("/api/v1/tasks/42/events");
        asyncRequest.setAttribute("traceId", "trace-async-1");

        ResponseEntity<Object> response = v2Handler().handleAppException(
                new DependencyException(ModelErrors.PROVIDER_CALL_FAILED, "async provider failure"),
                asyncRequest
        );

        assertThat(response.getBody()).isInstanceOf(UserErrorResponse.class);
        assertThat(((UserErrorResponse) response.getBody()).traceId()).isEqualTo("trace-async-1");
    }

    @Test
    void v2AdminResponseKeepsRawPayloadsAndStacksInLogsOnly() {
        MDC.put("traceId", "trace-admin-redaction");
        AuthContext.set(new AuthUser(1L, "admin", UserType.ADMIN.name()));

        ResponseEntity<Object> payloadResponse = v2Handler().handleAppException(
                new DependencyException(
                        ModelErrors.PROVIDER_RESPONSE_INVALID,
                        "provider=minimax status=502 responseBody={\"secret\":\"raw-provider-body\"}"
                ),
                request("/api/admin/v1/tasks")
        );
        ResponseEntity<Object> stackResponse = v2Handler().handleAppException(
                new SystemException(
                        com.aiminilab.aitoolmarket.common.error.SystemErrors.INTERNAL_ERROR,
                        "Traceback (most recent call last): File \"worker.py\", line 42, password=stack-secret"
                ),
                request("/api/admin/v1/tasks")
        );

        JsonNode payloadBody = objectMapper.valueToTree(payloadResponse.getBody());
        JsonNode stackBody = objectMapper.valueToTree(stackResponse.getBody());
        assertThat(payloadBody.get("developerMessage").asText())
                .contains("provider=minimax", "responseBody=[REDACTED]")
                .doesNotContain("raw-provider-body");
        assertThat(stackBody.get("developerMessage").asText())
                .contains("stack trace redacted")
                .doesNotContain("worker.py", "stack-secret");
    }

    @Test
    void legacyDatabaseFailureNeverReturnsRootCause() {
        MDC.put("traceId", "trace-db-1");
        DataAccessResourceFailureException exception = new DataAccessResourceFailureException(
                "query failed",
                new IllegalStateException(
                        "jdbc:mysql://db.internal/app?password=topsecret SELECT password FROM users"
                )
        );

        ResponseEntity<Object> response = legacyHandler().handleDataAccessException(
                exception,
                request("/api/v1/tasks")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isInstanceOf(ApiResponse.class);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body.code()).isEqualTo(ErrorCode.SYSTEM_ERROR.name());
        assertThat(body.message()).isEqualTo("系统繁忙，请稍后重试");
        assertThat(body.toString())
                .doesNotContain("jdbc:mysql", "db.internal", "topsecret", "SELECT", "users");

        ResponseEntity<Object> unknownResponse = legacyHandler().handleException(
                new IllegalStateException("internalHost=db.private password=unknown-secret"),
                request("/api/v1/tasks")
        );
        ApiResponse<?> unknownBody = (ApiResponse<?>) unknownResponse.getBody();
        assertThat(unknownBody.message()).isEqualTo("系统繁忙，请稍后重试");
        assertThat(unknownBody.toString()).doesNotContain("db.private", "unknown-secret");

        ResponseEntity<Object> legacySystemResponse = legacyHandler().handleAppException(
                new BusinessException(ErrorCode.SYSTEM_ERROR, "供应商响应：token=provider-secret"),
                request("/api/v1/tasks")
        );
        ApiResponse<?> legacySystemBody = (ApiResponse<?>) legacySystemResponse.getBody();
        assertThat(legacySystemBody.message()).isEqualTo("系统繁忙，请稍后重试");
        assertThat(legacySystemBody.toString()).doesNotContain("provider-secret");
    }

    @Test
    void errorDefinitionControlsHttpStatusIndependentlyFromCode() {
        GlobalExceptionHandler handler = v2Handler();

        ResponseEntity<Object> missingTool = handler.handleAppException(
                new BizException(ToolErrors.TOOL_NOT_FOUND, "工具不存在", "toolCode=image-generator was not found"),
                request("/api/v1/tools/image-generator")
        );
        ResponseEntity<Object> taskConflict = handler.handleAppException(
                new BizException(TaskErrors.TASK_STATE_CONFLICT, "任务状态冲突", "taskId=42 status=SUCCEEDED"),
                request("/api/v1/tasks/42")
        );
        ResponseEntity<Object> modelTimeout = handler.handleAppException(
                new DependencyException(ModelErrors.RESPONSE_TIMEOUT, "provider request elapsedMs=60001"),
                request("/api/v1/tasks")
        );
        ResponseEntity<Object> uploadTooLarge = handler.handleUploadTooLarge(
                new MaxUploadSizeExceededException(1024),
                request("/api/v1/files")
        );

        assertThat(missingTool.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(taskConflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(modelTimeout.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(uploadTooLarge.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(((UserErrorResponse) uploadTooLarge.getBody()).errorCode()).isEqualTo("API_002");
    }

    @Test
    void legacyBusinessExceptionKeepsOldShapeAndStatus() {
        ResponseEntity<Object> response = legacyHandler().handleAppException(
                new BusinessException(ErrorCode.TASK_NOT_FOUND, "任务不存在"),
                request("/api/v1/tasks/missing")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(ApiResponse.class);
        ApiResponse<?> body = (ApiResponse<?>) response.getBody();
        assertThat(body.code()).isEqualTo("TASK_NOT_FOUND");
        assertThat(body.message()).isEqualTo("任务不存在");
    }

    @Test
    void everyLegacyErrorAndRegisteredDefinitionIsValid() {
        assertThat(Arrays.stream(ErrorCode.values())
                .filter(errorCode -> errorCode != ErrorCode.SUCCESS)
                .map(LegacyErrorCodeMapper::fromLegacy))
                .allSatisfy(definition -> assertThat(definition.code()).matches("^[A-Z][A-Z0-9]*_[0-9]{3}$"));

        assertThat(ErrorDefinitionRegistry.all())
                .extracting(definition -> definition.code())
                .doesNotHaveDuplicates()
                .allSatisfy(code -> assertThat(code).matches("^[A-Z][A-Z0-9]*_[0-9]{3}$"));
    }

    @Test
    void contractModeDefaultsToLegacy() {
        assertThat(new ErrorContractProperties().getMode()).isEqualTo(ErrorContractMode.LEGACY);
        assertThat(ApiErrors.JSON_BODY_UNREADABLE.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private GlobalExceptionHandler legacyHandler() {
        return new GlobalExceptionHandler(new ErrorContractResponseFactory(new ErrorContractProperties()));
    }

    private GlobalExceptionHandler v2Handler() {
        ErrorContractProperties properties = new ErrorContractProperties();
        properties.setMode(ErrorContractMode.V2);
        return new GlobalExceptionHandler(new ErrorContractResponseFactory(properties));
    }

    private MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }
}
