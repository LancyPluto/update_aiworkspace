package com.aiminilab.aitoolmarket.credit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
