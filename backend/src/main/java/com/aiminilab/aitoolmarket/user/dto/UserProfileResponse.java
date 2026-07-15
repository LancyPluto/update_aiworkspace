package com.aiminilab.aitoolmarket.user.dto;

import com.aiminilab.aitoolmarket.user.entity.User;
import com.aiminilab.aitoolmarket.credit.entity.UserMembership;

import java.time.LocalDateTime;

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
        String membershipPlan,
        String membershipStatus,
        LocalDateTime membershipStartedAt,
        LocalDateTime membershipExpiresAt,
        Long pendingMembershipOrderId
) {
    public static UserProfileResponse from(User user) {
        return from(user, null);
    }

    public static UserProfileResponse from(User user, UserMembership membership) {
        String membershipStatus = membership == null ? "NONE" : membership.getStatus();
        boolean active = "ACTIVE".equals(membershipStatus);
        boolean pending = "PENDING".equals(membershipStatus);
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
                active ? membership.getPackageCode() : null,
                membershipStatus,
                membership == null ? null : membership.getStartedAt(),
                membership == null ? null : membership.getExpiresAt(),
                pending ? membership.getOrderId() : null
        );
    }
}
