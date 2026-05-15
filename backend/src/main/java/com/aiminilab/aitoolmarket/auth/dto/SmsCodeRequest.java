package com.aiminilab.aitoolmarket.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record SmsCodeRequest(
        @NotBlank String phone,
        @NotBlank String scene
) {
}
