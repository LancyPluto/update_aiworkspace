package com.aiminilab.aitoolmarket.user.dto;

import com.aiminilab.aitoolmarket.credit.dto.CreditAccountResponse;
import com.aiminilab.aitoolmarket.user.entity.User;

import java.time.LocalDateTime;

public record AdminUserResponse(
        Long id,
        String publicCode,
        String username,
        String phone,
        String email,
        String nickname,
        String avatarUrl,
        String userType,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        CreditAccountResponse creditAccount
) {
    public static AdminUserResponse of(User user, CreditAccountResponse creditAccount) {
        return new AdminUserResponse(
                user.getId(),
                user.getPublicCode(),
                user.getUsername(),
                user.getPhone(),
                user.getEmail(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getUserType(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                creditAccount
        );
    }
}
