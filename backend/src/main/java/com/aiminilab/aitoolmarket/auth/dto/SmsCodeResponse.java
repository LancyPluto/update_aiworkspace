package com.aiminilab.aitoolmarket.auth.dto;

public record SmsCodeResponse(
        int expiresInSeconds,
        int cooldownSeconds,
        String debugCode
) {
}
