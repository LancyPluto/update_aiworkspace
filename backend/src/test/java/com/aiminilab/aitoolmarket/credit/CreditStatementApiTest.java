package com.aiminilab.aitoolmarket.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:credit_statement_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.connection-init-sql=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "spring.task.scheduling.enabled=false"
})
class CreditStatementApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void laterPagesRemainReachableWithStableOrderingAndStrictUserIsolation() throws Exception {
        RegisteredUser owner = register("statement_page_owner");
        RegisteredUser other = register("statement_page_other");
        resetLogs(owner.userId());
        resetLogs(other.userId());

        LocalDateTime sameTimestamp = LocalDateTime.of(2026, 7, 27, 10, 0);
        for (int i = 0; i < 105; i++) {
            insertCreditLog(owner.userId(), null, null, null, null,
                    "MANUAL_ADD", 1, "page fixture", sameTimestamp);
        }
        insertCreditLog(other.userId(), null, null, null, null,
                "MANUAL_ADD", 999, "must stay isolated", sameTimestamp.plusDays(1));

        JsonNode firstPage = statement(owner.token(), 1, 20);
        JsonNode sixthPage = statement(owner.token(), 6, 20);

        assertThat(firstPage.path("total").asLong()).isEqualTo(105);
        assertThat(firstPage.path("list").size()).isEqualTo(20);
        assertThat(firstPage.path("list").get(0).path("id").asLong())
                .isGreaterThan(firstPage.path("list").get(1).path("id").asLong());
        assertThat(sixthPage.path("pageNo").asInt()).isEqualTo(6);
        assertThat(sixthPage.path("list").size()).isEqualTo(5);
        assertThat(sixthPage.path("hasNext").asBoolean()).isFalse();
        sixthPage.path("list").forEach(row ->
                assertThat(row.path("userId").asLong()).isEqualTo(owner.userId()));
    }

    @Test
    void sourcedDeductionsAggregateLegacyKeysAndUseWalletAmount() throws Exception {
        RegisteredUser user = register("statement_task_aggregate");
        resetLogs(user.userId());
        long toolId = insertTool(user.userId(), "statement_task_tool", "商品文案");
        long taskId = insertTask(user.userId(), toolId, "STATEMENT-TASK-1");

        insertCreditLog(user.userId(), null, null, " task ", taskId,
                "DEDUCT", 1, "first deduction", LocalDateTime.of(2026, 7, 27, 11, 0));
        insertCreditLog(user.userId(), taskId, null, null, null,
                "DEDUCT", 2, "legacy deduction", LocalDateTime.of(2026, 7, 27, 11, 1));
        insertCreditLog(user.userId(), taskId, null, "TASK", taskId,
                "FREEZE", 7, "internal freeze", LocalDateTime.of(2026, 7, 27, 10, 59));
        insertCreditLog(user.userId(), null, null, null, null,
                "RECHARGE", 0, "zero entry", LocalDateTime.of(2026, 7, 27, 11, 2));
        insertCreditLog(user.userId(), null, null, "TASK", 999999L,
                "DEDUCT", 5, "offsetting positive", LocalDateTime.of(2026, 7, 27, 11, 3));
        insertCreditLog(user.userId(), null, null, "TASK", 999999L,
                "DEDUCT", -5, "offsetting negative", LocalDateTime.of(2026, 7, 27, 11, 4));
        insertUsage(user.userId(), "TASK", taskId, 10, "qwen", "qwen-plus", "statement-task-usage");

        JsonNode data = statement(user.token(), 1, 20);
        JsonNode row = data.path("list").get(0);

        assertThat(data.path("total").asLong()).isEqualTo(1);
        assertThat(row.path("sourceType").asText()).isEqualTo("TASK");
        assertThat(row.path("sourceRef").asLong()).isEqualTo(taskId);
        assertThat(row.path("amount").asInt()).isEqualTo(3);
        assertThat(row.path("taskNo").asText()).isEqualTo("STATEMENT-TASK-1-" + user.userId());
        assertThat(row.path("toolName").asText()).isEqualTo("商品文案");
        assertThat(row.path("modelName").asText()).isEqualTo("qwen-plus");
        assertThat(row.path("provider").asText()).isEqualTo("qwen");
    }

    @Test
    void workflowRowsUseStepSnapshotAndPreferChargedUsageMetadata() throws Exception {
        RegisteredUser user = register("statement_workflow_projection");
        resetLogs(user.userId());
        long toolId = insertTool(user.userId(), "statement_workflow_tool", "AI漫剧工作流");
        long workflowId = insertWorkflow(toolId, "default");
        long runId = insertWorkflowRun(user.userId(), toolId, workflowId);
        long stepId = insertWorkflowStep(runId, "script-split", "AI拆分分镜");

        insertCreditLog(user.userId(), null, null, "WORKFLOW_STEP", stepId,
                "DEDUCT", 3, "workflow deduction", LocalDateTime.of(2026, 7, 27, 12, 0));
        insertUsage(user.userId(), "WORKFLOW_STEP", stepId, 10,
                "dashscope", "qwen-plus", "statement-workflow-positive");
        insertUsage(user.userId(), "WORKFLOW_STEP", stepId, 0,
                "failed-provider", "failed-newer-model", "statement-workflow-newer-zero");

        JsonNode row = statement(user.token(), 1, 20).path("list").get(0);

        assertThat(row.path("amount").asInt()).isEqualTo(3);
        assertThat(row.path("workflowRunId").asLong()).isEqualTo(runId);
        assertThat(row.path("workflowId").asLong()).isEqualTo(workflowId);
        assertThat(row.path("workflowName").asText()).isEqualTo("AI漫剧工作流");
        assertThat(row.path("workflowNodeId").asText()).isEqualTo("script-split");
        assertThat(row.path("workflowStepName").asText()).isEqualTo("AI拆分分镜");
        assertThat(row.path("modelName").asText()).isEqualTo("qwen-plus");
        assertThat(row.path("provider").asText()).isEqualTo("dashscope");
    }

    @Test
    void workflowMetadataDoesNotLeakWhenSourcePointsToAnotherUsersStep() throws Exception {
        RegisteredUser owner = register("statement_workflow_owner");
        RegisteredUser other = register("statement_workflow_other");
        resetLogs(owner.userId());
        resetLogs(other.userId());
        long otherToolId = insertTool(other.userId(), "statement_other_workflow_tool", "他人的工作流");
        long otherWorkflowId = insertWorkflow(otherToolId, "private-workflow");
        long otherRunId = insertWorkflowRun(other.userId(), otherToolId, otherWorkflowId);
        long otherStepId = insertWorkflowStep(otherRunId, "private-node", "不应泄露的步骤标题");

        insertCreditLog(owner.userId(), null, null, "WORKFLOW_STEP", otherStepId,
                "DEDUCT", 4, "invalid cross-user source", LocalDateTime.of(2026, 7, 27, 13, 0));

        JsonNode row = statement(owner.token(), 1, 20).path("list").get(0);

        assertThat(row.path("sourceRef").asLong()).isEqualTo(otherStepId);
        assertThat(row.path("workflowRunId").isNull()).isTrue();
        assertThat(row.path("workflowId").isNull()).isTrue();
        assertThat(row.path("workflowName").isNull()).isTrue();
        assertThat(row.path("workflowNodeId").isNull()).isTrue();
        assertThat(row.path("workflowStepName").isNull()).isTrue();
        assertThat(row.path("toolName").isNull()).isTrue();
    }

    private JsonNode statement(String token, int pageNo, int pageSize) throws Exception {
        String response = mockMvc.perform(get("/api/v1/credits/statement-logs")
                        .param("pageNo", String.valueOf(pageNo))
                        .param("pageSize", String.valueOf(pageSize))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageSize").value(pageSize))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).path("data");
    }

    private RegisteredUser register(String prefix) throws Exception {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
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
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode data = objectMapper.readTree(response).path("data");
        RegisteredUser registered = new RegisteredUser(
                data.path("accessToken").asText(),
                data.path("user").path("id").asLong()
        );
        mockMvc.perform(get("/api/v1/credits/account")
                        .header("Authorization", "Bearer " + registered.token()))
                .andExpect(status().isOk());
        return registered;
    }

    private void resetLogs(long userId) {
        jdbcTemplate.update("DELETE FROM billing_usage_logs WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM credit_logs WHERE user_id = ?", userId);
    }

    private void insertCreditLog(long userId,
                                 Long taskId,
                                 Long agentRunId,
                                 String sourceType,
                                 Long sourceRef,
                                 String logType,
                                 int amount,
                                 String reason,
                                 LocalDateTime createdAt) {
        Long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM credit_accounts WHERE user_id = ?", Long.class, userId);
        jdbcTemplate.update("""
                INSERT INTO credit_logs(
                  user_id, account_id, task_id, agent_run_id, source_type, source_ref,
                  log_type, amount, frozen_amount, balance_before, balance_after,
                  frozen_before, frozen_after, operator_type, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 1000, 1000, 0, 0, 'SYSTEM', ?, ?)
                """, userId, accountId, taskId, agentRunId, sourceType, sourceRef,
                logType, amount, reason, createdAt);
    }

    private long insertTool(long userId, String codePrefix, String name) {
        String code = codePrefix + "_" + userId;
        jdbcTemplate.update("""
                INSERT INTO ai_tools(tool_code, tool_name, status, execution_mode, billing_mode, is_deleted)
                VALUES (?, ?, 'ONLINE', 'DIRECT', 'FIXED', 0)
                """, code, name);
        return jdbcTemplate.queryForObject("SELECT id FROM ai_tools WHERE tool_code = ?", Long.class, code);
    }

    private long insertTask(long userId, long toolId, String taskNoPrefix) {
        String taskNo = taskNoPrefix + "-" + userId;
        jdbcTemplate.update("""
                INSERT INTO ai_tasks(task_no, user_id, tool_id, params_json)
                VALUES (?, ?, ?, '{}')
                """, taskNo, userId, toolId);
        return jdbcTemplate.queryForObject("SELECT id FROM ai_tasks WHERE task_no = ?", Long.class, taskNo);
    }

    private long insertWorkflow(long toolId, String workflowName) {
        jdbcTemplate.update("""
                INSERT INTO tool_workflows(
                  tool_id, workflow_name, nodes_json, edges_json, status, execution_enabled
                ) VALUES (?, ?, '[]', '[]', 'PUBLISHED', 1)
                """, toolId, workflowName);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflows WHERE tool_id = ? AND workflow_name = ?",
                Long.class, toolId, workflowName);
    }

    private long insertWorkflowRun(long userId, long toolId, long workflowId) {
        jdbcTemplate.update("""
                INSERT INTO workflow_runs(
                  user_id, tool_id, workflow_id, root_task_id, client_request_id, status
                ) VALUES (?, ?, ?, ?, ?, 'SUCCESS')
                """, userId, toolId, workflowId, 900000L + userId, "statement-run-" + userId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_runs WHERE user_id = ? AND client_request_id = ?",
                Long.class, userId, "statement-run-" + userId);
    }

    private long insertWorkflowStep(long runId, String nodeId, String nodeTitle) {
        jdbcTemplate.update("""
                INSERT INTO workflow_run_steps(run_id, node_id, node_title, node_def_type, status)
                VALUES (?, ?, ?, 'LLM_TEXT', 'SUCCESS')
                """, runId, nodeId, nodeTitle);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ? AND node_id = ?",
                Long.class, runId, nodeId);
    }

    private void insertUsage(long userId,
                             String sourceType,
                             long sourceId,
                             int chargedCredits,
                             String provider,
                             String modelName,
                             String idempotencyKey) {
        jdbcTemplate.update("""
                INSERT INTO billing_usage_logs(
                  idempotency_key, source_type, source_id, user_id, provider,
                  model_name, charged_credits, customer_charge_credits
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, idempotencyKey + "-" + userId, sourceType, sourceId, userId,
                provider, modelName, chargedCredits, chargedCredits);
    }

    private record RegisteredUser(String token, long userId) {
    }
}
