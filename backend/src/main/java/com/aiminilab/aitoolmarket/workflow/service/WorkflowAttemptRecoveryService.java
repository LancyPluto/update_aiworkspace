package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.service.TaskStateMachine;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepAttempt;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowStepStatus;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowAttemptStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class WorkflowAttemptRecoveryService {

    private static final String LEASE_EXPIRED_MESSAGE = "Workflow step attempt lease expired";
    private static final List<String> ACTIVE_STEP_STATUSES = List.of(
            WorkflowStepStatus.QUEUED.name(),
            WorkflowStepStatus.RUNNING.name()
    );
    private static final Set<String> RECOVERABLE_ATTEMPT_STATUSES = Set.of(
            WorkflowAttemptStatus.DISPATCHED.name(),
            WorkflowAttemptStatus.QUEUED.name(),
            WorkflowAttemptStatus.RUNNING.name()
    );

    private final TaskMapper taskMapper;
    private final WorkflowStepAttemptMapper attemptMapper;
    private final WorkflowRunStepMapper stepMapper;
    private final WorkflowRunLockService runLockService;
    private final WorkflowExecutionService executionService;
    private final WorkflowBillingService billingService;
    private final WorkflowStepScheduler stepScheduler;
    private final WorkflowMetrics metrics;
    private final WorkflowRuntimeGate runtimeGate;

    public WorkflowAttemptRecoveryService(TaskMapper taskMapper,
                                          WorkflowStepAttemptMapper attemptMapper,
                                          WorkflowRunStepMapper stepMapper,
                                          WorkflowRunLockService runLockService,
                                          WorkflowExecutionService executionService,
                                          WorkflowBillingService billingService,
                                          WorkflowStepScheduler stepScheduler,
                                          WorkflowMetrics metrics,
                                          WorkflowRuntimeGate runtimeGate) {
        this.taskMapper = taskMapper;
        this.attemptMapper = attemptMapper;
        this.stepMapper = stepMapper;
        this.runLockService = runLockService;
        this.executionService = executionService;
        this.billingService = billingService;
        this.stepScheduler = stepScheduler;
        this.metrics = metrics;
        this.runtimeGate = runtimeGate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverOne(Long attemptId, LocalDateTime cutoff) {
        WorkflowStepAttempt attemptHint = attemptMapper.selectById(attemptId);
        if (attemptHint == null || !RECOVERABLE_ATTEMPT_STATUSES.contains(attemptHint.getStatus())) {
            return false;
        }

        runLockService.requireByStepId(attemptHint.getStepId());
        WorkflowRunStep step = stepMapper.selectByIdForUpdate(attemptHint.getStepId());
        WorkflowStepAttempt attempt = attemptMapper.selectByIdForUpdate(attemptId);
        if (step == null || attempt == null || !RECOVERABLE_ATTEMPT_STATUSES.contains(attempt.getStatus())) {
            return false;
        }
        if (!step.getId().equals(attempt.getStepId())) {
            throw new IllegalStateException("Workflow attempt changed step while being recovered: " + attemptId);
        }
        AiTask child = attempt.getChildTaskId() == null
                ? null
                : taskMapper.selectByIdForUpdate(attempt.getChildTaskId());
        if (child != null && TaskStateMachine.isTerminal(child.getStatus())) {
            if (attemptMapper.markLostForTerminalChild(
                    attempt.getId(),
                    "Workflow child task was already terminal: " + child.getStatus()
            ) != 1) {
                throw conflict("Terminal child convergence", attemptId);
            }
            metrics.recordBillingState(WorkflowMetrics.BillingState.LOST);
            runtimeGate.markReconciliationUnhealthy();
            return false;
        }

        if (WorkflowAttemptStatus.RUNNING.name().equals(attempt.getStatus())) {
            return recoverUnknownRunningAttempt(attempt, child, isCurrent(step, attempt), cutoff);
        }
        if (child != null && !TaskStatus.QUEUED.name().equals(child.getStatus())) {
            throw new IllegalStateException("Dispatched workflow child is not queued: " + child.getStatus());
        }
        boolean current = isCurrent(step, attempt);
        timeoutUnclaimedChild(attempt, child, cutoff);
        billingService.release(attempt.getId());
        if (!current) {
            return false;
        }
        recoverSafeStep(step, attempt);
        return true;
    }

    private boolean isCurrent(WorkflowRunStep step, WorkflowStepAttempt attempt) {
        return step != null
                && step.getCurrentAttemptId() != null
                && step.getCurrentAttemptId().equals(attempt.getId());
    }

    private boolean recoverUnknownRunningAttempt(WorkflowStepAttempt attempt,
                                                  AiTask child,
                                                  boolean current,
                                                  LocalDateTime cutoff) {
        if (child == null || !TaskStatus.PROCESSING.name().equals(child.getStatus())) {
            throw new IllegalStateException("Running workflow attempt has no processing child task");
        }
        if (taskMapper.markFailed(
                child.getId(),
                TaskStatus.TIMEOUT.name(),
                "ATTEMPT_LEASE_EXPIRED",
                LEASE_EXPIRED_MESSAGE,
                LEASE_EXPIRED_MESSAGE,
                List.of(TaskStatus.PROCESSING.name())
        ) != 1) {
            throw conflict("Running child timeout", attempt.getId());
        }
        if (attemptMapper.markLostIfExpired(attempt.getId(), cutoff) != 1) {
            throw conflict("Running attempt LOST", attempt.getId());
        }
        metrics.recordBillingState(WorkflowMetrics.BillingState.LOST);
        runtimeGate.markReconciliationUnhealthy();
        return current;
    }

    private void timeoutUnclaimedChild(WorkflowStepAttempt attempt, AiTask child, LocalDateTime cutoff) {
        if (child != null && taskMapper.markFailed(
                child.getId(),
                TaskStatus.TIMEOUT.name(),
                "ATTEMPT_LEASE_EXPIRED",
                LEASE_EXPIRED_MESSAGE,
                LEASE_EXPIRED_MESSAGE,
                List.of(TaskStatus.QUEUED.name())
        ) != 1) {
            throw conflict("Queued child timeout", attempt.getId());
        }
        if (attemptMapper.markTimedOutIfExpired(attempt.getId(), cutoff) != 1) {
            throw conflict("Dispatched attempt TIMEOUT", attempt.getId());
        }
    }

    private void recoverSafeStep(WorkflowRunStep step, WorkflowStepAttempt attempt) {
        int attemptNo = attempt.getAttemptNo() == null ? 0 : attempt.getAttemptNo();
        int maxAttempts = step.getMaxAttempts() == null ? 1 : Math.max(1, step.getMaxAttempts());
        if (attemptNo >= maxAttempts) {
            if (stepMapper.failActiveAttempt(
                    step.getId(),
                    revision(step),
                    attempt.getId(),
                    LEASE_EXPIRED_MESSAGE,
                    ACTIVE_STEP_STATUSES
            ) != 1) {
                throw conflict("Exhausted step failure", attempt.getId());
            }
            executionService.onStepAttemptsExhausted(step.getId(), leaseExpiredFailure());
            return;
        }
        if (stepMapper.releaseActiveAttemptForRetry(
                step.getId(),
                revision(step),
                attempt.getId(),
                LEASE_EXPIRED_MESSAGE,
                ACTIVE_STEP_STATUSES
        ) != 1) {
            throw conflict("Step retry release", attempt.getId());
        }
        stepScheduler.retry(step.getId());
    }

    private WorkflowRecoveryConflictException conflict(String operation, Long attemptId) {
        return new WorkflowRecoveryConflictException(operation + " compare-and-set failed: " + attemptId);
    }

    private WorkerFailedRequest leaseExpiredFailure() {
        return new WorkerFailedRequest(
                "ATTEMPT_LEASE_EXPIRED",
                LEASE_EXPIRED_MESSAGE,
                "WORKFLOW_RECOVERY",
                false,
                null,
                null,
                null,
                0,
                0,
                0,
                null
        );
    }

    private long revision(WorkflowRunStep step) {
        return step.getRevision() == null ? 0L : step.getRevision();
    }
}
