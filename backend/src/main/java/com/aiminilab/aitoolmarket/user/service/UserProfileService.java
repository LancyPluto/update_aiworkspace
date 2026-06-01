package com.aiminilab.aitoolmarket.user.service;

import com.aiminilab.aitoolmarket.user.dto.UpdateUserProfileRequest;
import com.aiminilab.aitoolmarket.auth.dto.SmsCodeResponse;
import com.aiminilab.aitoolmarket.user.dto.CancelAccountRequest;
import com.aiminilab.aitoolmarket.user.dto.CommunitySettingsRequest;
import com.aiminilab.aitoolmarket.user.dto.UserAvatarUploadResponse;
import com.aiminilab.aitoolmarket.user.dto.UserProfileResponse;
import org.springframework.web.multipart.MultipartFile;

public interface UserProfileService {

    UserProfileResponse update(Long userId, UpdateUserProfileRequest request);

    UserAvatarUploadResponse uploadAvatar(Long userId, MultipartFile file);

    UserProfileResponse updateCommunitySettings(Long userId, CommunitySettingsRequest request);

    SmsCodeResponse sendCancelAccountSmsCode(Long userId);

    void cancelAccount(Long userId, CancelAccountRequest request);
}
