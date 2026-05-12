package com.aiminilab.aitoolmarket.auth.controller;

import com.aiminilab.aitoolmarket.auth.dto.AuthenticatedSession;
import com.aiminilab.aitoolmarket.auth.dto.LoginRequest;
import com.aiminilab.aitoolmarket.auth.dto.LoginResponse;
import com.aiminilab.aitoolmarket.auth.dto.RegisterRequest;
import com.aiminilab.aitoolmarket.auth.dto.SmsAuthRequest;
import com.aiminilab.aitoolmarket.auth.dto.SmsCodeRequest;
import com.aiminilab.aitoolmarket.auth.dto.SmsCodeResponse;
import com.aiminilab.aitoolmarket.auth.security.AuthCookieSupport;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.auth.service.AuthService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
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

    public AuthController(AuthService authService, JwtTokenProvider jwtTokenProvider, AuthCookieSupport authCookieSupport) {
        this.authService = authService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authCookieSupport = authCookieSupport;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<LoginResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthenticatedSession session = authService.register(request);
        return authenticated(session);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthenticatedSession session = authService.login(request, false);
        return authenticated(session);
    }

    @PostMapping("/sms-code")
    public ApiResponse<SmsCodeResponse> sendSmsCode(@Valid @RequestBody SmsCodeRequest request) {
        return ApiResponse.success(authService.sendSmsCode(request.phone(), request.scene()));
    }

    @PostMapping("/sms-register")
    public ResponseEntity<ApiResponse<LoginResponse>> smsRegister(@Valid @RequestBody SmsAuthRequest request) {
        AuthenticatedSession session = authService.registerWithSmsCode(request);
        return authenticated(session);
    }

    @PostMapping("/sms-login")
    public ResponseEntity<ApiResponse<LoginResponse>> smsLogin(@Valid @RequestBody SmsAuthRequest request) {
        AuthenticatedSession session = authService.loginWithSmsCode(request);
        return authenticated(session);
    }

    private ResponseEntity<ApiResponse<LoginResponse>> authenticated(AuthenticatedSession session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieSupport.userSessionCookie(session.jwt()).toString())
                .body(ApiResponse.success(session.body()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        extractBearerToken(request).ifPresent(jwtTokenProvider::revokeToken);
        return ApiResponse.success(null);
    }

    private java.util.Optional<String> extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(authorization.substring("Bearer ".length()));
    }
}
