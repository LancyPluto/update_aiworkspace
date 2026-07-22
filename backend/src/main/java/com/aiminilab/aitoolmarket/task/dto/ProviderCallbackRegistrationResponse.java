package com.aiminilab.aitoolmarket.task.dto;

import java.time.LocalDateTime;

public record ProviderCallbackRegistrationResponse(
        Long registrationId,
        Long taskId,
        String providerCode,
        String callbackUrl,
        LocalDateTime expiresAt
) {
}
