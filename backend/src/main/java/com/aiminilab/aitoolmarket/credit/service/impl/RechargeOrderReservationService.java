package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrderItem;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargeOrderMapper;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargeOrderItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RechargeOrderReservationService {
    private final CreditRechargeOrderMapper orderMapper;
    private final CreditRechargeOrderItemMapper orderItemMapper;

    public RechargeOrderReservationService(CreditRechargeOrderMapper orderMapper,
                                           CreditRechargeOrderItemMapper orderItemMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
    }

    @Transactional
    public void insertOrder(CreditRechargeOrder order) {
        orderMapper.insert(order);
    }

    @Transactional
    public void insertGiftCardOrder(CreditRechargeOrder order, java.util.List<CreditRechargeOrderItem> items) {
        orderMapper.insert(order);
        for (CreditRechargeOrderItem item : items) {
            item.setOrderId(order.getId());
            orderItemMapper.insert(item);
        }
    }
}
