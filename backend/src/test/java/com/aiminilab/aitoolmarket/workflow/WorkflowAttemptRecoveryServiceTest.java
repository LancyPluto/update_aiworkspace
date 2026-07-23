package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepAttempt;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowAttemptRecoveryService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowBillingService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowExecutionService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunLockService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowStepScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowAttemptRecoveryServiceTest {

    private TaskMapper taskMapper;
    private WorkflowStepAttemptMapper attemptMapper;
    private WorkflowRunStepMapper stepMapper;
    private WorkflowRunLockService runLockService;
    private WorkflowExecutionService executionService;
    private WorkflowBillingService billingService;
    private WorkflowStepScheduler stepScheduler;
    private WorkflowMetrics metrics;
    private WorkflowRuntimeGate runtimeGate;
    private WorkflowAttemptRecoveryService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TaskMapper.class);
        attemptMapper = mock(WorkflowStepAttemptMapper.class);
        stepMapper = mock(WorkflowRunStepMapper.class);
        runLockService = mock(WorkflowRunLockService.class);
        executionService = mock(WorkflowExecutionService.class);
        billingService = mock(WorkflowBillingService.class);
        stepScheduler = mock(WorkflowStepScheduler.class);
        metrics = mock(WorkflowMetrics.class);
        runtimeGate = mock(WorkflowRuntimeGate.class);
        service = new WorkflowAttemptRecoveryService(
                taskMapper,
                attemptMapper,
                stepMapper,
                runLockService,
                executionService,
                billingService,
                stepScheduler,
                metrics,
                runtimeGate
        );
    }

    @Test
    void expiredUnclaimedAttemptReleasesAndImmediatelyDispatchesNextAttempt() {
        LocalDateTime cutoff = LocalDateTime.now();
        WorkflowStepAttempt attempt = attempt("DISPATCHED", 1);
        WorkflowRunStep step = currentStep(attempt, 2);
        AiTask child = child(TaskStatus.QUEUED.name());
        stub(attempt, step, child);
        when(attemptMapper.markTimedOutIfExpired(attempt.getId(), cutoff)).thenReturn(1);
        when(stepMapper.releaseActiveAttemptForRetry(
                eq(step.getId()), eq(0L), eq(attempt.getId()),
                eq("Workflow step attempt lease expired"), anyList()
        )).thenReturn(1);

        assertThat(service.recoverOne(attempt.getId(), cutoff)).isTrue();

        verify(billingService).release(attempt.getId());
        var lockOrder = inOrder(runLockService, billingService);
        lockOrder.verify(runLockService).requireByStepId(step.getId());
        lockOrder.verify(billingService).release(attempt.getId());
        verify(stepScheduler).retry(step.getId());
        verify(executionService, never()).onStepAttemptsExhausted(eq(step.getId()), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void historicalQueuedAttemptUsesTheSameTimeoutRecoveryPath() {
        LocalDateTime cutoff = LocalDateTime.now();
        WorkflowStepAttempt attempt = attempt("QUEUED", 1);
        WorkflowRunStep step = currentStep(attempt, 2);
        AiTask child = child(TaskStatus.QUEUED.name());
        stub(attempt, step, child);
        when(attemptMapper.markTimedOutIfExpired(attempt.getId(), cutoff)).thenReturn(1);
        when(stepMapper.releaseActiveAttemptForRetry(
                eq(step.getId()), eq(0L), eq(attempt.getId()),
                eq("Workflow step attempt lease expired"), anyList()
        )).thenReturn(1);

        assertThat(service.recoverOne(attempt.getId(), cutoff)).isTrue();

        verify(attemptMapper).markTimedOutIfExpired(attempt.getId(), cutoff);
        verify(billingService).release(attempt.getId());
        verify(stepScheduler).retry(step.getId());
    }

    @Test
    void exhaustedExpiredAttemptFailsStepWithoutDispatchingAgain() {
        LocalDateTime cutoff = LocalDateTime.now();
        WorkflowStepAttempt attempt = attempt("DISPATCHED", 2);
        WorkflowRunStep step = currentStep(attempt, 2);
        AiTask child = child(TaskStatus.QUEUED.name());
        stub(attempt, step, child);
        when(attemptMapper.markTimedOutIfExpired(attempt.getId(), cutoff)).thenReturn(1);
        when(stepMapper.failActiveAttemptWithContract(
                eq(step.getId()), eq(0L), eq(attempt.getId()),
                eq("ATTEMPT_LEASE_EXPIRED"), eq("工作流执行超时，请稍后重试"),
                eq("Workflow step attempt lease expired"), eq(null), anyList()
        )).thenReturn(1);

        assertThat(service.recoverOne(attempt.getId(), cutoff)).isTrue();

        verify(stepScheduler, never()).retry(step.getId());
        verify(executionService).onStepAttemptsExhausted(eq(step.getId()), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unknownRunningAttemptBecomesLostAndIsNeverRetried() {
        LocalDateTime cutoff = LocalDateTime.now();
        WorkflowStepAttempt attempt = attempt("RUNNING", 1);
        WorkflowRunStep step = currentStep(attempt, 2);
        AiTask child = child(TaskStatus.PROCESSING.name());
        stub(attempt, step, child);
        when(taskMapper.markFailed(
                eq(child.getId()), eq(TaskStatus.TIMEOUT.name()), eq("ATTEMPT_LEASE_EXPIRED"),
                eq("Workflow step attempt lease expired"), eq("Workflow step attempt lease expired"), anyList()
        )).thenReturn(1);
        when(attemptMapper.markLostIfExpired(attempt.getId(), cutoff)).thenReturn(1);

        assertThat(service.recoverOne(attempt.getId(), cutoff)).isTrue();

        verify(runLockService).requireByStepId(step.getId());
        verify(billingService, never()).release(attempt.getId());
        verify(stepScheduler, never()).retry(step.getId());
        verify(metrics).recordBillingState(WorkflowMetrics.BillingState.LOST);
        verify(runtimeGate).markReconciliationUnhealthy();
    }

    @Test
    void terminalChildIsConvergedOnlyAfterRunAndStepAreLocked() {
        LocalDateTime cutoff = LocalDateTime.now();
        WorkflowStepAttempt attempt = attempt("DISPATCHED", 1);
        WorkflowRunStep step = currentStep(attempt, 2);
        AiTask child = child(TaskStatus.SUCCESS.name());
        stub(attempt, step, child);
        when(attemptMapper.markLostForTerminalChild(
                attempt.getId(), "Workflow child task was already terminal: SUCCESS"
        )).thenReturn(1);

        assertThat(service.recoverOne(attempt.getId(), cutoff)).isFalse();

        var lockOrder = inOrder(runLockService, stepMapper, attemptMapper, taskMapper);
        lockOrder.verify(runLockService).requireByStepId(step.getId());
        lockOrder.verify(stepMapper).selectByIdForUpdate(step.getId());
        lockOrder.verify(attemptMapper).selectByIdForUpdate(attempt.getId());
        lockOrder.verify(taskMapper).selectByIdForUpdate(child.getId());
        verify(attemptMapper).markLostForTerminalChild(
                attempt.getId(), "Workflow child task was already terminal: SUCCESS"
        );
        verify(billingService, never()).release(attempt.getId());
        verify(metrics).recordBillingState(WorkflowMetrics.BillingState.LOST);
        verify(runtimeGate).markReconciliationUnhealthy();
    }

    private void stub(WorkflowStepAttempt attempt, WorkflowRunStep step, AiTask child) {
        when(attemptMapper.selectById(attempt.getId())).thenReturn(attempt);
        when(stepMapper.selectByIdForUpdate(step.getId())).thenReturn(step);
        when(attemptMapper.selectByIdForUpdate(attempt.getId())).thenReturn(attempt);
        when(taskMapper.selectByIdForUpdate(child.getId())).thenReturn(child);
        when(taskMapper.markFailed(
                eq(child.getId()), eq(TaskStatus.TIMEOUT.name()), eq("ATTEMPT_LEASE_EXPIRED"),
                eq("Workflow step attempt lease expired"), eq("Workflow step attempt lease expired"), anyList()
        )).thenReturn(1);
    }

    private WorkflowStepAttempt attempt(String status, int attemptNo) {
        WorkflowStepAttempt attempt = new WorkflowStepAttempt();
        attempt.setId(101L);
        attempt.setStepId(201L);
        attempt.setChildTaskId(301L);
        attempt.setAttemptNo(attemptNo);
        attempt.setStatus(status);
        return attempt;
    }

    private WorkflowRunStep currentStep(WorkflowStepAttempt attempt, int maxAttempts) {
        WorkflowRunStep step = new WorkflowRunStep();
        step.setId(attempt.getStepId());
        step.setRunId(401L);
        step.setCurrentAttemptId(attempt.getId());
        step.setRevision(0L);
        step.setMaxAttempts(maxAttempts);
        step.setStatus("RUNNING");
        return step;
    }

    private AiTask child(String status) {
        AiTask child = new AiTask();
        child.setId(301L);
        child.setStatus(status);
        return child;
    }
}
