package com.aiminilab.aitoolmarket.admin;

import com.aiminilab.aitoolmarket.admin.dto.BillingUsageLogResponse;
import com.aiminilab.aitoolmarket.admin.mapper.BillingUsageLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:billing_usage_workflow_projection_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class BillingUsageLogMapperWorkflowProjectionTest {

    private static final long USER_ID = 8101L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BillingUsageLogMapper mapper;

    @BeforeEach
    void cleanProjectionFixtures() {
        jdbcTemplate.update("DELETE FROM billing_usage_logs WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_run_steps WHERE run_id IN (SELECT id FROM workflow_runs WHERE user_id = ?)", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_runs WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM ai_tasks WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM tool_workflows WHERE tool_id IN (SELECT id FROM ai_tools WHERE tool_code = 'billing_projection_tool')");
        jdbcTemplate.update("DELETE FROM ai_tools WHERE tool_code = 'billing_projection_tool'");
    }

    @Test
    void workflowRowsExposeRunWorkflowAndStepSnapshotsAfterPaging() {
        Fixture fixture = insertWorkflowFixture("default");
        long titledStepId = insertStep(fixture.runId(), "script-split", "AI拆分分镜");
        insertUsage("workflow-titled", "WORKFLOW_STEP", titledStepId);
        long historicalStepId = insertStep(fixture.runId(), "legacy-render", null);
        insertUsage("workflow-historical", "WORKFLOW_STEP", historicalStepId);

        List<BillingUsageLogResponse> firstPage = findLogs("WORKFLOW_STEP", null, 1, 0);
        List<BillingUsageLogResponse> secondPage = findLogs("WORKFLOW_STEP", null, 1, 1);

        assertThat(firstPage).singleElement().satisfies(log -> {
            assertThat(log.workflowRunId()).isEqualTo(fixture.runId());
            assertThat(log.workflowId()).isEqualTo(fixture.workflowId());
            assertThat(log.workflowName()).isEqualTo("AI漫剧工作流");
            assertThat(log.workflowNodeId()).isEqualTo("legacy-render");
            assertThat(log.workflowStepName()).isEqualTo("legacy-render");
        });
        assertThat(secondPage).singleElement().satisfies(log -> {
            assertThat(log.workflowName()).isEqualTo("AI漫剧工作流");
            assertThat(log.workflowNodeId()).isEqualTo("script-split");
            assertThat(log.workflowStepName()).isEqualTo("AI拆分分镜");
        });
    }

    @Test
    void taskAndAgentRowsKeepTheirExistingProjection() {
        Fixture fixture = insertWorkflowFixture("custom-workflow");
        jdbcTemplate.update("""
                INSERT INTO ai_tasks(task_no, user_id, tool_id, params_json)
                VALUES ('BILLING-PROJECTION-TASK', ?, ?, '{}')
                """, USER_ID, fixture.toolId());
        long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tasks WHERE task_no = 'BILLING-PROJECTION-TASK'",
                Long.class
        );
        insertUsage("ordinary-task", "TASK", taskId);
        insertUsage("ordinary-agent", "AGENT_RUN", 99101L);

        BillingUsageLogResponse taskLog = findLogs("TASK", taskId, 10, 0).get(0);
        assertThat(taskLog.taskNo()).isEqualTo("BILLING-PROJECTION-TASK");
        assertThat(taskLog.inputModality()).isEqualTo("TEXT");
        assertThat(taskLog.outputModality()).isEqualTo("TEXT");
        assertThat(taskLog.workflowRunId()).isNull();
        assertThat(taskLog.workflowStepName()).isNull();

        BillingUsageLogResponse agentLog = findLogs("AGENT_RUN", 99101L, 10, 0).get(0);
        assertThat(agentLog.sourceType()).isEqualTo("AGENT_RUN");
        assertThat(agentLog.taskNo()).isNull();
        assertThat(agentLog.workflowRunId()).isNull();
        assertThat(agentLog.workflowStepName()).isNull();
    }

    @Test
    void corruptCrossUserWorkflowSourceDoesNotExposeStepMetadata() {
        Fixture fixture = insertWorkflowFixture("private-workflow");
        long foreignStepId = insertStep(fixture.runId(), "private-step", "不应泄露的步骤标题");
        insertUsage("foreign-source", "WORKFLOW_STEP", foreignStepId, USER_ID + 1);

        BillingUsageLogResponse log = findLogsForUser("WORKFLOW_STEP", foreignStepId, USER_ID + 1).get(0);

        assertThat(log.workflowRunId()).isNull();
        assertThat(log.workflowId()).isNull();
        assertThat(log.workflowName()).isNull();
        assertThat(log.workflowNodeId()).isNull();
        assertThat(log.workflowStepName()).isNull();
    }

    private Fixture insertWorkflowFixture(String workflowName) {
        jdbcTemplate.update("""
                INSERT INTO ai_tools(
                  tool_code, tool_name, status, execution_mode, billing_mode, is_deleted
                ) VALUES ('billing_projection_tool', 'AI漫剧工作流', 'ONLINE', 'WORKFLOW', 'WORKFLOW_STEP', 0)
                """);
        long toolId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tools WHERE tool_code = 'billing_projection_tool'",
                Long.class
        );
        jdbcTemplate.update("""
                INSERT INTO tool_workflows(
                  tool_id, workflow_name, nodes_json, edges_json, version, status,
                  draft_revision, execution_enabled, created_at, updated_at
                ) VALUES (?, ?, '[]', '[]', 1, 'PUBLISHED', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, toolId, workflowName);
        long workflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflows WHERE tool_id = ? AND workflow_name = ?",
                Long.class,
                toolId,
                workflowName
        );
        jdbcTemplate.update("""
                INSERT INTO workflow_runs(
                  user_id, tool_id, workflow_id, workflow_version, root_task_id,
                  client_request_id, status
                ) VALUES (?, ?, ?, 1, 98101, 'billing-projection-run', 'SUCCESS')
                """, USER_ID, toolId, workflowId);
        long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_runs WHERE user_id = ? AND client_request_id = 'billing-projection-run'",
                Long.class,
                USER_ID
        );
        return new Fixture(toolId, workflowId, runId);
    }

    private long insertStep(long runId, String nodeId, String nodeTitle) {
        jdbcTemplate.update("""
                INSERT INTO workflow_run_steps(run_id, node_id, node_title, node_def_type, status)
                VALUES (?, ?, ?, 'LLM_TEXT', 'SUCCESS')
                """, runId, nodeId, nodeTitle);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ? AND node_id = ?",
                Long.class,
                runId,
                nodeId
        );
    }

    private void insertUsage(String key, String sourceType, long sourceId) {
        insertUsage(key, sourceType, sourceId, USER_ID);
    }

    private void insertUsage(String key, String sourceType, long sourceId, long userId) {
        jdbcTemplate.update("""
                INSERT INTO billing_usage_logs(
                  idempotency_key, source_type, source_id, user_id, provider,
                  model_name, charged_credits, customer_charge_credits
                ) VALUES (?, ?, ?, ?, 'qwen', 'qwen-plus', 12, 12)
                """, key, sourceType, sourceId, userId);
    }

    private List<BillingUsageLogResponse> findLogs(String sourceType, Long sourceId, int limit, int offset) {
        return findLogsForUser(sourceType, sourceId, USER_ID, limit, offset);
    }

    private List<BillingUsageLogResponse> findLogsForUser(String sourceType, Long sourceId, long userId) {
        return findLogsForUser(sourceType, sourceId, userId, 10, 0);
    }

    private List<BillingUsageLogResponse> findLogsForUser(String sourceType,
                                                          Long sourceId,
                                                          long userId,
                                                          int limit,
                                                          int offset) {
        return mapper.findLogs(
                limit, offset, null, null, userId, null,
                null, null, sourceType, sourceId
        );
    }

    private record Fixture(long toolId, long workflowId, long runId) {
    }
}
