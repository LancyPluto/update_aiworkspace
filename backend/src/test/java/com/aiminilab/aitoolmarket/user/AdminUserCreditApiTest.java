package com.aiminilab.aitoolmarket.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_user_credit_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class AdminUserCreditApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void taskCreditsFreezeSettleAndReleaseAcrossTaskLifecycle() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "credit_lifecycle_tool", 10);
        publishTool(adminToken, toolId);
        String userToken = register("credit_lifecycle_user");

        Long successTaskId = createTask(userToken, "credit_lifecycle_tool", "credit-life-success");

        mockMvc.perform(get("/api/v1/credits/account")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(100))
                .andExpect(jsonPath("$.data.frozen").value(10))
                .andExpect(jsonPath("$.data.available").value(90))
                .andExpect(jsonPath("$.data.totalConsumed").value(0));

        mockMvc.perform(post("/api/internal/v1/tasks/{taskId}/success", successTaskId)
                        .header("X-Internal-Token", "local-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "resourceType": "MARKDOWN",
                                  "contentText": "ok"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/credits/account")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(90))
                .andExpect(jsonPath("$.data.frozen").value(0))
                .andExpect(jsonPath("$.data.available").value(90))
                .andExpect(jsonPath("$.data.totalConsumed").value(10));

        Long failedTaskId = createTask(userToken, "credit_lifecycle_tool", "credit-life-failed");

        mockMvc.perform(post("/api/internal/v1/tasks/{taskId}/failed", failedTaskId)
                        .header("X-Internal-Token", "local-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "errorCode": "MODEL_CALL_FAILED",
                                  "errorMessage": "model timeout"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));

        mockMvc.perform(get("/api/v1/credits/account")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(90))
                .andExpect(jsonPath("$.data.frozen").value(0))
                .andExpect(jsonPath("$.data.available").value(90));
    }

    @Test
    void adminCanManageUsersAndCredits() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");

        mockMvc.perform(get("/api/admin/v1/users")
                        .param("keyword", "user1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].username").value("user1"))
                .andExpect(jsonPath("$.data.list[0].creditAccount.balance").value(100));

        mockMvc.perform(get("/api/admin/v1/users/{userId}", 2)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("user1"))
                .andExpect(jsonPath("$.data.creditAccount.available").value(100));

        mockMvc.perform(post("/api/admin/v1/users/{userId}/credits/manual-add", 2)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 50,
                                  "reason": "test grant"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(150))
                .andExpect(jsonPath("$.data.totalGranted").value(150));

        mockMvc.perform(post("/api/admin/v1/users/{userId}/credits/manual-deduct", 2)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 20,
                                  "reason": "test adjust"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(130));

        mockMvc.perform(get("/api/admin/v1/users/{userId}/credits/logs", 2)
                        .param("logType", "MANUAL_ADD")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].reason").value("test grant"));

        mockMvc.perform(patch("/api/admin/v1/users/{userId}/status", 2)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "DISABLED",
                                  "reason": "test disable"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "user1",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private String login(String path, String account) throws Exception {
        String response = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "%s",
                                  "password": "123456"
                                }
                                """.formatted(account)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return response.replaceAll("(?s).*\\\"accessToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");
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

    private Long createTool(String adminToken, String toolCode, int estimatedCreditCost) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "%s",
                                  "toolName": "%s",
                                  "categoryId": 1,
                                  "description": "Credit lifecycle tool",
                                  "coverUrl": "",
                                  "estimatedCreditCost": %d
                                }
                                """.formatted(toolCode, toolCode, estimatedCreditCost)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private void publishTool(String adminToken, Long toolId) throws Exception {
        mockMvc.perform(post("/api/admin/v1/tools/{toolId}/publish", toolId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    private Long createTask(String userToken, String toolCode, String clientRequestId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "%s",
                                  "params": {
                                    "productName": "Credit Test Product"
                                  },
                                  "clientRequestId": "%s"
                                }
                                """.formatted(toolCode, clientRequestId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"taskId\\\"\\s*:\\s*(\\d+).*", "$1"));
    }
}
