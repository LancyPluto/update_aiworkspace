package com.aiminilab.aitoolmarket.config;

import com.aiminilab.aitoolmarket.auth.metrics.AuthMetrics;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.auth.security.AuthCookieSupport;
import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.auth.security.InternalRequestSignatureVerifier;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.UserStatus;
import com.aiminilab.aitoolmarket.common.enums.UserType;
import com.aiminilab.aitoolmarket.common.error.ApiErrors;
import com.aiminilab.aitoolmarket.common.error.AuthErrors;
import com.aiminilab.aitoolmarket.common.error.ErrorContractResponseFactory;
import com.aiminilab.aitoolmarket.common.error.ErrorDefinition;
import com.aiminilab.aitoolmarket.task.support.ProviderCheckpointLimits;
import com.aiminilab.aitoolmarket.ppt.security.PptExecutionTokenService;
import com.aiminilab.aitoolmarket.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
public class AuthInterceptor implements HandlerInterceptor, Filter {

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    private final ErrorContractResponseFactory responseFactory;
    private final InternalRequestSignatureVerifier internalRequestSignatureVerifier;
    private final UserMapper userMapper;
    private final AuthMetrics authMetrics;
    private final PptExecutionTokenService pptExecutionTokenService;

    @Autowired
    public AuthInterceptor(JwtTokenProvider jwtTokenProvider,
                           ObjectMapper objectMapper,
                           ErrorContractResponseFactory responseFactory,
                           InternalRequestSignatureVerifier internalRequestSignatureVerifier,
                           UserMapper userMapper,
                           AuthMetrics authMetrics,
                           PptExecutionTokenService pptExecutionTokenService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
        this.responseFactory = responseFactory;
        this.internalRequestSignatureVerifier = internalRequestSignatureVerifier;
        this.userMapper = userMapper;
        this.authMetrics = authMetrics;
        this.pptExecutionTokenService = pptExecutionTokenService;
    }

    public AuthInterceptor(JwtTokenProvider jwtTokenProvider,
                           ObjectMapper objectMapper,
                           ErrorContractResponseFactory responseFactory,
                           InternalRequestSignatureVerifier internalRequestSignatureVerifier,
                           UserMapper userMapper,
                           AuthMetrics authMetrics) {
        this(jwtTokenProvider, objectMapper, responseFactory,
                internalRequestSignatureVerifier, userMapper, authMetrics, null);
    }

    @Override
    public void doFilter(ServletRequest servletRequest,
                         ServletResponse servletResponse,
                         FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        if (!request.getRequestURI().startsWith("/api/internal/v1/")) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        if (isPptModelInvocationRequest(request)
                && pptExecutionTokenService != null
                && pptExecutionTokenService.accepts(request.getHeader("Authorization"))) {
            request.setAttribute(
                    ErrorContractResponseFactory.INTERNAL_SIGNATURE_VERIFIED_ATTRIBUTE,
                    Boolean.TRUE);
            filterChain.doFilter(request, response);
            return;
        }

        byte[] body;
        if (isProviderCheckpointRequest(request)) {
            long contentLength = request.getContentLengthLong();
            if (contentLength > ProviderCheckpointLimits.MAX_REQUEST_BODY_BYTES) {
                try {
                    writeError(request, response, HttpStatus.PAYLOAD_TOO_LARGE, ApiErrors.UPLOAD_TOO_LARGE,
                            ErrorCode.PARAM_ERROR, "供应商任务检查点请求过大",
                            "Provider checkpoint request exceeded the configured body limit");
                } catch (Exception exception) {
                    throw new ServletException(exception);
                }
                return;
            }
            body = request.getInputStream().readNBytes(ProviderCheckpointLimits.MAX_REQUEST_BODY_BYTES + 1);
            if (body.length > ProviderCheckpointLimits.MAX_REQUEST_BODY_BYTES) {
                try {
                    writeError(request, response, HttpStatus.PAYLOAD_TOO_LARGE, ApiErrors.UPLOAD_TOO_LARGE,
                            ErrorCode.PARAM_ERROR, "供应商任务检查点请求过大",
                            "Provider checkpoint request exceeded the configured body limit");
                } catch (Exception exception) {
                    throw new ServletException(exception);
                }
                return;
            }
        } else {
            body = StreamUtils.copyToByteArray(request.getInputStream());
        }
        CachedBodyRequest wrappedRequest = new CachedBodyRequest(request, body);
        if (!verifyInternalSignature(wrappedRequest, body)) {
            try {
                authMetrics.recordUnauthorizedRequest(request.getRequestURI(), "internal_signature_invalid");
                writeError(request, response, HttpStatus.UNAUTHORIZED, AuthErrors.CREDENTIALS_MISSING,
                        ErrorCode.UNAUTHORIZED, "内部接口签名无效",
                        "Internal request signature is missing or invalid");
            } catch (Exception exception) {
                throw new ServletException(exception);
            }
            return;
        }

        wrappedRequest.setAttribute(ErrorContractResponseFactory.INTERNAL_SIGNATURE_VERIFIED_ATTRIBUTE, Boolean.TRUE);
        filterChain.doFilter(wrappedRequest, response);
    }

