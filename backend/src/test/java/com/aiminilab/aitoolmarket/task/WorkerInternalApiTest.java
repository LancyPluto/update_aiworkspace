package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private CreditService creditService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                        .param("pageSize", "10")
                        .param("userId", "2")
                        .param("sourceType", "TASK")
                        .param("sourceId", taskId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].sourceType").value("TASK"))
                .andExpect(jsonPath("$.data.list[0].sourceId").value(taskId.intValue()))
                .andExpect(jsonPath("$.data.list[0].taskNo", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.list[0].promptTokens").value(120))
                .andExpect(jsonPath("$.data.list[0].completionTokens").value(35))
                .andExpect(jsonPath("$.data.list[0].totalTokens").value(155))
                .andExpect(jsonPath("$.data.list[0].chargedCredits").value(10));

        mockMvc.perform(get("/api/admin/v1/billing/overview")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("userId", "2")
                        .param("sourceType", "TASK")
                        .param("sourceId", taskId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayTotalTokens").value(155))
                .andExpect(jsonPath("$.data.todayChargedCredits").value(10))
                .andExpect(jsonPath("$.data.modelCosts[0].modelName").isNotEmpty())
                .andExpect(jsonPath("$.data.userCosts[0].userId").value(2))
                .andExpect(jsonPath("$.data.userCosts[0].usageCount").value(1))
                .andExpect(jsonPath("$.data.modalityCosts[0].modality").value("TEXT"))
                .andExpect(jsonPath("$.data.dailyCosts[0].usageCount").value(1));
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
                .andExpect(jsonPath("$.data.progressMessage").value("模型调用失败，请稍后重试"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.result").doesNotExist());
    }

    @Test
    void workerRiskControlFailureUsesUserFriendlyPromptMessage() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "worker_risk_control_tool", 1);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "worker_risk_control_tool");

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
                                  "errorCode": "MODEL_RISK_CONTROL_REJECTED",
                                  "errorMessage": "Failure to pass the risk control system"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/failed", taskId), "POST",
                        "/api/internal/v1/tasks/%d/failed".formatted(taskId), failedBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.progressMessage").value("您的提示词包含违禁词"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}/status", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.progressMessage").value("您的提示词包含违禁词"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.errorCode").value("MODEL_RISK_CONTROL_REJECTED"))
                .andExpect(jsonPath("$.data.errorMessage").value("Failure to pass the risk control system"))
                .andExpect(jsonPath("$.data.progressMessage").value("您的提示词包含违禁词"));
    }

    @Test
    void workerModelTimeoutMarksTaskTimeout() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "worker_timeout_tool", 1);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "worker_timeout_tool");

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
                                  "errorCode": "MODEL_TIMEOUT",
                                  "errorMessage": "model request timed out"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/failed", taskId), "POST",
                        "/api/internal/v1/tasks/%d/failed".formatted(taskId), failedBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TIMEOUT"))
                .andExpect(jsonPath("$.data.progress").value(100))
                .andExpect(jsonPath("$.data.progressMessage").value("模型响应超时，请稍后重试"));
    }

    @Test
    void workerFailedStatusAcceptsLongProviderErrorMessage() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "worker_long_failed_tool", 1);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "worker_long_failed_tool");

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

        String longMessage = "SSL EOF ".repeat(800);
        String failedBody = """
                                {
                                  "errorCode": "MODEL_CALL_FAILED",
                                  "errorMessage": "%s"
                                }
                                """.formatted(longMessage);
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/failed", taskId), "POST",
                        "/api/internal/v1/tasks/%d/failed".formatted(taskId), failedBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.progressMessage").value("模型调用失败，请稍后重试"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    @Test
    void workerSuccessStillSavesResultWhenFrozenCreditWasReleased() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "worker_success_after_release_tool", 10);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "worker_success_after_release_tool");

        creditService.releaseForTask(2L, taskId, 10);

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

        String successBody = """
                                {
                                  "resourceType": "VIDEO",
                                  "contentText": "{\\"videos\\":[{\\"url\\":\\"/generated/video.mp4\\"}]}",
                                  "billableUnits": 1
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/success", taskId), "POST",
                        "/api/internal/v1/tasks/%d/success".formatted(taskId), successBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.result.resourceType").value("VIDEO"))
                .andExpect(jsonPath("$.data.result.contentText").value("{\"videos\":[{\"url\":\"/generated/video.mp4\"}]}"));

        mockMvc.perform(get("/api/admin/v1/billing/usage-logs")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageNo", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].sourceId").value(taskId.intValue()))
                .andExpect(jsonPath("$.data.list[0].taskNo", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.list[0].chargedCredits").value(10));
    }

    @Test
    void perCallModelUsageRecordsBillableUnitsAndCost() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long modelConfigId = createImageModelConfig(adminToken);
        Long toolId = createTool(adminToken, "worker_per_call_image_tool", 5, "IMAGE_GENERATION", modelConfigId);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "worker_per_call_image_tool");

        String processingBody = """
                                {
                                  "progress": 35,
                                  "progressMessage": "AI is generating image"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/processing", taskId), "POST",
                        "/api/internal/v1/tasks/%d/processing".formatted(taskId), processingBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processingBody))
                .andExpect(status().isOk());

        String successBody = """
                                {
                                  "resourceType": "IMAGE",
                                  "contentText": "{\\"images\\":[{\\"url\\":\\"/generated/a.png\\"},{\\"url\\":\\"/generated/b.png\\"}]}",
                                  "billableUnits": 2
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/success", taskId), "POST",
                        "/api/internal/v1/tasks/%d/success".formatted(taskId), successBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        mockMvc.perform(get("/api/admin/v1/billing/usage-logs")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("pageNo", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].sourceId").value(taskId.intValue()))
                .andExpect(jsonPath("$.data.list[0].taskNo", not(blankOrNullString())))
                .andExpect(jsonPath("$.data.list[0].billingUnit").value("PER_CALL"))
                .andExpect(jsonPath("$.data.list[0].billableUnits").value(2))
                .andExpect(jsonPath("$.data.list[0].unitPrice").value(0.03))
                .andExpect(jsonPath("$.data.list[0].costAmount").value(0.06))
                .andExpect(jsonPath("$.data.list[0].chargedCredits").value(8));
    }

    @Test
    void adminRejectsToolBindingToDisabledImageModelBeforeWorkerDispatch() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        String modelResponse = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Disabled GPT Image",
                                  "configCode": "disabled_gpt_image",
                                  "provider": "openai_images_gateway",
                                  "modelName": "gpt-image-2",
                                  "baseUrl": "https://shiyunapi.com/v1",
                                  "apiKey": "",
                                  "timeoutSeconds": 60,
                                  "billingUnit": "IMAGE_TOKEN",
                                  "unitPrice": 0.01,
                                  "enabled": false,
                                  "agentEnabled": false,
                                  "capabilities": ["IMAGE_GENERATION"]
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long modelConfigId = objectMapper.readTree(modelResponse).path("data").path("id").asLong();

        mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "worker_disabled_image_tool",
                                  "toolName": "worker_disabled_image_tool",
                                  "categoryId": 1,
                                  "description": "Worker test tool",
                                  "coverUrl": "",
                                  "estimatedCreditCost": 5,
                                  "toolType": "IMAGE_GENERATION",
                                  "modelConfigId": %d
                                }
                                """.formatted(modelConfigId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("bound model config is disabled: Disabled GPT Image"));
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
    void workerExecutionContextIncludesRuntimePromptForMinimalImageTool() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long modelConfigId = createImageModelConfig(adminToken);
        Long toolId = createMinimalImageTool(adminToken, "worker_background_remover", modelConfigId);
        publishTool(adminToken, toolId);

        String userToken = login("/api/v1/auth/login", "user1");
        jdbcTemplate.update("""
                INSERT INTO user_upload_assets(
                  user_id, file_id, asset_kind, original_filename, content_type,
                  file_size, url, storage_path, status, created_at, updated_at
                ) VALUES (2, 'worker-person-image', 'image', 'person.png', 'image/png',
                          3, '/generated/uploads/person.png', 'local/uploads/person.png',
                          'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        String response = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "worker_background_remover",
                                  "params": {
                                    "sourceImageUrl": "/generated/uploads/person.png"
                                  },
                                  "clientRequestId": "worker-background-remover-request"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        Long taskId = Long.parseLong(response.replaceAll("(?s).*\\\"taskId\\\"\\s*:\\s*(\\d+).*", "$1"));

        mockMvc.perform(signed(get("/api/internal/v1/tasks/{taskId}/execution-context", taskId), "GET",
                        "/api/internal/v1/tasks/%d/execution-context".formatted(taskId), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolType").value("IMAGE_TO_IMAGE"))
                .andExpect(jsonPath("$.data.inputModality").value("IMAGE"))
                .andExpect(jsonPath("$.data.outputModality").value("IMAGE"))
                .andExpect(jsonPath("$.data.executionHandler").value("IMAGE_GENERATION"))
                .andExpect(jsonPath("$.data.params.sourceImageUrl").value("http://127.0.0.1:8080/generated/uploads/person.png"))
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("sourceImageUrl"))
                .andExpect(jsonPath("$.data.userPromptTemplate").value("Remove the background from {{sourceImageUrl}} and return a transparent PNG."))
                .andExpect(jsonPath("$.data.systemPrompt").value("You are an image editing model."));
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
        return createTool(adminToken, toolCode, estimatedCreditCost, null);
    }

    private Long createTool(String adminToken, String toolCode, int estimatedCreditCost, String toolType) throws Exception {
        return createTool(adminToken, toolCode, estimatedCreditCost, toolType, null);
    }

    private Long createTool(String adminToken, String toolCode, int estimatedCreditCost, String toolType, Long modelConfigId) throws Exception {
        String typeFragment = toolType == null || toolType.isBlank() ? "" : ",\n                                  \"toolType\": \"" + toolType + "\"";
        String modelFragment = modelConfigId == null ? "" : ",\n                                  \"modelConfigId\": " + modelConfigId;
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
                                  "estimatedCreditCost": %d%s%s
                                }
                                """.formatted(toolCode, toolCode, estimatedCreditCost, typeFragment, modelFragment)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private Long createImageModelConfig(String adminToken) throws Exception {
        String configCode = "image_runtime_model_" + System.nanoTime();
        String response = mockMvc.perform(post("/api/admin/v1/agent/model-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Image Runtime Model",
                                  "configCode": "%s",
                                  "provider": "siliconflow_images",
                                  "modelName": "Tongyi-MAI/Z-Image-Turbo",
                                  "baseUrl": "https://api.siliconflow.cn",
                                  "apiKey": "fake-key",
                                  "timeoutSeconds": 60,
                                  "billingUnit": "PER_CALL",
                                  "unitPrice": 0.03,
                                  "capabilities": ["IMAGE_GENERATION"],
                                  "enabled": true,
                                  "agentEnabled": true,
                                  "isDefault": false
                                }
                                """.formatted(configCode)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Long.parseLong(response.replaceAll("(?s).*\\\"id\\\"\\s*:\\s*(\\d+).*", "$1"));
    }

    private Long createMinimalImageTool(String adminToken, String toolCode, Long modelConfigId) throws Exception {
        String response = mockMvc.perform(post("/api/admin/v1/tools")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolCode": "%s",
                                  "toolName": "背景去除器",
                                  "description": "上传图片并去除背景",
                                  "coverUrl": "",
                                  "toolType": "IMAGE_TO_IMAGE",
                                  "inputModality": "IMAGE",
                                  "outputModality": "IMAGE",
                                  "executionHandler": "IMAGE_GENERATION",
                                  "modelConfigId": %d,
                                  "estimatedCreditCost": 3,
                                  "configNote": "<!-- ai-tool-runtime:{\\"toolKind\\":\\"image\\",\\"systemPrompt\\":\\"You are an image editing model.\\",\\"adminPrompt\\":\\"Remove the background from {{sourceImageUrl}} and return a transparent PNG.\\",\\"userInputs\\":[{\\"fieldKey\\":\\"sourceImageUrl\\",\\"fieldName\\":\\"上传图片\\",\\"fieldType\\":\\"image_upload\\",\\"required\\":true}]} -->"
                                }
                                """.formatted(toolCode, modelConfigId)))
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
