package com.aiminilab.aitoolmarket.credit.dto;

import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RechargeOrderResponse(
        Long id,
        String orderNo,
        Long packageId,
        Integer credits,
        BigDecimal priceAmount,
        String currency,
        String paymentChannel,
        String status,
        String statusReason,
        String payUrl,
        String qrCodeUrl,
        LocalDateTime paidAt,
        LocalDateTime creditedAt,
        LocalDateTime closedAt,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        String orderType,
        Long giftCardPackageId
) {
    public static RechargeOrderResponse from(CreditRechargeOrder order) {
        return new RechargeOrderResponse(
                order.getId(),
                order.getOrderNo(),
                order.getPackageId(),
                order.getCredits(),
                order.getPriceAmount(),
                order.getCurrency(),
                order.getPaymentChannel(),
                order.getStatus(),
                order.getStatusReason(),
                order.getPayUrl(),
                order.getQrCodeUrl(),
                order.getPaidAt(),
                order.getCreditedAt(),
                order.getClosedAt(),
                order.getExpiresAt(),
                order.getCreatedAt(),
                order.getOrderType(),
                order.getGiftCardPackageId()
        );
    }
}
