package com.aiminilab.aitoolmarket.user.dto;

import com.aiminilab.aitoolmarket.user.entity.User;

public record UserProfileResponse(
        Long id,
        String username,
        String nickname,
        String avatarUrl,
        String bio,
        Boolean autoPublishAssets,
        Boolean promptPublicByDefault,
        String userType,
        String status,
        String phone,
        String email,
        String membershipPlan
) {
    public static UserProfileResponse from(User user) {
        return from(user, null);
    }

    public static UserProfileResponse from(User user, String membershipPlan) {
        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getBio(),
                user.getAutoPublishAssets(),
                user.getPromptPublicByDefault(),
                user.getUserType(),
                user.getStatus(),
                user.getPhone(),
                user.getEmail(),
                membershipPlan
        );
    }
}
