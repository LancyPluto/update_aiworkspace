package com.aiminilab.aitoolmarket.credit.alipay;

import java.util.Map;

public interface AlipayPagePayClient {
    AlipayPagePayResponse createPagePayOrder(AlipayPagePayRequest request);

    String buildPagePayHtml(AlipayPagePayRequest request);

    AlipayNotification parseNotification(Map<String, String> params);
}
