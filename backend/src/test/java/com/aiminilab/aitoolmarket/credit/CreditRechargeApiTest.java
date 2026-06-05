package com.aiminilab.aitoolmarket.credit;

import com.aiminilab.aitoolmarket.credit.alipay.AlipayNotification;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayClient;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayRequest;
import com.aiminilab.aitoolmarket.credit.alipay.AlipayPagePayResponse;
import com.aiminilab.aitoolmarket.credit.wechat.NativePrepayRequest;
import com.aiminilab.aitoolmarket.credit.wechat.NativePrepayResponse;
import com.aiminilab.aitoolmarket.credit.wechat.WechatNativePayClient;
import com.aiminilab.aitoolmarket.credit.wechat.WechatPayCallbackHeaders;
import com.aiminilab.aitoolmarket.credit.wechat.WechatPayNotification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        "app.payment.wechat-native.appid=wx-test",
        "app.payment.wechat-native.mchid=mch-test",
        "app.payment.alipay-page.app-id=alipay-test-app"
})
class CreditRechargeApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WechatNativePayClient wechatNativePayClient;

    @MockBean
    private AlipayPagePayClient alipayPagePayClient;

    @Test
    void userCanCreateRechargeOrderAndGrantCreditsIdempotently() throws Exception {
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
                                  "paymentChannel": "mock",
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

        mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "mock",
                                  "clientRequestId": "recharge-idem-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(orderId.intValue()));

        mockMvc.perform(post("/api/v1/credits/recharge-orders/{orderId}/mock-pay-success", orderId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREDITED"))
                .andExpect(jsonPath("$.data.paidAt").exists())
                .andExpect(jsonPath("$.data.creditedAt").exists());

        mockMvc.perform(post("/api/v1/credits/recharge-orders/{orderId}/mock-pay-success", orderId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREDITED"));

        mockMvc.perform(get("/api/v1/credits/account")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(1100))
                .andExpect(jsonPath("$.data.totalGranted").value(1100));

        mockMvc.perform(get("/api/v1/credits/logs")
                        .param("logType", "RECHARGE")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].amount").value(1000));
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
    void mockRechargeNotificationCreditsOnceAndRejectsAmountMismatch() throws Exception {
        String userToken = register("mock_notify_recharge_user");

        String orderResponse = mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "MOCK",
                                  "clientRequestId": "mock-notify-idem-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentChannel").value("MOCK"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String orderNo = orderResponse.replaceAll("(?s).*\\\"orderNo\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(post("/api/v1/pay/mock/notify")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("order_no", orderNo)
                        .param("external_trade_no", "MOCK-TX-001")
                        .param("trade_status", "SUCCESS")
                        .param("total_amount", "9.99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("FAIL"));

        postMockNotify(orderNo);
        postMockNotify(orderNo);

        mockMvc.perform(get("/api/v1/credits/account")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(1100));

        mockMvc.perform(get("/api/v1/credits/logs")
                        .param("logType", "RECHARGE")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void alipayPageRechargeUsesNotifyToCreditIdempotently() throws Exception {
        when(alipayPagePayClient.createPagePayOrder(any(AlipayPagePayRequest.class)))
                .thenReturn(AlipayPagePayResponse.qr("https://qr.alipay.com/bax-test"));
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
                .andExpect(jsonPath("$.data.payUrl").value("https://qr.alipay.com/bax-test"))
                .andExpect(jsonPath("$.data.qrCodeUrl").isNotEmpty())
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
    void alipayPageRechargeAlwaysReturnsQrCodeForScannerPayment() throws Exception {
        when(alipayPagePayClient.createPagePayOrder(any(AlipayPagePayRequest.class)))
                .thenReturn(AlipayPagePayResponse.qr("https://qr.alipay.com/bax-scanner-only"));
        String userToken = register("alipay_scanner_user");

        mockMvc.perform(post("/api/v1/credits/recharge-orders")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "packageId": 1,
                                  "paymentChannel": "ALIPAY_PAGE",
                                  "clientRequestId": "alipay-page-scanner-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentChannel").value("ALIPAY_PAGE"))
                .andExpect(jsonPath("$.data.payUrl").value("https://qr.alipay.com/bax-scanner-only"))
                .andExpect(jsonPath("$.data.qrCodeUrl").value(org.hamcrest.Matchers.startsWith("data:image/svg+xml")));
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

    private void postMockNotify(String orderNo) throws Exception {
        mockMvc.perform(post("/api/v1/pay/mock/notify")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("order_no", orderNo)
                        .param("external_trade_no", "MOCK-TX-001")
                        .param("trade_status", "SUCCESS")
                        .param("total_amount", "10.00"))
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
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "123456",
                                  "nickname": "%s"
                                }
                                """.formatted(username, username)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return response.replaceAll("(?s).*\\\"accessToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");
    }
}
