package com.aiminilab.aitoolmarket.user.dto;

import java.time.LocalDateTime;

public record AdminUserSummaryResponse(
        Long id,
        String username,
        String nickname,
        String userType,
        String status,
        Integer credits,
        LocalDateTime createdAt
) {
}
