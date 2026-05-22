package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.RechargeOrderStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.dto.CreateRechargeOrderRequest;
import com.aiminilab.aitoolmarket.credit.dto.RechargeOrderResponse;
import com.aiminilab.aitoolmarket.credit.dto.RechargePackageResponse;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargePackage;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargeOrderMapper;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargePackageMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditRechargeService;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
public class CreditRechargeServiceImpl implements CreditRechargeService {
    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int ORDER_EXPIRE_MINUTES = 30;

    private final CreditRechargePackageMapper packageMapper;
    private final CreditRechargeOrderMapper orderMapper;
    private final CreditService creditService;
    private final ObjectMapper objectMapper;

    public CreditRechargeServiceImpl(CreditRechargePackageMapper packageMapper,
                                     CreditRechargeOrderMapper orderMapper,
                                     CreditService creditService,
                                     ObjectMapper objectMapper) {
        this.packageMapper = packageMapper;
        this.orderMapper = orderMapper;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RechargePackageResponse> packages() {
        return packageMapper.findActive().stream()
                .map(item -> RechargePackageResponse.from(item, objectMapper))
                .toList();
    }

    @Override
    @Transactional
    public RechargeOrderResponse createOrder(Long userId, CreateRechargeOrderRequest request) {
        String idempotencyKey = normalizeIdempotencyKey(request.clientRequestId());
        if (idempotencyKey != null) {
            CreditRechargeOrder existing = orderMapper.findByUserAndIdempotencyKey(userId, idempotencyKey);
            if (existing != null) {
                return RechargeOrderResponse.from(existing);
            }
        }

        CreditRechargePackage rechargePackage = activePackageOrThrow(request.packageId());
        LocalDateTime now = LocalDateTime.now();
        CreditRechargeOrder order = new CreditRechargeOrder();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setPackageId(rechargePackage.getId());
        order.setCredits(rechargePackage.getCredits());
        order.setPriceAmount(rechargePackage.getPriceAmount());
        order.setCurrency(rechargePackage.getCurrency());
        order.setPaymentChannel(normalizePaymentChannel(request.paymentChannel()));
        order.setStatus(RechargeOrderStatus.WAITING_PAYMENT.name());
        order.setStatusReason("waiting for payment");
        order.setPayUrl("/mock-pay/recharge/" + order.getOrderNo());
        order.setQrCodeUrl(null);
        order.setIdempotencyKey(idempotencyKey);
        order.setExpiresAt(now.plusMinutes(ORDER_EXPIRE_MINUTES));
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orderMapper.insert(order);
        return RechargeOrderResponse.from(order);
    }

    @Override
    public RechargeOrderResponse getOrder(Long userId, Long orderId) {
        return RechargeOrderResponse.from(orderOrThrow(userId, orderId));
    }

    @Override
    @Transactional
    public RechargeOrderResponse mockPaySuccess(Long userId, Long orderId) {
        CreditRechargeOrder order = orderOrThrow(userId, orderId);
        if (RechargeOrderStatus.CREDITED.name().equals(order.getStatus())) {
            return RechargeOrderResponse.from(order);
        }
        if (RechargeOrderStatus.WAITING_PAYMENT.name().equals(order.getStatus()) && order.getExpiresAt() != null
                && order.getExpiresAt().isBefore(LocalDateTime.now())) {
            transitOrThrow(order, RechargeOrderStatus.CLOSED, "order expired");
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order expired");
        }
        if (RechargeOrderStatus.WAITING_PAYMENT.name().equals(order.getStatus())) {
            transitOrThrow(order, RechargeOrderStatus.PAID, "mock payment confirmed");
            order = orderOrThrow(userId, orderId);
        }
        if (!RechargeOrderStatus.PAID.name().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order cannot be paid from status " + order.getStatus());
        }
        creditService.rechargeAdd(order.getUserId(), order.getId(), order.getCredits(), "Recharge order " + order.getOrderNo());
        transitOrThrow(order, RechargeOrderStatus.CREDITED, "credits granted");
        return RechargeOrderResponse.from(orderOrThrow(userId, orderId));
    }

    private CreditRechargePackage activePackageOrThrow(Long packageId) {
        CreditRechargePackage rechargePackage = packageMapper.selectOne(new LambdaQueryWrapper<CreditRechargePackage>()
                .eq(CreditRechargePackage::getId, packageId)
                .eq(CreditRechargePackage::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (rechargePackage == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge package not found");
        }
        return rechargePackage;
    }

    private CreditRechargeOrder orderOrThrow(Long userId, Long orderId) {
        CreditRechargeOrder order = orderMapper.findByIdAndUserId(orderId, userId);
        if (order == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order not found");
        }
        return order;
    }

    private void transitOrThrow(CreditRechargeOrder order, RechargeOrderStatus toStatus, String reason) {
        RechargeOrderStatus fromStatus = RechargeOrderStatus.valueOf(order.getStatus());
        if (!fromStatus.canTransitTo(toStatus)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "invalid recharge order transition");
        }
        int updated = orderMapper.transit(order.getId(), fromStatus.name(), toStatus.name(), reason, LocalDateTime.now());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order status changed, please retry");
        }
    }

    private String normalizePaymentChannel(String paymentChannel) {
        if (paymentChannel == null || paymentChannel.isBlank()) {
            return "MOCK";
        }
        return paymentChannel.trim().toUpperCase();
    }

    private String normalizeIdempotencyKey(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        return clientRequestId.trim();
    }

    private String generateOrderNo() {
        String date = ORDER_DATE.format(LocalDate.now());
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return "R" + date + suffix;
    }
}
