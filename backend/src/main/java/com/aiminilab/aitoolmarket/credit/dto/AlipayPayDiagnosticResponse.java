package com.aiminilab.aitoolmarket.credit.dto;

import java.util.Map;

public record AlipayPayDiagnosticResponse(
        String appId,
        String method,
        String outTradeNo,
        String productCode,
        String totalAmount,
        String notifyUrl,
        String returnUrl,
        String gatewayUrl,
        String bizContent,
        String permissionProbeMethod,
        String traceId,
        String errorCode,
        String subCode,
        String subMsg,
        String diagnosticToolUrl,
        Map<String, String> signedPagePayParams
) {
}
