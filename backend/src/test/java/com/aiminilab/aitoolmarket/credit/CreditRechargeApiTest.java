package com.aiminilab.aitoolmarket.credit;

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

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:credit_recharge_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class CreditRechargeApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WechatNativePayClient wechatNativePayClient;

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
