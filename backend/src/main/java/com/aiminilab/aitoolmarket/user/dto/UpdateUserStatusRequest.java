package com.aiminilab.aitoolmarket.user.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateUserStatusRequest(
        @NotBlank String status,
        String reason
) {
}
