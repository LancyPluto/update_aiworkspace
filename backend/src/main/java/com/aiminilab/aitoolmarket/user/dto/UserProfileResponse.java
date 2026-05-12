package com.aiminilab.aitoolmarket.user.dto;

import com.aiminilab.aitoolmarket.user.entity.User;

public record UserProfileResponse(
        Long id,
        String username,
        String nickname,
        String userType,
        String status,
        String phone,
        String email
) {
    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getUserType(),
                user.getStatus(),
                user.getPhone(),
                user.getEmail()
        );
    }
}
