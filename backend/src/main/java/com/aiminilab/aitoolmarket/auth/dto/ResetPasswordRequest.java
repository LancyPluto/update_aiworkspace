package com.aiminilab.aitoolmarket.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank String phone,
        @NotBlank String code,
        @NotBlank @Size(min = 6, max = 64) String password
) {
}
