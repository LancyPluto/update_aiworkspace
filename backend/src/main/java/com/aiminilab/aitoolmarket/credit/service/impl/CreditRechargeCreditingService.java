package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.common.enums.RechargeOrderStatus;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargeOrderMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Responsible for turning PAID recharge orders into CREDITED by granting credits.
 * Kept isolated so async dispatch does not create circular references.
 */
@Service
public class CreditRechargeCreditingService {

    private final CreditRechargeOrderMapper orderMapper;
    private final CreditService creditService;

    public CreditRechargeCreditingService(CreditRechargeOrderMapper orderMapper,
                                          CreditService creditService) {
        this.orderMapper = orderMapper;
        this.creditService = creditService;
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

        creditService.rechargeAdd(order.getUserId(), order.getId(), order.getCredits(),
                (reason == null || reason.isBlank() ? "Recharge order " + order.getOrderNo() : reason));

        // Optimistic transition (idempotent): if another thread already credited, transitOrCurrent logic handles it.
        orderMapper.transit(order.getId(), RechargeOrderStatus.PAID.name(), RechargeOrderStatus.CREDITED.name(),
                "credits granted", java.time.LocalDateTime.now());
    }
}

