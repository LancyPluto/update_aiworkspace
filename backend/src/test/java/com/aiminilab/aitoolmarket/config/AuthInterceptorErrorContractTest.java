package com.aiminilab.aitoolmarket.config;

import com.aiminilab.aitoolmarket.auth.metrics.AuthMetrics;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.auth.security.AuthCookieSupport;
import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.auth.security.InternalRequestSignatureVerifier;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.common.enums.UserStatus;
import com.aiminilab.aitoolmarket.common.enums.UserType;
import com.aiminilab.aitoolmarket.common.error.ErrorContractMode;
import com.aiminilab.aitoolmarket.common.error.ErrorContractProperties;
import com.aiminilab.aitoolmarket.common.error.ErrorContractResponseFactory;
import com.aiminilab.aitoolmarket.task.support.ProviderCheckpointLimits;
import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthInterceptorErrorContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearTraceId() {
        MDC.remove("traceId");
        AuthContext.clear();
    }

    @Test
    void adminRoutesPreferAdminSessionWhenBothCookiesArePresent() throws Exception {
        for (String path : List.of(
                "/api/admin/v1/tasks",
                "/api/admin/ai-tools",
                "/api/admin/upload-icon"
        )) {
            assertAdminSessionPreferred(path);
        }
    }

    @Test
    void v2MissingAdminCredentialsUsesUserContract() throws Exception {
        MDC.put("traceId", "trace-admin-auth-1");
        AuthInterceptor interceptor = interceptor(v2Properties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertUserContract(response, "AUTH_001", "trace-admin-auth-1");
    }

    @Test
    void v2PresentedButInvalidCredentialUsesExpiredCode() throws Exception {
        MDC.put("traceId", "trace-expired-auth-1");
        AuthInterceptor interceptor = interceptor(v2Properties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertUserContract(response, "AUTH_002", "trace-expired-auth-1");
    }

    @Test
    void legacyInvalidCredentialKeepsUnauthorizedCode() throws Exception {
        MDC.put("traceId", "trace-legacy-auth-1");
        AuthInterceptor interceptor = interceptor(new ErrorContractProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(body.get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("message").asText()).isEqualTo("未登录或 Token 失效");
    }

    @Test
    void v2InvalidInternalSignatureDoesNotReceiveDeveloperMessage() throws Exception {
        MDC.put("traceId", "trace-internal-auth-1");
        AuthInterceptor interceptor = interceptor(v2Properties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/internal/v1/tasks/1/processing");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("{}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertUserContract(response, "AUTH_001", "trace-internal-auth-1");
    }

    @Test
    void v2OversizedInternalCheckpointUsesUploadErrorContract() throws Exception {
        MDC.put("traceId", "trace-internal-size-1");
        AuthInterceptor interceptor = interceptor(v2Properties());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/internal/v1/tasks/1/provider-checkpoint"
        );
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[ProviderCheckpointLimits.MAX_REQUEST_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertUserContract(response, "API_002", "trace-internal-size-1");
    }

    private void assertUserContract(MockHttpServletResponse response,
                                    String expectedErrorCode,
                                    String expectedTraceId) throws Exception {
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.properties().stream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("errorCode", "userMessage", "traceId");
        assertThat(body.get("errorCode").asText()).isEqualTo(expectedErrorCode);
        assertThat(body.get("traceId").asText()).isEqualTo(expectedTraceId);
        assertThat(body.toString()).doesNotContain("developerMessage", "message", "details", "data");
    }

    private void assertAdminSessionPreferred(String path) throws Exception {
        JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
        UserMapper userMapper = mock(UserMapper.class);
        AuthUser adminUser = new AuthUser(7L, "admin", UserType.ADMIN.name());
        User activeUser = new User();
        activeUser.setStatus(UserStatus.ACTIVE.name());
        activeUser.setDeleted(false);
        when(jwtTokenProvider.parseToken("admin-token")).thenReturn(Optional.of(adminUser));
        when(jwtTokenProvider.parseToken("user-token"))
                .thenReturn(Optional.of(new AuthUser(8L, "user", UserType.USER.name())));
        when(userMapper.findById(7L)).thenReturn(Optional.of(activeUser));

        AuthInterceptor interceptor = new AuthInterceptor(
                jwtTokenProvider,
                objectMapper,
                new ErrorContractResponseFactory(v2Properties()),
                mock(InternalRequestSignatureVerifier.class),
                userMapper,
                mock(AuthMetrics.class)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setCookies(
                new Cookie(AuthCookieSupport.USER_SESSION_COOKIE, "user-token"),
                new Cookie(AuthCookieSupport.ADMIN_SESSION_COOKIE, "admin-token")
        );

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).as(path).isTrue();
        assertThat(AuthContext.get()).as(path).isEqualTo(adminUser);
        verify(jwtTokenProvider).parseToken("admin-token");
        verify(jwtTokenProvider, never()).parseToken("user-token");
        AuthContext.clear();
    }

    private AuthInterceptor interceptor(ErrorContractProperties properties) {
        return new AuthInterceptor(
                mock(JwtTokenProvider.class),
                objectMapper,
                new ErrorContractResponseFactory(properties),
                mock(InternalRequestSignatureVerifier.class),
                mock(UserMapper.class),
                mock(AuthMetrics.class)
        );
    }

    private ErrorContractProperties v2Properties() {
        ErrorContractProperties properties = new ErrorContractProperties();
        properties.setMode(ErrorContractMode.V2);
        return properties;
    }
}
