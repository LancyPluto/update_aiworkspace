package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargeOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class CreditRechargeCreditDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CreditRechargeCreditDispatcher.class);

    private final CreditRechargeOrderMapper orderMapper;
    private final CreditRechargeCreditingService creditingService;

    public CreditRechargeCreditDispatcher(CreditRechargeOrderMapper orderMapper,
                                         CreditRechargeCreditingService creditingService) {
        this.orderMapper = orderMapper;
        this.creditingService = creditingService;
    }

    public void dispatchOrderNo(String orderNo) {
        try {
            creditingService.creditPaidOrderIfNeeded(orderNo, "async recharge credit dispatch");
        } catch (Exception exception) {
            log.warn("Async credit recharge dispatch failed, orderNo={}, message={}", orderNo, exception.getMessage(), exception);
        }
    }

    /**
     * Compensation loop: orders can reach PAID but not CREDITED if async crediting fails
     * (e.g. transient DB failures). This retries quickly to keep user balance fresh.
     */
    @Scheduled(fixedDelayString = "${app.recharge-credit.dispatch-interval-ms:5000}")
    public void dispatchPendingPaidOrders() {
        List<CreditRechargeOrder> orders = orderMapper.findPaidNotCredited(
                LocalDateTime.now().minusSeconds(10),
                50
        );
        for (CreditRechargeOrder order : orders) {
            dispatchOrderNo(order.getOrderNo());
        }
    }
}

