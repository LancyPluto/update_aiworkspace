package com.aiminilab.aitoolmarket.user.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.auth.security.AuthCookieSupport;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.auth.dto.SmsCodeResponse;
import com.aiminilab.aitoolmarket.auth.service.AuthService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.user.dto.CancelAccountRequest;
import com.aiminilab.aitoolmarket.user.dto.UpdateUserProfileRequest;
import com.aiminilab.aitoolmarket.user.dto.CommunitySettingsRequest;
import com.aiminilab.aitoolmarket.user.dto.UserAvatarUploadResponse;
import com.aiminilab.aitoolmarket.user.dto.UserProfileResponse;
import com.aiminilab.aitoolmarket.user.service.UserProfileService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final AuthService authService;
    private final UserProfileService userProfileService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthCookieSupport authCookieSupport;

    public UserController(AuthService authService,
                          UserProfileService userProfileService,
                          JwtTokenProvider jwtTokenProvider,
                          AuthCookieSupport authCookieSupport) {
        this.authService = authService;
        this.userProfileService = userProfileService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authCookieSupport = authCookieSupport;
    }

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> me() {
        return ApiResponse.success(authService.currentUser(AuthContext.get().userId()));
    }

    @PatchMapping("/me")
    public ApiResponse<UserProfileResponse> updateMe(@RequestBody UpdateUserProfileRequest request) {
        return ApiResponse.success(userProfileService.update(AuthContext.get().userId(), request));
    }

    @PostMapping("/me/avatar")
    public ApiResponse<UserAvatarUploadResponse> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(userProfileService.uploadAvatar(AuthContext.get().userId(), file));
    }

    @PatchMapping("/me/community-settings")
    public ApiResponse<UserProfileResponse> updateCommunitySettings(@RequestBody CommunitySettingsRequest request) {
        return ApiResponse.success(userProfileService.updateCommunitySettings(AuthContext.get().userId(), request));
    }

    @PostMapping("/me/cancel/sms-code")
    public ApiResponse<SmsCodeResponse> sendCancelAccountSmsCode() {
        return ApiResponse.success(userProfileService.sendCancelAccountSmsCode(AuthContext.get().userId()));
    }

    @PostMapping("/me/cancel")
    public ResponseEntity<ApiResponse<Void>> cancelAccount(@Valid @RequestBody CancelAccountRequest request,
                                                           HttpServletRequest servletRequest) {
        userProfileService.cancelAccount(AuthContext.get().userId(), request);
        extractToken(servletRequest).ifPresent(jwtTokenProvider::revokeToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookieSupport.deleteUserCookie().toString())
                .body(ApiResponse.success(null));
    }

    private java.util.Optional<String> extractToken(HttpServletRequest request) {
        java.util.Optional<String> bearerToken = extractBearerToken(request);
        if (bearerToken.isPresent()) {
            return bearerToken;
        }
        return extractCookieToken(request, AuthCookieSupport.USER_SESSION_COOKIE);
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
