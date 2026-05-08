package com.aiminilab.aitoolmarket.auth.dto;

import com.aiminilab.aitoolmarket.user.dto.UserProfileResponse;

public record LoginResponse(
        String accessToken,
        UserProfileResponse user
) {
}
