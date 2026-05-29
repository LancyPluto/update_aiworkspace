package com.aiminilab.aitoolmarket.user.dto;

public record UserAvatarUploadResponse(
        String avatarUrl,
        UserProfileResponse user
) {
}
