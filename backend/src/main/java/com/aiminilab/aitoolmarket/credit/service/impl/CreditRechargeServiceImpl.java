package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.RechargeOrderStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayNotification;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayClient;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayRequest;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreateCustomRechargeOrderRequest;
import com.aiminilab.aitoolmarket.credit.dto.CreateRechargeOrderRequest;
import com.aiminilab.aitoolmarket.credit.dto.RechargeOrderResponse;
import com.aiminilab.aitoolmarket.credit.dto.RechargePackageResponse;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargePackage;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargeOrderMapper;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargePackageMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditRechargeService;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.wechat.NativePrepayRequest;
import com.aiminilab.aitoolmarket.credit.wechat.NativePrepayResponse;
import com.aiminilab.aitoolmarket.credit.wechat.QrCodeDataUriGenerator;
import com.aiminilab.aitoolmarket.credit.wechat.WechatNativePayClient;
import com.aiminilab.aitoolmarket.credit.wechat.WechatPayCallbackHeaders;
import com.aiminilab.aitoolmarket.credit.wechat.WechatPayNotification;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CreditRechargeServiceImpl implements CreditRechargeService {
    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int ORDER_EXPIRE_MINUTES = 30;
    private static final int WECHAT_QUERY_THROTTLE_SECONDS = 10;

    private final CreditRechargePackageMapper packageMapper;
    private final CreditRechargeOrderMapper orderMapper;
    private final CreditService creditService;
    private final ObjectMapper objectMapper;
    private final WechatNativePayClient wechatNativePayClient;
    private final AlipayPagePayClient alipayPagePayClient;
    private final QrCodeDataUriGenerator qrCodeDataUriGenerator;
    private final AppProperties.WechatNative wechatProperties;
    private final AppProperties.AlipayPage alipayProperties;
    private final AppProperties appProperties;

    public CreditRechargeServiceImpl(CreditRechargePackageMapper packageMapper,
                                     CreditRechargeOrderMapper orderMapper,
                                     CreditService creditService,
                                     ObjectMapper objectMapper,
                                     WechatNativePayClient wechatNativePayClient,
                                     AlipayPagePayClient alipayPagePayClient,
                                     QrCodeDataUriGenerator qrCodeDataUriGenerator,
                                     AppProperties appProperties) {
        this.packageMapper = packageMapper;
        this.orderMapper = orderMapper;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
        this.wechatNativePayClient = wechatNativePayClient;
        this.alipayPagePayClient = alipayPagePayClient;
        this.qrCodeDataUriGenerator = qrCodeDataUriGenerator;
        this.wechatProperties = appProperties.getPayment().getWechatNative();
        this.alipayProperties = appProperties.getPayment().getAlipayPage();
        this.appProperties = appProperties;
    }

    @Override
    public List<RechargePackageResponse> packages() {
        return packageMapper.findActive().stream()
                .map(item -> RechargePackageResponse.from(item, objectMapper))
                .toList();
    }

    @Override
    public RechargeOrderResponse createOrder(Long userId, CreateRechargeOrderRequest request) {
        String idempotencyKey = normalizeIdempotencyKey(request.clientRequestId());
        if (idempotencyKey != null) {
            CreditRechargeOrder existing = orderMapper.findByUserAndIdempotencyKey(userId, idempotencyKey);
            if (existing != null) {
                return responseFrom(existing);
            }
        }

        CreditRechargePackage rechargePackage = activePackageOrThrow(request.packageId());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(ORDER_EXPIRE_MINUTES);
        String paymentChannel = normalizePaymentChannel(request.paymentChannel());
        CreditRechargeOrder order = new CreditRechargeOrder();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setPackageId(rechargePackage.getId());
        order.setCredits(rechargePackage.getCredits());
        order.setPriceAmount(rechargePackage.getPriceAmount());
        order.setCurrency(rechargePackage.getCurrency());
        order.setPaymentChannel(paymentChannel);
        order.setStatus(RechargeOrderStatus.WAITING_PAYMENT.name());
        order.setStatusReason("waiting for payment");
        order.setIdempotencyKey(idempotencyKey);
        order.setExpiresAt(expiresAt);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        if ("WECHAT_NATIVE".equals(paymentChannel) || "ALIPAY_PAGE".equals(paymentChannel)) {
            order.setQrCodeUrl(null);
        } else {
            order.setPayUrl("/mock-pay/recharge/" + order.getOrderNo());
            order.setQrCodeUrl(null);
        }
        orderMapper.insert(order);
        if ("WECHAT_NATIVE".equals(paymentChannel)) {
            try {
                NativePrepayResponse prepay = wechatNativePayClient.createNativeOrder(new NativePrepayRequest(
                        order.getOrderNo(),
                        "AI Tool Market credits recharge - " + rechargePackage.getPackageName(),
                        priceToFen(rechargePackage.getPriceAmount()),
                        rechargePackage.getCurrency(),
                        expiresAt
                ));
                if (orderMapper.bindPayUrl(order.getId(), prepay.codeUrl(), "WeChat Native prepay created", LocalDateTime.now()) != 1) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order status changed before WeChat prepay binding");
                }
            } catch (BusinessException exception) {
                orderMapper.transit(order.getId(), RechargeOrderStatus.WAITING_PAYMENT.name(), RechargeOrderStatus.FAILED.name(),
                        exception.getMessage(), LocalDateTime.now());
                throw exception;
            }
        } else if ("ALIPAY_PAGE".equals(paymentChannel)) {
            try {
                AlipayPagePayResponse prepay = alipayPagePayClient.createPagePayOrder(new AlipayPagePayRequest(
                        order.getOrderNo(),
                        "AI Tool Market credits recharge - " + rechargePackage.getPackageName(),
                        rechargePackage.getPriceAmount(),
                        expiresAt
                ));
                if (orderMapper.bindPayUrl(order.getId(), prepay.payUrl(), "Alipay page pay created", LocalDateTime.now()) != 1) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order status changed before Alipay pay url binding");
                }
            } catch (BusinessException exception) {
                orderMapper.transit(order.getId(), RechargeOrderStatus.WAITING_PAYMENT.name(), RechargeOrderStatus.FAILED.name(),
                        exception.getMessage(), LocalDateTime.now());
                throw exception;
            }
        }
        return responseFrom(orderMapper.findByOrderNo(order.getOrderNo()));
    }

    @Override
    @Transactional
    public RechargeOrderResponse createCustomOrder(Long userId, CreateCustomRechargeOrderRequest request) {
        String idempotencyKey = normalizeIdempotencyKey(request.clientRequestId());
        if (idempotencyKey != null) {
            CreditRechargeOrder existing = orderMapper.findByUserAndIdempotencyKey(userId, idempotencyKey);
            if (existing != null) {
                return responseFrom(existing);
            }
        }
        BigDecimal amount = request.amount();
        int credits = amount.multiply(new BigDecimal("100")).setScale(0, RoundingMode.DOWN).intValue();
        if (credits <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "充值金额过小，至少需要 0.01 元");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(ORDER_EXPIRE_MINUTES);
        String paymentChannel = normalizePaymentChannel(request.paymentChannel());
        CreditRechargeOrder order = new CreditRechargeOrder();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setPackageId(null);
        order.setCredits(credits);
        order.setPriceAmount(amount);
        order.setCurrency("CNY");
        order.setPaymentChannel(paymentChannel);
        order.setStatus(RechargeOrderStatus.WAITING_PAYMENT.name());
        order.setStatusReason("custom recharge");
        order.setIdempotencyKey(idempotencyKey);
        order.setExpiresAt(expiresAt);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        if ("WECHAT_NATIVE".equals(paymentChannel) || "ALIPAY_PAGE".equals(paymentChannel)) {
            order.setPayUrl(null);
            order.setQrCodeUrl(null);
        } else {
            order.setPayUrl("/mock-pay/recharge/" + order.getOrderNo());
            order.setQrCodeUrl(null);
        }
        orderMapper.insert(order);
        if ("WECHAT_NATIVE".equals(paymentChannel)) {
            try {
                NativePrepayResponse prepay = wechatNativePayClient.createNativeOrder(new NativePrepayRequest(
                        order.getOrderNo(),
                        "Custom credits recharge " + credits + " credits",
                        priceToFen(amount),
                        "CNY",
                        expiresAt
                ));
                if (orderMapper.bindPayUrl(order.getId(), prepay.codeUrl(), "WeChat Native prepay created", LocalDateTime.now()) != 1) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order status changed before WeChat prepay binding");
                }
            } catch (BusinessException exception) {
                orderMapper.transit(order.getId(), RechargeOrderStatus.WAITING_PAYMENT.name(), RechargeOrderStatus.FAILED.name(),
                        exception.getMessage(), LocalDateTime.now());
                throw exception;
            }
        } else if ("ALIPAY_PAGE".equals(paymentChannel)) {
            try {
                AlipayPagePayResponse payResponse = alipayPagePayClient.createPagePayOrder(new AlipayPagePayRequest(
                        order.getOrderNo(),
                        "Custom credits recharge " + credits + " credits",
                        amount,
                        expiresAt
                ));
                if (orderMapper.bindPayUrl(order.getId(), payResponse.payUrl(), "Alipay page pay created", LocalDateTime.now()) != 1) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order status changed before Alipay pay url binding");
                }
            } catch (BusinessException exception) {
                orderMapper.transit(order.getId(), RechargeOrderStatus.WAITING_PAYMENT.name(), RechargeOrderStatus.FAILED.name(),
                        exception.getMessage(), LocalDateTime.now());
                throw exception;
            }
        }
        return responseFrom(orderMapper.findByOrderNo(order.getOrderNo()));
    }

    @Override
    public RechargeOrderResponse getOrder(Long userId, Long orderId) {
        CreditRechargeOrder order = orderOrThrow(userId, orderId);
        refreshWechatOrderIfNeeded(order);
        return responseFrom(orderOrThrow(userId, orderId));
    }

    @Override
    @Transactional
    public void handleWechatNativePaymentNotification(String serial,
                                                      String signature,
                                                      String timestamp,
                                                      String nonce,
                                                      String body) {
        WechatPayNotification notification = wechatNativePayClient.parseNotification(
                new WechatPayCallbackHeaders(serial, signature, timestamp, nonce),
                body
        );
        validateWechatMerchant(notification);
        if (!"SUCCESS".equals(notification.tradeState())) {
            return;
        }
        if (!"NATIVE".equals(notification.tradeType())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported WeChat trade type");
        }
        CreditRechargeOrder order = orderMapper.findByOrderNo(notification.outTradeNo());
        if (order == null || !"WECHAT_NATIVE".equals(order.getPaymentChannel())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order not found for WeChat notification");
        }
        if (RechargeOrderStatus.CREDITED.name().equals(order.getStatus())) {
            return;
        }
        if (priceToFen(order.getPriceAmount()) != notification.totalAmountFen()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat payment amount mismatch");
        }
        if (order.getCurrency() != null && !order.getCurrency().equals(notification.currency())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat payment currency mismatch");
        }
        if (orderMapper.bindExternalTradeNo(order.getId(), notification.transactionId(), LocalDateTime.now()) == 0) {
            CreditRechargeOrder current = orderMapper.findByOrderNo(notification.outTradeNo());
            if (current == null || !notification.transactionId().equals(current.getExternalTradeNo())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat transaction id conflicts with recharge order");
            }
        }
        grantPaidOrderIfNeeded(orderMapper.findByOrderNo(notification.outTradeNo()), "WeChat Native payment confirmed",
                "WeChat Native recharge order " + order.getOrderNo());
    }

    @Override
    @Transactional
    public void handleAlipayPagePaymentNotification(Map<String, String> params) {
        AlipayNotification notification = alipayPagePayClient.parseNotification(params);
        validateAlipayNotification(notification);
        if (!"TRADE_SUCCESS".equals(notification.tradeStatus()) && !"TRADE_FINISHED".equals(notification.tradeStatus())) {
            return;
        }
        CreditRechargeOrder order = orderMapper.findByOrderNo(notification.outTradeNo());
        if (order == null || !"ALIPAY_PAGE".equals(order.getPaymentChannel())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order not found for Alipay notification");
        }
        applyPaidExternalNotification(order, notification.tradeNo(), notification.totalAmount(),
                "Alipay page payment confirmed", "Alipay page recharge order " + order.getOrderNo());
    }

    @Override
    @Transactional
    public void handleMockPaymentNotification(String orderNo,
                                              String externalTradeNo,
                                              String tradeStatus,
                                              String totalAmount) {
        if (appProperties.isProductionMode()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "mock payment notification is disabled in production mode");
        }
        if (!"SUCCESS".equalsIgnoreCase(tradeStatus)) {
            return;
        }
        CreditRechargeOrder order = orderMapper.findByOrderNo(orderNo);
        if (order == null || !"MOCK".equals(order.getPaymentChannel())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order not found for mock notification");
        }
        String normalizedTradeNo = externalTradeNo == null || externalTradeNo.isBlank()
                ? "MOCK-" + order.getOrderNo()
                : externalTradeNo.trim();
        applyPaidExternalNotification(order, normalizedTradeNo, new BigDecimal(totalAmount),
                "mock payment notification confirmed", "Mock recharge order " + order.getOrderNo());
    }

    private void refreshWechatOrderIfNeeded(CreditRechargeOrder order) {
        if (!"WECHAT_NATIVE".equals(order.getPaymentChannel())) {
            return;
        }
        RechargeOrderStatus status = RechargeOrderStatus.valueOf(order.getStatus());
        if (status.terminal()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (RechargeOrderStatus.WAITING_PAYMENT.name().equals(order.getStatus())
                && order.getExpiresAt() != null
                && order.getExpiresAt().isBefore(now)) {
            transitOrCurrent(order, RechargeOrderStatus.CLOSED, "order expired");
            return;
        }
        if (order.getUpdatedAt() != null && order.getUpdatedAt().isAfter(now.minusSeconds(WECHAT_QUERY_THROTTLE_SECONDS))) {
            return;
        }
        WechatPayNotification notification = wechatNativePayClient.queryNativeOrder(order.getOrderNo());
        if (notification == null) {
            orderMapper.touchStatusReason(order.getId(), order.getStatus(), "WeChat order not found yet", now);
            return;
        }
        validateWechatMerchant(notification);
        if (!"NATIVE".equals(notification.tradeType())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported WeChat trade type");
        }
        if ("SUCCESS".equals(notification.tradeState())) {
            applyPaidWechatNotification(order, notification);
            return;
        }
        if ("CLOSED".equals(notification.tradeState()) || "REVOKED".equals(notification.tradeState())) {
            transitOrCurrent(order, RechargeOrderStatus.CLOSED, "WeChat order " + notification.tradeState());
            return;
        }
        if ("PAYERROR".equals(notification.tradeState())) {
            transitOrCurrent(order, RechargeOrderStatus.FAILED, "WeChat payment failed");
            return;
        }
        orderMapper.touchStatusReason(order.getId(), order.getStatus(), "WeChat order state " + notification.tradeState(), now);
    }

    private void applyPaidWechatNotification(CreditRechargeOrder order, WechatPayNotification notification) {
        if (order == null || !"WECHAT_NATIVE".equals(order.getPaymentChannel())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order not found for WeChat notification");
        }
        if (RechargeOrderStatus.CREDITED.name().equals(order.getStatus())) {
            return;
        }
        if (priceToFen(order.getPriceAmount()) != notification.totalAmountFen()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat payment amount mismatch");
        }
        if (order.getCurrency() != null && !order.getCurrency().equals(notification.currency())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat payment currency mismatch");
        }
        if (orderMapper.bindExternalTradeNo(order.getId(), notification.transactionId(), LocalDateTime.now()) == 0) {
            CreditRechargeOrder current = orderMapper.findByOrderNo(notification.outTradeNo());
            if (current == null || !notification.transactionId().equals(current.getExternalTradeNo())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat transaction id conflicts with recharge order");
            }
        }
        grantPaidOrderIfNeeded(orderMapper.findByOrderNo(notification.outTradeNo()), "WeChat Native payment confirmed",
                "WeChat Native recharge order " + order.getOrderNo());
    }

    private void applyPaidExternalNotification(CreditRechargeOrder order,
                                               String externalTradeNo,
                                               BigDecimal totalAmount,
                                               String paidReason,
                                               String creditReason) {
        if (order == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order not found");
        }
        if (RechargeOrderStatus.CREDITED.name().equals(order.getStatus())) {
            return;
        }
        if (order.getPriceAmount().compareTo(totalAmount) != 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "payment amount mismatch");
        }
        if (orderMapper.bindExternalTradeNo(order.getId(), externalTradeNo, LocalDateTime.now()) == 0) {
            CreditRechargeOrder current = orderMapper.findByOrderNo(order.getOrderNo());
            if (current == null || !externalTradeNo.equals(current.getExternalTradeNo())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "payment transaction id conflicts with recharge order");
            }
        }
        grantPaidOrderIfNeeded(orderMapper.findByOrderNo(order.getOrderNo()), paidReason, creditReason);
    }

    @Override
    @Transactional
    public RechargeOrderResponse mockPaySuccess(Long userId, Long orderId) {
        CreditRechargeOrder order = orderOrThrow(userId, orderId);
        if (RechargeOrderStatus.CREDITED.name().equals(order.getStatus())) {
            return responseFrom(order);
        }
        if (RechargeOrderStatus.WAITING_PAYMENT.name().equals(order.getStatus()) && order.getExpiresAt() != null
                && order.getExpiresAt().isBefore(LocalDateTime.now())) {
            transitOrThrow(order, RechargeOrderStatus.CLOSED, "order expired");
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order expired");
        }
        if (RechargeOrderStatus.WAITING_PAYMENT.name().equals(order.getStatus())) {
            transitOrCurrent(order, RechargeOrderStatus.PAID, "mock payment confirmed");
            order = orderOrThrow(userId, orderId);
        }
        if (!RechargeOrderStatus.PAID.name().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order cannot be paid from status " + order.getStatus());
        }
        grantPaidOrderIfNeeded(order, "mock payment confirmed", "Recharge order " + order.getOrderNo());
        return responseFrom(orderOrThrow(userId, orderId));
    }

    private void grantPaidOrderIfNeeded(CreditRechargeOrder order, String paidReason, String creditReason) {
        if (order == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order not found");
        }
        if (RechargeOrderStatus.CREDITED.name().equals(order.getStatus())) {
            return;
        }
        if (RechargeOrderStatus.WAITING_PAYMENT.name().equals(order.getStatus())) {
            transitOrCurrent(order, RechargeOrderStatus.PAID, paidReason);
            order = orderMapper.findByOrderNo(order.getOrderNo());
        }
        if (RechargeOrderStatus.PAID.name().equals(order.getStatus())) {
            creditService.rechargeAdd(order.getUserId(), order.getId(), order.getCredits(), creditReason);
            transitOrCurrent(order, RechargeOrderStatus.CREDITED, "credits granted");
            return;
        }
        if (!RechargeOrderStatus.valueOf(order.getStatus()).terminal()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order cannot grant credits from status " + order.getStatus());
        }
    }

    private void validateWechatMerchant(WechatPayNotification notification) {
        if (notification == null
                || !wechatProperties.getAppid().equals(notification.appid())
                || !wechatProperties.getMchid().equals(notification.mchid())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat payment merchant identity mismatch");
        }
    }

    private void validateAlipayNotification(AlipayNotification notification) {
        if (notification == null
                || !alipayProperties.getAppId().equals(notification.appId())
                || notification.outTradeNo() == null
                || notification.tradeNo() == null
                || notification.totalAmount() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Alipay payment notification is invalid");
        }
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

    private void transitOrCurrent(CreditRechargeOrder order, RechargeOrderStatus toStatus, String reason) {
        RechargeOrderStatus fromStatus = RechargeOrderStatus.valueOf(order.getStatus());
        if (fromStatus == toStatus) {
            return;
        }
        if (!fromStatus.canTransitTo(toStatus)) {
            CreditRechargeOrder current = orderMapper.findByOrderNo(order.getOrderNo());
            if (current != null && RechargeOrderStatus.valueOf(current.getStatus()).terminal()) {
                return;
            }
            throw new BusinessException(ErrorCode.PARAM_ERROR, "invalid recharge order transition");
        }
        int updated = orderMapper.transit(order.getId(), fromStatus.name(), toStatus.name(), reason, LocalDateTime.now());
        if (updated != 1) {
            CreditRechargeOrder current = orderMapper.findByOrderNo(order.getOrderNo());
            if (current != null && (toStatus.name().equals(current.getStatus())
                    || RechargeOrderStatus.CREDITED.name().equals(current.getStatus()))) {
                return;
            }
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

    private int priceToFen(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private RechargeOrderResponse responseFrom(CreditRechargeOrder order) {
        if (("WECHAT_NATIVE".equals(order.getPaymentChannel()) || "ALIPAY_PAGE".equals(order.getPaymentChannel()))
                && order.getPayUrl() != null
                && !order.getPayUrl().isBlank()
                && (order.getQrCodeUrl() == null || order.getQrCodeUrl().isBlank())) {
            order.setQrCodeUrl(qrCodeDataUriGenerator.generate(order.getPayUrl()));
        }
        return RechargeOrderResponse.from(order);
    }
}
