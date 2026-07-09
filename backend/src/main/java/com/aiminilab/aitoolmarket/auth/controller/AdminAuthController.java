package com.aiminilab.aitoolmarket.auth.controller;

import com.aiminilab.aitoolmarket.auth.dto.AuthenticatedSession;
import com.aiminilab.aitoolmarket.auth.dto.LoginRequest;
import com.aiminilab.aitoolmarket.auth.dto.LoginResponse;
import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.auth.security.AuthCookieSupport;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.auth.service.AuthService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.user.dto.UserProfileResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/v1/auth")
public class AdminAuthController {

    private final AuthService authService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthCookieSupport authCookieSupport;

    public AdminAuthController(AuthService authService, JwtTokenProvider jwtTokenProvider, AuthCookieSupport authCookieSupport) {
        this.authService = authService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authCookieSupport = authCookieSupport;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthenticatedSession session = authService.login(request, true);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieSupport.adminSessionCookie(session.jwt()).toString())
                .body(ApiResponse.success(session.body()));
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> me() {
        return ApiResponse.success(authService.currentUser(AuthContext.get().userId()));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        extractTokens(request).forEach(jwtTokenProvider::revokeToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieSupport.deleteAdminCookie().toString())
                .body(ApiResponse.success(null));
    }

    private java.util.Set<String> extractTokens(HttpServletRequest request) {
        java.util.Set<String> tokens = new java.util.LinkedHashSet<>();
        extractCookieToken(request, AuthCookieSupport.ADMIN_SESSION_COOKIE).ifPresent(tokens::add);
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
}
