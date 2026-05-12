package com.aiminilab.aitoolmarket.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.aiminilab.aitoolmarket.user.dto.UserProfileResponse;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        String accessToken,
        UserProfileResponse user
) {
}