    private boolean isProviderCheckpointRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().matches("/api/internal/v1/tasks/[^/]+/provider-checkpoint");
    }

    private boolean isPptModelInvocationRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/internal/v1/ppt/model-invocations")
                || path.matches("/api/internal/v1/ppt/model-invocations/[^/]+");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (isPublicPath(request.getMethod(), path)) {
            trySetAuthContext(request);
            return true;
        }

        if (path.startsWith("/api/internal/v1/")) {
            if (Boolean.TRUE.equals(request.getAttribute(
                    ErrorContractResponseFactory.INTERNAL_SIGNATURE_VERIFIED_ATTRIBUTE))) {
                return true;
            }
            authMetrics.recordUnauthorizedRequest(path, "internal_signature_invalid");
            writeError(request, response, HttpStatus.UNAUTHORIZED, AuthErrors.CREDENTIALS_MISSING,
                    ErrorCode.UNAUTHORIZED, "内部接口签名无效",
                    "Internal request signature is missing or invalid");
            return false;
        }

        Optional<AuthUser> authUser = extractAuthUser(request);
        if (authUser.isEmpty()) {
            boolean credentialsPresented = hasPresentedCredentials(request);
            authMetrics.recordUnauthorizedRequest(
                    path,
                    credentialsPresented ? "invalid_or_expired_token" : "missing_token"
            );
            writeError(request, response, HttpStatus.UNAUTHORIZED,
                    credentialsPresented ? AuthErrors.CREDENTIALS_EXPIRED : AuthErrors.CREDENTIALS_MISSING,
                    ErrorCode.UNAUTHORIZED, "未登录或 Token 失效",
                    credentialsPresented
                            ? "Authentication credentials are invalid or expired"
                            : "Authentication credentials are missing");
            return false;
        }

        if (!isActiveUser(authUser.get())) {
            authMetrics.recordUnauthorizedRequest(path, "inactive_user");
            writeError(request, response, HttpStatus.UNAUTHORIZED, AuthErrors.CREDENTIALS_EXPIRED,
                    ErrorCode.UNAUTHORIZED, "账号已注销或被禁用",
                    "Authenticated account is inactive or deleted");
            return false;
        }

        if (requiresAdmin(path)
                && !UserType.ADMIN.name().equals(authUser.get().userType())) {
            authMetrics.recordUnauthorizedRequest(path, "admin_forbidden");
            writeError(request, response, HttpStatus.FORBIDDEN, AuthErrors.ACCESS_DENIED,
                    ErrorCode.ADMIN_FORBIDDEN, "管理员无权限",
                    "Authenticated principal does not have administrator access");
            return false;
        }

        AuthContext.set(authUser.get());
        return true;
    }

    private boolean isActiveUser(AuthUser authUser) {
        return userMapper.findById(authUser.userId())
                .filter(user -> user.getDeleted() == null || !user.getDeleted())
                .filter(user -> UserStatus.ACTIVE.name().equals(user.getStatus()))
                .isPresent();
    }

    private void trySetAuthContext(HttpServletRequest request) {
        extractAuthUser(request)
                .filter(this::isActiveUser)
                .ifPresent(AuthContext::set);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }

    private boolean isPublicPath(String method, String path) {
        return path.equals("/api/health")
                || path.equals("/api/v1/ping")
                || path.equals("/api/admin/v1/ping")
                || path.equals("/actuator/health")
                || path.equals("/actuator/info")
                || path.equals("/actuator/prometheus")
                || path.startsWith("/actuator/metrics")
                || path.startsWith("/api/v1/auth/")
                || ("POST".equalsIgnoreCase(method) && path.equals("/api/v1/observability/web-vitals"))
                || ("POST".equalsIgnoreCase(method) && path.equals("/api/v1/observability/media-events"))
                || ("POST".equalsIgnoreCase(method)
                    && path.matches("/api/v1/provider-callbacks/suno/music/[a-f0-9]{64}"))
                || path.equals("/api/v1/settings/customer-service")
                || path.startsWith("/api/v1/pay/wechat/")
                || path.startsWith("/api/v1/pay/alipay/")
                || path.equals("/api/v1/tool-categories")
                || ("GET".equalsIgnoreCase(method) && path.equals("/api/v1/model-options"))
                || path.equals("/api/v1/tools")
                || path.startsWith("/api/v1/tools/")
                || path.equals("/api/v1/ai-tools")
                || path.startsWith("/api/v1/ai-tools/")
                || ("GET".equalsIgnoreCase(method) && path.startsWith("/api/v1/community/users/"))
                || ("GET".equalsIgnoreCase(method) && path.startsWith("/api/v1/community/creators/"))
                || ("GET".equalsIgnoreCase(method) && path.equals("/api/v1/community/search"))
                || ("GET".equalsIgnoreCase(method) && path.equals("/api/v1/community/topics"))
                || ("GET".equalsIgnoreCase(method) && path.startsWith("/api/v1/community/topics/"))
                || ("GET".equalsIgnoreCase(method) && path.equals("/api/v1/community/posts"))
                || ("GET".equalsIgnoreCase(method) && path.matches("/api/v1/community/posts/\\d+/download"))
                || ("GET".equalsIgnoreCase(method) && path.matches("/api/v1/community/posts/\\d+"))
                || ("POST".equalsIgnoreCase(method) && path.equals("/api/v1/community/events"))
                || isAdminAuthLoginPath(path);
    }

    private boolean isAdminAuthLoginPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;
        return "/api/admin/v1/auth/login".equals(normalized);
    }

    private boolean requiresAdmin(String path) {
        return path.startsWith("/api/admin/v1/")
                || path.startsWith("/api/admin/ai-tools")
                || path.equals("/api/admin/upload-icon");
    }

    private boolean verifyInternalSignature(HttpServletRequest request, byte[] body) {
        return internalRequestSignatureVerifier.verify(
                request.getMethod(),
                request.getRequestURI(),
                request.getHeader("X-Internal-Timestamp"),
                request.getHeader("X-Internal-Nonce"),
                request.getHeader("X-Internal-Signature"),
                body
        );
    }

    private Optional<AuthUser> extractAuthUser(HttpServletRequest request) {
        Optional<AuthUser> cookieUser = extractSessionCookieToken(request)
                .flatMap(jwtTokenProvider::parseToken);
        if (cookieUser.isPresent()) {
            return cookieUser;
        }
        Optional<String> bearerToken = extractBearerToken(request);
        if (bearerToken.isPresent()) {
            return jwtTokenProvider.parseToken(bearerToken.get());
        }
        return Optional.empty();
    }

    private Optional<String> extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return Optional.of(authorization.substring("Bearer ".length()));
    }

    private boolean hasPresentedCredentials(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return true;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if ((AuthCookieSupport.USER_SESSION_COOKIE.equals(cookie.getName())
                    || AuthCookieSupport.ADMIN_SESSION_COOKIE.equals(cookie.getName()))
                    && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> extractSessionCookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return Optional.empty();
        }
        String preferredCookie = requiresAdmin(request.getRequestURI())
                ? AuthCookieSupport.ADMIN_SESSION_COOKIE
                : AuthCookieSupport.USER_SESSION_COOKIE;
        Optional<String> preferred = cookieValue(cookies, preferredCookie);
        if (preferred.isPresent()) {
            return preferred;
        }
        return cookieValue(cookies, AuthCookieSupport.ADMIN_SESSION_COOKIE);
    }

    private Optional<String> cookieValue(Cookie[] cookies, String name) {
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private void writeError(HttpServletRequest request,
                            HttpServletResponse response,
                            HttpStatus legacyStatus,
                            ErrorDefinition definition,
                            ErrorCode legacyErrorCode,
                            String userMessage,
                            String developerMessage) throws Exception {
        String contractUserMessage = responseFactory.isV2()
                ? definition.defaultUserMessage()
                : userMessage;
        var contractResponse = responseFactory.errorResponse(
                request,
                definition,
                contractUserMessage,
                developerMessage,
                legacyErrorCode,
                null,
                legacyStatus
        );
        response.setStatus(contractResponse.getStatusCode().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(contractResponse.getBody()));
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return inputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return inputStream.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}

