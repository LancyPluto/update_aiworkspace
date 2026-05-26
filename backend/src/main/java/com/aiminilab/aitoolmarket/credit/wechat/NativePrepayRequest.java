package com.aiminilab.aitoolmarket.credit.wechat;

import java.time.LocalDateTime;

public record NativePrepayRequest(
        String orderNo,
        String description,
        int totalAmountFen,
        String currency,
        LocalDateTime expiresAt
) {
}
