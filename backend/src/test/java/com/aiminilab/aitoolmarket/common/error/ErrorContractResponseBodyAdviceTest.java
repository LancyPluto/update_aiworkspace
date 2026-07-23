package com.aiminilab.aitoolmarket.common.error;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.UserType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorContractResponseBodyAdviceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearContext() {
        MDC.remove("traceId");
        AuthContext.clear();
    }

    @Test
    void legacyModeLeavesApiResponseUnchanged() {
        ErrorContractProperties properties = new ErrorContractProperties();
        ErrorContractResponseBodyAdvice advice = advice(properties);
        ApiResponse<String> original = ApiResponse.success("ok-data");

        AdviceResult result = apply(advice, original, request("/api/v1/ping"));

        assertThat(result.body()).isSameAs(original);
        assertThat(result.response().getStatus()).isEqualTo(200);
    }

    @Test
    void v2SuccessContainsOnlyCodeDataAndTraceId() {
        MDC.put("traceId", "trace-success-1");
        ErrorContractResponseBodyAdvice advice = advice(v2Properties());

        AdviceResult result = apply(advice, ApiResponse.success(Map.of("ready", true)), request("/api/v1/ping"));

        JsonNode body = objectMapper.valueToTree(result.body());
        assertThat(body.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("code", "data", "traceId");
        assertThat(body.get("code").asText()).isEqualTo("SUCCESS");
        assertThat(body.get("traceId").asText()).isEqualTo("trace-success-1");
        assertThat(body.toString()).doesNotContain("message");
    }

    @Test
    void v2LegacyFailureUsesSafeUserContractAndDefinitionStatus() {
        MDC.put("traceId", "trace-user-failure-1");
        ErrorContractResponseBodyAdvice advice = advice(v2Properties());

        AdviceResult result = apply(
                advice,
                ApiResponse.fail(ErrorCode.SYSTEM_ERROR, "jdbc:mysql://db/private password=secret-value"),
                request("/api/v1/tasks")
        );

        assertThat(result.response().getStatus()).isEqualTo(500);
        assertThat(result.body()).isInstanceOf(UserErrorResponse.class);
        JsonNode body = objectMapper.valueToTree(result.body());
        assertThat(body.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("errorCode", "userMessage", "traceId");
        assertThat(body.toString()).doesNotContain("developerMessage", "jdbc:mysql", "secret-value", "data");
    }

    @Test
    void v2AdminFailureRequiresAuthenticatedAdminAndLoginStaysUserSafe() {
        MDC.put("traceId", "trace-admin-failure-1");
        AuthContext.set(new AuthUser(7L, "ops", UserType.ADMIN.name()));
        ErrorContractResponseBodyAdvice advice = advice(v2Properties());

        AdviceResult adminResult = apply(
                advice,
                ApiResponse.fail(ErrorCode.PARAM_ERROR, "workflowId=42 password=secret-value"),
                request("/api/admin/v1/tools/42/workflow/publish")
        );
        AdviceResult loginResult = apply(
                advice,
                ApiResponse.fail(ErrorCode.PARAM_ERROR, "provider diagnostic password=secret-value"),
                request("/api/admin/v1/auth/login")
        );

        assertThat(adminResult.body()).isInstanceOf(AdminErrorResponse.class);
        assertThat(objectMapper.valueToTree(adminResult.body()).toString())
                .contains("developerMessage", "workflowId=42", "[REDACTED]")
                .doesNotContain("userMessage", "secret-value", "message", "details", "data");
        assertThat(loginResult.body()).isInstanceOf(UserErrorResponse.class);
        assertThat(objectMapper.valueToTree(loginResult.body()).toString())
                .doesNotContain("developerMessage", "diagnostic", "secret-value");
    }

    @Test
    void v2InternalFailureRequiresVerifiedSignatureAttribute() {
        MDC.put("traceId", "trace-internal-failure-1");
        ErrorContractResponseBodyAdvice advice = advice(v2Properties());
        MockHttpServletRequest verified = request("/api/internal/v1/tasks/42/failed");
        verified.setAttribute(ErrorContractResponseFactory.INTERNAL_SIGNATURE_VERIFIED_ATTRIBUTE, Boolean.TRUE);

        AdviceResult verifiedResult = apply(
                advice,
                ApiResponse.fail(ErrorCode.MODEL_CALL_FAILED, "provider=minimax requestId=req-42"),
                verified
        );
        AdviceResult unverifiedResult = apply(
                advice,
                ApiResponse.fail(ErrorCode.MODEL_CALL_FAILED, "provider=minimax requestId=req-42"),
                request("/api/internal/v1/tasks/42/failed")
        );

        assertThat(verifiedResult.body()).isInstanceOf(AdminErrorResponse.class);
        assertThat(unverifiedResult.body()).isInstanceOf(UserErrorResponse.class);
        assertThat(objectMapper.valueToTree(unverifiedResult.body()).toString())
                .doesNotContain("developerMessage", "minimax", "req-42");
    }

    private ErrorContractResponseBodyAdvice advice(ErrorContractProperties properties) {
        return new ErrorContractResponseBodyAdvice(new ErrorContractResponseFactory(properties));
    }

    private ErrorContractProperties v2Properties() {
        ErrorContractProperties properties = new ErrorContractProperties();
        properties.setMode(ErrorContractMode.V2);
        return properties;
    }

    private AdviceResult apply(ErrorContractResponseBodyAdvice advice,
                               Object body,
                               MockHttpServletRequest servletRequest) {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        Object advised = advice.beforeBodyWrite(
                body,
                null,
                MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class,
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(servletResponse)
        );
        return new AdviceResult(advised, servletResponse);
    }

    private MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private record AdviceResult(Object body, MockHttpServletResponse response) {
    }
}
