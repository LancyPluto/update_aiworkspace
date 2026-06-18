package com.aiminilab.aitoolmarket.config;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.auth.security.AuthCookieSupport;
import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.auth.security.InternalRequestSignatureVerifier;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.UserStatus;
import com.aiminilab.aitoolmarket.common.enums.UserType;
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

    private static final String INTERNAL_SIGNATURE_VERIFIED_ATTRIBUTE =
            AuthInterceptor.class.getName() + ".internalSignatureVerified";

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    private final InternalRequestSignatureVerifier internalRequestSignatureVerifier;
    private final UserMapper userMapper;

    public AuthInterceptor(JwtTokenProvider jwtTokenProvider,
                           ObjectMapper objectMapper,
                           InternalRequestSignatureVerifier internalRequestSignatureVerifier,
                           UserMapper userMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
        this.internalRequestSignatureVerifier = internalRequestSignatureVerifier;
        this.userMapper = userMapper;
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

        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        CachedBodyRequest wrappedRequest = new CachedBodyRequest(request, body);
        if (!verifyInternalSignature(wrappedRequest, body)) {
            try {
                writeError(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "内部接口签名无效");
            } catch (Exception exception) {
                throw new ServletException(exception);
            }
            return;
        }

        wrappedRequest.setAttribute(INTERNAL_SIGNATURE_VERIFIED_ATTRIBUTE, Boolean.TRUE);
        filterChain.doFilter(wrappedRequest, response);
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
            if (Boolean.TRUE.equals(request.getAttribute(INTERNAL_SIGNATURE_VERIFIED_ATTRIBUTE))) {
                return true;
            }
            writeError(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "内部接口签名无效");
            return false;
        }

        Optional<AuthUser> authUser = extractAuthUser(request);
        if (authUser.isEmpty()) {
            writeError(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "未登录或 Token 失效");
            return false;
        }

        if (!isActiveUser(authUser.get())) {
            writeError(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "账号已注销或被禁用");
            return false;
        }

        if (requiresAdmin(path)
                && !UserType.ADMIN.name().equals(authUser.get().userType())) {
            writeError(response, HttpStatus.FORBIDDEN, ErrorCode.ADMIN_FORBIDDEN, "管理员无权限");
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
                || ("GET".equalsIgnoreCase(method) && path.matches("/api/v1/community/posts/\\d+"))
                || ("POST".equalsIgnoreCase(method) && path.equals("/api/v1/community/events"))
                || path.startsWith("/api/v1/assets/private/")
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
        Optional<String> bearerToken = extractBearerToken(request);
        if (bearerToken.isPresent()) {
            return jwtTokenProvider.parseToken(bearerToken.get());
        }
        return extractSessionCookieToken(request)
                .flatMap(jwtTokenProvider::parseToken);
    }

    private Optional<String> extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return Optional.of(authorization.substring("Bearer ".length()));
    }

    private Optional<String> extractSessionCookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return Optional.empty();
        }
        String preferredCookie = request.getRequestURI().startsWith("/api/admin/v1/")
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

    private void writeError(HttpServletResponse response, HttpStatus status, ErrorCode errorCode, String message) throws Exception {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(errorCode, message)));
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
