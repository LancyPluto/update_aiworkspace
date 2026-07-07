package com.aiminilab.aitoolmarket.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        String username,
        @NotBlank @Size(min = 6, max = 64) String password,
        String phone,
        String nickname,
        String inviteCode
) {
}
