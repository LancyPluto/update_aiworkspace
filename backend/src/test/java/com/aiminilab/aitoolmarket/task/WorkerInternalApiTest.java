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

import java.math.BigDecimal;

import static com.aiminilab.aitoolmarket.testsupport.InternalApiTestSupport.signed;
import static org.assertj.core.api.Assertions.assertThat;
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
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.provider-callback.public-base-url=https://wlcloudai.com"
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
    void sunoCallbackRegistrationAndInboxArePublicIdempotentAndWorkerReadable() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "worker_suno_callback_tool", 3);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "worker_suno_callback_tool");

        String claimBody = """
                {"workerId":"worker-suno","claimToken":"claim-suno"}
                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/claim", taskId), "POST",
                        "/api/internal/v1/tasks/%d/claim".formatted(taskId), claimBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claimed").value(true));

        String registrationBody = """
                {"providerCode":"suno_music","claimToken":"claim-suno"}
                """;
        String registrationJson = mockMvc.perform(signed(
                        post("/api/internal/v1/tasks/{taskId}/provider-callback-registration", taskId),
                        "POST",
                        "/api/internal/v1/tasks/%d/provider-callback-registration".formatted(taskId),
                        registrationBody
                ).contentType(MediaType.APPLICATION_JSON).content(registrationBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.callbackUrl").value(
                        org.hamcrest.Matchers.startsWith("https://wlcloudai.com/api/v1/provider-callbacks/suno/music/")
                ))
                .andReturn().getResponse().getContentAsString();
        String callbackUrl = objectMapper.readTree(registrationJson).path("data").path("callbackUrl").asText();
        String callbackToken = callbackUrl.substring(callbackUrl.lastIndexOf('/') + 1);

        String callbackBody = """
                {
                  "code":200,
                  "msg":"All generated successfully.",
                  "data":{
                    "callbackType":"complete",
                    "task_id":"suno-provider-task-1",
                    "data":[{"id":"audio-1","audio_url":"https://cdn.example/song.mp3"}]
                  }
                }
                """;
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/provider-callbacks/suno/music/{token}", callbackToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(callbackBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("received"));
        }

        String callbackPath = "/api/internal/v1/tasks/%d/provider-callback".formatted(taskId);
        mockMvc.perform(signed(get(callbackPath), "GET", callbackPath, "")
                        .param("providerCode", "suno_music")
                        .param("claimToken", "claim-suno"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerTaskId").value("suno-provider-task-1"))
                .andExpect(jsonPath("$.data.callbackType").value("complete"))
                .andExpect(jsonPath("$.data.payload.data.data[0].audio_url")
                        .value("https://cdn.example/song.mp3"));

        Integer inboxCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_callback_inbox WHERE task_id = ?",
                Integer.class,
                taskId
        );
        assertThat(inboxCount).isEqualTo(1);
    }

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
    void workerClaimLeasePreventsDuplicateExecutionAndRequiresMatchingToken() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "worker_claim_lease_tool", 3);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "worker_claim_lease_tool");

        String claimBody = """
                                {
                                  "workerId": "worker-a",
                                  "claimToken": "claim-token-a"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/claim", taskId), "POST",
                        "/api/internal/v1/tasks/%d/claim".formatted(taskId), claimBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claimed").value(true))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.claimToken").value("claim-token-a"));

        String duplicateClaimBody = """
                                {
                                  "workerId": "worker-b",
                                  "claimToken": "claim-token-b"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/claim", taskId), "POST",
                        "/api/internal/v1/tasks/%d/claim".formatted(taskId), duplicateClaimBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateClaimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claimed").value(false))
                .andExpect(jsonPath("$.data.reason").value("already_claimed"));

        String wrongProcessingBody = """
                                {
                                  "progress": 35,
                                  "progressMessage": "wrong token",
                                  "claimToken": "claim-token-b"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/processing", taskId), "POST",
                        "/api/internal/v1/tasks/%d/processing".formatted(taskId), wrongProcessingBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongProcessingBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_INVALID"));

        String renewBody = """
                                {
                                  "workerId": "worker-a",
                                  "claimToken": "claim-token-a"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/lease/renew", taskId), "POST",
                        "/api/internal/v1/tasks/%d/lease/renew".formatted(taskId), renewBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(renewBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claimed").value(true))
                .andExpect(jsonPath("$.data.reason").value("renewed"));

        String successBody = """
                                {
                                  "resourceType": "MARKDOWN",
                                  "contentText": "# Claimed result",
                                  "claimToken": "claim-token-a"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/success", taskId), "POST",
                        "/api/internal/v1/tasks/%d/success".formatted(taskId), successBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(successBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        Integer activeLeaseCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tasks WHERE id = ? AND claim_token IS NOT NULL",
                Integer.class,
                taskId
        );
        assertThat(activeLeaseCount).isZero();
    }

    @Test
    void staleClaimTokenCallbacksAreRejectedWithoutBillingOrCreditSideEffects() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "worker_stale_claim_tool", 4);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "worker_stale_claim_tool");

        String claimBody = """
                                {
                                  "workerId": "worker-a",
                                  "claimToken": "claim-token-a"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/claim", taskId), "POST",
                        "/api/internal/v1/tasks/%d/claim".formatted(taskId), claimBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claimed").value(true));

        jdbcTemplate.update("""
                        UPDATE ai_tasks
                        SET claimed_by = 'worker-b',
                            claim_token = 'claim-token-b',
                            lease_until = DATEADD('MINUTE', 30, CURRENT_TIMESTAMP)
                        WHERE id = ?
                        """,
                taskId);

        String staleSuccessBody = """
                                {
                                  "resourceType": "MARKDOWN",
                                  "contentText": "# stale result",
                                  "claimToken": "claim-token-a"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/success", taskId), "POST",
                        "/api/internal/v1/tasks/%d/success".formatted(taskId), staleSuccessBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleSuccessBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_INVALID"));

        String staleFailedBody = """
                                {
                                  "errorCode": "MODEL_CALL_FAILED",
                                  "errorMessage": "stale failure",
                                  "providerCharged": true,
                                  "providerCostAmount": 0.040000,
                                  "claimToken": "claim-token-a"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/failed", taskId), "POST",
                        "/api/internal/v1/tasks/%d/failed".formatted(taskId), staleFailedBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleFailedBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_INVALID"));

        assertThat(countCreditLogs(taskId, "DEDUCT")).isZero();
        assertThat(countCreditLogs(taskId, "RELEASE")).isZero();
        assertThat(countBillingUsageLogs(taskId)).isZero();
        assertThat(countResultResources(taskId)).isZero();
        String currentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM ai_tasks WHERE id = ?",
                String.class,
                taskId
        );
        assertThat(currentStatus).isEqualTo("PROCESSING");
    }

    @Test
    void providerCheckpointUsesClaimTokenAndVersionCompareAndSet() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long toolId = createTool(adminToken, "worker_provider_checkpoint_tool", 1);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "worker_provider_checkpoint_tool");

        String claimBody = """
                {
                  "workerId": "worker-a",
                  "claimToken": "checkpoint-claim-a"
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/claim", taskId), "POST",
                        "/api/internal/v1/tasks/%d/claim".formatted(taskId), claimBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claimed").value(true));

        String checkpointBody = """
                {
                  "expectedVersion": 0,
                  "claimToken": "checkpoint-claim-a",
                  "checkpoint": {
                    "kind": "WORKFLOW_VIDEO",
                    "scenes": {
                      "1": {"status": "SUBMITTED", "taskId": "provider-task-1"}
                    }
                  }
                }
                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/provider-checkpoint", taskId), "POST",
                        "/api/internal/v1/tasks/%d/provider-checkpoint".formatted(taskId), checkpointBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkpointBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.checkpoint.scenes.1.taskId").value("provider-task-1"));

        mockMvc.perform(signed(get("/api/internal/v1/tasks/{taskId}/execution-context", taskId), "GET",
                        "/api/internal/v1/tasks/%d/execution-context".formatted(taskId), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.providerCheckpointVersion").value(1))
                .andExpect(jsonPath("$.data.providerCheckpoint.scenes.1.status").value("SUBMITTED"));

        String versionOneBody = checkpointBody
                .replace("\"expectedVersion\": 0", "\"expectedVersion\": 1");
        jdbcTemplate.update("UPDATE ai_tasks SET lease_until = DATEADD('MINUTE', -1, CURRENT_TIMESTAMP) WHERE id = ?", taskId);
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/provider-checkpoint", taskId), "POST",
                        "/api/internal/v1/tasks/%d/provider-checkpoint".formatted(taskId), versionOneBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(versionOneBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_INVALID"));

        jdbcTemplate.update("""
                        UPDATE ai_tasks
                        SET claimed_by = 'worker-b',
                            claim_token = 'checkpoint-claim-b',
                            lease_until = DATEADD('MINUTE', 30, CURRENT_TIMESTAMP)
                        WHERE id = ?
                        """,
                taskId);

        String staleClaimBody = versionOneBody;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/provider-checkpoint", taskId), "POST",
                        "/api/internal/v1/tasks/%d/provider-checkpoint".formatted(taskId), staleClaimBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleClaimBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_INVALID"));

        String currentClaimBody = staleClaimBody.replace("checkpoint-claim-a", "checkpoint-claim-b");
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/provider-checkpoint", taskId), "POST",
                        "/api/internal/v1/tasks/%d/provider-checkpoint".formatted(taskId), currentClaimBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(currentClaimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));

        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/provider-checkpoint", taskId), "POST",
                        "/api/internal/v1/tasks/%d/provider-checkpoint".formatted(taskId), currentClaimBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(currentClaimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));

        String conflictingReplay = currentClaimBody.replace("provider-task-1", "provider-task-conflict");
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/provider-checkpoint", taskId), "POST",
                        "/api/internal/v1/tasks/%d/provider-checkpoint".formatted(taskId), conflictingReplay)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictingReplay))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_INVALID"));
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
    void failedTaskCanRecordExplicitProviderCostWithoutChargingUserCredits() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        Long modelConfigId = createImageModelConfig(adminToken);
        Long accountId = createManualVendorAccount("manual_failure_cost_account", "https://api.siliconflow.cn", "fake-key", new BigDecimal("10.0000"));
        jdbcTemplate.update("UPDATE agent_model_configs SET vendor_account_id = ?, api_key = '' WHERE id = ?", accountId, modelConfigId);
        Long toolId = createTool(adminToken, "worker_failed_provider_cost_tool", 5, "IMAGE_GENERATION", modelConfigId);
        publishTool(adminToken, toolId);
        String userToken = login("/api/v1/auth/login", "user1");
        Long taskId = createTask(userToken, "worker_failed_provider_cost_tool");

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

        String failedBody = """
                                {
                                  "errorCode": "MODEL_CALL_FAILED",
                                  "errorMessage": "provider charged but callback failed",
                                  "failureStage": "PROVIDER_SUBMITTED",
                                  "providerCostAmount": 0.030000,
                                  "providerCostCurrency": "CNY",
                                  "providerErrorCode": "UPSTREAM_FAILED",
                                  "providerRequestId": "provider-request-1"
                                }
                                """;
        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/failed", taskId), "POST",
                        "/api/internal/v1/tasks/%d/failed".formatted(taskId), failedBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));

        assertThat(countCreditLogs(taskId, "DEDUCT")).isZero();
        assertThat(countCreditLogs(taskId, "RELEASE")).isEqualTo(1);
        Integer usageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE source_type = 'TASK' AND source_id = ? AND outcome = 'FAILED'",
                Integer.class,
                taskId
        );
        assertThat(usageCount).isEqualTo(1);
        Integer chargedCredits = jdbcTemplate.queryForObject(
                "SELECT charged_credits FROM billing_usage_logs WHERE source_type = 'TASK' AND source_id = ?",
                Integer.class,
                taskId
        );
        BigDecimal vendorCost = jdbcTemplate.queryForObject(
                "SELECT vendor_cost_amount FROM billing_usage_logs WHERE source_type = 'TASK' AND source_id = ?",
                BigDecimal.class,
                taskId
        );
        Integer providerCharged = jdbcTemplate.queryForObject(
                "SELECT provider_charged FROM billing_usage_logs WHERE source_type = 'TASK' AND source_id = ?",
                Integer.class,
                taskId
        );
        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT balance_amount FROM model_vendor_accounts WHERE id = ?",
                BigDecimal.class,
                accountId
        );
        assertThat(chargedCredits).isZero();
        assertThat(vendorCost).isEqualByComparingTo("0.030000");
        assertThat(providerCharged).isEqualTo(1);
        assertThat(balance).isEqualByComparingTo("9.970000");

        mockMvc.perform(signed(post("/api/internal/v1/tasks/{taskId}/failed", taskId), "POST",
                        "/api/internal/v1/tasks/%d/failed".formatted(taskId), failedBody)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        Integer duplicateUsageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE source_type = 'TASK' AND source_id = ?",
                Integer.class,
                taskId
        );
        assertThat(duplicateUsageCount).isEqualTo(1);
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
                                  "errorMessage": "Failure to pass the risk control system",
                                  "userMessage": "apiKey=must-not-reach-users",
                                  "developerMessage": "provider apiKey=secret-value request failed",
                                  "failureTraceId": "worker-risk-trace",
                                  "providerErrorCode": "UPSTREAM_451",
                                  "providerRequestId": "provider-risk-1"
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
                .andExpect(jsonPath("$.data.errorMessage").value("您的提示词包含违禁词"))
                .andExpect(jsonPath("$.data.failureTraceId").value("worker-risk-trace"))
                .andExpect(jsonPath("$.data.progressMessage").value("您的提示词包含违禁词"));

        mockMvc.perform(get("/api/admin/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.errorMessage").value("provider apiKey=[REDACTED] request failed"))
                .andExpect(jsonPath("$.data.failureTraceId").value("worker-risk-trace"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_message FROM ai_tasks WHERE id = ?", String.class, taskId
        )).isEqualTo("您的提示词包含违禁词");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT developer_message FROM ai_tasks WHERE id = ?", String.class, taskId
        )).doesNotContain("secret-value");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM ai_tasks WHERE id = ?", String.class, taskId
        )).doesNotContain("secret-value");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_trace_id FROM ai_tasks WHERE id = ?", String.class, taskId
        )).isEqualTo("worker-risk-trace");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_error_code FROM ai_tasks WHERE id = ?", String.class, taskId
        )).isEqualTo("UPSTREAM_451");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_request_id FROM ai_tasks WHERE id = ?", String.class, taskId
        )).isEqualTo("provider-risk-1");
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
                .andExpect(jsonPath("$.data.result.contentText").value("{\"videos\":[{\"url\":\"/generated/video.mp4\",\"downloadUrl\":\"/generated/video.mp4\"}]}"));

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
        Long accountId = createManualVendorAccount("manual_image_account", "https://api.siliconflow.cn", "fake-key", new BigDecimal("10.0000"));
        jdbcTemplate.update("UPDATE agent_model_configs SET vendor_account_id = ?, api_key = '' WHERE id = ?", accountId, modelConfigId);
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

        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT balance_amount FROM model_vendor_accounts WHERE id = ?",
                BigDecimal.class,
                accountId
        );
        Integer adjustmentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vendor_balance_adjustments WHERE vendor_account_id = ?",
                Integer.class,
                accountId
        );
        assertThat(balance).isEqualByComparingTo("9.940000");
        assertThat(adjustmentCount).isEqualTo(1);
    }

    @Test
    void adminRejectsToolBindingToDisabledImageModelBeforeWorkerDispatch() throws Exception {
        String adminToken = login("/api/admin/v1/auth/login", "admin");
        jdbcTemplate.update("""
                INSERT INTO agent_model_configs(
                    display_name, config_code, provider, model_name, base_url, api_key,
                    timeout_seconds, billing_unit, unit_price, capabilities,
                    enabled, agent_enabled, is_default, is_deleted
                ) VALUES (
                    'Disabled GPT Image', 'disabled_gpt_image', 'openai_images_gateway', 'gpt-image-2',
                    'https://shiyunapi.com/v1', '', 60, 'IMAGE_TOKEN', 0.01, '["IMAGE_GENERATION"]',
                    0, 0, 0, 0
                )
                """);
        Long modelConfigId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_model_configs WHERE config_code = 'disabled_gpt_image'",
                Long.class
        );

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

    private int countCreditLogs(Long taskId, String logType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE task_id = ? AND log_type = ?",
                Integer.class,
                taskId,
                logType
        );
        return count == null ? 0 : count;
    }

    private int countBillingUsageLogs(Long taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE source_type = 'TASK' AND source_id = ?",
                Integer.class,
                taskId
        );
        return count == null ? 0 : count;
    }

    private int countResultResources(Long taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_result_resources WHERE task_id = ?",
                Integer.class,
                taskId
        );
        return count == null ? 0 : count;
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

    private Long createManualVendorAccount(String accountName, String baseUrl, String apiKey, BigDecimal balance) {
        jdbcTemplate.update("""
                        INSERT INTO model_vendor_accounts(vendor_code, account_name, base_url, api_key,
                                                          balance_query_mode, balance_amount, balance_currency,
                                                          balance_status, health_status, enabled, is_deleted,
                                                          created_at, updated_at)
                        VALUES('siliconflow', ?, ?, ?, 'MANUAL', ?, 'CNY', 'OK', 'OK', 1, 0, NOW(), NOW())
                        """,
                accountName,
                baseUrl,
                apiKey,
                balance);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM model_vendor_accounts WHERE account_name = ?",
                Long.class,
                accountName
        );
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
