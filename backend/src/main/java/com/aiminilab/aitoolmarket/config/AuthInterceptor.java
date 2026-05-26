package com.aiminilab.aitoolmarket.config;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.auth.security.AuthCookieSupport;
import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.auth.security.InternalRequestSignatureVerifier;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.UserType;
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

    public AuthInterceptor(JwtTokenProvider jwtTokenProvider,
                           ObjectMapper objectMapper,
                           InternalRequestSignatureVerifier internalRequestSignatureVerifier) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
        this.internalRequestSignatureVerifier = internalRequestSignatureVerifier;
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
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublicPath(path)) {
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

        if (path.startsWith("/api/admin/v1/")
                && !UserType.ADMIN.name().equals(authUser.get().userType())) {
            writeError(response, HttpStatus.FORBIDDEN, ErrorCode.ADMIN_FORBIDDEN, "管理员无权限");
            return false;
        }

        AuthContext.set(authUser.get());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }

    private boolean isPublicPath(String path) {
        return path.equals("/api/health")
                || path.equals("/api/v1/ping")
                || path.equals("/api/admin/v1/ping")
                || path.equals("/actuator/health")
                || path.equals("/actuator/info")
                || path.equals("/actuator/prometheus")
                || path.startsWith("/actuator/metrics")
                || path.startsWith("/api/v1/auth/")
                || path.equals("/api/v1/settings/customer-service")
                || path.equals("/api/v1/tool-categories")
                || path.equals("/api/v1/tools")
                || path.startsWith("/api/v1/tools/")
                || path.equals("/api/admin/v1/auth/login");
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
