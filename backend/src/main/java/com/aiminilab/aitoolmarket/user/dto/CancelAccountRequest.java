package com.aiminilab.aitoolmarket.user.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelAccountRequest(
        @NotBlank String smsCode
) {
}
