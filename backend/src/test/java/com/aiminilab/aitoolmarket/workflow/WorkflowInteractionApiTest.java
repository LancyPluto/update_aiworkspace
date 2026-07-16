package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.auth.security.AuthUser;
import com.aiminilab.aitoolmarket.auth.security.JwtTokenProvider;
import com.aiminilab.aitoolmarket.auth.security.TokenDenylistService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowExecutionService;
import com.aiminilab.aitoolmarket.task.dto.ClaimTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.service.InternalTaskService;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepAttempt;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowConfirmation;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowConfirmationMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowConfirmationTokenService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowCancellationService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowBillingService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowStepCallbackService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_interaction_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.connection-init-sql=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.workflow.confirmation.hmac-secret=test-workflow-confirmation-secret-with-at-least-32-characters"
})
class WorkflowInteractionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkflowExecutionService executionService;

    @Autowired
    private WorkflowStepAttemptMapper attemptMapper;

    @Autowired
    private InternalTaskService internalTaskService;

    @Autowired
    private WorkflowStepCallbackService callbackService;

    @Autowired
    private WorkflowConfirmationMapper confirmationMapper;

    @Autowired
    private WorkflowRunMapper runMapper;

    @Autowired
    private WorkflowRunStepMapper stepMapper;

    @Autowired
    private WorkflowConfirmationTokenService confirmationTokenService;

    @Autowired
    private WorkflowCancellationService cancellationService;

    @Autowired
    private WorkflowBillingService billingService;

    @MockBean
    private TokenDenylistService tokenDenylistService;

    @Test
    void confirmationPausesAndTokenCanOnlyBeConsumedOnce() throws Exception {
        UserFixture owner = insertUser("workflow_confirmation_owner");
        RunFixture fixture = startConfirmWorkflow(owner.userId(), true);

        assertThat(runStatus(fixture.runId())).isEqualTo("AWAITING_USER");
        assertThat(stepStatus(fixture.runId(), "confirm")).isEqualTo("AWAITING_USER");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_confirmations WHERE run_id = ? AND status = 'PENDING'",
                Integer.class,
                fixture.runId()
        )).isEqualTo(1);

        String token = confirmationToken(fixture.rootTaskId(), owner.token());
        String body = feedbackBody(fixture.focalStepId(), "APPROVE", token, "confirm-once");
        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/feedback", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/feedback", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_confirmations WHERE run_id = ? AND status = 'CONSUMED'",
                Integer.class,
                fixture.runId()
        )).isEqualTo(1);
    }

    @Test
    void confirmationRejectsOtherOwnerAndChangedParameters() throws Exception {
        UserFixture owner = insertUser("workflow_confirmation_security_owner");
        UserFixture other = insertUser("workflow_confirmation_security_other");
        RunFixture fixture = startConfirmWorkflow(owner.userId(), true);
        String token = confirmationToken(fixture.rootTaskId(), owner.token());
        String body = feedbackBody(fixture.focalStepId(), "APPROVE", token, "security-check");

        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/feedback", fixture.rootTaskId())
                        .header("Authorization", bearer(other.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        jdbcTemplate.update(
                "UPDATE workflow_runs SET context_json = '{\"changed\":true}' WHERE id = ?",
                fixture.runId()
        );
        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/feedback", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void legacyFeedbackCannotBypassConfirmationToken() throws Exception {
        UserFixture owner = insertUser("workflow_legacy_feedback_owner");
        RunFixture fixture = startConfirmWorkflow(owner.userId(), true);

        mockMvc.perform(post("/api/v1/tasks/{taskId}/workflow-feedback", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"));

        assertThat(runStatus(fixture.runId())).isEqualTo("AWAITING_USER");
        assertThat(stepStatus(fixture.runId(), "confirm")).isEqualTo("AWAITING_USER");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_confirmations WHERE run_id = ? AND status = 'PENDING'",
                Integer.class,
                fixture.runId()
        )).isEqualTo(1);
    }

    @Test
    void expiredConfirmationTokenCannotAdvanceRun() throws Exception {
        UserFixture owner = insertUser("workflow_confirmation_expired_owner");
        RunFixture fixture = startConfirmWorkflow(owner.userId(), true);
        jdbcTemplate.update(
                "UPDATE workflow_confirmations SET expires_at = DATEADD('MINUTE', -1, CURRENT_TIMESTAMP) WHERE run_id = ?",
                fixture.runId()
        );
        WorkflowConfirmation confirmation = confirmationMapper.selectPendingByRunId(fixture.runId());
        WorkflowRun run = runMapper.selectById(fixture.runId());
        WorkflowRunStep step = stepMapper.selectById(fixture.focalStepId());
        String token = confirmationTokenService.rawToken(confirmation, run, step);
        jdbcTemplate.update(
                "UPDATE workflow_confirmations SET token_hash = ? WHERE id = ?",
                confirmationTokenService.tokenHash(token),
                confirmation.getId()
        );

        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/feedback", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody(fixture.focalStepId(), "APPROVE", token, "expired-token")))
                .andExpect(status().isConflict());
        assertThat(runStatus(fixture.runId())).isEqualTo("AWAITING_USER");
    }

    @Test
    void missingAllowedActionsDoesNotGrantApproval() throws Exception {
        UserFixture owner = insertUser("workflow_confirmation_closed_owner");
        RunFixture fixture = startConfirmWorkflow(owner.userId(), false);
        MvcResult detail = mockMvc.perform(get("/api/v1/agents/runs/{taskId}", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userAction.allowedActions").isEmpty())
                .andReturn();
        String token = objectMapper.readTree(detail.getResponse().getContentAsString())
                .path("data").path("userAction").path("confirmationToken").asText();

        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/feedback", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody(fixture.focalStepId(), "APPROVE", token, "closed-actions")))
                .andExpect(status().isBadRequest());

        assertThat(runStatus(fixture.runId())).isEqualTo("AWAITING_USER");
    }

    @Test
    void rejectConsumesTokenThenSettlesCancellationAfterCommit() throws Exception {
        UserFixture owner = insertUser("workflow_confirmation_reject_owner");
        RunFixture fixture = startConfirmWorkflow(owner.userId(), true);
        String token = confirmationToken(fixture.rootTaskId(), owner.token());

        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/feedback", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody(fixture.focalStepId(), "REJECT", token, "reject-once")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, decision FROM workflow_confirmations WHERE run_id = ?",
                fixture.runId()
        )).containsEntry("status", "CONSUMED")
                .containsEntry("decision", "REJECT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cancellation_generation FROM workflow_runs WHERE id = ?",
                Long.class,
                fixture.runId()
        )).isEqualTo(1L);
    }

    @Test
    void rejectWithLostReservationStopsAtCancellingForReconciliation() throws Exception {
        UserFixture owner = insertUser("workflow_confirmation_reject_lost_owner");
        RunFixture fixture = startConfirmWorkflow(owner.userId(), true);
        Long completedStepId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ? AND node_id = 'start'",
                Long.class,
                fixture.runId()
        );
        jdbcTemplate.update(
                "INSERT INTO credit_accounts(user_id, balance, membership_balance, gift_balance, frozen, total_granted, total_consumed, status) VALUES (?, 100, 100, 0, 20, 100, 0, 'ACTIVE')",
                owner.userId()
        );
        jdbcTemplate.update(
                "INSERT INTO workflow_step_attempts(step_id, attempt_no, cancellation_generation, status, claim_token) VALUES (?, 1, 0, 'LOST', ?)",
                completedStepId,
                "lost-reject-" + fixture.runId()
        );
        Long lostAttemptId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_step_attempts WHERE claim_token = ?",
                Long.class,
                "lost-reject-" + fixture.runId()
        );
        jdbcTemplate.update(
                "INSERT INTO workflow_step_charges(run_id, step_id, attempt_id, user_id, status, reserved_credits, charged_credits, idempotency_key) VALUES (?, ?, ?, ?, 'RESERVED', 20, 0, ?)",
                fixture.runId(),
                completedStepId,
                lostAttemptId,
                owner.userId(),
                "lost-reject-charge-" + fixture.runId()
        );
        String token = confirmationToken(fixture.rootTaskId(), owner.token());

        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/feedback", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedbackBody(fixture.focalStepId(), "REJECT", token, "reject-lost")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLING"))
                .andExpect(jsonPath("$.data.cost.reservedCredits").value(20));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_confirmations WHERE run_id = ?",
                String.class,
                fixture.runId()
        )).isEqualTo("CONSUMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                String.class,
                lostAttemptId
        )).isEqualTo("RESERVED");
    }

    @Test
    void fundsResumeDoesNotAdvanceUntilReservationSucceeds() throws Exception {
        UserFixture owner = insertUser("workflow_funds_resume_owner");
        RunFixture fixture = startPaidWorkerWorkflow(owner.userId(), 20, 0);

        assertThat(runStatus(fixture.runId())).isEqualTo("AWAITING_FUNDS");
        assertThat(currentStepId(fixture.runId())).isEqualTo(fixture.focalStepId());
        assertThat(attemptCount(fixture.focalStepId())).isZero();

        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/resume", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_FUNDS"));
        assertThat(currentStepId(fixture.runId())).isEqualTo(fixture.focalStepId());
        assertThat(attemptCount(fixture.focalStepId())).isZero();

        jdbcTemplate.update(
                "UPDATE credit_accounts SET balance = 20, membership_balance = 20, total_granted = 20 WHERE user_id = ?",
                owner.userId()
        );
        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/resume", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        assertThat(attemptCount(fixture.focalStepId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_charges WHERE run_id = ? AND status = 'RESERVED'",
                Integer.class,
                fixture.runId()
        )).isEqualTo(1);
    }

    @Test
    void cancellationConvergesOnceAndLateProviderChargeOnlyRecordsCost() throws Exception {
        UserFixture owner = insertUser("workflow_cancel_owner");
        RunFixture fixture = startPaidWorkerWorkflow(owner.userId(), 20, 100);
        AgentLink agent = attachDelegatedAgentCall(fixture, owner.userId());
        WorkflowStepAttempt attempt = attemptMapper.selectById(
                jdbcTemplate.queryForObject(
                        "SELECT current_attempt_id FROM workflow_run_steps WHERE id = ?",
                        Long.class,
                        fixture.focalStepId()
                )
        );
        assertThat(attempt.getCancellationGeneration()).isZero();
        assertThat(internalTaskService.claim(
                attempt.getChildTaskId(), new ClaimTaskRequest("interaction-worker", "interaction-claim")
        ).claimed()).isTrue();

        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/cancel", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/cancel", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, cancellation_generation FROM workflow_runs WHERE id = ?",
                fixture.runId()
        )).containsEntry("status", "CANCELLED")
                .containsEntry("cancellation_generation", 1L);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, error_code, error_message FROM agent_tool_calls WHERE id = ?",
                agent.toolCallId()
        )).containsEntry("status", "CANCELLED")
                .containsEntry("error_code", "WORKFLOW_CANCELLED")
                .containsEntry("error_message", "USER_CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run_events WHERE run_id = ? AND event_type = 'tool.finished'",
                Integer.class,
                agent.agentRunId()
        )).isEqualTo(1);
        assertThat(stepStatus(fixture.runId(), "worker")).isEqualTo("CANCELLED");
        assertThat(attemptMapper.selectById(attempt.getId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_tasks WHERE id = ?", String.class, fixture.rootTaskId()
        )).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, charged_credits FROM workflow_step_charges WHERE attempt_id = ?",
                attempt.getId()
        )).containsEntry("status", "RELEASED")
                .containsEntry("charged_credits", 0);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT balance, frozen, total_consumed FROM credit_accounts WHERE user_id = ?",
                owner.userId()
        )).containsEntry("balance", 100)
                .containsEntry("frozen", 0)
                .containsEntry("total_consumed", 0);

        WorkerFailedRequest lateChargedFailure = new WorkerFailedRequest(
                "PROVIDER_ERROR", "charged after cancellation", "PROVIDER_CALL", true,
                new BigDecimal("0.25"), "CNY", "UPSTREAM_500", "late-provider-request",
                10, 5, 1, "interaction-claim"
        );
        assertThat(callbackService.failed(attempt.getChildTaskId(), lateChargedFailure)).isFalse();
        assertThat(callbackService.failed(attempt.getChildTaskId(), lateChargedFailure)).isFalse();

        assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_cost FROM workflow_step_charges WHERE attempt_id = ?",
                BigDecimal.class,
                attempt.getId()
        )).isEqualByComparingTo("0.25");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                attempt.getClaimToken() + ":usage"
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT charged_credits FROM billing_usage_logs WHERE idempotency_key = ?",
                Integer.class,
                attempt.getClaimToken() + ":usage"
        )).isZero();
    }

    @Test
    void lostAttemptWithReservedChargeKeepsRunCancellingForReconciliation() throws Exception {
        UserFixture owner = insertUser("workflow_cancel_lost_owner");
        RunFixture fixture = startPaidWorkerWorkflow(owner.userId(), 20, 100);
        AgentLink agent = attachDelegatedAgentCall(fixture, owner.userId());
        Long attemptId = jdbcTemplate.queryForObject(
                "SELECT current_attempt_id FROM workflow_run_steps WHERE id = ?",
                Long.class,
                fixture.focalStepId()
        );
        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET status = 'LOST' WHERE id = ?",
                attemptId
        );

        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/cancel", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLING"))
                .andExpect(jsonPath("$.data.cost.reservedCredits").value(20));
        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/cancel", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLING"));
        assertThat(cancellationService.settlePersisted(fixture.runId())).isFalse();
        assertThat(cancellationService.settlePersisted(fixture.runId())).isFalse();

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, cancellation_generation FROM workflow_runs WHERE id = ?",
                fixture.runId()
        )).containsEntry("status", "CANCELLING")
                .containsEntry("cancellation_generation", 1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                String.class,
                attemptId
        )).isEqualTo("RESERVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT frozen FROM credit_accounts WHERE user_id = ?",
                Integer.class,
                owner.userId()
        )).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM agent_tool_calls WHERE id = ?",
                String.class,
                agent.toolCallId()
        )).isEqualTo("DELEGATED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run_events WHERE run_id = ? AND event_type = 'tool.finished'",
                Integer.class,
                agent.agentRunId()
        )).isZero();
    }

    @Test
    void lostAttemptWithoutReservedChargeStillWaitsForReconciliation() throws Exception {
        UserFixture owner = insertUser("workflow_cancel_lost_released_owner");
        RunFixture fixture = startPaidWorkerWorkflow(owner.userId(), 20, 100);
        AgentLink agent = attachDelegatedAgentCall(fixture, owner.userId());
        Long attemptId = jdbcTemplate.queryForObject(
                "SELECT current_attempt_id FROM workflow_run_steps WHERE id = ?",
                Long.class,
                fixture.focalStepId()
        );
        billingService.release(attemptId);
        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET status = 'LOST' WHERE id = ?",
                attemptId
        );

        mockMvc.perform(post("/api/v1/agents/runs/{taskId}/cancel", fixture.rootTaskId())
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLING"));
        assertThat(cancellationService.settlePersisted(fixture.runId())).isFalse();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                String.class,
                attemptId
        )).isEqualTo("RELEASED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM agent_tool_calls WHERE id = ?",
                String.class,
                agent.toolCallId()
        )).isEqualTo("DELEGATED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run_events WHERE run_id = ? AND event_type = 'tool.finished'",
                Integer.class,
                agent.agentRunId()
        )).isZero();

        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET status = 'CANCELLED' WHERE id = ? AND status = 'LOST'",
                attemptId
        );
        assertThat(cancellationService.settlePersisted(fixture.runId())).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM agent_tool_calls WHERE id = ?",
                String.class,
                agent.toolCallId()
        )).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run_events WHERE run_id = ? AND event_type = 'tool.finished'",
                Integer.class,
                agent.agentRunId()
        )).isEqualTo(1);
    }

    @Test
    void persistedCancellingRunCanResumeSecondPhaseWithoutIncrementingGeneration() {
        UserFixture owner = insertUser("workflow_cancel_recovery_owner");
        RunFixture fixture = startPaidWorkerWorkflow(owner.userId(), 20, 100);

        cancellationService.begin(fixture.rootTaskId(), owner.userId(), "USER_CANCELLED");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, cancellation_generation FROM workflow_runs WHERE id = ?",
                fixture.runId()
        )).containsEntry("status", "CANCELLING")
                .containsEntry("cancellation_generation", 1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE run_id = ?",
                String.class,
                fixture.runId()
        )).isEqualTo("RESERVED");

        assertThat(cancellationService.settlePersisted(fixture.runId())).isTrue();
        assertThat(cancellationService.settlePersisted(fixture.runId())).isTrue();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, cancellation_generation FROM workflow_runs WHERE id = ?",
                fixture.runId()
        )).containsEntry("status", "CANCELLED")
                .containsEntry("cancellation_generation", 1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE run_id = ?",
                String.class,
                fixture.runId()
        )).isEqualTo("RELEASED");
    }

    private RunFixture startConfirmWorkflow(Long userId, boolean includeAllowedActions) {
        String parameters = includeAllowedActions
                ? "{\"sourceNodeId\":\"start\",\"allowedActions\":[\"APPROVE\",\"REJECT\",\"CONTINUE_WITH_FEEDBACK\"]}"
                : "{\"sourceNodeId\":\"start\"}";
        String nodes = """
                [{"id":"start","data":{"nodeDefType":"start","title":"Start"}},
                 {"id":"confirm","data":{"nodeDefType":"user_confirm","title":"Confirm","parameters":%s}},
                 {"id":"output","data":{"nodeDefType":"video_output","title":"Output"}}]
                """.formatted(parameters);
        String edges = """
                [{"id":"e1","source":"start","target":"confirm"},
                 {"id":"e2","source":"confirm","target":"output"}]
                """;
        return startWorkflow(userId, "confirm", nodes, edges, "{}", 0);
    }

    private RunFixture startPaidWorkerWorkflow(Long userId, int requiredCredits, int balance) {
        String nodes = """
                [{"id":"worker","data":{"nodeDefType":"llm_text","title":"Worker",
                  "parameters":{"maxCreditCost":%d}}}]
                """.formatted(requiredCredits);
        String policy = """
                {"mode":"WORKFLOW_STEP","nodePolicies":{"worker":{
                  "maxCreditCost":%d,"maxProviderCostCny":0.10,"fallbackChargeCredits":%d,"staticParams":{},
                  "modelPricingSnapshot":null,
                  "pricingPolicy":{"markupRatio":1.0,"minCredits":0,
                  "imageEstimateInputTokens":8000,"imageEstimateOutputTokens":8000,"rules":[]}}}}
                """.formatted(requiredCredits, requiredCredits);
        jdbcTemplate.update(
                "INSERT INTO credit_accounts(user_id, balance, membership_balance, gift_balance, frozen, total_granted, total_consumed, status) VALUES (?, ?, ?, 0, 0, ?, 0, 'ACTIVE')",
                userId,
                balance,
                balance,
                balance
        );
        return startWorkflow(userId, "worker", nodes, "[]", policy, requiredCredits);
    }

    private RunFixture startWorkflow(Long userId,
                                     String focalNodeId,
                                     String nodes,
                                     String edges,
                                     String billingPolicy,
                                     int estimatedCredits) {
        String suffix = userId + "_" + focalNodeId + "_" + System.nanoTime();
        String toolCode = "workflow_interaction_" + suffix;
        jdbcTemplate.update(
                "INSERT INTO ai_tools(tool_code, tool_name, category_id, status, estimated_credit_cost, execution_handler, execution_mode, billing_mode, agent_surface_enabled, minimum_required_credits, is_deleted) VALUES (?, 'Interaction Test', 1, 'ONLINE', ?, 'TEXT_GENERATION', 'WORKFLOW', 'WORKFLOW_STEP', 1, ?, 0)",
                toolCode,
                estimatedCredits,
                estimatedCredits
        );
        Long toolId = jdbcTemplate.queryForObject("SELECT id FROM ai_tools WHERE tool_code = ?", Long.class, toolCode);
        jdbcTemplate.update(
                "INSERT INTO tool_workflows(tool_id, workflow_name, nodes_json, edges_json, config_json, version, status, draft_revision, execution_enabled) VALUES (?, 'default', ?, ?, '{}', 1, 'PUBLISHED', 1, 1)",
                toolId,
                nodes,
                edges
        );
        Long workflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflows WHERE tool_id = ?",
                Long.class,
                toolId
        );
        jdbcTemplate.update(
                "INSERT INTO tool_workflow_versions(workflow_id, version, nodes_json, edges_json, config_json, canonical_dsl_json, dsl_version, node_registry_version, dsl_hash, input_schema_snapshot_json, dependency_manifest_json, billing_policy_json, risk_policy_json, source_draft_revision, published_at, published_by) VALUES (?, 1, ?, ?, '{}', '{}', '1', 'p0', ?, '{}', '{}', ?, '{}', 1, CURRENT_TIMESTAMP, ?)",
                workflowId,
                nodes,
                edges,
                "hash-" + suffix,
                billingPolicy,
                userId
        );
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflow_versions WHERE workflow_id = ?",
                Long.class,
                workflowId
        );
        jdbcTemplate.update("UPDATE tool_workflows SET published_version_id = ? WHERE id = ?", versionId, workflowId);
        jdbcTemplate.update(
                "INSERT INTO ai_tasks(task_no, user_id, tool_id, status, progress, progress_message, params_json, idempotency_key, estimated_credit_cost, queued_at) VALUES (?, ?, ?, 'QUEUED', 0, 'queued', '{}', ?, 0, CURRENT_TIMESTAMP)",
                "ROOT-" + suffix,
                userId,
                toolId,
                "root-" + suffix
        );
        Long rootTaskId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tasks WHERE idempotency_key = ?",
                Long.class,
                "root-" + suffix
        );
        executionService.startForRootTask(rootTaskId);
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_runs WHERE root_task_id = ?",
                Long.class,
                rootTaskId
        );
        Long stepId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ? AND node_id = ?",
                Long.class,
                runId,
                focalNodeId
        );
        return new RunFixture(rootTaskId, runId, stepId);
    }

    private UserFixture insertUser(String username) {
        jdbcTemplate.update(
                "INSERT INTO users(username, password_hash, nickname, user_type, status, is_deleted) VALUES (?, 'hash', ?, 'USER', 'ACTIVE', 0)",
                username,
                username
        );
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
        return new UserFixture(userId, jwtTokenProvider.createToken(new AuthUser(userId, username, "USER")));
    }

    private AgentLink attachDelegatedAgentCall(RunFixture fixture, Long userId) {
        String key = "interaction-agent-link-" + fixture.rootTaskId();
        String toolCode = jdbcTemplate.queryForObject("""
                SELECT tool.tool_code
                FROM ai_tasks task
                JOIN ai_tools tool ON tool.id = task.tool_id
                WHERE task.id = ?
                """, String.class, fixture.rootTaskId());
        jdbcTemplate.update(
                "INSERT INTO agent_sessions(user_id, title, status) VALUES (?, ?, 'ACTIVE')",
                userId,
                key
        );
        Long sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_sessions WHERE user_id = ? AND title = ?",
                Long.class,
                userId,
                key
        );
        jdbcTemplate.update("""
                INSERT INTO agent_runs(
                  session_id, user_id, status, client_request_id, started_at, finished_at, created_at, updated_at
                ) VALUES (?, ?, 'SUCCESS', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, sessionId, userId, key);
        Long agentRunId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_runs WHERE user_id = ? AND client_request_id = ?",
                Long.class,
                userId,
                key
        );
        jdbcTemplate.update("""
                INSERT INTO agent_tool_calls(
                  run_id, user_id, tool_code, task_id, status, arguments_json, started_at, created_at
                ) VALUES (?, ?, ?, ?, 'DELEGATED', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, agentRunId, userId, toolCode, fixture.rootTaskId());
        Long toolCallId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_tool_calls WHERE run_id = ? AND task_id = ?",
                Long.class,
                agentRunId,
                fixture.rootTaskId()
        );
        return new AgentLink(agentRunId, toolCallId);
    }

    private String confirmationToken(Long rootTaskId, String token) throws Exception {
        MvcResult detail = mockMvc.perform(get("/api/v1/agents/runs/{taskId}", rootTaskId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userAction.confirmationToken").isNotEmpty())
                .andReturn();
        JsonNode body = objectMapper.readTree(detail.getResponse().getContentAsString());
        return body.path("data").path("userAction").path("confirmationToken").asText();
    }

    private String feedbackBody(Long stepId, String action, String token, String idempotencyKey) {
        return """
                {"stepId":%d,"action":"%s","fields":{},"confirmationToken":"%s","idempotencyKey":"%s"}
                """.formatted(stepId, action, token, idempotencyKey);
    }

    private String runStatus(Long runId) {
        return jdbcTemplate.queryForObject("SELECT status FROM workflow_runs WHERE id = ?", String.class, runId);
    }

    private String stepStatus(Long runId, String nodeId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_run_steps WHERE run_id = ? AND node_id = ?",
                String.class,
                runId,
                nodeId
        );
    }

    private Long currentStepId(Long runId) {
        return jdbcTemplate.queryForObject("SELECT current_step_id FROM workflow_runs WHERE id = ?", Long.class, runId);
    }

    private int attemptCount(Long stepId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_attempts WHERE step_id = ?",
                Integer.class,
                stepId
        );
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record UserFixture(Long userId, String token) {
    }

    private record RunFixture(Long rootTaskId, Long runId, Long focalStepId) {
    }

    private record AgentLink(Long agentRunId, Long toolCallId) {
    }
}
