package com.aiminilab.aitoolmarket.credit.alipay;

import java.util.Map;

public interface AlipayPagePayClient {
    /**
     * Create a QR-code based order (alipay.trade.precreate).
     * The returned string is the qr_code value that should be encoded as QR.
     */
    AlipayPagePayResponse createPagePayOrder(AlipayPagePayRequest request);

    AlipayNotification parseNotification(Map<String, String> params);
}
