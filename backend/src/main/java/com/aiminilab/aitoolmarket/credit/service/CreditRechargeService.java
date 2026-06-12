package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.credit.dto.AlipayPayDiagnosticResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreateCustomRechargeOrderRequest;
import com.aiminilab.aitoolmarket.credit.dto.CreateRechargeOrderRequest;
import com.aiminilab.aitoolmarket.credit.dto.RechargeOrderResponse;
import com.aiminilab.aitoolmarket.credit.dto.RechargePackageResponse;
import com.aiminilab.aitoolmarket.credit.dto.RechargePaymentOptionsResponse;

import java.util.List;

public interface CreditRechargeService {
    List<RechargePackageResponse> packages();

    RechargePaymentOptionsResponse paymentOptions();

    RechargeOrderResponse createOrder(Long userId, CreateRechargeOrderRequest request);

    RechargeOrderResponse createCustomOrder(Long userId, CreateCustomRechargeOrderRequest request);

    RechargeOrderResponse getOrder(Long userId, Long orderId);

    AlipayPayDiagnosticResponse alipayDiagnostic(Long userId, Long orderId);

    void handleAlipayPagePaymentNotification(java.util.Map<String, String> params);

    void handleWechatNativePaymentNotification(
            String serial,
            String signature,
            String timestamp,
            String nonce,
            String body
    );
}
