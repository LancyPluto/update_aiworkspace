package com.aiminilab.aitoolmarket.community.dto;

public record PublicUserProfileResponse(
        Long id,
        String username,
        String nickname,
        String avatarUrl,
        String bio,
        Long postCount,
        Long likeCount,
        Long favoriteCount
) {
}
