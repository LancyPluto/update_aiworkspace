package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.RechargeOrderStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayNotification;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayClient;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayRequest;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayResponse;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayTradeQueryResult;
import com.aiminilab.aitoolmarket.credit.dto.AlipayPayDiagnosticResponse;
import com.aiminilab.aitoolmarket.credit.dto.CreateCustomRechargeOrderRequest;
import com.aiminilab.aitoolmarket.credit.dto.CreateRechargeOrderRequest;
import com.aiminilab.aitoolmarket.credit.dto.RechargeOrderResponse;
import com.aiminilab.aitoolmarket.credit.dto.RechargePackageResponse;
import com.aiminilab.aitoolmarket.credit.dto.RechargePaymentOptionsResponse;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrder;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargeOrderItem;
import com.aiminilab.aitoolmarket.credit.entity.CreditRechargePackage;
import com.aiminilab.aitoolmarket.credit.entity.GiftCardPackage;
import com.aiminilab.aitoolmarket.credit.entity.UserMembership;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargeOrderMapper;
import com.aiminilab.aitoolmarket.credit.mapper.CreditRechargePackageMapper;
import com.aiminilab.aitoolmarket.credit.mapper.GiftCardPackageMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditRechargeService;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.support.MembershipTier;
import com.aiminilab.aitoolmarket.credit.wechat.NativePrepayRequest;
import com.aiminilab.aitoolmarket.credit.wechat.NativePrepayResponse;
import com.aiminilab.aitoolmarket.credit.wechat.QrCodeDataUriGenerator;
import com.aiminilab.aitoolmarket.credit.wechat.WechatNativePayClient;
import com.aiminilab.aitoolmarket.credit.wechat.WechatPayCallbackHeaders;
import com.aiminilab.aitoolmarket.credit.wechat.WechatPayNotification;
import com.aiminilab.aitoolmarket.common.cache.BypassCacheService;
import com.aiminilab.aitoolmarket.common.cache.CacheNamespaces;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class CreditRechargeServiceImpl implements CreditRechargeService {
    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int ORDER_EXPIRE_MINUTES = 30;
    private static final int WECHAT_QUERY_THROTTLE_SECONDS = 10;

    private final CreditRechargePackageMapper packageMapper;
    private final GiftCardPackageMapper giftCardPackageMapper;
    private final CreditRechargeOrderMapper orderMapper;
    private final CreditService creditService;
    private final ObjectMapper objectMapper;
    private final WechatNativePayClient wechatNativePayClient;
    private final AlipayPagePayClient alipayPagePayClient;
    private final QrCodeDataUriGenerator qrCodeDataUriGenerator;
    private final AppProperties.WechatNative wechatProperties;
    private final AppProperties.AlipayPage alipayProperties;
    private final AppProperties appProperties;
    private final CreditRechargeCreditDispatcher creditDispatcher;
    private final BypassCacheService bypassCacheService;
    private final MembershipService membershipService;
    private final RechargeOrderReservationService orderReservationService;

    public CreditRechargeServiceImpl(CreditRechargePackageMapper packageMapper,
                                     GiftCardPackageMapper giftCardPackageMapper,
                                     CreditRechargeOrderMapper orderMapper,
                                     CreditService creditService,
                                     ObjectMapper objectMapper,
                                     WechatNativePayClient wechatNativePayClient,
                                     AlipayPagePayClient alipayPagePayClient,
                                     QrCodeDataUriGenerator qrCodeDataUriGenerator,
                                     AppProperties appProperties,
                                     CreditRechargeCreditDispatcher creditDispatcher,
                                     BypassCacheService bypassCacheService,
                                     MembershipService membershipService,
                                     RechargeOrderReservationService orderReservationService) {
        this.packageMapper = packageMapper;
        this.giftCardPackageMapper = giftCardPackageMapper;
        this.orderMapper = orderMapper;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
        this.wechatNativePayClient = wechatNativePayClient;
        this.alipayPagePayClient = alipayPagePayClient;
        this.qrCodeDataUriGenerator = qrCodeDataUriGenerator;
        this.wechatProperties = appProperties.getPayment().getWechatNative();
        this.alipayProperties = appProperties.getPayment().getAlipayPage();
        this.appProperties = appProperties;
        this.creditDispatcher = creditDispatcher;
        this.bypassCacheService = bypassCacheService;
        this.membershipService = membershipService;
        this.orderReservationService = orderReservationService;
    }

    @Override
    public RechargePaymentOptionsResponse paymentOptions() {
        return new RechargePaymentOptionsResponse(
                isWechatNativeConfigured(),
                isAlipayConfigured(),
                "PAGE"
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void invalidateRechargePackageCacheOnStartup() {
        bypassCacheService.invalidateRechargePackages();
    }

    @Override
    public List<RechargePackageResponse> packages() {
        JavaType type = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, RechargePackageResponse.class);
        return bypassCacheService.getOrLoad(
                CacheNamespaces.RECHARGE_PACKAGES,
                bypassCacheService.packageTtl(),
                type,
                () -> {
                    List<CreditRechargePackage> packages = packageMapper.findActive();
                    Map<String, BigDecimal> monthlyPriceByTier = monthlyPriceByTier(packages);
                    return packages.stream()
                            .map(item -> RechargePackageResponse.from(item, objectMapper, monthlyPriceByTier))
                            .toList();
                }
        );
    }

    @Override
    public RechargeOrderResponse createOrder(Long userId, CreateRechargeOrderRequest request) {
        String idempotencyKey = normalizeIdempotencyKey(request.clientRequestId());
        String paymentChannel = normalizePaymentChannel(request.paymentChannel());
        String requestFingerprint = fingerprint(canonicalRequest(request, paymentChannel));
        boolean isGiftCard = "GIFT_CARD".equals(request.orderType());
        if (!isGiftCard) {
            closeExpiredPendingMembershipOrder(userId);
        }
        if (idempotencyKey != null) {
            CreditRechargeOrder existing = orderMapper.findByUserAndIdempotencyKey(userId, idempotencyKey);
            if (existing != null) {
                assertSameFingerprint(existing, requestFingerprint);
                return responseFrom(existing);
            }
        }

        String productDescription;
        BigDecimal orderPriceAmount;
        String orderCurrency;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(ORDER_EXPIRE_MINUTES);
        CreditRechargeOrder order = new CreditRechargeOrder();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setPaymentChannel(paymentChannel);
        order.setStatus(RechargeOrderStatus.WAITING_PAYMENT.name());
        order.setStatusReason("waiting for payment");
        order.setIdempotencyKey(idempotencyKey);
        order.setRequestFingerprint(requestFingerprint);
        order.setExpiresAt(expiresAt);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.setPayUrl(null);
        order.setQrCodeUrl(null);

        if (isGiftCard) {
            List<GiftCardOrderLine> lines = resolveGiftCardOrderLines(userId, request);
            order.setPackageId(null);
            order.setCredits(lines.stream().mapToInt(line -> line.pkg().getCredits() * line.quantity()).sum());
            order.setPriceAmount(lines.stream()
                    .map(line -> line.pkg().getPriceAmount().multiply(BigDecimal.valueOf(line.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            order.setCurrency(lines.get(0).pkg().getCurrency());
            order.setOrderType("GIFT_CARD");
            order.setGiftCardPackageId(lines.size() == 1 ? lines.get(0).pkg().getId() : null);
            productDescription = lines.size() == 1
                    ? "Gift card - " + lines.get(0).pkg().getPackageName()
                    : "Gift cards x " + lines.stream().mapToInt(GiftCardOrderLine::quantity).sum();
            orderPriceAmount = order.getPriceAmount();
            orderCurrency = order.getCurrency();
            try {
                orderReservationService.insertGiftCardOrder(order, buildGiftCardOrderItems(lines, now));
            } catch (DuplicateKeyException exception) {
                CreditRechargeOrder existing = orderMapper.findByUserAndIdempotencyKey(userId, idempotencyKey);
                if (existing == null) {
                    throw exception;
                }
                assertSameFingerprint(existing, requestFingerprint);
                return responseFrom(existing);
            }
        } else {
            CreditRechargePackage rechargePackage = activePackageOrThrow(request.packageId());
            order.setPackageId(rechargePackage.getId());
            order.setCredits(rechargePackage.getCredits());
            order.setPriceAmount(rechargePackage.getPriceAmount());
            order.setCurrency(rechargePackage.getCurrency());
            order.setOrderType("MEMBERSHIP");
            order.setGiftCardPackageId(null);
            order.setPackageCodeSnapshot(rechargePackage.getPackageCode());
            order.setValidityDaysSnapshot(rechargePackage.getValidityDays());
            productDescription = "AI Tool Market credits recharge - " + rechargePackage.getPackageName();
            orderPriceAmount = rechargePackage.getPriceAmount();
            orderCurrency = rechargePackage.getCurrency();
            CreditRechargeOrder reserved = membershipService.reserveOrder(userId, order, rechargePackage);
            if (!order.getOrderNo().equals(reserved.getOrderNo())) {
                return responseFrom(reserved);
            }
        }
        if ("WECHAT_NATIVE".equals(paymentChannel)) {
            try {
                NativePrepayResponse prepay = wechatNativePayClient.createNativeOrder(new NativePrepayRequest(
                        order.getOrderNo(),
                        productDescription,
                        priceToFen(orderPriceAmount),
                        orderCurrency,
                        expiresAt
                ));
                if (orderMapper.bindPayUrl(order.getId(), prepay.codeUrl(), "WeChat Native prepay created", LocalDateTime.now()) != 1) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order status changed before WeChat prepay binding");
                }
            } catch (BusinessException exception) {
                if ("MEMBERSHIP".equals(order.getOrderType())) {
                    reconcileFailedMembershipWechatPrepay(order, exception);
                } else {
                    orderMapper.transit(order.getId(), RechargeOrderStatus.WAITING_PAYMENT.name(),
                            RechargeOrderStatus.FAILED.name(), statusReason(exception), LocalDateTime.now());
                }
                throw exception;
            }
        } else if ("ALIPAY_PAGE".equals(paymentChannel)) {
            try {
                AlipayPagePayResponse prepay = alipayPagePayClient.createPagePayOrder(new AlipayPagePayRequest(
                        order.getOrderNo(),
                        productDescription,
                        orderPriceAmount,
                        expiresAt
                ));
                String payBinding = prepay.redirectPath();
                if (payBinding == null || payBinding.isBlank()) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "Alipay page pay launch path missing");
                }
                if (orderMapper.bindPayUrl(order.getId(), payBinding, "Alipay page pay order created", LocalDateTime.now()) != 1) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order status changed before Alipay pay binding");
                }
            } catch (BusinessException exception) {
                orderMapper.transit(order.getId(), RechargeOrderStatus.WAITING_PAYMENT.name(), RechargeOrderStatus.FAILED.name(),
                        statusReason(exception), LocalDateTime.now());
                throw exception;
            }
        }
        return responseFrom(orderMapper.findByOrderNo(order.getOrderNo()));
    }

    @Override
    public RechargeOrderResponse createCustomOrder(Long userId, CreateCustomRechargeOrderRequest request) {
        String idempotencyKey = normalizeIdempotencyKey(request.clientRequestId());
        String paymentChannel = normalizePaymentChannel(request.paymentChannel());
        String requestFingerprint = fingerprint("CREDITS|" + request.amount().stripTrailingZeros().toPlainString()
                + "|" + paymentChannel);
        if (idempotencyKey != null) {
            CreditRechargeOrder existing = orderMapper.findByUserAndIdempotencyKey(userId, idempotencyKey);
            if (existing != null) {
                assertSameFingerprint(existing, requestFingerprint);
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
        order.setRequestFingerprint(requestFingerprint);
        order.setOrderType("CREDITS");
        order.setExpiresAt(expiresAt);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order.setPayUrl(null);
        order.setQrCodeUrl(null);
        try {
            orderReservationService.insertOrder(order);
        } catch (DuplicateKeyException exception) {
            CreditRechargeOrder existing = orderMapper.findByUserAndIdempotencyKey(userId, idempotencyKey);
            if (existing == null) {
                throw exception;
            }
            assertSameFingerprint(existing, requestFingerprint);
            return responseFrom(existing);
        }
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
                        statusReason(exception), LocalDateTime.now());
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
                String payBinding = payResponse.redirectPath();
                if (payBinding == null || payBinding.isBlank()) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "Alipay page pay launch path missing");
                }
                if (orderMapper.bindPayUrl(order.getId(), payBinding, "Alipay page pay order created", LocalDateTime.now()) != 1) {
                    throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order status changed before Alipay pay binding");
                }
            } catch (BusinessException exception) {
                orderMapper.transit(order.getId(), RechargeOrderStatus.WAITING_PAYMENT.name(), RechargeOrderStatus.FAILED.name(),
                        statusReason(exception), LocalDateTime.now());
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
    public AlipayPayDiagnosticResponse alipayDiagnostic(Long userId, Long orderId) {
        CreditRechargeOrder order = orderOrThrow(userId, orderId);
        if (!"ALIPAY_PAGE".equals(order.getPaymentChannel())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "order is not an Alipay page pay recharge order");
        }
        String subject = order.getPackageId() == null
                ? "Custom credits recharge " + order.getCredits() + " credits"
                : "AI Tool Market credits recharge";
        return alipayPagePayClient.buildDiagnostic(new AlipayPagePayRequest(
                order.getOrderNo(),
                subject,
                order.getPriceAmount(),
                order.getExpiresAt()
        ));
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
        markPaidAndDispatchCredit(orderMapper.findByOrderNo(notification.outTradeNo()), "WeChat Native payment confirmed");
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
        applyPaidExternalNotification(order, notification.tradeNo(), notification.totalAmount(), "Alipay page payment confirmed");
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
            closeExpiredWechatOrder(order);
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
        validateWechatQueryResult(order, notification);
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

    private void closeExpiredPendingMembershipOrder(Long userId) {
        UserMembership membership = membershipService.current(userId);
        if (membership == null || !"PENDING".equals(membership.getStatus()) || membership.getOrderId() == null) {
            return;
        }
        CreditRechargeOrder order = orderMapper.selectById(membership.getOrderId());
        if (order == null || !RechargeOrderStatus.WAITING_PAYMENT.name().equals(order.getStatus())
                || order.getExpiresAt() == null || order.getExpiresAt().isAfter(LocalDateTime.now())) {
            return;
        }
        if ("WECHAT_NATIVE".equals(order.getPaymentChannel()) && closeExpiredWechatOrder(order)) {
            membershipService.releasePending(userId, order.getId());
        } else if ("ALIPAY_PAGE".equals(order.getPaymentChannel()) && closeExpiredAlipayOrder(order)) {
            membershipService.releasePending(userId, order.getId());
        }
    }

    private boolean closeExpiredWechatOrder(CreditRechargeOrder order) {
        WechatPayNotification notification = wechatNativePayClient.queryNativeOrder(order.getOrderNo());
        if (notification != null) {
            validateWechatQueryResult(order, notification);
            if ("SUCCESS".equals(notification.tradeState())) {
                applyPaidWechatNotification(order, notification);
                return false;
            }
            if ("CLOSED".equals(notification.tradeState()) || "REVOKED".equals(notification.tradeState())) {
                transitOrCurrent(order, RechargeOrderStatus.CLOSED, "WeChat order " + notification.tradeState());
                return true;
            }
        }
        if (!wechatNativePayClient.closeNativeOrder(order.getOrderNo())) {
            return false;
        }
        transitOrCurrent(order, RechargeOrderStatus.CLOSED, "WeChat order closed after channel confirmation");
        return true;
    }

    private void reconcileFailedMembershipWechatPrepay(CreditRechargeOrder order, BusinessException prepayFailure) {
        boolean channelClosed = false;
        String closeFailure = null;
        try {
            channelClosed = closeExpiredWechatOrder(order);
        } catch (RuntimeException exception) {
            closeFailure = statusReason(exception);
        }
        if (channelClosed) {
            membershipService.releasePending(order.getUserId(), order.getId());
            return;
        }
        String reason = "WeChat prepay failed; channel closure unconfirmed: " + statusReason(prepayFailure);
        if (closeFailure != null) {
            reason = reason + "; " + closeFailure;
        }
        orderMapper.touchStatusReason(order.getId(), RechargeOrderStatus.WAITING_PAYMENT.name(),
                reason.length() <= 255 ? reason : reason.substring(0, 252) + "...", LocalDateTime.now());
    }

    private boolean closeExpiredAlipayOrder(CreditRechargeOrder order) {
        AlipayTradeQueryResult result = alipayPagePayClient.queryOrder(order.getOrderNo());
        if (result != null) {
            validateAlipayQueryResult(order, result);
        }
        if (result != null && ("TRADE_SUCCESS".equals(result.tradeStatus())
                || "TRADE_FINISHED".equals(result.tradeStatus()))) {
            if (result.tradeNo() == null || result.totalAmount() == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "Alipay paid order query result is incomplete");
            }
            applyPaidExternalNotification(order, result.tradeNo(), result.totalAmount(),
                    "Alipay payment confirmed by order query");
            return false;
        }
        if (result != null && "TRADE_CLOSED".equals(result.tradeStatus())) {
            transitOrCurrent(order, RechargeOrderStatus.CLOSED, "Alipay order already closed");
            return true;
        }
        if (result == null || (!("WAIT_BUYER_PAY".equals(result.tradeStatus()))
                && !("NOT_FOUND".equals(result.tradeStatus())))) {
            return false;
        }
        if (!alipayPagePayClient.closeOrder(order.getOrderNo())) {
            return false;
        }
        transitOrCurrent(order, RechargeOrderStatus.CLOSED, "Alipay order closed after channel confirmation");
        return true;
    }

    private void applyPaidWechatNotification(CreditRechargeOrder order, WechatPayNotification notification) {
        if (order == null || !"WECHAT_NATIVE".equals(order.getPaymentChannel())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order not found for WeChat notification");
        }
        if (!order.getOrderNo().equals(notification.outTradeNo())
                || !StringUtils.hasText(notification.transactionId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat payment transaction is invalid");
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
        markPaidAndDispatchCredit(orderMapper.findByOrderNo(notification.outTradeNo()), "WeChat Native payment confirmed");
    }

    private void applyPaidExternalNotification(CreditRechargeOrder order,
                                               String externalTradeNo,
                                               BigDecimal totalAmount,
                                               String paidReason) {
        if (order == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order not found");
        }
        if (!StringUtils.hasText(externalTradeNo) || totalAmount == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "payment transaction is incomplete");
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
        markPaidAndDispatchCredit(orderMapper.findByOrderNo(order.getOrderNo()), paidReason);
    }

    private void markPaidAndDispatchCredit(CreditRechargeOrder order, String paidReason) {
        if (order == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "recharge order not found");
        }
        if (RechargeOrderStatus.CREDITED.name().equals(order.getStatus())) {
            return;
        }
        if (RechargeOrderStatus.WAITING_PAYMENT.name().equals(order.getStatus())
                || RechargeOrderStatus.CLOSED.name().equals(order.getStatus())
                || RechargeOrderStatus.FAILED.name().equals(order.getStatus())) {
            transitOrCurrent(order, RechargeOrderStatus.PAID, paidReason);
            order = orderMapper.findByOrderNo(order.getOrderNo());
        }
        if (RechargeOrderStatus.PAID.name().equals(order.getStatus())) {
            creditDispatcher.dispatchOrderNo(order.getOrderNo());
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
                || !StringUtils.hasText(notification.outTradeNo())
                || !StringUtils.hasText(notification.tradeNo())
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

    private List<GiftCardOrderLine> resolveGiftCardOrderLines(Long userId, CreateRechargeOrderRequest request) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        if (request.giftCardItems() != null && !request.giftCardItems().isEmpty()) {
            for (CreateRechargeOrderRequest.GiftCardItemRequest item : request.giftCardItems()) {
                if (item == null || item.giftCardPackageId() == null) {
                    continue;
                }
                int quantity = normalizeQuantity(item.quantity());
                quantities.merge(item.giftCardPackageId(), quantity, Integer::sum);
            }
        } else if (request.giftCardPackageId() != null) {
            quantities.put(request.giftCardPackageId(), normalizeQuantity(request.quantity()));
        }
        if (quantities.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "gift card package not found");
        }
        int totalQuantity = quantities.values().stream().mapToInt(Integer::intValue).sum();
        if (totalQuantity > 99 || quantities.values().stream().anyMatch(quantity -> quantity > 99)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "gift card quantity must be between 1 and 99");
        }

        List<GiftCardOrderLine> lines = new ArrayList<>();
        String currency = null;
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            GiftCardPackage giftPkg = giftCardPackageMapper.findActiveById(entry.getKey());
            if (giftPkg == null) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "gift card package not found");
            }
            if (currency == null) {
                currency = giftPkg.getCurrency();
            } else if (!currency.equals(giftPkg.getCurrency())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "gift card currency mismatch");
            }
            if ("MEMBER_CREDIT".equalsIgnoreCase(giftPkg.getCardType())) {
                membershipService.requireActiveTierAtLeast(
                        userId,
                        giftPkg.getRequiredMemberTier(),
                        "购买");
            }
            lines.add(new GiftCardOrderLine(giftPkg, entry.getValue()));
        }
        return lines;
    }

    private int normalizeQuantity(Integer quantity) {
        int normalized = quantity == null ? 1 : quantity;
        if (normalized <= 0 || normalized > 99) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "gift card quantity must be between 1 and 99");
        }
        return normalized;
    }

    private String canonicalRequest(CreateRechargeOrderRequest request, String paymentChannel) {
        if (!"GIFT_CARD".equals(request.orderType())) {
            return "MEMBERSHIP|" + request.packageId() + "|" + paymentChannel;
        }
        Map<Long, Integer> quantities = new TreeMap<>();
        if (request.giftCardItems() != null && !request.giftCardItems().isEmpty()) {
            for (CreateRechargeOrderRequest.GiftCardItemRequest item : request.giftCardItems()) {
                if (item != null && item.giftCardPackageId() != null) {
                    quantities.merge(item.giftCardPackageId(), normalizeQuantity(item.quantity()), Integer::sum);
                }
            }
        } else if (request.giftCardPackageId() != null) {
            quantities.put(request.giftCardPackageId(), normalizeQuantity(request.quantity()));
        }
        String items = quantities.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
        return "GIFT_CARD|" + items + "|" + paymentChannel;
    }

    private List<CreditRechargeOrderItem> buildGiftCardOrderItems(List<GiftCardOrderLine> lines, LocalDateTime now) {
        List<CreditRechargeOrderItem> items = new ArrayList<>();
        for (GiftCardOrderLine line : lines) {
            CreditRechargeOrderItem item = new CreditRechargeOrderItem();
            item.setGiftCardPackageId(line.pkg().getId());
            item.setQuantity(line.quantity());
            item.setCredits(line.pkg().getCredits());
            item.setPriceAmount(line.pkg().getPriceAmount());
            item.setItemType("GIFT_CARD");
            item.setCardTypeSnapshot(normalizeCardType(line.pkg().getCardType()));
            item.setRequiredMemberTierSnapshot(normalizeRequiredMemberTier(line.pkg().getRequiredMemberTier()));
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            items.add(item);
        }
        return items;
    }

    private Map<String, BigDecimal> monthlyPriceByTier(List<CreditRechargePackage> packages) {
        Map<String, BigDecimal> monthlyPrices = new LinkedHashMap<>();
        for (CreditRechargePackage pkg : packages) {
            if (pkg.getPackageCode() == null || !pkg.getPackageCode().startsWith("monthly_")) {
                continue;
            }
            MembershipTier.fromPackageCode(pkg.getPackageCode())
                    .ifPresent(tier -> monthlyPrices.put(tier.code(), pkg.getPriceAmount()));
        }
        return monthlyPrices;
    }

    private String normalizeCardType(String cardType) {
        return cardType == null || cardType.isBlank()
                ? "CREDIT"
                : cardType.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String normalizeRequiredMemberTier(String requiredMemberTier) {
        return MembershipTier.fromCode(requiredMemberTier)
                .map(MembershipTier::code)
                .orElse(null);
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

    private boolean isWechatNativeConfigured() {
        return wechatProperties.isEnabled()
                && StringUtils.hasText(wechatProperties.getAppid())
                && StringUtils.hasText(wechatProperties.getMchid())
                && StringUtils.hasText(wechatProperties.getMerchantSerialNo())
                && StringUtils.hasText(wechatProperties.getMerchantPrivateKeyPath())
                && StringUtils.hasText(wechatProperties.getApiV3Key())
                && StringUtils.hasText(wechatProperties.getWechatPayPublicKeyId())
                && StringUtils.hasText(wechatProperties.getWechatPayPublicKeyPath())
                && StringUtils.hasText(wechatProperties.getNotifyUrl());
    }

    private boolean isAlipayConfigured() {
        return alipayProperties.isEnabled()
                && StringUtils.hasText(alipayProperties.getAppId())
                && StringUtils.hasText(alipayProperties.getMerchantPrivateKey())
                && StringUtils.hasText(alipayProperties.getAlipayPublicKey())
                && StringUtils.hasText(alipayProperties.getNotifyUrl());
    }

    private String normalizePaymentChannel(String paymentChannel) {
        if (paymentChannel == null || paymentChannel.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "payment channel is required");
        }
        String normalized = paymentChannel.trim().toUpperCase();
        if ("MOCK".equals(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "mock payment is not supported");
        }
        if (!"WECHAT_NATIVE".equals(normalized) && !"ALIPAY_PAGE".equals(normalized)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported payment channel: " + normalized);
        }
        return normalized;
    }

    private String normalizeIdempotencyKey(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            return null;
        }
        return clientRequestId.trim();
    }

    private void assertSameFingerprint(CreditRechargeOrder existing, String requestFingerprint) {
        if (existing.getRequestFingerprint() != null
                && !existing.getRequestFingerprint().equals(requestFingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "同一 clientRequestId 不能用于不同的充值请求");
        }
    }

    private void validateWechatQueryResult(CreditRechargeOrder order, WechatPayNotification notification) {
        validateWechatMerchant(notification);
        if (!order.getOrderNo().equals(notification.outTradeNo())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "WeChat order query number mismatch");
        }
        if (!"NATIVE".equals(notification.tradeType())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported WeChat trade type");
        }
    }

    private void validateAlipayQueryResult(CreditRechargeOrder order, AlipayTradeQueryResult result) {
        if (!order.getOrderNo().equals(result.outTradeNo())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Alipay order query number mismatch");
        }
    }

    private String fingerprint(String canonicalRequest) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String statusReason(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "payment gateway request failed";
        }
        return message.length() <= 255 ? message : message.substring(0, 252) + "...";
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
        boolean alipayPageLaunch = "ALIPAY_PAGE".equals(order.getPaymentChannel())
                && order.getPayUrl() != null
                && order.getPayUrl().contains("/pay/alipay/page/launch");
        if (alipayPageLaunch) {
            order.setQrCodeUrl(null);
        } else if (order.getPayUrl() != null
                && !order.getPayUrl().isBlank()
                && (order.getQrCodeUrl() == null || order.getQrCodeUrl().isBlank())
                && ("WECHAT_NATIVE".equals(order.getPaymentChannel()) || "ALIPAY_PAGE".equals(order.getPaymentChannel()))) {
            order.setQrCodeUrl(qrCodeDataUriGenerator.generate(order.getPayUrl()));
        }
        return RechargeOrderResponse.from(order);
    }

    private record GiftCardOrderLine(GiftCardPackage pkg, int quantity) {
    }
}
