package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.task.dto.ClaimTaskRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.service.InternalTaskService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepAttempt;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowExecutionService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRecoveryScheduler;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowStepCallbackService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowStepScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_step_scheduler_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class WorkflowStepSchedulerTest {

    @Autowired
    private WorkflowStepScheduler scheduler;

    @Autowired
    private WorkflowRecoveryScheduler recoveryScheduler;

    @Autowired
    private WorkflowStepCallbackService callbackService;

    @Autowired
    private WorkflowRunStepMapper stepMapper;

    @Autowired
    private WorkflowStepAttemptMapper attemptMapper;

    @Autowired
    private InternalTaskService internalTaskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockBean
    private WorkflowExecutionService executionService;

    private long runId;
    private long stepId;

    @BeforeEach
    void setUp() {
        TestWorkflow workflow = insertWorkflow("workflow_scheduler_test");
        runId = workflow.runId();
        stepId = workflow.stepId();
    }

    @Test
    void repeatedDispatchCreatesOneAttemptChildTaskAndOutbox() {
        WorkflowStepAttempt first = scheduler.dispatch(stepId);
        WorkflowStepAttempt duplicate = scheduler.dispatch(stepId);

        assertThat(duplicate.getId()).isEqualTo(first.getId());
        assertThat(first.getAttemptNo()).isEqualTo(1);
        assertThat(first.getClaimToken()).isEqualTo(
                "workflow:%d:step:%d:attempt:1".formatted(runId, stepId));
        assertThat(first.getChildTaskId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM ai_tasks WHERE id = ?",
                String.class,
                first.getChildTaskId()
        )).isEqualTo(first.getClaimToken());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_attempts WHERE step_id = ?",
                Integer.class,
                stepId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_outbox_events WHERE task_id = ? AND event_type = 'TASK_CREATED'",
                Integer.class,
                first.getChildTaskId()
        )).isEqualTo(1);

        WorkflowRunStep step = stepMapper.selectById(stepId);
        assertThat(step.getCurrentAttemptId()).isEqualTo(first.getId());
        assertThat(step.getAttemptCount()).isEqualTo(1);
        assertThat(step.getStatus()).isEqualTo("QUEUED");
    }

    @Test
    void dispatchPassesStableHandlerKeyAndOperationPayloadToChildTask() {
        jdbcTemplate.update("""
                UPDATE tool_workflow_versions
                SET nodes_json = '[{"id":"worker","data":{"nodeDefType":"llm_text","title":"Worker","parameters":{"handlerKey":"comic.shot.video","maxCreditCost":1}}}]'
                WHERE id = (SELECT workflow_version_id FROM workflow_runs WHERE id = ?)
                """, runId);
        jdbcTemplate.update(
                "UPDATE workflow_run_steps SET input_json = ? WHERE id = ?",
                "{\"operationInput\":{\"shot\":{\"id\":7}}}",
                stepId
        );

        WorkflowStepAttempt attempt = scheduler.dispatch(stepId);
        String paramsJson = jdbcTemplate.queryForObject(
                "SELECT params_json FROM ai_tasks WHERE id = ?", String.class, attempt.getChildTaskId()
        );

        assertThat(paramsJson).contains("handlerKey").contains("comic.shot.video");
        assertThat(paramsJson).contains("operation").contains("operationInput");
    }

    @Test
    void insufficientFundsPausesBeforeAttemptChildChargeOrOutbox() {
        configurePaidStep(20, 10);

        assertThat(scheduler.dispatch(stepId)).isNull();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?", String.class, runId
        )).isEqualTo("AWAITING_FUNDS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_step_id FROM workflow_runs WHERE id = ?", Long.class, runId
        )).isEqualTo(stepId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_tasks WHERE id = (SELECT root_task_id FROM workflow_runs WHERE id = ?)",
                String.class,
                runId
        )).isEqualTo("AWAITING_FUNDS");
        assertThat(stepMapper.selectById(stepId).getStatus()).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_attempts WHERE step_id = ?", Integer.class, stepId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_charges WHERE step_id = ?", Integer.class, stepId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tasks WHERE idempotency_key LIKE ?", Integer.class,
                "workflow:" + runId + ":step:" + stepId + ":attempt:%"
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_outbox_events WHERE task_id IN "
                        + "(SELECT id FROM ai_tasks WHERE idempotency_key LIKE ?)",
                Integer.class,
                "workflow:" + runId + ":step:" + stepId + ":attempt:%"
        )).isZero();

        assertThat(scheduler.dispatch(stepId)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_attempts WHERE step_id = ?", Integer.class, stepId
        )).isZero();
    }

    @Test
    void paidDispatchReservesBeforeCreatingAttemptChildAndOutbox() {
        configurePaidStep(20, 100);

        WorkflowStepAttempt attempt = scheduler.dispatch(stepId);

        assertThat(attempt).isNotNull();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, reserved_credits, charged_credits, attempt_id "
                        + "FROM workflow_step_charges WHERE step_id = ?",
                stepId
        )).containsEntry("status", "RESERVED")
                .containsEntry("reserved_credits", 20)
                .containsEntry("charged_credits", 0)
                .containsEntry("attempt_id", attempt.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT frozen FROM credit_accounts WHERE user_id = 1", Integer.class
        )).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_outbox_events WHERE task_id = ? AND event_type = 'TASK_CREATED'",
                Integer.class,
                attempt.getChildTaskId()
        )).isEqualTo(1);
    }

    @Test
    void cancellingRunCannotDispatchOrCreateBillingAndExecutionRecords() {
        configurePaidStep(20, 100);
        jdbcTemplate.update(
                "UPDATE workflow_runs SET status = 'CANCELLING', revision = revision + 1 WHERE id = ?",
                runId
        );

        assertThatThrownBy(() -> scheduler.dispatch(stepId))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TASK_STATUS_INVALID);
                    assertThat(exception).hasMessageContaining("CANCELLING");
                });

        assertThat(stepMapper.selectById(stepId).getStatus()).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT frozen FROM credit_accounts WHERE user_id = 1", Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_charges WHERE step_id = ?", Integer.class, stepId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_attempts WHERE step_id = ?", Integer.class, stepId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_tasks WHERE idempotency_key LIKE ?", Integer.class,
                "workflow:" + runId + ":step:" + stepId + ":attempt:%"
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_outbox_events WHERE task_id IN "
                        + "(SELECT id FROM ai_tasks WHERE idempotency_key LIKE ?)",
                Integer.class,
                "workflow:" + runId + ":step:" + stepId + ":attempt:%"
        )).isZero();
    }

    @Test
    void paidDslWithoutInlineCapUsesPublishedNodeReservationCap() {
        resetCredits(100);
        setVersionFallbackCap(15);

        WorkflowStepAttempt attempt = scheduler.dispatch(stepId);

        assertThat(attempt).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_credits FROM workflow_step_charges WHERE attempt_id = ?",
                Integer.class,
                attempt.getId()
        )).isEqualTo(15);
    }

    @Test
    void paidWorkerStepWithoutAnyReservationCapFailsClosedBeforeDispatch() {
        resetCredits(100);
        setVersionFallbackCap(0);

        assertThatThrownBy(() -> scheduler.dispatch(stepId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reservation cap");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_attempts WHERE step_id = ?", Integer.class, stepId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_charges WHERE step_id = ?", Integer.class, stepId
        )).isZero();
    }

    @Test
    void expiredRunningAttemptBecomesLostAndWaitsForReconciliation() {
        configurePaidStep(20, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        internalTaskService.claim(
                active.getChildTaskId(),
                new ClaimTaskRequest("workflow-worker", "unknown-provider-claim")
        );
        LocalDateTime cutoff = LocalDateTime.now();
        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                cutoff.minusMinutes(1),
                active.getId()
        );

        assertThat(recoveryScheduler.recoverExpiredAttempts(cutoff, 10)).isEqualTo(1);
        assertThat(attemptMapper.selectById(active.getId()).getStatus()).isEqualTo("LOST");
        WorkflowRunStep recovered = stepMapper.selectById(stepId);
        assertThat(recovered.getStatus()).isEqualTo("RUNNING");
        assertThat(recovered.getCurrentAttemptId()).isEqualTo(active.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                String.class,
                active.getId()
        )).isEqualTo("RESERVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT frozen FROM credit_accounts WHERE user_id = 1", Integer.class
        )).isEqualTo(20);
        assertThatThrownBy(() -> scheduler.retry(stepId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void expiredDispatchedAttemptReleasesAndAutomaticallyDispatchesRetry() {
        configurePaidStep(20, 100);
        WorkflowStepAttempt active = scheduler.dispatch(stepId);
        LocalDateTime cutoff = LocalDateTime.now();
        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                cutoff.minusMinutes(1),
                active.getId()
        );

        assertThat(recoveryScheduler.recoverExpiredAttempts(cutoff, 10)).isEqualTo(1);
        assertThat(attemptMapper.selectById(active.getId()).getStatus()).isEqualTo("TIMEOUT");
        WorkflowRunStep recovered = stepMapper.selectById(stepId);
        WorkflowStepAttempt retry = attemptMapper.selectById(recovered.getCurrentAttemptId());
        assertThat(recovered.getStatus()).isEqualTo("QUEUED");
        assertThat(recovered.getCurrentAttemptId()).isNotEqualTo(active.getId());
        assertThat(retry.getAttemptNo()).isEqualTo(2);
        assertThat(retry.getStatus()).isEqualTo("DISPATCHED");
        assertThat(taskStatus(active.getChildTaskId())).isEqualTo("TIMEOUT");
        assertThat(taskStatus(retry.getChildTaskId())).isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                String.class,
                active.getId()
        )).isEqualTo("RELEASED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                String.class,
                retry.getId()
        )).isEqualTo("RESERVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT frozen FROM credit_accounts WHERE user_id = 1", Integer.class
        )).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_outbox_events WHERE task_id = ? AND event_type = 'TASK_CREATED'",
                Integer.class,
                retry.getChildTaskId()
        )).isEqualTo(1);
    }

    @Test
    void expiredNonCurrentAttemptDoesNotAffectCurrentStep() {
        WorkflowStepAttempt first = scheduler.dispatch(stepId);
        jdbcTemplate.update("UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(2), first.getId());
        jdbcTemplate.update(
                "UPDATE workflow_run_steps SET status = 'READY', revision = revision + 1 WHERE id = ?",
                stepId
        );
        WorkflowStepAttempt second = scheduler.retry(stepId);

        assertThat(recoveryScheduler.recoverExpiredAttempts(LocalDateTime.now(), 10)).isZero();
        assertThat(attemptMapper.selectById(first.getId()).getStatus()).isEqualTo("TIMEOUT");
        WorkflowRunStep current = stepMapper.selectById(stepId);
        assertThat(current.getCurrentAttemptId()).isEqualTo(second.getId());
        assertThat(current.getStatus()).isEqualTo("QUEUED");
    }

    @Test
    void expiredNonCurrentAttemptsCannotStarveCurrentRecoveryBatch() {
        LocalDateTime cutoff = LocalDateTime.now();
        for (int attemptNo = 1; attemptNo <= 3; attemptNo++) {
            jdbcTemplate.update("""
                    INSERT INTO workflow_step_attempts(
                      step_id, attempt_no, status, claim_token, lease_expires_at,
                      started_at, created_at, updated_at
                    ) VALUES (?, ?, 'DISPATCHED', ?, ?, CURRENT_TIMESTAMP,
                              CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    stepId,
                    attemptNo,
                    "old-noncurrent-" + attemptNo,
                    cutoff.minusMinutes(5)
            );
        }
        jdbcTemplate.update(
                "UPDATE workflow_run_steps SET attempt = 3, attempt_count = 3, max_attempts = 5 WHERE id = ?",
                stepId
        );
        WorkflowStepAttempt current = scheduler.dispatch(stepId);
        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                cutoff.minusMinutes(1),
                current.getId()
        );

        assertThat(recoveryScheduler.recoverExpiredAttempts(cutoff, 2)).isZero();
        assertThat(recoveryScheduler.recoverExpiredAttempts(cutoff, 2)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM workflow_step_attempts WHERE step_id = ? ORDER BY attempt_no",
                String.class,
                stepId
        )).containsExactly("TIMEOUT", "TIMEOUT", "TIMEOUT", "TIMEOUT", "DISPATCHED");
        WorkflowRunStep recovered = stepMapper.selectById(stepId);
        WorkflowStepAttempt retry = attemptMapper.selectById(recovered.getCurrentAttemptId());
        assertThat(recovered.getCurrentAttemptId()).isNotEqualTo(current.getId());
        assertThat(recovered.getStatus()).isEqualTo("QUEUED");
        assertThat(retry.getAttemptNo()).isEqualTo(5);
        assertThat(retry.getStatus()).isEqualTo("DISPATCHED");
        assertThat(taskStatus(current.getChildTaskId())).isEqualTo("TIMEOUT");
        assertThat(taskStatus(retry.getChildTaskId())).isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                String.class,
                current.getId()
        )).isEqualTo("RELEASED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                String.class,
                retry.getId()
        )).isEqualTo("RESERVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_outbox_events WHERE task_id = ? AND event_type = 'TASK_CREATED'",
                Integer.class,
                retry.getChildTaskId()
        )).isEqualTo(1);
    }

    @Test
    void recoveryWaitsForStepLockBeforeMutatingAttemptsAndThenConverges() throws Exception {
        LocalDateTime cutoff = LocalDateTime.now();
        jdbcTemplate.update("UPDATE workflow_run_steps SET max_attempts = 3 WHERE id = ?", stepId);
        WorkflowStepAttempt first = scheduler.dispatch(stepId);
        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                cutoff.minusMinutes(2),
                first.getId()
        );
        jdbcTemplate.update(
                "UPDATE workflow_run_steps SET status = 'READY', revision = revision + 1 WHERE id = ?",
                stepId
        );
        WorkflowStepAttempt second = scheduler.retry(stepId);
        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                cutoff.minusMinutes(1),
                second.getId()
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Integer>> recovery = new AtomicReference<>();
        boolean[] firstCommitted = {false};
        int recovered;
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                stepMapper.selectByIdForUpdate(stepId);
                recovery.set(executor.submit(() -> recoveryScheduler.recoverExpiredAttempts(cutoff, 10)));
                firstCommitted[0] = awaitAttemptStatus(first.getId(), "TIMEOUT", 250, TimeUnit.MILLISECONDS);
                jdbcTemplate.update(
                        "UPDATE workflow_run_steps SET revision = revision + 1 WHERE id = ?",
                        stepId
                );
            });

            assertThat(firstCommitted[0]).isFalse();
            recovered = recovery.get().get(5, TimeUnit.SECONDS);
            assertThat(recovered).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(attemptMapper.selectById(first.getId()).getStatus()).isEqualTo("TIMEOUT");
        assertThat(taskStatus(first.getChildTaskId())).isEqualTo("TIMEOUT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                String.class,
                first.getId()
        )).isEqualTo("RELEASED");

        WorkflowRunStep recoveredStep = stepMapper.selectById(stepId);
        if (recovered == 0) {
            assertThat(attemptMapper.selectById(second.getId()).getStatus()).isEqualTo("DISPATCHED");
            assertThat(taskStatus(second.getChildTaskId())).isEqualTo("QUEUED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                    String.class,
                    second.getId()
            )).isEqualTo("RESERVED");
            assertThat(recoveredStep.getStatus()).isEqualTo("QUEUED");
            assertThat(recoveredStep.getCurrentAttemptId()).isEqualTo(second.getId());
            assertThat(recoveredStep.getAttemptCount()).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM workflow_step_attempts WHERE step_id = ?",
                    Integer.class,
                    stepId
            )).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM task_outbox_events WHERE task_id = ? AND event_type = 'TASK_CREATED'",
                    Integer.class,
                    second.getChildTaskId()
            )).isEqualTo(1);
        } else {
            assertThat(recovered).isEqualTo(1);
            assertThat(attemptMapper.selectById(second.getId()).getStatus()).isEqualTo("TIMEOUT");
            assertThat(taskStatus(second.getChildTaskId())).isEqualTo("TIMEOUT");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                    String.class,
                    second.getId()
            )).isEqualTo("RELEASED");
            WorkflowStepAttempt third = attemptMapper.selectById(recoveredStep.getCurrentAttemptId());
            assertThat(recoveredStep.getStatus()).isEqualTo("QUEUED");
            assertThat(recoveredStep.getCurrentAttemptId()).isNotNull().isNotEqualTo(second.getId());
            assertThat(recoveredStep.getAttemptCount()).isEqualTo(3);
            assertThat(third).isNotNull();
            assertThat(third.getAttemptNo()).isEqualTo(3);
            assertThat(third.getStatus()).isEqualTo("DISPATCHED");
            assertThat(taskStatus(third.getChildTaskId())).isEqualTo("QUEUED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM workflow_step_charges WHERE attempt_id = ?",
                    String.class,
                    third.getId()
            )).isEqualTo("RESERVED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM workflow_step_attempts WHERE step_id = ?",
                    Integer.class,
                    stepId
            )).isEqualTo(3);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM task_outbox_events WHERE task_id = ? AND event_type = 'TASK_CREATED'",
                    Integer.class,
                    third.getChildTaskId()
            )).isEqualTo(1);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT frozen FROM credit_accounts WHERE user_id = 1", Integer.class
        )).isEqualTo(1);
    }

    @Test
    void workerClaimMarksCurrentAttemptRunningWithTaskLease() {
        WorkflowStepAttempt attempt = scheduler.dispatch(stepId);

        var claim = internalTaskService.claim(
                attempt.getChildTaskId(),
                new ClaimTaskRequest("workflow-worker", "workflow-worker-claim")
        );

        assertThat(claim.claimed()).isTrue();
        WorkflowStepAttempt running = attemptMapper.selectById(attempt.getId());
        assertThat(running.getStatus()).isEqualTo("RUNNING");
        assertThat(running.getLeaseExpiresAt()).isEqualTo(claim.leaseUntil());
        assertThat(stepMapper.selectById(stepId).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void workerLeaseRenewalExtendsCurrentAttemptLease() {
        WorkflowStepAttempt attempt = scheduler.dispatch(stepId);
        ClaimTaskRequest claimRequest = new ClaimTaskRequest("workflow-worker", "workflow-worker-renew");
        internalTaskService.claim(attempt.getChildTaskId(), claimRequest);
        LocalDateTime expired = LocalDateTime.now().minusMinutes(1);
        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                expired,
                attempt.getId()
        );

        assertThat(internalTaskService.renewLease(attempt.getChildTaskId(), claimRequest).claimed()).isTrue();
        WorkflowStepAttempt renewed = attemptMapper.selectById(attempt.getId());
        assertThat(renewed.getStatus()).isEqualTo("RUNNING");
        assertThat(renewed.getLeaseExpiresAt()).isAfter(expired);
    }

    @Test
    void expiredFinalAttemptFailsStepInsteadOfLeavingItReady() {
        jdbcTemplate.update("UPDATE workflow_run_steps SET max_attempts = 1 WHERE id = ?", stepId);
        WorkflowStepAttempt attempt = scheduler.dispatch(stepId);
        LocalDateTime cutoff = LocalDateTime.now();
        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                cutoff.minusMinutes(1),
                attempt.getId()
        );

        assertThat(recoveryScheduler.recoverExpiredAttempts(cutoff, 10)).isEqualTo(1);
        assertThat(attemptMapper.selectById(attempt.getId()).getStatus()).isEqualTo("TIMEOUT");
        assertThat(stepMapper.selectById(stepId).getStatus()).isEqualTo("FAILED");
        verify(executionService).onStepAttemptsExhausted(
                org.mockito.ArgumentMatchers.eq(stepId),
                argThat((WorkerFailedRequest request) -> "ATTEMPT_LEASE_EXPIRED".equals(request.errorCode()))
        );
    }

    @Test
    void expiredAttemptTimesOutChildAndRejectsLateSuccess() {
        WorkflowStepAttempt attempt = scheduler.dispatch(stepId);
        ClaimTaskRequest claim = new ClaimTaskRequest("workflow-worker", "expired-worker-claim");
        internalTaskService.claim(attempt.getChildTaskId(), claim);
        LocalDateTime cutoff = LocalDateTime.now();
        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                cutoff.minusMinutes(1),
                attempt.getId()
        );

        assertThat(recoveryScheduler.recoverExpiredAttempts(cutoff, 10)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, claimed_by, claim_token, lease_until FROM ai_tasks WHERE id = ?",
                attempt.getChildTaskId()
        )).containsEntry("status", "TIMEOUT")
                .containsEntry("claimed_by", null)
                .containsEntry("claim_token", null)
                .containsEntry("lease_until", null);
        assertThat(internalTaskService.markSuccess(
                attempt.getChildTaskId(),
                success("expired-worker-claim")
        ).status()).isEqualTo("TIMEOUT");
        assertThat(taskStatus(attempt.getChildTaskId())).isEqualTo("TIMEOUT");
        assertThat(attemptMapper.selectById(attempt.getId()).getStatus()).isEqualTo("LOST");
    }

    @Test
    void workflowSuccessCallbackRollsBackChildWhenAttemptIsNoLongerCurrent() {
        WorkflowStepAttempt attempt = scheduler.dispatch(stepId);
        internalTaskService.claim(
                attempt.getChildTaskId(),
                new ClaimTaskRequest("workflow-worker", "stale-success-claim")
        );
        jdbcTemplate.update(
                "UPDATE workflow_run_steps SET current_attempt_id = NULL WHERE id = ?",
                stepId
        );

        assertThatThrownBy(() -> internalTaskService.markSuccess(
                attempt.getChildTaskId(),
                success("stale-success-claim")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TASK_STATUS_INVALID));

        assertThat(taskStatus(attempt.getChildTaskId())).isEqualTo("PROCESSING");
        assertThat(attemptMapper.selectById(attempt.getId()).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void workflowFailureCallbackRollsBackChildWhenAttemptIsNoLongerCurrent() {
        WorkflowStepAttempt attempt = scheduler.dispatch(stepId);
        internalTaskService.claim(
                attempt.getChildTaskId(),
                new ClaimTaskRequest("workflow-worker", "stale-failure-claim")
        );
        jdbcTemplate.update(
                "UPDATE workflow_run_steps SET current_attempt_id = NULL WHERE id = ?",
                stepId
        );

        assertThatThrownBy(() -> internalTaskService.markFailed(
                attempt.getChildTaskId(),
                failure("stale-failure-claim")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TASK_STATUS_INVALID));

        assertThat(taskStatus(attempt.getChildTaskId())).isEqualTo("PROCESSING");
        assertThat(attemptMapper.selectById(attempt.getId()).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void unclaimedWorkflowSuccessIsRejectedWithoutMutatingStateOrCredits() {
        WorkflowStepAttempt attempt = scheduler.dispatch(stepId);
        int creditLogCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE task_id = ?",
                Integer.class,
                attempt.getChildTaskId()
        );

        assertThatThrownBy(() -> internalTaskService.markSuccess(
                attempt.getChildTaskId(),
                success("unclaimed-worker")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TASK_STATUS_INVALID));

        assertThat(taskStatus(attempt.getChildTaskId())).isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM workflow_runs WHERE id = ?",
                String.class,
                runId
        )).isEqualTo("RUNNING");
        assertThat(stepMapper.selectById(stepId).getStatus()).isEqualTo("QUEUED");
        assertThat(attemptMapper.selectById(attempt.getId()).getStatus()).isEqualTo("DISPATCHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM credit_logs WHERE task_id = ?",
                Integer.class,
                attempt.getChildTaskId()
        )).isEqualTo(creditLogCount);
    }

    @Test
    void directUnclaimedWorkflowCallbacksCannotCompleteDispatchedAttempt() {
        WorkflowStepAttempt attempt = scheduler.dispatch(stepId);

        assertThat(callbackService.succeeded(
                attempt.getChildTaskId(),
                success("unclaimed-direct")
        )).isFalse();
        assertThat(callbackService.failed(
                attempt.getChildTaskId(),
                failure("unclaimed-direct")
        )).isFalse();

        assertThat(stepMapper.selectById(stepId).getStatus()).isEqualTo("QUEUED");
        assertThat(attemptMapper.selectById(attempt.getId()).getStatus()).isEqualTo("DISPATCHED");
    }

    @Test
    void leaseRenewalCannotPromoteDispatchedAttempt() {
        WorkflowStepAttempt attempt = scheduler.dispatch(stepId);

        assertThat(callbackService.leaseRenewed(
                attempt.getChildTaskId(),
                LocalDateTime.now().plusMinutes(30)
        )).isFalse();
        assertThat(attemptMapper.selectById(attempt.getId()).getStatus()).isEqualTo("DISPATCHED");
    }

    @Test
    void lateOldChildRenewalCannotExtendCurrentAttempt() {
        WorkflowStepAttempt first = scheduler.dispatch(stepId);
        LocalDateTime cutoff = LocalDateTime.now();
        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                cutoff.minusMinutes(1),
                first.getId()
        );
        recoveryScheduler.recoverExpiredAttempts(cutoff, 10);
        WorkflowStepAttempt second = scheduler.retry(stepId);
        LocalDateTime secondLease = second.getLeaseExpiresAt();

        assertThat(internalTaskService.renewLease(
                first.getChildTaskId(),
                new ClaimTaskRequest("workflow-worker", "old-worker-claim")
        ).claimed()).isFalse();
        assertThat(taskStatus(first.getChildTaskId())).isEqualTo("TIMEOUT");
        assertThat(attemptMapper.selectById(first.getId()).getStatus()).isEqualTo("TIMEOUT");
        assertThat(attemptMapper.selectById(second.getId()).getLeaseExpiresAt()).isEqualTo(secondLease);
    }

    @Test
    void staleTimedOutChildCannotBeClaimedAfterRetryStarts() {
        WorkflowStepAttempt first = scheduler.dispatch(stepId);
        LocalDateTime cutoff = LocalDateTime.now();
        jdbcTemplate.update(
                "UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                cutoff.minusMinutes(1),
                first.getId()
        );
        recoveryScheduler.recoverExpiredAttempts(cutoff, 10);
        scheduler.retry(stepId);

        assertThat(internalTaskService.claim(
                first.getChildTaskId(),
                new ClaimTaskRequest("late-worker", "late-worker-claim")
        ).claimed()).isFalse();
        assertThat(taskStatus(first.getChildTaskId())).isEqualTo("TIMEOUT");
    }

    @Test
    void terminalChildPoisonRecordIsConvergedAndDoesNotStarveLaterRecovery() {
        LocalDateTime cutoff = LocalDateTime.now();
        WorkflowStepAttempt poison = scheduler.dispatch(stepId);
        jdbcTemplate.update("UPDATE ai_tasks SET status = 'SUCCESS' WHERE id = ?", poison.getChildTaskId());
        jdbcTemplate.update("UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                cutoff.minusMinutes(2), poison.getId());
        jdbcTemplate.update("UPDATE workflow_run_steps SET status = 'READY', revision = revision + 1 WHERE id = ?", stepId);
        WorkflowStepAttempt recoverable = scheduler.retry(stepId);
        jdbcTemplate.update("UPDATE workflow_step_attempts SET lease_expires_at = ? WHERE id = ?",
                cutoff.minusMinutes(1), recoverable.getId());

        assertThat(recoveryScheduler.recoverExpiredAttempts(cutoff, 1)).isZero();
        assertThat(attemptMapper.selectById(poison.getId()).getStatus()).isEqualTo("LOST");
        assertThat(recoveryScheduler.recoverExpiredAttempts(cutoff, 1)).isEqualTo(1);
        assertThat(attemptMapper.selectById(recoverable.getId()).getStatus()).isEqualTo("TIMEOUT");
    }

    private WorkerSuccessRequest success(String claimToken) {
        return new WorkerSuccessRequest("JSON", "{\"value\":42}", 10, 5, 1, claimToken);
    }

    private WorkerFailedRequest failure(String claimToken) {
        return new WorkerFailedRequest(
                "MODEL_CALL_FAILED",
                "workflow child failed",
                "PROVIDER_CALL",
                false,
                null,
                null,
                null,
                0,
                0,
                0,
                claimToken
        );
    }

    private String taskStatus(Long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM ai_tasks WHERE id = ?",
                String.class,
                taskId
        );
    }

    private boolean awaitAttemptStatus(Long attemptId,
                                       String expectedStatus,
                                       long timeout,
                                       TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            String current = jdbcTemplate.queryForObject(
                    "SELECT status FROM workflow_step_attempts WHERE id = ?",
                    String.class,
                    attemptId
            );
            if (expectedStatus.equals(current)) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        return false;
    }

    private TestWorkflow insertWorkflow(String toolCode) {
        jdbcTemplate.update("DELETE FROM workflow_step_charges");
        jdbcTemplate.update("DELETE FROM workflow_step_attempts");
        jdbcTemplate.update("DELETE FROM task_outbox_events");
        jdbcTemplate.update("DELETE FROM workflow_run_steps");
        jdbcTemplate.update("DELETE FROM workflow_runs");
        jdbcTemplate.update(
                "DELETE FROM ai_tasks WHERE idempotency_key LIKE 'workflow:%' OR idempotency_key = ?",
                "root-" + toolCode
        );
        jdbcTemplate.update("DELETE FROM tool_workflow_versions WHERE workflow_id IN (SELECT id FROM tool_workflows WHERE workflow_name = ?)", toolCode);
        jdbcTemplate.update("DELETE FROM tool_workflows WHERE workflow_name = ?", toolCode);
        jdbcTemplate.update("DELETE FROM ai_tools WHERE tool_code = ?", toolCode);

        jdbcTemplate.update("""
                INSERT INTO ai_tools(
                  tool_code, tool_name, category_id, description, status,
                  estimated_credit_cost, execution_handler, execution_mode,
                  billing_mode, agent_surface_enabled, minimum_required_credits, is_deleted
                ) VALUES (?, 'Workflow Scheduler Test', 1, 'test', 'ONLINE', 1,
                          'TEXT_GENERATION', 'WORKFLOW', 'WORKFLOW_STEP', 1, 1, 0)
                """, toolCode);
        long toolId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tools WHERE tool_code = ?", Long.class, toolCode);
        String nodes = """
                [{"id":"worker","data":{"nodeDefType":"llm_text","title":"Worker"}}]
                """;
        jdbcTemplate.update("""
                INSERT INTO tool_workflows(
                  tool_id, workflow_name, nodes_json, edges_json, config_json,
                  version, status, draft_revision, execution_enabled, created_at, updated_at
                ) VALUES (?, ?, ?, '[]', '{}', 1, 'PUBLISHED', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, toolId, toolCode, nodes);
        long workflowId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflows WHERE workflow_name = ?", Long.class, toolCode);
        jdbcTemplate.update("""
                INSERT INTO tool_workflow_versions(
                  workflow_id, version, nodes_json, edges_json, config_json,
                  canonical_dsl_json, dsl_version, node_registry_version, dsl_hash,
                  input_schema_snapshot_json, dependency_manifest_json, billing_policy_json,
                  risk_policy_json, source_draft_revision, published_at, published_by, created_at
                ) VALUES (?, 1, ?, '[]', '{}', '{}', '1', 'p0', ?, '{}', '{}',
                          '{"mode":"WORKFLOW_STEP","nodePolicies":{"worker":{"maxCreditCost":1,"maxProviderCostCny":0.10,"fallbackChargeCredits":1,"staticParams":{},"modelPricingSnapshot":null,"pricingPolicy":{"markupRatio":1.5,"minCredits":0,"imageEstimateInputTokens":8000,"imageEstimateOutputTokens":8000,"rules":[]}}}}',
                          '{}', 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP)
                """, workflowId, nodes, "hash-" + toolCode);
        long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM tool_workflow_versions WHERE workflow_id = ?", Long.class, workflowId);
        jdbcTemplate.update(
                "UPDATE tool_workflows SET published_version_id = ? WHERE id = ?",
                versionId,
                workflowId
        );
        jdbcTemplate.update("""
                INSERT INTO ai_tasks(
                  task_no, user_id, tool_id, status, progress, progress_message,
                  params_json, idempotency_key, estimated_credit_cost, queued_at, started_at
                ) VALUES (?, 1, ?, 'PROCESSING', 5, 'running', '{}', ?, 0,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "ROOT-" + toolCode, toolId, "root-" + toolCode);
        long rootTaskId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_tasks WHERE idempotency_key = ?", Long.class, "root-" + toolCode);
        jdbcTemplate.update("""
                INSERT INTO workflow_runs(
                  user_id, tool_id, workflow_id, workflow_version, workflow_version_id,
                  root_task_id, launch_source, client_request_id, status, revision,
                  cancellation_generation, input_json, context_json, billing_status,
                  started_at, created_at, updated_at
                ) VALUES (1, ?, ?, 1, ?, ?, 'AGENTS_PAGE', ?, 'RUNNING', 0, 0,
                          '{"prompt":"hello"}', '{}', 'CLEAR', CURRENT_TIMESTAMP,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, toolId, workflowId, versionId, rootTaskId, "request-" + toolCode);
        long createdRunId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_runs WHERE root_task_id = ?", Long.class, rootTaskId);
        jdbcTemplate.update("""
                INSERT INTO workflow_run_steps(
                  run_id, node_id, sequence_no, node_def_type, status, revision,
                  attempt, attempt_count, max_attempts, input_json
                ) VALUES (?, 'worker', 1, 'LLM_TEXT', 'READY', 0, 0, 0, 2,
                          '{"form":{"prompt":"hello"}}')
                """, createdRunId);
        long createdStepId = jdbcTemplate.queryForObject(
                "SELECT id FROM workflow_run_steps WHERE run_id = ? AND node_id = 'worker'",
                Long.class,
                createdRunId
        );
        resetCredits(200);
        return new TestWorkflow(createdRunId, createdStepId);
    }

    private void configurePaidStep(int maxCreditCost, int balance) {
        resetCredits(balance);
        String nodes = """
                [{"id":"worker","data":{"nodeDefType":"llm_text","title":"Worker",
                  "parameters":{"maxCreditCost":%d}}}]
                """.formatted(maxCreditCost);
        jdbcTemplate.update("""
                UPDATE tool_workflow_versions
                SET nodes_json = ?
                WHERE id = (SELECT workflow_version_id FROM workflow_runs WHERE id = ?)
                """, nodes, runId);
    }

    private void resetCredits(int balance) {
        jdbcTemplate.update("DELETE FROM credit_logs WHERE user_id = 1");
        jdbcTemplate.update("DELETE FROM credit_accounts WHERE user_id = 1");
        jdbcTemplate.update("""
                INSERT INTO credit_accounts(
                  user_id, balance, membership_balance, gift_balance, frozen,
                  total_granted, total_consumed, status
                ) VALUES (1, ?, ?, 0, 0, ?, 0, 'ACTIVE')
                """, balance, balance, balance);
    }

    private void setVersionFallbackCap(int credits) {
        jdbcTemplate.update("""
                UPDATE tool_workflow_versions
                SET billing_policy_json = ?
                WHERE id = (SELECT workflow_version_id FROM workflow_runs WHERE id = ?)
                """, "{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{\"worker\":{"
                + "\"maxCreditCost\":" + credits
                + ",\"maxProviderCostCny\":0.10"
                + ",\"fallbackChargeCredits\":0,\"staticParams\":{},\"modelPricingSnapshot\":null,"
                + "\"pricingPolicy\":{\"markupRatio\":1.5,"
                + "\"minCredits\":0,\"imageEstimateInputTokens\":8000,"
                + "\"imageEstimateOutputTokens\":8000,\"rules\":[]}}}}", runId);
    }

    private record TestWorkflow(long runId, long stepId) {
    }
}
