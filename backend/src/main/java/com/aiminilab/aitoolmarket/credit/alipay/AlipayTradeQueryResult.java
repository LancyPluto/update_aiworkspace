package com.aiminilab.aitoolmarket.credit.alipay;

import java.math.BigDecimal;

public record AlipayTradeQueryResult(
        String outTradeNo,
        String tradeNo,
        String tradeStatus,
        BigDecimal totalAmount
) {
}
