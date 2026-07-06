package com.aiminilab.aitoolmarket.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record SmsAuthRequest(
        @NotBlank String phone,
        @NotBlank String code,
        String nickname,
        String password,
        String inviteCode
) {
}
