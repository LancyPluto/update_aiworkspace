package com.aiminilab.aitoolmarket.credit.alipay;

import com.aiminilab.aitoolmarket.credit.dto.AlipayPayDiagnosticResponse;

import java.util.Map;

public interface AlipayPagePayClient {
    AlipayPagePayResponse createPagePayOrder(AlipayPagePayRequest request);

    String buildPagePayHtml(AlipayPagePayRequest request);

    AlipayPayDiagnosticResponse buildDiagnostic(AlipayPagePayRequest request);

    AlipayNotification parseNotification(Map<String, String> params);
}
