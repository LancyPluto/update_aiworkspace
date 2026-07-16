package com.aiminilab.aitoolmarket.task;

import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.auth.security.TokenDenylistService;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:task_workflow_boundary_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.connection-init-sql=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class TaskWorkflowBoundaryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private TaskMapper taskMapper;

    @MockBean
    private TokenDenylistService tokenDenylistService;

    @Test
    void userListAndStaleMapperKeepRootButExcludeWorkflowChildFromGenericHandling() throws Exception {
        UserFixture user = insertUser("workflow_task_query_boundary");
        WorkflowFixture workflow = insertWorkflow(user.userId(), "query-boundary", true);
        makeTaskStale(workflow.rootTaskId());
        makeTaskStale(workflow.childTaskId());

        assertThat(taskMapper.findByUserId(user.userId(), null, null, 10, 0))
                .extracting("id")
                .containsExactly(workflow.rootTaskId());
        assertThat(taskMapper.countByUserId(user.userId(), null, null)).isEqualTo(1);
        assertThat(taskMapper.findStaleActiveTasks(LocalDateTime.now().minusMinutes(120), 10)).isEmpty();

        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].taskId").value(workflow.rootTaskId().intValue()));

        mockMvc.perform(post("/api/admin/v1/tasks/reconcile-stale")
                        .param("staleMinutes", "120")
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timedOut").value(0));

        assertTaskStatus(workflow.rootTaskId(), "PROCESSING");
        assertTaskStatus(workflow.childTaskId(), "QUEUED");
        assertRunStatus(workflow.runId(), "RUNNING");
    }

    @Test
    void genericMutationApisRejectWorkflowChildrenAndRetryOrRegenerateForAnyWorkflowTask() throws Exception {
        UserFixture user = insertUser("workflow_task_mutation_boundary");
        WorkflowFixture workflow = insertWorkflow(user.userId(), "mutation-boundary", true);
        int initialTasks = count("SELECT COUNT(*) FROM ai_tasks");
        int initialOutbox = count("SELECT COUNT(*) FROM task_outbox_events");
        int initialTaskCredits = count("SELECT COUNT(*) FROM credit_logs WHERE source_type = 'TASK'");

        expectTaskStatusInvalid(post("/api/v1/tasks/{taskId}/cancel", workflow.childTaskId())
                .header("Authorization", bearer(user.token())));
        expectTaskStatusInvalid(regenerate(workflow.rootTaskId(), user.token(), "root-regenerate"));
        expectTaskStatusInvalid(regenerate(workflow.childTaskId(), user.token(), "child-regenerate"));
        expectTaskStatusInvalid(post("/api/admin/v1/tasks/{taskId}/retry", workflow.rootTaskId())
                .header("Authorization", bearer(adminToken())));
        expectTaskStatusInvalid(post("/api/admin/v1/tasks/{taskId}/retry", workflow.childTaskId())
                .header("Authorization", bearer(adminToken())));
        expectTaskStatusInvalid(post("/api/admin/v1/tasks/{taskId}/cancel", workflow.childTaskId())
                .header("Authorization", bearer(adminToken())));

        assertThat(count("SELECT COUNT(*) FROM ai_tasks")).isEqualTo(initialTasks);
        assertThat(count("SELECT COUNT(*) FROM task_outbox_events")).isEqualTo(initialOutbox);
        assertThat(count("SELECT COUNT(*) FROM credit_logs WHERE source_type = 'TASK'"))
                .isEqualTo(initialTaskCredits);
        assertTaskStatus(workflow.rootTaskId(), "PROCESSING");
        assertTaskStatus(workflow.childTaskId(), "QUEUED");
        assertRunStatus(workflow.runId(), "RUNNING");
    }

    @Test
    void userAndAdminRootCancellationUseWorkflowStateMachineWithoutTaskCreditOrOutboxEffects() throws Exception {
        UserFixture user = insertUser("workflow_task_root_cancel_boundary");
        WorkflowFixture userWorkflow = insertWorkflow(user.userId(), "user-cancel", false);
        WorkflowFixture adminWorkflow = insertWorkflow(user.userId(), "admin-cancel", false);
        int initialOutbox = count("SELECT COUNT(*) FROM task_outbox_events");
        int initialTaskCredits = count("SELECT COUNT(*) FROM credit_logs WHERE source_type = 'TASK'");

        mockMvc.perform(post("/api/v1/tasks/{taskId}/cancel", userWorkflow.rootTaskId())
                        .header("Authorization", bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        mockMvc.perform(post("/api/admin/v1/tasks/{taskId}/cancel", adminWorkflow.rootTaskId())
                        .header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertWorkflowCancelled(userWorkflow);
        assertWorkflowCancelled(adminWorkflow);
        assertThat(count("SELECT COUNT(*) FROM task_outbox_events")).isEqualTo(initialOutbox);
        assertThat(count("SELECT COUNT(*) FROM credit_logs WHERE source_type = 'TASK'"))
                .isEqualTo(initialTaskCredits);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder regenerate(
            Long taskId,
            String token,
            String requestId
    ) {
        return post("/api/v1/tasks/{taskId}/regenerate", taskId)
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"params":{"prompt":"blocked"},"clientRequestId":"%s"}
                        """.formatted(requestId));
    }

    private void expectTaskStatusInvalid(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request
    ) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TASK_STATUS_INVALID"));
    }

    private WorkflowFixture insertWorkflow(Long userId, String suffix, boolean includeChild) {
        Long toolId = insertTool("workflow-task-boundary-" + suffix);
        Long rootTaskId = insertTask(userId, toolId, "ROOT-" + suffix, "root-" + suffix, "PROCESSING");
        jdbcTemplate.update("""
                INSERT INTO workflow_runs(
                  user_id, tool_id, workflow_id, root_task_id, client_request_id, status,
                  revision, input_json, context_json, billing_status, started_at
                ) VALUES (?, ?, ?, ?, ?, 'RUNNING', 0, '{}', '{}', 'CLEAR', CURRENT_TIMESTAMP)
                """, userId, toolId, toolId, rootTaskId, "run-" + suffix);
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_runs WHERE root_task_id = ?",
                Long.class,
                rootTaskId
        );
        if (!includeChild) {
            return new WorkflowFixture(rootTaskId, null, runId);
        }

        Long childTaskId = insertTask(userId, toolId, "CHILD-" + suffix, "child-" + suffix, "QUEUED");
        jdbcTemplate.update("""
                INSERT INTO workflow_run_steps(
                  run_id, node_id, sequence_no, node_def_type, status, revision, task_id, attempt, attempt_count
                ) VALUES (?, 'worker', 1, 'llm_text', 'RUNNING', 0, ?, 1, 1)
                """, runId, childTaskId);
        Long stepId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ? AND node_id = 'worker'",
                Long.class,
                runId
        );
        jdbcTemplate.update("""
                INSERT INTO workflow_step_attempts(
                  step_id, attempt_no, cancellation_generation, child_task_id, status, claim_token
                ) VALUES (?, 1, 0, ?, 'DISPATCHED', ?)
                """, stepId, childTaskId, "claim-" + suffix);
        return new WorkflowFixture(rootTaskId, childTaskId, runId);
    }

    private Long insertTool(String toolCode) {
        jdbcTemplate.update("""
                INSERT INTO ai_tools(
                  tool_code, tool_name, category_id, status, estimated_credit_cost,
                  execution_handler, execution_mode, billing_mode, is_deleted
                ) VALUES (?, ?, 1, 'ONLINE', 0, 'TEXT_GENERATION', 'WORKFLOW', 'WORKFLOW_STEP', 0)
                """, toolCode, toolCode);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tools WHERE tool_code = ?",
                Long.class,
                toolCode
        );
    }

    private Long insertTask(Long userId, Long toolId, String taskNo, String idempotencyKey, String status) {
        jdbcTemplate.update("""
                INSERT INTO ai_tasks(
                  task_no, user_id, tool_id, status, progress, progress_message,
                  params_json, idempotency_key, estimated_credit_cost, queued_at, started_at
                ) VALUES (?, ?, ?, ?, 10, 'workflow boundary test', '{}', ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, taskNo, userId, toolId, status, idempotencyKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tasks WHERE user_id = ? AND idempotency_key = ?",
                Long.class,
                userId,
                idempotencyKey
        );
    }

    private UserFixture insertUser(String username) {
        jdbcTemplate.update("""
                INSERT INTO users(username, password_hash, nickname, user_type, status, is_deleted)
                VALUES (?, 'hash', ?, 'USER', 'ACTIVE', 0)
                """, username, username);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = ?",
                Long.class,
                username
        );
        return new UserFixture(userId, jwtTokenProvider.createToken(new AuthUser(userId, username, "USER")));
    }

    private void makeTaskStale(Long taskId) {
        jdbcTemplate.update("""
                UPDATE ai_tasks
                SET updated_at = DATEADD('MINUTE', -180, CURRENT_TIMESTAMP),
                    queued_at = DATEADD('MINUTE', -180, CURRENT_TIMESTAMP),
                    started_at = DATEADD('MINUTE', -180, CURRENT_TIMESTAMP)
                WHERE id = ?
                """, taskId);
    }

    private void assertWorkflowCancelled(WorkflowFixture workflow) {
        assertTaskStatus(workflow.rootTaskId(), "CANCELLED");
        assertRunStatus(workflow.runId(), "CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cancellation_generation FROM workflow_runs WHERE id = ?",
                Long.class,
                workflow.runId()
        )).isEqualTo(1L);
    }

    private void assertTaskStatus(Long taskId, String expected) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_tasks WHERE id = ?",
                String.class,
                taskId
        )).isEqualTo(expected);
    }

    private void assertRunStatus(Long runId, String expected) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?",
                String.class,
                runId
        )).isEqualTo(expected);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private String adminToken() {
        return jwtTokenProvider.createToken(new AuthUser(1L, "boundary-admin", "ADMIN"));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record UserFixture(Long userId, String token) {
    }

    private record WorkflowFixture(Long rootTaskId, Long childTaskId, Long runId) {
    }
}
