package com.aiminilab.aitoolmarket.credit.alipay;

import java.math.BigDecimal;

public record AlipayNotification(
        String appId,
        String outTradeNo,
        String tradeNo,
        String tradeStatus,
        BigDecimal totalAmount
) {
}
