package com.aiminilab.aitoolmarket.auth.controller;

import com.aiminilab.aitoolmarket.auth.dto.AuthenticatedSession;
import com.aiminilab.aitoolmarket.auth.dto.LoginRequest;
import com.aiminilab.aitoolmarket.auth.dto.LoginResponse;
import com.aiminilab.aitoolmarket.auth.dto.RegisterRequest;
import com.aiminilab.aitoolmarket.auth.dto.ResetPasswordRequest;
import com.aiminilab.aitoolmarket.auth.dto.SmsAuthRequest;
import com.aiminilab.aitoolmarket.auth.dto.SmsCodeRequest;
import com.aiminilab.aitoolmarket.auth.dto.SmsCodeResponse;
import com.aiminilab.aitoolmarket.auth.security.AuthCookieSupport;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.auth.service.AuthService;
import com.aiminilab.aitoolmarket.auth.service.AuthSecurityAuditService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.common.enums.UserType;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthCookieSupport authCookieSupport;
    private final AuthSecurityAuditService authSecurityAuditService;

    public AuthController(AuthService authService,
                          JwtTokenProvider jwtTokenProvider,
                          AuthCookieSupport authCookieSupport,
                          AuthSecurityAuditService authSecurityAuditService) {
        this.authService = authService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authCookieSupport = authCookieSupport;
        this.authSecurityAuditService = authSecurityAuditService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<LoginResponse>> register(@Valid @RequestBody RegisterRequest request,
                                                               HttpServletRequest servletRequest) {
        try {
            AuthenticatedSession session = authService.register(request);
            audit(servletRequest, "REGISTER_PASSWORD", "SUCCESS", "password", UserType.USER.name(),
                    session.body().user().id(), firstNonBlank(request.phone(), request.username()), null);
            return authenticated(session);
        } catch (RuntimeException exception) {
            audit(servletRequest, "REGISTER_PASSWORD", "FAILED", "password", UserType.USER.name(),
                    null, firstNonBlank(request.phone(), request.username()), reason(exception));
            throw exception;
        }
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
                                                            HttpServletRequest servletRequest) {
        try {
            AuthenticatedSession session = authService.login(request, false);
            audit(servletRequest, "LOGIN_PASSWORD", "SUCCESS", "password", UserType.USER.name(),
                    session.body().user().id(), request.account(), null);
            return authenticated(session);
        } catch (RuntimeException exception) {
            audit(servletRequest, "LOGIN_PASSWORD", "FAILED", "password", UserType.USER.name(),
                    null, request.account(), reason(exception));
            throw exception;
        }
    }

    @PostMapping("/sms-code")
    public ApiResponse<SmsCodeResponse> sendSmsCode(@Valid @RequestBody SmsCodeRequest request,
                                                    HttpServletRequest servletRequest) {
        try {
            SmsCodeResponse response = authService.sendSmsCode(request.phone(), request.scene(), request.captchaVerifyParam());
            audit(servletRequest, "SEND_SMS_CODE", "SUCCESS", "sms", UserType.USER.name(),
                    null, request.phone(), request.scene());
            return ApiResponse.success(response);
        } catch (RuntimeException exception) {
            audit(servletRequest, "SEND_SMS_CODE", "FAILED", "sms", UserType.USER.name(),
                    null, request.phone(), reason(exception));
            throw exception;
        }
    }

    @PostMapping("/sms-register")
    public ResponseEntity<ApiResponse<LoginResponse>> smsRegister(@Valid @RequestBody SmsAuthRequest request,
                                                                  HttpServletRequest servletRequest) {
        try {
            AuthenticatedSession session = authService.registerWithSmsCode(request);
            audit(servletRequest, "REGISTER_SMS", "SUCCESS", "sms", UserType.USER.name(),
                    session.body().user().id(), request.phone(), null);
            return authenticated(session);
        } catch (RuntimeException exception) {
            audit(servletRequest, "REGISTER_SMS", "FAILED", "sms", UserType.USER.name(),
                    null, request.phone(), reason(exception));
            throw exception;
        }
    }

    @PostMapping("/sms-login")
    public ResponseEntity<ApiResponse<LoginResponse>> smsLogin(@Valid @RequestBody SmsAuthRequest request,
                                                               HttpServletRequest servletRequest) {
        try {
            AuthenticatedSession session = authService.loginWithSmsCode(request);
            audit(servletRequest, "LOGIN_SMS", "SUCCESS", "sms", UserType.USER.name(),
                    session.body().user().id(), request.phone(), null);
            return authenticated(session);
        } catch (RuntimeException exception) {
            audit(servletRequest, "LOGIN_SMS", "FAILED", "sms", UserType.USER.name(),
                    null, request.phone(), reason(exception));
            throw exception;
        }
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                           HttpServletRequest servletRequest) {
        try {
            authService.resetPasswordWithSmsCode(request);
            audit(servletRequest, "RESET_PASSWORD", "SUCCESS", "sms", UserType.USER.name(),
                    null, request.phone(), null);
            return ApiResponse.success(null);
        } catch (RuntimeException exception) {
            audit(servletRequest, "RESET_PASSWORD", "FAILED", "sms", UserType.USER.name(),
                    null, request.phone(), reason(exception));
            throw exception;
        }
    }

    private ResponseEntity<ApiResponse<LoginResponse>> authenticated(AuthenticatedSession session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieSupport.userSessionCookie(session.jwt()).toString())
                .body(ApiResponse.success(session.body()));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        extractTokens(request).forEach(jwtTokenProvider::revokeToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieSupport.deleteUserCookie().toString())
                .body(ApiResponse.success(null));
    }

    private java.util.Set<String> extractTokens(HttpServletRequest request) {
        java.util.Set<String> tokens = new java.util.LinkedHashSet<>();
        extractCookieToken(request, AuthCookieSupport.USER_SESSION_COOKIE).ifPresent(tokens::add);
        extractBearerToken(request).ifPresent(tokens::add);
        return tokens;
    }

    private java.util.Optional<String> extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(authorization.substring("Bearer ".length()));
    }

    private java.util.Optional<String> extractCookieToken(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return java.util.Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return java.util.Optional.of(cookie.getValue());
            }
        }
        return java.util.Optional.empty();
    }

    private void audit(HttpServletRequest request,
                       String eventType,
                       String result,
                       String method,
                       String userType,
                       Long userId,
                       String account,
                       String reason) {
        authSecurityAuditService.record(request, eventType, result, method, userType, userId, account, reason);
    }

    private String reason(RuntimeException exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.getErrorCode().name();
        }
        return exception.getClass().getSimpleName();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
