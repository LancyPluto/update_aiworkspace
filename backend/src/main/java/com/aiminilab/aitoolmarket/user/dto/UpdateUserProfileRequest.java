package com.aiminilab.aitoolmarket.user.dto;

public record UpdateUserProfileRequest(
        String nickname,
        String avatarUrl
) {
}
