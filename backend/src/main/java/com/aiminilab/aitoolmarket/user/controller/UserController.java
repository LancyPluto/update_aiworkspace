package com.aiminilab.aitoolmarket.user.controller;

import com.aiminilab.aitoolmarket.auth.security.AuthContext;
import com.aiminilab.aitoolmarket.auth.service.AuthService;
import com.aiminilab.aitoolmarket.common.dto.ApiResponse;
import com.aiminilab.aitoolmarket.user.dto.UpdateUserProfileRequest;
import com.aiminilab.aitoolmarket.user.dto.CommunitySettingsRequest;
import com.aiminilab.aitoolmarket.user.dto.UserAvatarUploadResponse;
import com.aiminilab.aitoolmarket.user.dto.UserProfileResponse;
import com.aiminilab.aitoolmarket.user.service.UserProfileService;
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

    public UserController(AuthService authService, UserProfileService userProfileService) {
        this.authService = authService;
        this.userProfileService = userProfileService;
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
}
