package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:task_credit_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class TaskCreditApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void userCanCreateTaskAndQueryCreditsStatusDetailAndList() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "task_copywriting", 10);
        publishTool(adminToken, toolId);

        String userToken = login("/api/v1/auth/login", "user1");

        mockMvc.perform(get("/api/v1/credits/account")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.balance").value(100))
                .andExpect(jsonPath("$.data.totalGranted").value(100))
                .andExpect(jsonPath("$.data.totalConsumed").value(0));

        String taskResponse = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "task_copywriting",
                                  "params": {
                                    "productName": "Test Product",
                                    "targetCustomer": "Young users",
                                    "style": "planting"
                                  },
                                  "clientRequestId": "task-credit-test-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.taskId").exists())
                .andExpect(jsonPath("$.data.taskNo", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.progress").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String taskId = taskResponse.replaceAll("(?s).*\\\"taskId\\\"\\s*:\\s*(\\d+).*", "$1");

        mockMvc.perform(get("/api/v1/credits/account")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(100))
                .andExpect(jsonPath("$.data.frozen").value(10))
                .andExpect(jsonPath("$.data.available").value(90))
                .andExpect(jsonPath("$.data.totalConsumed").value(0));

        mockMvc.perform(get("/api/v1/tasks/{taskId}/status", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(Integer.parseInt(taskId)))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.progress").value(0));

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(Integer.parseInt(taskId)))
                .andExpect(jsonPath("$.data.toolCode").value("task_copywriting"))
                .andExpect(jsonPath("$.data.params.productName").value("Test Product"))
                .andExpect(jsonPath("$.data.result").doesNotExist());

        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].taskId").value(Integer.parseInt(taskId)))
                .andExpect(jsonPath("$.data.list[0].toolCode").value("task_copywriting"));
    }

    @Test
    void rejectsTaskCreationWhenCreditIsNotEnough() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "expensive_tool", 101);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "expensive_tool",
                                  "params": {
                                    "productName": "Test Product"
                                  },
                                  "clientRequestId": "task-credit-test-002"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDIT_NOT_ENOUGH"));
    }

    @Test
    void userCanFilterCancelAndRegenerateOwnTasks() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "user_task_ops_tool", 5);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");

        Long taskId = createTask(userToken, "user_task_ops_tool", "first-request", "Original Product");

        mockMvc.perform(get("/api/v1/tasks")
                        .param("status", "QUEUED")
                        .param("toolCode", "user_task_ops_tool")
                        .param("pageNo", "1")
                        .param("pageSize", "10")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNo").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.list[0].taskId").value(taskId.intValue()));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/cancel", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.progress").value(100));

        String regenerateResponse = mockMvc.perform(post("/api/v1/tasks/{taskId}/regenerate", taskId)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "params": {
                                    "productName": "Regenerated Product",
                                    "targetCustomer": "Young users",
                                    "style": "review"
                                  },
                                  "clientRequestId": "regenerate-request-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long regeneratedTaskId = Long.parseLong(regenerateResponse.replaceAll("(?s).*\\\"taskId\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}", regeneratedTaskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.params.productName").value("Regenerated Product"));
    }

    private String login(String path, String account) throws Exception {
        var result = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "%s",
                                  "password": "123456"
                                }
                                """.formatted(account)))
                .andExpect(status().isOk())
                .andReturn();
        if (path.contains("/admin/")) {
            return AuthTestTokens.adminJwtFrom(result);
        }
        return AuthTestTokens.userJwtFrom(result);
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
                                  "description": "Task test tool",
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

    private Long createTask(String userToken, String toolCode, String clientRequestId, String productName) throws Exception {
        String response = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "%s",
                                  "params": {
                                    "productName": "%s",
                                    "targetCustomer": "Young users",
                                    "style": "planting"
                                  },
                                  "clientRequestId": "%s"
                                }
                                """.formatted(toolCode, productName, clientRequestId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"taskId\\\"\\s*:\\s*(\\d+).*", "$1"));
    }
}
