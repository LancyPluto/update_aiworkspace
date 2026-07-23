package com.aiminilab.aitoolmarket.community.dto;

public record PublicUserProfileResponse(
        String publicCode,
        String nickname,
        String avatarUrl,
        String bio,
        Long postCount,
        Long likeCount,
        Long favoriteCount
) {
}
