package com.aiminilab.aitoolmarket.credit.alipay;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AlipayPagePayRequest(
        String outTradeNo,
        String subject,
        BigDecimal totalAmount,
        LocalDateTime expiresAt
) {
}
