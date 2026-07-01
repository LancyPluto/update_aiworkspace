package com.aiminilab.aitoolmarket.task;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.aiminilab.aitoolmarket.testsupport.InternalApiTestSupport.signed;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:worker_callback_idempotency_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class WorkerCallbackIdempotencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void repeatedSuccessCallbackDeductsCreditsAndInsertsResultOnce() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "idempotent_success_tool", 10);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "idempotent_success_tool", "idempotent-success-request");
        markProcessing(taskId);

        markSuccess(taskId, "# First result")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        markSuccess(taskId, "# Duplicate result")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        org.assertj.core.api.Assertions.assertThat(countCreditLogs(taskId, "DEDUCT")).isEqualTo(1);

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.contentText").value("# First result"));
    }

    @Test
    void repeatedFailedCallbackReleasesCreditsOnceAndKeepsCurrentFailure() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "idempotent_failed_tool", 7);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "idempotent_failed_tool", "idempotent-failed-request");
        markProcessing(taskId);

        markFailed(taskId, "first timeout")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.progressMessage").value("模型调用失败，请稍后重试"));

        markFailed(taskId, "duplicate timeout")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.progressMessage").value("模型调用失败，请稍后重试"));

        org.assertj.core.api.Assertions.assertThat(countCreditLogs(taskId, "RELEASE")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(countCreditLogs(taskId, "DEDUCT")).isZero();
    }

    @Test
    void cancelThenSuccessCallbackReturnsCancelledAndDoesNotDeductCredits() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "cancel_then_success_tool", 6);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "cancel_then_success_tool", "cancel-then-success-request");

        mockMvc.perform(post("/api/v1/tasks/{taskId}/cancel", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        markSuccess(taskId, "# Late worker result")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        org.assertj.core.api.Assertions.assertThat(countCreditLogs(taskId, "RELEASE")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(countCreditLogs(taskId, "DEDUCT")).isZero();

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.result").doesNotExist());
    }

    @Test
    void cancelThenFailedCallbackReturnsCancelledAndDoesNotReleaseTwice() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "cancel_then_failed_tool", 6);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "cancel_then_failed_tool", "cancel-then-failed-request");

        mockMvc.perform(post("/api/v1/tasks/{taskId}/cancel", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        markFailed(taskId, "late worker failure")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        org.assertj.core.api.Assertions.assertThat(countCreditLogs(taskId, "RELEASE")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(countCreditLogs(taskId, "DEDUCT")).isZero();
    }

    @Test
    void adminRetryFromFailedReturnsTaskToQueued() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "retry_failed_tool", 3);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "retry_failed_tool", "retry-failed-request");
        markProcessing(taskId);
        markFailed(taskId, "retry me").andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/v1/tasks/{taskId}/retry", taskId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.progress").value(0));
    }

    @Test
    void invalidAdminRetryTransitionReturnsBusinessError() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "retry_queued_tool", 2);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "retry_queued_tool", "retry-queued-request");

        mockMvc.perform(post("/api/admin/v1/tasks/{taskId}/retry", taskId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_INVALID"));
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

    private int countCreditLogs(Long taskId, String logType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE task_id = ? AND log_type = ?",
                Integer.class,
                taskId,
                logType
        );
        return count == null ? 0 : count;
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
                                  "description": "Worker callback idempotency test tool",
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
                                    "productName": "Callback Product",
                                    "targetCustomer": "Young users",
                                    "style": "planting"
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

    private void markProcessing(Long taskId) throws Exception {
        String body = """
                {
                  "progress": 20,
                  "progressMessage": "started"
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/processing", taskId), "POST",
                        "/api/internal/v1/tasks/%d/processing".formatted(taskId), body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    private org.springframework.test.web.servlet.ResultActions markSuccess(Long taskId, String contentText) throws Exception {
        String body = """
                {
                  "resourceType": "MARKDOWN",
                  "contentText": "%s"
                }
                """.formatted(contentText);
        return mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/success", taskId), "POST",
                        "/api/internal/v1/tasks/%d/success".formatted(taskId), body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions markFailed(Long taskId, String errorMessage) throws Exception {
        String body = """
                {
                  "errorCode": "MODEL_CALL_FAILED",
                  "errorMessage": "%s"
                }
                """.formatted(errorMessage);
        return mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/failed", taskId), "POST",
                        "/api/internal/v1/tasks/%d/failed".formatted(taskId), body)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }
}
