package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.aiminilab.aitoolmarket.testsupport.InternalApiTestSupport.signed;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:worker_internal_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class WorkerInternalApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void workerCanReadContextMarkProcessingAndWriteSuccessResult() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "worker_copywriting", 10);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "worker_copywriting");

        mockMvc.perform(signed(get("/api/internal/v1/tasks/{taskId}/execution-context", taskId), "GET",
                        "/api/internal/v1/tasks/%d/execution-context".formatted(taskId), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.taskId").value(taskId.intValue()))
                .andExpect(jsonPath("$.data.toolCode").value("worker_copywriting"))
                .andExpect(jsonPath("$.data.params.productName").value("Worker Test Product"))
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("productName"));

        String processingBody = """
                                {
                                  "progress": 35,
                                  "progressMessage": "AI is generating"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/processing", taskId), "POST",
                        "/api/internal/v1/tasks/%d/processing".formatted(taskId), processingBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processingBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.progress").value(35));

        String successBody = """
                                {
                                  "resourceType": "MARKDOWN",
                                  "contentText": "# Generated result",
                                  "promptTokens": 120,
                                  "completionTokens": 35
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/success", taskId), "POST",
                        "/api/internal/v1/tasks/%d/success".formatted(taskId), successBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.progress").value(100));

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.result.resourceType").value("MARKDOWN"))
                .andExpect(jsonPath("$.data.result.contentText").value("# Generated result"));

        mockMvc.perform(get("/api/admin/v1/billing/usage-logs")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageNo", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].sourceType").value("TASK"))
                .andExpect(jsonPath("$.data.list[0].sourceId").value(taskId.intValue()))
                .andExpect(jsonPath("$.data.list[0].promptTokens").value(120))
                .andExpect(jsonPath("$.data.list[0].completionTokens").value(35))
                .andExpect(jsonPath("$.data.list[0].totalTokens").value(155))
                .andExpect(jsonPath("$.data.list[0].chargedCredits").value(10));
    }

    @Test
    void workerCanWriteFailedStatus() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "worker_failed_tool", 1);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "worker_failed_tool");

        String processingBody = """
                                {
                                  "progress": 35,
                                  "progressMessage": "AI is generating"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/processing", taskId), "POST",
                        "/api/internal/v1/tasks/%d/processing".formatted(taskId), processingBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processingBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        String failedBody = """
                                {
                                  "errorCode": "MODEL_CALL_FAILED",
                                  "errorMessage": "model timeout"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/failed", taskId), "POST",
                        "/api/internal/v1/tasks/%d/failed".formatted(taskId), failedBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.progress").value(100))
                .andExpect(jsonPath("$.data.progressMessage").value("model timeout"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.result").doesNotExist());
    }

    @Test
    void agentServiceCanCreateAndReadTaskThroughInternalApi() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "agent_internal_task_tool", 1);
        publishTool(adminToken, toolId);

        String createBody = """
                            {
                              "userId": 1,
                              "toolCode": "agent_internal_task_tool",
                              "params": {
                                "productName": "Agent Product",
                                "targetCustomer": "Young users",
                                "style": "planting"
                              },
                              "clientRequestId": "agent-run-1-tool-call-1"
                            }
                            """;
        String response = mockMvc.perform(signed(post("/api/internal/v1/tasks"), "POST",
                        "/api/internal/v1/tasks", createBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long taskId = Long.parseLong(response.replaceAll("(?s).*\\\"taskId\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(signed(get("/api/internal/v1/tasks/{taskId}", taskId)
                                .queryParam("userId", "1"),
                        "GET", "/api/internal/v1/tasks/%d".formatted(taskId), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(taskId.intValue()))
                .andExpect(jsonPath("$.data.toolCode").value("agent_internal_task_tool"))
                .andExpect(jsonPath("$.data.params.productName").value("Agent Product"));
    }

    @Test
    void workerInternalApiRejectsMissingOrWrongInternalToken() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "worker_token_tool", 1);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "worker_token_tool");

        mockMvc.perform(get("/api/internal/v1/tasks/{taskId}/execution-context", taskId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/internal/v1/tasks/{taskId}/execution-context", taskId)
                        .header("X-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
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
                                  "description": "Worker test tool",
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
                                    "productName": "Worker Test Product",
                                    "targetCustomer": "Young users",
                                    "style": "planting"
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
