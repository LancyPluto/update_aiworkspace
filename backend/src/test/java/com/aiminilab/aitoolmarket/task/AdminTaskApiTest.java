package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin_task_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class AdminTaskApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCanListViewRetryAndCancelTasks() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "admin_task_tool", 1);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "admin_task_tool");

        mockMvc.perform(get("/api/admin/v1/tasks")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].taskId").value(taskId.intValue()))
                .andExpect(jsonPath("$.data.list[0].userId").exists())
                .andExpect(jsonPath("$.data.list[0].status").value("QUEUED"));

        mockMvc.perform(get("/api/admin/v1/tasks")
                        .param("status", "QUEUED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].taskId").value(taskId.intValue()));

        mockMvc.perform(get("/api/admin/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(taskId.intValue()))
                .andExpect(jsonPath("$.data.params.productName").value("Admin Task Product"));

        mockMvc.perform(post("/api/internal/v1/tasks/{taskId}/failed", taskId)
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

        mockMvc.perform(post("/api/admin/v1/tasks/{taskId}/retry", taskId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.progress").value(0));

        mockMvc.perform(post("/api/admin/v1/tasks/{taskId}/cancel", taskId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.progress").value(100));
    }

    @Test
    void userTokenCannotAccessAdminTaskApi() throws Exception {
        String userToken = login("/api/v1/auth/login", "user1");

        mockMvc.perform(get("/api/admin/v1/tasks")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_FORBIDDEN"));
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
                                  "description": "Admin task test tool",
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

    private Long createTask(String userToken, String toolCode) throws Exception {
        String response = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "%s",
                                  "params": {
                                    "productName": "Admin Task Product",
                                    "targetCustomer": "Team users",
                                    "style": "admin test"
                                  },
                                  "clientRequestId": "%s-request"
                                }
                                """.formatted(toolCode, toolCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskNo", not(blankOrNullString())))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"taskId\\\"\\s*:\\s*(\\d+).*", "$1"));
    }
}
