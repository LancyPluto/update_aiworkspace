package com.aiminilab.aitoolmarket.config;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.UserType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    private final String internalApiToken;

    public AuthInterceptor(JwtTokenProvider jwtTokenProvider,
                           ObjectMapper objectMapper,
                           @Value("${app.internal-api-token}") String internalApiToken) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;
        this.internalApiToken = internalApiToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isPublicPath(path)) {
            return true;
        }

        if (path.startsWith("/api/internal/v1/")) {
            return validateInternalToken(request, response);
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
                || path.startsWith("/api/v1/auth/")
                || path.equals("/api/v1/tool-categories")
                || path.equals("/api/v1/tools")
                || path.startsWith("/api/v1/tools/")
                || path.equals("/api/admin/v1/auth/login");
    }

    private boolean validateInternalToken(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String token = request.getHeader("X-Internal-Token");
        if (!internalApiToken.equals(token)) {
            writeError(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "内部接口 Token 无效");
            return false;
        }
        return true;
    }

    private Optional<AuthUser> extractAuthUser(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return jwtTokenProvider.parseToken(authorization.substring("Bearer ".length()));
    }

    private void writeError(HttpServletResponse response, HttpStatus status, ErrorCode errorCode, String message) throws Exception {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(errorCode, message)));
    }
}
