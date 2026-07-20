package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.common.enums.RechargeOrderStatus;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrderItem;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargeOrderItemMapper;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargeOrderMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.GiftCardService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Responsible for turning PAID recharge orders into CREDITED by granting credits.
 * Kept isolated so async dispatch does not create circular references.
 */
@Service
public class CreditRechargeCreditingService {

    private final CreditRechargeOrderMapper orderMapper;
    private final CreditRechargeOrderItemMapper orderItemMapper;
    private final CreditService creditService;
    private final GiftCardService giftCardService;
    private final MembershipService membershipService;

    public CreditRechargeCreditingService(CreditRechargeOrderMapper orderMapper,
                                          CreditRechargeOrderItemMapper orderItemMapper,
                                          CreditService creditService,
                                          GiftCardService giftCardService,
                                          MembershipService membershipService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.creditService = creditService;
        this.giftCardService = giftCardService;
        this.membershipService = membershipService;
    }

    @Transactional
    public void creditPaidOrderIfNeeded(String orderNo, String reason) {
        CreditRechargeOrder order = orderMapper.findByOrderNo(orderNo);
        if (order == null) {
            return;
        }
        if (RechargeOrderStatus.CREDITED.name().equals(order.getStatus())) {
            return;
        }
        if (!RechargeOrderStatus.PAID.name().equals(order.getStatus())) {
            return;
        }

        if ("GIFT_CARD".equals(order.getOrderType())) {
            java.util.List<CreditRechargeOrderItem> items = orderItemMapper.findByOrderId(order.getId());
            if (items.isEmpty()) {
                giftCardService.createGiftCardFromOrder(order.getUserId(), order.getId(),
                        order.getGiftCardPackageId(), order.getCredits());
            } else {
                giftCardService.createGiftCardsFromOrderItems(order.getUserId(), order.getId(), items);
            }
        } else {
            if ("MEMBERSHIP".equals(order.getOrderType())) {
                creditService.membershipRechargeAdd(order.getUserId(), order.getId(), order.getCredits(),
                        (reason == null || reason.isBlank() ? "Membership order " + order.getOrderNo() : reason));
                membershipService.activate(order);
            } else {
                creditService.rechargeAdd(order.getUserId(), order.getId(), order.getCredits(),
                        (reason == null || reason.isBlank() ? "Recharge order " + order.getOrderNo() : reason));
            }
        }

        // Optimistic transition (idempotent): if another thread already credited, transitOrCurrent logic handles it.
        orderMapper.transit(order.getId(), RechargeOrderStatus.PAID.name(), RechargeOrderStatus.CREDITED.name(),
                "credits granted", java.time.LocalDateTime.now());
    }
}

