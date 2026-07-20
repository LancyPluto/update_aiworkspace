package com.aiminilab.aitoolmarket.credit;

import com.aiminilab.aitoolmarket.credit.alipay.AlipayNotification;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayClient;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayRequest;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayResponse;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayTradeQueryResult;
import com.aiminilab.aitoolmarket.credit.entity.ReferralRegistrationReward;
import com.aiminilab.aitoolmarket.credit.mapper.ReferralRegistrationRewardMapper;
import com.aiminilab.aitoolmarket.credit.service.ReferralService;
import com.aiminilab.aitoolmarket.credit.wechat.NativePrepayRequest;
import com.aiminilab.aitoolmarket.credit.wechat.NativePrepayResponse;
import com.aiminilab.aitoolmarket.credit.wechat.WechatNativePayClient;
import com.aiminilab.aitoolmarket.credit.wechat.WechatPayCallbackHeaders;
import com.aiminilab.aitoolmarket.credit.wechat.WechatPayNotification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:credit_recharge_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.production-mode=false",
        "app.payment.wechat-native.enabled=true",
        "app.payment.wechat-native.appid=wx-test",
        "app.payment.wechat-native.mchid=mch-test",
        "app.payment.wechat-native.merchant-serial-no=test-serial",
        "app.payment.wechat-native.merchant-private-key-path=/tmp/wechat-test-key.pem",
        "app.payment.wechat-native.api-v3-key=01234567890123456789012345678901",
        "app.payment.wechat-native.wechat-pay-public-key-id=test-pub-id",
        "app.payment.wechat-native.wechat-pay-public-key-path=/tmp/wechat-test-pub.pem",
        "app.payment.wechat-native.notify-url=https://example.com/api/v1/pay/wechat/native/notify",
        "app.payment.alipay-page.enabled=true",
        "app.payment.alipay-page.app-id=alipay-test-app",
        "app.payment.alipay-page.merchant-private-key=test-private-key",
        "app.payment.alipay-page.alipay-public-key=test-public-key",
        "app.payment.alipay-page.notify-url=https://example.com/api/v1/pay/alipay/page/notify"
})
class CreditRechargeApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WechatNativePayClient wechatNativePayClient;

    @MockBean
    private AlipayPagePayClient alipayPagePayClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReferralService referralService;

    @SpyBean
    private ReferralRegistrationRewardMapper referralRegistrationRewardMapper;

    @Test
    void membershipOrderRequiresClientRequestId() throws Exception {
        String userToken = register("membership_idempotency_required");

        mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "WECHAT_NATIVE"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sameMembershipAndChannelReusePendingOrderAcrossDevices() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=membership-pending"));
        String userToken = register("membership_pending_reuse");

        String first = createMembershipOrder(userToken, "WECHAT_NATIVE", "membership-device-a");
        String second = createMembershipOrder(userToken, "WECHAT_NATIVE", "membership-device-b");

        assertThatJsonOrderId(second, extractOrderId(first));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_recharge_orders WHERE user_id = (SELECT id FROM users WHERE username = ?)",
                Integer.class,
                "membership_pending_reuse"
        )).isEqualTo(1);
    }

    @Test
    void currentUserExposesPendingMembershipWithoutActivePlan() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=membership-profile-pending"));
        String userToken = register("membership_profile_pending");
        Long orderId = extractOrderId(createMembershipOrder(
                userToken, "WECHAT_NATIVE", "membership-profile-pending"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membershipStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.pendingMembershipOrderId").value(orderId.intValue()))
                .andExpect(jsonPath("$.data.membershipPlan").doesNotExist())
                .andExpect(jsonPath("$.data.membershipStartedAt").doesNotExist())
                .andExpect(jsonPath("$.data.membershipExpiresAt").doesNotExist());
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejected() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=membership-fingerprint"));
        when(alipayPagePayClient.createPagePayOrder(any(AlipayPagePayRequest.class)))
                .thenReturn(AlipayPagePayResponse.pageRedirect("/api/v1/pay/alipay/page/launch?orderNo=idem-conflict"));
        String userToken = register("membership_idempotency_conflict");

        createMembershipOrder(userToken, "WECHAT_NATIVE", "membership-same-key");

        mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "ALIPAY_PAGE",
                                  "clientRequestId": "membership-same-key"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void verifiedPaymentRecoversLocallyClosedOrder() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=late-payment"));
        String userToken = register("membership_late_payment");
        String orderResponse = createMembershipOrder(userToken, "WECHAT_NATIVE", "membership-late-payment");
        Long orderId = extractOrderId(orderResponse);
        String orderNo = extractOrderNo(orderResponse);
        jdbcTemplate.update("UPDATE credit_recharge_orders SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP WHERE id = ?", orderId);

        when(wechatNativePayClient.parseNotification(any(WechatPayCallbackHeaders.class), anyString()))
                .thenReturn(new WechatPayNotification(
                        "wx-test", "mch-test", orderNo, "4200000000000000888",
                        "NATIVE", "SUCCESS", 1000, "CNY"
                ));

        postWechatNotify();

        mockMvc.perform(get("/api/v1/credits/recharge-orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREDITED"));
    }

    @Test
    void wechatNotificationFailureReturnsHttpErrorForPlatformRetry() throws Exception {
        when(wechatNativePayClient.parseNotification(any(WechatPayCallbackHeaders.class), anyString()))
                .thenThrow(new IllegalStateException("temporary database failure"));

        mockMvc.perform(post("/api/v1/pay/wechat/native/notify")
                        .header("Wechatpay-Serial", "PUB_KEY_ID_TEST")
                        .header("Wechatpay-Signature", "signature")
                        .header("Wechatpay-Timestamp", "1710000000")
                        .header("Wechatpay-Nonce", "nonce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"EV-RETRY\"}"))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value("FAIL"));
    }

    @Test
    void activeMembershipBlocksAnotherPurchaseUntilExpiration() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=membership-active"));
        String userToken = register("membership_active_block");
        String orderResponse = createMembershipOrder(userToken, "WECHAT_NATIVE", "membership-active-first");

        when(wechatNativePayClient.parseNotification(any(WechatPayCallbackHeaders.class), anyString()))
                .thenReturn(new WechatPayNotification(
                        "wx-test", "mch-test", extractOrderNo(orderResponse), "4200000000000000661",
                        "NATIVE", "SUCCESS", 1000, "CNY"
                ));
        postWechatNotify();

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membershipStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.membershipPlan").value("ci_recharge_1000"))
                .andExpect(jsonPath("$.data.membershipStartedAt").exists())
                .andExpect(jsonPath("$.data.membershipExpiresAt").exists())
                .andExpect(jsonPath("$.data.pendingMembershipOrderId").doesNotExist());

        mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "WECHAT_NATIVE",
                                  "clientRequestId": "membership-active-second"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_ACTIVE"))
                .andExpect(jsonPath("$.data.expiresAt").exists());
    }

    @Test
    void expiredMembershipAllowsNewPurchaseAndClearsOnlyMembershipCredits() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=membership-renew"));
        RegisteredUser user = registerUser("membership_expired_renew");
        String orderResponse = createMembershipOrder(user.token(), "WECHAT_NATIVE", "membership-expired-first");

        when(wechatNativePayClient.parseNotification(any(WechatPayCallbackHeaders.class), anyString()))
                .thenReturn(new WechatPayNotification(
                        "wx-test", "mch-test", extractOrderNo(orderResponse), "4200000000000000662",
                        "NATIVE", "SUCCESS", 1000, "CNY"
                ));
        postWechatNotify();
        jdbcTemplate.update("UPDATE user_memberships SET expires_at = ? WHERE user_id = ?",
                LocalDateTime.now().minusMinutes(1), user.userId());

        createMembershipOrder(user.token(), "WECHAT_NATIVE", "membership-expired-second");

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_recharge_orders WHERE user_id = ? AND order_type = 'MEMBERSHIP'",
                Integer.class, user.userId())).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT membership_balance FROM credit_accounts WHERE user_id = ?",
                Integer.class, user.userId())).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT permanent_balance FROM credit_accounts WHERE user_id = ?",
                Integer.class, user.userId())).isEqualTo(200);
    }

    @Test
    void expiredPendingWechatMembershipMustCloseAtChannelBeforeNewOrder() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=membership-channel-close"));
        RegisteredUser user = registerUser("membership_channel_close");
        String first = createMembershipOrder(user.token(), "WECHAT_NATIVE", "membership-close-first");
        Long firstOrderId = extractOrderId(first);
        String firstOrderNo = extractOrderNo(first);
        jdbcTemplate.update("UPDATE credit_recharge_orders SET expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), firstOrderId);
        when(wechatNativePayClient.queryNativeOrder(firstOrderNo))
                .thenReturn(new WechatPayNotification(
                        "wx-test", "mch-test", firstOrderNo, "", "NATIVE", "NOTPAY", 1000, "CNY"));
        when(wechatNativePayClient.closeNativeOrder(firstOrderNo)).thenReturn(true);

        String second = createMembershipOrder(user.token(), "WECHAT_NATIVE", "membership-close-second");

        org.assertj.core.api.Assertions.assertThat(extractOrderId(second)).isNotEqualTo(firstOrderId);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM credit_recharge_orders WHERE id = ?", String.class, firstOrderId))
                .isEqualTo("CLOSED");
        verify(wechatNativePayClient).closeNativeOrder(firstOrderNo);
    }

    @Test
    void expiredPendingAlipayMembershipMustCloseAtChannelBeforeNewOrder() throws Exception {
        when(alipayPagePayClient.createPagePayOrder(any(AlipayPagePayRequest.class)))
                .thenReturn(AlipayPagePayResponse.pageRedirect("/api/v1/pay/alipay/page/launch?orderNo=close-test"));
        RegisteredUser user = registerUser("membership_alipay_channel_close");
        String first = createMembershipOrder(user.token(), "ALIPAY_PAGE", "membership-alipay-close-first");
        Long firstOrderId = extractOrderId(first);
        String firstOrderNo = extractOrderNo(first);
        jdbcTemplate.update("UPDATE credit_recharge_orders SET expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), firstOrderId);
        when(alipayPagePayClient.queryOrder(firstOrderNo))
                .thenReturn(new AlipayTradeQueryResult(firstOrderNo, null, "WAIT_BUYER_PAY", null));
        when(alipayPagePayClient.closeOrder(firstOrderNo)).thenReturn(true);

        String second = createMembershipOrder(user.token(), "ALIPAY_PAGE", "membership-alipay-close-second");

        org.assertj.core.api.Assertions.assertThat(extractOrderId(second)).isNotEqualTo(firstOrderId);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM credit_recharge_orders WHERE id = ?", String.class, firstOrderId))
                .isEqualTo("CLOSED");
        verify(alipayPagePayClient).closeOrder(firstOrderNo);
    }

    @Test
    void mismatchedWechatQueryOrderNumberCannotReleaseMembershipSlot() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=wechat-mismatch"));
        RegisteredUser user = registerUser("membership_wechat_query_mismatch");
        String first = createMembershipOrder(user.token(), "WECHAT_NATIVE", "membership-wechat-mismatch-first");
        Long firstOrderId = extractOrderId(first);
        String firstOrderNo = extractOrderNo(first);
        jdbcTemplate.update("UPDATE credit_recharge_orders SET expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), firstOrderId);
        when(wechatNativePayClient.queryNativeOrder(firstOrderNo))
                .thenReturn(new WechatPayNotification(
                        "wx-test", "mch-test", "OTHER-ORDER", "", "NATIVE", "CLOSED", 1000, "CNY"));

        mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "WECHAT_NATIVE",
                                  "clientRequestId": "membership-wechat-mismatch-second"
                                }
                                """))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM credit_recharge_orders WHERE id = ?", String.class, firstOrderId))
                .isEqualTo("WAITING_PAYMENT");
    }

    @Test
    void mismatchedAlipayQueryOrderNumberCannotReleaseMembershipSlot() throws Exception {
        when(alipayPagePayClient.createPagePayOrder(any(AlipayPagePayRequest.class)))
                .thenReturn(AlipayPagePayResponse.pageRedirect("/api/v1/pay/alipay/page/launch?orderNo=mismatch"));
        RegisteredUser user = registerUser("membership_alipay_query_mismatch");
        String first = createMembershipOrder(user.token(), "ALIPAY_PAGE", "membership-alipay-mismatch-first");
        Long firstOrderId = extractOrderId(first);
        String firstOrderNo = extractOrderNo(first);
        jdbcTemplate.update("UPDATE credit_recharge_orders SET expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), firstOrderId);
        when(alipayPagePayClient.queryOrder(firstOrderNo))
                .thenReturn(new AlipayTradeQueryResult("OTHER-ORDER", null, "TRADE_CLOSED", null));

        mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "ALIPAY_PAGE",
                                  "clientRequestId": "membership-alipay-mismatch-second"
                                }
                                """))
                .andExpect(status().isBadRequest());

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM credit_recharge_orders WHERE id = ?", String.class, firstOrderId))
                .isEqualTo("WAITING_PAYMENT");
    }

    @Test
    void uncertainWechatPrepayFailureKeepsTheMembershipSlot() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenThrow(new com.aiminilab.aitoolmarket.common.exception.BusinessException(
                        com.aiminilab.aitoolmarket.common.enums.ErrorCode.PARAM_ERROR, "gateway timeout"));
        RegisteredUser user = registerUser("membership_wechat_prepay_uncertain");

        mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "WECHAT_NATIVE",
                                  "clientRequestId": "membership-prepay-uncertain-first"
                                }
                                """))
                .andExpect(status().isBadRequest());
        Long firstOrderId = jdbcTemplate.queryForObject(
                "SELECT id FROM credit_recharge_orders WHERE user_id = ?", Long.class, user.userId());

        String retry = createMembershipOrder(
                user.token(), "WECHAT_NATIVE", "membership-prepay-uncertain-second");

        org.assertj.core.api.Assertions.assertThat(extractOrderId(retry)).isEqualTo(firstOrderId);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM credit_recharge_orders WHERE id = ?", String.class, firstOrderId))
                .isEqualTo("WAITING_PAYMENT");
    }

    @Test
    void failedWechatPrepayReleasesMembershipOnlyAfterConfirmedChannelClose() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenThrow(new com.aiminilab.aitoolmarket.common.exception.BusinessException(
                        com.aiminilab.aitoolmarket.common.enums.ErrorCode.PARAM_ERROR, "gateway timeout"))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=prepay-retry"));
        when(wechatNativePayClient.queryNativeOrder(anyString()))
                .thenAnswer(invocation -> new WechatPayNotification(
                        "wx-test", "mch-test", invocation.getArgument(0), "",
                        "NATIVE", "NOTPAY", 1000, "CNY"));
        when(wechatNativePayClient.closeNativeOrder(anyString())).thenReturn(true);
        RegisteredUser user = registerUser("membership_wechat_prepay_closed");

        mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "WECHAT_NATIVE",
                                  "clientRequestId": "membership-prepay-close-first"
                                }
                                """))
                .andExpect(status().isBadRequest());
        Long firstOrderId = jdbcTemplate.queryForObject(
                "SELECT id FROM credit_recharge_orders WHERE user_id = ?", Long.class, user.userId());
        String firstOrderNo = jdbcTemplate.queryForObject(
                "SELECT order_no FROM credit_recharge_orders WHERE id = ?", String.class, firstOrderId);

        String retry = createMembershipOrder(
                user.token(), "WECHAT_NATIVE", "membership-prepay-close-second");

        org.assertj.core.api.Assertions.assertThat(extractOrderId(retry)).isNotEqualTo(firstOrderId);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM credit_recharge_orders WHERE id = ?", String.class, firstOrderId))
                .isEqualTo("CLOSED");
        verify(wechatNativePayClient).closeNativeOrder(firstOrderNo);
    }

    @Test
    void pendingMembershipRejectsDifferentPackage() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=membership-package-conflict"));
        RegisteredUser user = registerUser("membership_pending_package_conflict");
        jdbcTemplate.update("""
                INSERT INTO credit_recharge_packages(
                    package_code, package_name, credits, price_amount, currency, validity_days,
                    benefits_json, recommended, sort_order, status
                ) VALUES (?, ?, 2500, 20.00, 'CNY', 60, '[]', 0, 20, 'ACTIVE')
                """, "ci_recharge_2500_" + user.userId(), "60-day membership");
        Long otherPackageId = jdbcTemplate.queryForObject(
                "SELECT id FROM credit_recharge_packages WHERE package_code = ?", Long.class,
                "ci_recharge_2500_" + user.userId());

        Long pendingOrderId = extractOrderId(
                createMembershipOrder(user.token(), "WECHAT_NATIVE", "membership-package-first"));

        mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": %d,
                                  "paymentChannel": "WECHAT_NATIVE",
                                  "clientRequestId": "membership-package-second"
                                }
                                """.formatted(otherPackageId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBERSHIP_ORDER_PENDING"))
                .andExpect(jsonPath("$.data.orderId").value(pendingOrderId.intValue()));
    }

    @Test
    void concurrentMembershipRequestsCreateOnlyOneOrder() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=membership-concurrent"));
        RegisteredUser user = registerUser("membership_concurrent_devices");

        CompletableFuture<String> deviceA = CompletableFuture.supplyAsync(
                () -> createMembershipOrderUnchecked(user.token(), "membership-concurrent-a"));
        CompletableFuture<String> deviceB = CompletableFuture.supplyAsync(
                () -> createMembershipOrderUnchecked(user.token(), "membership-concurrent-b"));
        String first = deviceA.get(10, TimeUnit.SECONDS);
        String second = deviceB.get(10, TimeUnit.SECONDS);

        assertThatJsonOrderId(second, extractOrderId(first));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_recharge_orders WHERE user_id = ? AND order_type = 'MEMBERSHIP'",
                Integer.class, user.userId())).isEqualTo(1);
        verify(wechatNativePayClient, times(1)).createNativeOrder(any(NativePrepayRequest.class));
    }

    @Test
    void concurrentCustomRechargeWithSameKeyReusesOneOrder() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=custom-concurrent"));
        RegisteredUser user = registerUser("custom_recharge_concurrent");

        CompletableFuture<String> deviceA = CompletableFuture.supplyAsync(
                () -> createCustomOrderUnchecked(user.token(), "custom-concurrent-key"));
        CompletableFuture<String> deviceB = CompletableFuture.supplyAsync(
                () -> createCustomOrderUnchecked(user.token(), "custom-concurrent-key"));
        String first = deviceA.get(10, TimeUnit.SECONDS);
        String second = deviceB.get(10, TimeUnit.SECONDS);

        assertThatJsonOrderId(second, extractOrderId(first));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_recharge_orders WHERE user_id = ? AND order_type = 'CREDITS'",
                Integer.class, user.userId())).isEqualTo(1);
        verify(wechatNativePayClient, times(1)).createNativeOrder(any(NativePrepayRequest.class));
    }

    @Test
    void giftCardFingerprintIgnoresItemOrder() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=gift-fingerprint"));
        RegisteredUser user = registerUser("gift_card_fingerprint_order");

        String first = createGiftCardOrder(user.token(), "gift-fingerprint-key", false);
        String second = createGiftCardOrder(user.token(), "gift-fingerprint-key", true);

        assertThatJsonOrderId(second, extractOrderId(first));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_recharge_orders WHERE user_id = ? AND order_type = 'GIFT_CARD'",
                Integer.class, user.userId())).isEqualTo(1);
    }

    @Test
    void concurrentGiftCardOrderWithSameKeyPersistsOneCompleteOrder() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=gift-concurrent"));
        RegisteredUser user = registerUser("gift_card_order_concurrent");

        CompletableFuture<String> deviceA = CompletableFuture.supplyAsync(
                () -> createGiftCardOrderUnchecked(user.token(), "gift-concurrent-key"));
        CompletableFuture<String> deviceB = CompletableFuture.supplyAsync(
                () -> createGiftCardOrderUnchecked(user.token(), "gift-concurrent-key"));
        String first = deviceA.get(10, TimeUnit.SECONDS);
        String second = deviceB.get(10, TimeUnit.SECONDS);

        Long orderId = extractOrderId(first);
        assertThatJsonOrderId(second, orderId);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_recharge_orders WHERE user_id = ? AND order_type = 'GIFT_CARD'",
                Integer.class, user.userId())).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_recharge_order_items WHERE order_id = ?",
                Integer.class, orderId)).isEqualTo(2);
        verify(wechatNativePayClient, times(1)).createNativeOrder(any(NativePrepayRequest.class));
    }

    @Test
    void validInviteCodeGrantsFixedRegistrationRewardsOnceWithoutRechargeCommission() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=referral"));
        RegisteredUser inviter = registerUser("referral_inviter");
        String inviteCode = "WLCLOUD%05d".formatted(inviter.userId());
        RegisteredUser invitee = registerUser("referral_invitee", inviteCode);

        mockMvc.perform(get("/api/v1/credits/account")
                        .header("Authorization", "Bearer " + inviter.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(300))
                .andExpect(jsonPath("$.data.totalGranted").value(300));
        mockMvc.perform(get("/api/v1/credits/account")
                        .header("Authorization", "Bearer " + invitee.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(400))
                .andExpect(jsonPath("$.data.totalGranted").value(400));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM referral_registration_rewards WHERE referral_id = "
                        + "(SELECT id FROM user_referrals WHERE invitee_user_id = ?)",
                Integer.class, invitee.userId())).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT reward_credits FROM referral_registration_rewards WHERE referral_id = "
                        + "(SELECT id FROM user_referrals WHERE invitee_user_id = ?) AND beneficiary_role = 'INVITEE'",
                Integer.class, invitee.userId())).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT reward_credits FROM referral_registration_rewards WHERE referral_id = "
                        + "(SELECT id FROM user_referrals WHERE invitee_user_id = ?) AND beneficiary_role = 'INVITER'",
                Integer.class, invitee.userId())).isEqualTo(100);

        referralService.bindInviteCode(invitee.userId(), inviteCode);

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM credit_accounts WHERE user_id = ?", Integer.class, inviter.userId()))
                .isEqualTo(300);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT balance FROM credit_accounts WHERE user_id = ?", Integer.class, invitee.userId()))
                .isEqualTo(400);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM referral_registration_rewards WHERE referral_id = "
                        + "(SELECT id FROM user_referrals WHERE invitee_user_id = ?)",
                Integer.class, invitee.userId())).isEqualTo(2);

        String orderResponse = mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + invitee.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "WECHAT_NATIVE",
                                  "clientRequestId": "referral-recharge-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.credits").value(1000))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String orderNo = orderResponse.replaceAll("(?s).*\\\"orderNo\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");

        when(wechatNativePayClient.parseNotification(any(WechatPayCallbackHeaders.class), anyString()))
                .thenReturn(new WechatPayNotification(
                        "wx-test",
                        "mch-test",
                        orderNo,
                        "4200000000000000777",
                        "NATIVE",
                        "SUCCESS",
                        1000,
                        "CNY"
                ));
        postWechatNotify();
        postWechatNotify();

        mockMvc.perform(get("/api/v1/credits/account")
                        .header("Authorization", "Bearer " + inviter.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(300))
                .andExpect(jsonPath("$.data.totalGranted").value(300));
        mockMvc.perform(get("/api/v1/credits/account")
                        .header("Authorization", "Bearer " + invitee.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(1400))
                .andExpect(jsonPath("$.data.totalGranted").value(1400));

        mockMvc.perform(get("/api/v1/credits/logs")
                        .param("logType", "REFERRAL_BONUS")
                        .header("Authorization", "Bearer " + inviter.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].amount").value(100))
                .andExpect(jsonPath("$.data.list[0].reason").value(org.hamcrest.Matchers.containsString("邀请码")));
        mockMvc.perform(get("/api/v1/credits/logs")
                        .param("logType", "REFERRAL_BONUS")
                        .header("Authorization", "Bearer " + invitee.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].amount").value(200))
                .andExpect(jsonPath("$.data.list[0].reason").value(org.hamcrest.Matchers.containsString("邀请码")));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM referral_rewards r JOIN credit_recharge_orders o "
                        + "ON o.id = r.recharge_order_id WHERE o.order_no = ?",
                Integer.class, orderNo)).isZero();
    }

    @Test
    void referralRewardFailureRollsBackUserBindingAndFirstBenefit() throws Exception {
        RegisteredUser inviter = registerUser("referral_tx_inviter");
        String inviteCode = "WLCLOUD%05d".formatted(inviter.userId());
        String failedUsername = "referral_tx_rollback_invitee";
        int rewardCountBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM referral_registration_rewards", Integer.class);
        int registrationLogCountBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE idempotency_key LIKE 'REFERRAL_REGISTRATION:%'",
                Integer.class);
        int accountCountBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM credit_accounts", Integer.class);
        doThrow(new IllegalStateException("forced inviter reward failure"))
                .when(referralRegistrationRewardMapper)
                .insert(org.mockito.ArgumentMatchers.<ReferralRegistrationReward>argThat(
                        reward -> "INVITER".equals(reward.getBeneficiaryRole())));

        try {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "username": "%s",
                                      "password": "123456",
                                      "nickname": "%s",
                                      "inviteCode": "%s"
                                    }
                                    """.formatted(failedUsername, failedUsername, inviteCode)))
                    .andExpect(status().is5xxServerError());
        } finally {
            reset(referralRegistrationRewardMapper);
        }

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, failedUsername)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_referrals WHERE inviter_user_id = ? AND invite_code = ?",
                Integer.class, inviter.userId(), inviteCode)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM referral_registration_rewards rr JOIN user_referrals r "
                        + "ON r.id = rr.referral_id WHERE r.inviter_user_id = ? AND r.invite_code = ?",
                Integer.class, inviter.userId(), inviteCode)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM referral_registration_rewards", Integer.class)).isEqualTo(rewardCountBefore);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE idempotency_key LIKE 'REFERRAL_REGISTRATION:%'",
                Integer.class)).isEqualTo(registrationLogCountBefore);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_accounts", Integer.class)).isEqualTo(accountCountBefore);
    }

    @Test
    void userCanCreateRechargeOrderAndGrantCreditsIdempotently() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=recharge-idem"));
        String userToken = register("recharge_user");

        mockMvc.perform(get("/api/v1/credits/recharge-packages")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].credits").value(1000))
                .andExpect(jsonPath("$.data[0].priceAmount").value(10.00));

        String orderResponse = mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "WECHAT_NATIVE",
                                  "clientRequestId": "recharge-idem-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_PAYMENT"))
                .andExpect(jsonPath("$.data.credits").value(1000))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long orderId = Long.parseLong(orderResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        String orderNo = orderResponse.replaceAll("(?s).*\\\"orderNo\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "WECHAT_NATIVE",
                                  "clientRequestId": "recharge-idem-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orderId.intValue()));

        when(wechatNativePayClient.parseNotification(any(WechatPayCallbackHeaders.class), anyString()))
                .thenReturn(new WechatPayNotification(
                        "wx-test",
                        "mch-test",
                        orderNo,
                        "4200000000000000999",
                        "NATIVE",
                        "SUCCESS",
                        1000,
                        "CNY"
                ));
        postWechatNotify();
        postWechatNotify();

        mockMvc.perform(get("/api/v1/credits/recharge-orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREDITED"))
                .andExpect(jsonPath("$.data.paidAt").exists())
                .andExpect(jsonPath("$.data.creditedAt").exists());

        mockMvc.perform(get("/api/v1/credits/account")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(1200))
                .andExpect(jsonPath("$.data.totalGranted").value(1200));

        mockMvc.perform(get("/api/v1/credits/logs")
                        .param("logType", "RECHARGE")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].amount").value(1000));
    }

    @Test
    void mockPaymentChannelIsRejected() throws Exception {
        String userToken = register("mock_channel_rejected_user");

        mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "MOCK",
                                  "clientRequestId": "mock-channel-rejected-001"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("mock payment is not supported")));
    }

    @Test
    void wechatNativeRechargeUsesCallbackToCreditIdempotently() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=test"));
        String userToken = register("wechat_recharge_user");

        String orderResponse = mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "WECHAT_NATIVE",
                                  "clientRequestId": "wechat-native-idem-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentChannel").value("WECHAT_NATIVE"))
                .andExpect(jsonPath("$.data.payUrl").value("weixin://pay.weixin.qq.com/bizpayurl/up?pr=test"))
                .andExpect(jsonPath("$.data.qrCodeUrl").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long orderId = Long.parseLong(orderResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        String orderNo = orderResponse.replaceAll("(?s).*\\\"orderNo\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");

        when(wechatNativePayClient.parseNotification(any(WechatPayCallbackHeaders.class), anyString()))
                .thenReturn(new WechatPayNotification(
                        "wx-test",
                        "mch-test",
                        orderNo,
                        "4200000000000000001",
                        "NATIVE",
                        "SUCCESS",
                        1000,
                        "CNY"
                ));

        postWechatNotify();
        postWechatNotify();

        mockMvc.perform(get("/api/v1/credits/recharge-orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREDITED"))
                .andExpect(jsonPath("$.data.paidAt").exists())
                .andExpect(jsonPath("$.data.creditedAt").exists());

        mockMvc.perform(get("/api/v1/credits/logs")
                        .param("logType", "RECHARGE")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].amount").value(1000));
    }

    @Test
    void alipayPageRechargeUsesNotifyToCreditIdempotently() throws Exception {
        when(alipayPagePayClient.createPagePayOrder(any(AlipayPagePayRequest.class)))
                .thenReturn(AlipayPagePayResponse.pageRedirect("/api/v1/pay/alipay/page/launch?orderNo=R202606050002"));
        String userToken = register("alipay_recharge_user");

        String orderResponse = mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "ALIPAY_PAGE",
                                  "clientRequestId": "alipay-page-idem-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentChannel").value("ALIPAY_PAGE"))
                .andExpect(jsonPath("$.data.payUrl").value("/api/v1/pay/alipay/page/launch?orderNo=R202606050002"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long orderId = Long.parseLong(orderResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        String orderNo = orderResponse.replaceAll("(?s).*\\\"orderNo\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");

        when(alipayPagePayClient.parseNotification(any()))
                .thenReturn(new AlipayNotification("alipay-test-app", orderNo, "2026052800000000001",
                        "TRADE_SUCCESS", new java.math.BigDecimal("10.00")));

        postAlipayNotify();
        postAlipayNotify();

        mockMvc.perform(get("/api/v1/credits/recharge-orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREDITED"))
                .andExpect(jsonPath("$.data.paidAt").exists())
                .andExpect(jsonPath("$.data.creditedAt").exists());

        mockMvc.perform(get("/api/v1/credits/logs")
                        .param("logType", "RECHARGE")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void giftCardBatchOrderCreatesEverySelectedCardAfterPayment() throws Exception {
        when(wechatNativePayClient.createNativeOrder(any(NativePrepayRequest.class)))
                .thenReturn(new NativePrepayResponse("weixin://pay.weixin.qq.com/bizpayurl/up?pr=giftcards"));
        String userToken = register("gift_card_batch_user");

        String orderResponse = mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": null,
                                  "paymentChannel": "WECHAT_NATIVE",
                                  "clientRequestId": "gift-card-batch-001",
                                  "orderType": "GIFT_CARD",
                                  "giftCardPackageId": 1,
                                  "giftCardItems": [
                                    { "giftCardPackageId": 1, "quantity": 2 },
                                    { "giftCardPackageId": 2, "quantity": 1 }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderType").value("GIFT_CARD"))
                .andExpect(jsonPath("$.data.credits").value(900))
                .andExpect(jsonPath("$.data.priceAmount").value(17.90))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long orderId = Long.parseLong(orderResponse.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        String orderNo = orderResponse.replaceAll("(?s).*\\\"orderNo\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");

        when(wechatNativePayClient.parseNotification(any(WechatPayCallbackHeaders.class), anyString()))
                .thenReturn(new WechatPayNotification(
                        "wx-test",
                        "mch-test",
                        orderNo,
                        "4200000000000000666",
                        "NATIVE",
                        "SUCCESS",
                        1790,
                        "CNY"
                ));
        postWechatNotify();
        postWechatNotify();

        mockMvc.perform(get("/api/v1/credits/recharge-orders/{orderId}", orderId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREDITED"));

        mockMvc.perform(get("/api/v1/credits/gift-cards")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[*].credits").value(org.hamcrest.Matchers.containsInAnyOrder(200, 200, 500)));
    }

    @Test
    void rechargePaymentOptionsExposeConfiguredChannels() throws Exception {
        String userToken = register("payment_options_user");

        mockMvc.perform(get("/api/v1/credits/recharge-payment-options")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.wechatNativeEnabled").value(true))
                .andExpect(jsonPath("$.data.alipayEnabled").value(true))
                .andExpect(jsonPath("$.data.alipayPayMode").value("PAGE"));
    }

    @Test
    void alipayPageRechargeCanReturnCheckoutLaunchWithoutQrCode() throws Exception {
        when(alipayPagePayClient.createPagePayOrder(any(AlipayPagePayRequest.class)))
                .thenReturn(AlipayPagePayResponse.pageRedirect("/api/v1/pay/alipay/page/launch?orderNo=R202606050001"));
        String userToken = register("alipay_checkout_user");

        mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "ALIPAY_PAGE",
                                  "clientRequestId": "alipay-page-checkout-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentChannel").value("ALIPAY_PAGE"))
                .andExpect(jsonPath("$.data.payUrl").value("/api/v1/pay/alipay/page/launch?orderNo=R202606050001"))
                .andExpect(jsonPath("$.data.qrCodeUrl").doesNotExist());
    }

    private String createMembershipOrder(String token, String channel, String clientRequestId) throws Exception {
        return mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "%s",
                                  "clientRequestId": "%s"
                                }
                                """.formatted(channel, clientRequestId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String createMembershipOrderUnchecked(String token, String clientRequestId) {
        try {
            return createMembershipOrder(token, "WECHAT_NATIVE", clientRequestId);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String createCustomOrderUnchecked(String token, String clientRequestId) {
        try {
            return mockMvc.perform(post("/api/v1/credits/recharge-orders/custom")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "amount": 10.00,
                                      "paymentChannel": "WECHAT_NATIVE",
                                      "clientRequestId": "%s"
                                    }
                                    """.formatted(clientRequestId)))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String createGiftCardOrder(String token, String clientRequestId, boolean reverseItems) throws Exception {
        String items = reverseItems
                ? "[{\"giftCardPackageId\":2,\"quantity\":1},{\"giftCardPackageId\":1,\"quantity\":2}]"
                : "[{\"giftCardPackageId\":1,\"quantity\":2},{\"giftCardPackageId\":2,\"quantity\":1}]";
        return mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderType": "GIFT_CARD",
                                  "giftCardItems": %s,
                                  "paymentChannel": "WECHAT_NATIVE",
                                  "clientRequestId": "%s"
                                }
                                """.formatted(items, clientRequestId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String createGiftCardOrderUnchecked(String token, String clientRequestId) {
        try {
            return createGiftCardOrder(token, clientRequestId, false);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Long extractOrderId(String response) {
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private String extractOrderNo(String response) {
        return response.replaceAll("(?s).*\\\"orderNo\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");
    }

    private void assertThatJsonOrderId(String response, Long expectedOrderId) {
        org.assertj.core.api.Assertions.assertThat(extractOrderId(response)).isEqualTo(expectedOrderId);
    }

    private void postWechatNotify() throws Exception {
        mockMvc.perform(post("/api/v1/pay/wechat/native/notify")
                        .header("Wechatpay-Serial", "PUB_KEY_ID_TEST")
                        .header("Wechatpay-Signature", "signature")
                        .header("Wechatpay-Timestamp", "1710000000")
                        .header("Wechatpay-Nonce", "nonce")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"EV-TEST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    private void postAlipayNotify() throws Exception {
        mockMvc.perform(post("/api/v1/pay/alipay/page/notify")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("out_trade_no", "ignored-by-mock")
                        .param("trade_no", "ignored-by-mock")
                        .param("trade_status", "TRADE_SUCCESS")
                        .param("total_amount", "10.00")
                        .param("sign", "signature"))
                .andExpect(status().isOk())
                .andExpect(content().string("success"));
    }

    private String register(String username) throws Exception {
        return registerUser(username).token();
    }

    private RegisteredUser registerUser(String username) throws Exception {
        return registerUser(username, null);
    }

    private RegisteredUser registerUser(String username, String inviteCode) throws Exception {
        String inviteField = inviteCode == null ? "" : ",\n                                  \"inviteCode\": \"%s\"".formatted(inviteCode);
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "123456",
                                  "nickname": "%s"
                                  %s
                                }
                                """.formatted(username, username, inviteField)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = response.replaceAll("(?s).*\\\"accessToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");
        Long userId = Long.parseLong(response.replaceAll("(?s).*\\\"user\\\"\\s*:\\s*\\{\\s*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
        return new RegisteredUser(token, userId);
    }

    private record RegisteredUser(String token, Long userId) {
    }
}
