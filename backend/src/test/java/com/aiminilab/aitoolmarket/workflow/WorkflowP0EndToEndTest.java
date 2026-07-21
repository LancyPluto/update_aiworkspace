package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_p0_e2e_closed;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class WorkflowP0EndToEndTest {

    private static final long USER_ID = 7101L;
    private static final String TOOL_CODE = "p0_closed_workflow";

    @Autowired
    private WorkflowRunApplicationService runApplicationService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private PublishedWorkflow published;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM workflow_step_charges WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_step_attempts WHERE step_id IN (SELECT id FROM workflow_run_steps WHERE run_id IN (SELECT id FROM workflow_runs WHERE user_id = ?))", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_run_steps WHERE run_id IN (SELECT id FROM workflow_runs WHERE user_id = ?)", USER_ID);
        jdbcTemplate.update("DELETE FROM workflow_runs WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM task_outbox_events WHERE task_id IN (SELECT id FROM ai_tasks WHERE user_id = ?)", USER_ID);
        jdbcTemplate.update("DELETE FROM credit_logs WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM ai_tasks WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM tool_workflow_versions WHERE workflow_id IN (SELECT id FROM tool_workflows WHERE tool_id IN (SELECT id FROM ai_tools WHERE tool_code = ?))", TOOL_CODE);
        jdbcTemplate.update("DELETE FROM tool_workflows WHERE tool_id IN (SELECT id FROM ai_tools WHERE tool_code = ?)", TOOL_CODE);
        jdbcTemplate.update("DELETE FROM ai_tools WHERE tool_code = ?", TOOL_CODE);
        published = insertInlineWorkflow(TOOL_CODE);
    }

    @Test
    void defaultClosedRuntimeRejectsNewRunWithoutAnyWriteSideEffects() throws Exception {
        String requestId = "p0-closed-new";

        assertBlocked(
                () -> runApplicationService.create(command(requestId)),
                "runtime_disabled"
        );

        assertZeroCreationSideEffects(requestId);
    }

    @Test
    void legacyGenericWorkflowEntryIsRejectedBeforeTaskFreezeOrOutbox() throws Exception {
        String requestId = "p0-closed-legacy";
        CreateTaskRequest request = new CreateTaskRequest(
                TOOL_CODE,
                objectMapper.readTree("{\"prompt\":\"legacy\"}"),
                requestId,
                null,
                null
        );

        assertBlocked(() -> taskService.create(USER_ID, request), "legacy_workflow_entry_disabled");
        assertBlocked(() -> taskService.createForAgentTool(
                USER_ID,
                new CreateTaskRequest(TOOL_CODE, request.params(), requestId + "-agent", null, null)
        ), "legacy_workflow_entry_disabled");

        assertZeroCreationSideEffects(requestId);
        assertZeroCreationSideEffects(requestId + "-agent");
    }

    @Test
    void existingIdempotentRunIsReturnedEvenAfterKillSwitchCloses() throws Exception {
        String requestId = "p0-closed-existing";
        jdbcTemplate.update("""
                INSERT INTO ai_tasks(
                  task_no, user_id, tool_id, status, progress, params_json,
                  idempotency_key, estimated_credit_cost, queued_at, started_at
                ) VALUES ('P0-CLOSED-EXISTING', ?, ?, 'PROCESSING', 25,
                          '{"prompt":"new run"}', ?, 0,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, USER_ID, published.toolId(), requestId);
        Long rootTaskId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tasks WHERE user_id = ? AND idempotency_key = ?",
                Long.class,
                USER_ID,
                requestId
        );
        jdbcTemplate.update("""
                INSERT INTO workflow_runs(
                  user_id, tool_id, workflow_id, workflow_version, workflow_version_id,
                  root_task_id, launch_source, client_request_id, status, revision,
                  cancellation_generation, input_json, context_json, billing_status,
                  started_at, created_at, updated_at
                ) VALUES (?, ?, ?, 1, ?, ?, 'AGENTS_PAGE', ?, 'RUNNING', 0,
                          0, '{"prompt":"new run"}', '{}', 'CLEAR', CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, USER_ID, published.toolId(), published.workflowId(), published.versionId(),
                rootTaskId, requestId);
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_runs WHERE user_id = ? AND client_request_id = ?",
                Long.class,
                USER_ID,
                requestId
        );

        WorkflowRunCreated returned = runApplicationService.create(command(requestId));

        assertThat(returned.rootTaskId()).isEqualTo(rootTaskId);
        assertThat(returned.runId()).isEqualTo(runId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tasks WHERE user_id = ? AND idempotency_key = ?",
                Integer.class,
                USER_ID,
                requestId
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE user_id = ? AND client_request_id = ?",
                Integer.class,
                USER_ID,
                requestId
        )).isOne();
    }

    private CreateWorkflowRunCommand command(String requestId) throws Exception {
        return new CreateWorkflowRunCommand(
                USER_ID,
                TOOL_CODE,
                objectMapper.readTree("{\"prompt\":\"new run\"}"),
                requestId,
                "AGENTS_PAGE",
                null
        );
    }

    private PublishedWorkflow insertInlineWorkflow(String toolCode) {
        jdbcTemplate.update("""
                INSERT INTO ai_tools(
                  tool_code, tool_name, category_id, description, status,
                  estimated_credit_cost, execution_handler, execution_mode,
                  billing_mode, agent_surface_enabled, minimum_required_credits, is_deleted
                ) VALUES (?, 'P0 Closed Workflow', 1, 'test', 'ONLINE', 0,
                          'TEXT_GENERATION', 'WORKFLOW', 'WORKFLOW_STEP', 1, 0, 0)
                """, toolCode);
        Long toolId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tools WHERE tool_code = ?",
                Long.class,
                toolCode
        );
        String nodes = "[{\"id\":\"start\",\"data\":{\"nodeDefType\":\"start\",\"title\":\"Start\"}}]";
        jdbcTemplate.update("""
                INSERT INTO tool_workflows(
                  tool_id, workflow_name, nodes_json, edges_json, config_json,
                  version, status, draft_revision, execution_enabled, created_at, updated_at
                ) VALUES (?, 'default', ?, '[]', '{}', 1, 'PUBLISHED', 1, 1,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, toolId, nodes);
        Long workflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflows WHERE tool_id = ? AND workflow_name = 'default'",
                Long.class,
                toolId
        );
        jdbcTemplate.update("""
                INSERT INTO tool_workflow_versions(
                  workflow_id, version, nodes_json, edges_json, config_json,
                  canonical_dsl_json, dsl_version, node_registry_version, dsl_hash,
                  input_schema_snapshot_json, dependency_manifest_json, billing_policy_json,
                  risk_policy_json, source_draft_revision, published_at, published_by, created_at
                ) VALUES (?, 1, ?, '[]', '{}', '{}', '1', 'p0', ?, '{}', '{}',
                          '{"mode":"WORKFLOW_STEP","nodePolicies":{}}', '{}', 1,
                          CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                """, workflowId, nodes, "p0-closed-" + workflowId);
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflow_versions WHERE workflow_id = ? AND version = 1",
                Long.class,
                workflowId
        );
        jdbcTemplate.update(
                "UPDATE tool_workflows SET published_version_id = ? WHERE id = ?",
                versionId,
                workflowId
        );
        return new PublishedWorkflow(toolId, workflowId, versionId);
    }

    private void assertZeroCreationSideEffects(String requestId) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tasks WHERE user_id = ? AND idempotency_key = ?",
                Integer.class,
                USER_ID,
                requestId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs WHERE user_id = ? AND client_request_id = ?",
                Integer.class,
                USER_ID,
                requestId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_outbox_events WHERE task_id IN (SELECT id FROM ai_tasks WHERE user_id = ?)",
                Integer.class,
                USER_ID
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_charges WHERE user_id = ?",
                Integer.class,
                USER_ID
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE user_id = ?",
                Integer.class,
                USER_ID
        )).isZero();
    }

    private void assertBlocked(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                               String reason) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_RUNTIME_BLOCKED);
                    assertThat(exception.getData()).isInstanceOf(Map.class);
                    assertThat(((Map<?, ?>) exception.getData()).get("reason")).isEqualTo(reason);
                });
    }

    private record PublishedWorkflow(long toolId, long workflowId, long versionId) {
    }
}
