package com.aiminilab.aitoolmarket.auth.controller;

import com.aiminilab.aitoolmarket.auth.dto.AuthenticatedSession;
import com.aiminilab.aitoolmarket.auth.dto.LoginRequest;
import com.aiminilab.aitoolmarket.auth.dto.LoginResponse;
import com.aiminilab.aitoolmarket.auth.dto.RegisterRequest;
<<<<<<< Updated upstream
import com.aiminilab.aitoolmarket.auth.service.AuthService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
=======
import com.aiminilab.aitoolmarket.auth.security.AuthCookieSupport;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.auth.service.AuthService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
>>>>>>> Stashed changes
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
<<<<<<< Updated upstream

    public AuthController(AuthService authService) {
        this.authService = authService;
=======
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthCookieSupport authCookieSupport;

    public AuthController(AuthService authService,
                          JwtTokenProvider jwtTokenProvider,
                          AuthCookieSupport authCookieSupport) {
        this.authService = authService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authCookieSupport = authCookieSupport;
>>>>>>> Stashed changes
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<LoginResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthenticatedSession session = authService.register(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieSupport.userSessionCookie(session.jwt()).toString())
                .body(ApiResponse.success(session.body()));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthenticatedSession session = authService.login(request, false);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieSupport.userSessionCookie(session.jwt()).toString())
                .body(ApiResponse.success(session.body()));
    }

    @PostMapping("/logout")
<<<<<<< Updated upstream
    public ApiResponse<Void> logout() {
        return ApiResponse.success(null);
    }
=======
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        resolveUserToken(request).ifPresent(jwtTokenProvider::revokeToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieSupport.deleteUserCookie().toString())
                .body(ApiResponse.success(null));
    }

    private java.util.Optional<String> resolveUserToken(HttpServletRequest request) {
        java.util.Optional<String> bearer = extractBearerToken(request);
        if (bearer.isPresent()) {
            return bearer;
        }
        return readCookie(request, AuthCookieSupport.USER_SESSION_COOKIE);
    }

    private java.util.Optional<String> extractBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(authorization.substring("Bearer ".length()));
    }

    private java.util.Optional<String> readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return java.util.Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return java.util.Optional.of(value);
                }
            }
        }
        return java.util.Optional.empty();
    }
>>>>>>> Stashed changes
}
