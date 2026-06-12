package com.aiminilab.aitoolmarket.credit.dto;

public record RechargePaymentOptionsResponse(
        boolean wechatNativeEnabled,
        boolean alipayEnabled,
        /** 固定 PAGE：电脑网站收银台 */
        String alipayPayMode
) {
}
