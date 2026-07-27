package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.agent.service.AgentDelegatedToolCallLifecycleService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.comic.service.ComicWorkflowResultProjector;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepAttempt;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowConfirmationMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepChargeMapper;
import com.aiminilab.aitoolmarket.workflow.support.WorkflowBillingReconciliationState;
import com.aiminilab.aitoolmarket.workflow.support.WorkflowFailureContract;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class WorkflowCancellationService {

    private static final Set<String> ACTIVE_RUN_STATUSES = Set.of(
            "RUNNING", "AWAITING_USER", "AWAITING_FUNDS"
    );
    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of(
            "SUCCESS", "FAILED", "TIMEOUT", "CANCELLED"
    );
    private static final Set<String> RELEASABLE_ATTEMPT_STATUSES = Set.of(
            "CREATED", "DISPATCHED", "QUEUED", "RUNNING", "SUCCESS"
    );
    private static final Set<String> SAFE_ISOLATION_RELEASE_ATTEMPT_STATUSES = Set.of(
            "CREATED", "DISPATCHED", "QUEUED"
    );

    private final WorkflowRunMapper runMapper;
    private final WorkflowRunStepMapper stepMapper;
    private final WorkflowStepAttemptMapper attemptMapper;
    private final WorkflowStepChargeMapper chargeMapper;
    private final WorkflowConfirmationMapper confirmationMapper;
    private final WorkflowBillingService billingService;
    private final TaskMapper taskMapper;
    private final AgentDelegatedToolCallLifecycleService delegatedToolCallLifecycleService;
    private final ComicWorkflowResultProjector comicWorkflowResultProjector;
    private final WorkflowExecutionService executionService;

    public WorkflowCancellationService(WorkflowRunMapper runMapper,
                                       WorkflowRunStepMapper stepMapper,
                                       WorkflowStepAttemptMapper attemptMapper,
                                       WorkflowStepChargeMapper chargeMapper,
                                       WorkflowConfirmationMapper confirmationMapper,
                                       WorkflowBillingService billingService,
                                       TaskMapper taskMapper,
                                       AgentDelegatedToolCallLifecycleService delegatedToolCallLifecycleService,
                                       ComicWorkflowResultProjector comicWorkflowResultProjector,
                                       WorkflowExecutionService executionService) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.attemptMapper = attemptMapper;
        this.chargeMapper = chargeMapper;
        this.confirmationMapper = confirmationMapper;
        this.billingService = billingService;
        this.taskMapper = taskMapper;
        this.delegatedToolCallLifecycleService = delegatedToolCallLifecycleService;
        this.comicWorkflowResultProjector = comicWorkflowResultProjector;
        this.executionService = executionService;
    }

    @Transactional
    public void begin(Long rootTaskId, Long userId, String reason) {
        WorkflowRun run = requireOwnedRunForUpdate(rootTaskId, userId);
        if (TERMINAL_RUN_STATUSES.contains(run.getStatus()) || "CANCELLING".equals(run.getStatus())) {
            return;
        }
        if (!ACTIVE_RUN_STATUSES.contains(run.getStatus())) {
            throw conflict("当前工作流不能取消");
        }
        if (runMapper.beginCancellation(
                run.getId(), revision(run), List.copyOf(ACTIVE_RUN_STATUSES), normalizedReason(reason)
        ) != 1) {
            throw conflict("工作流取消状态已变化");
        }
        markRootCancelling(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean settle(Long rootTaskId, Long userId, String reason) {
        WorkflowRun run = requireOwnedRunForUpdate(rootTaskId, userId);
        return settleLocked(run, reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean isolateBillingReconciliationFailure(Long runId) {
        WorkflowRun run = runMapper.selectByIdForUpdate(runId);
        if (run == null) {
            throw new IllegalStateException("Workflow run not found during billing reconciliation: " + runId);
        }
        WorkflowFailureContract failure = WorkflowFailureContract.from(
                WorkflowBillingReconciliationState.ERROR_CODE,
                WorkflowBillingReconciliationState.ERROR_MESSAGE
        );
        if (TERMINAL_RUN_STATUSES.contains(run.getStatus())) {
            markBillingReconciliationFailed(run);
            return false;
        }
        if (ACTIVE_RUN_STATUSES.contains(run.getStatus())) {
            beginBillingReconciliationIsolation(run, failure);
        } else if ("CANCELLING".equals(run.getStatus())) {
            markBillingReconciliationFailed(run);
        } else {
            markBillingReconciliationFailed(run);
            return false;
        }
        markRootCancelling(run);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean settlePersisted(Long runId) {
        WorkflowRun run = runMapper.selectByIdForUpdate(runId);
        if (run == null || TERMINAL_RUN_STATUSES.contains(run.getStatus())) {
            return true;
        }
        String reason = run.getErrorMessage() == null || run.getErrorMessage().isBlank()
                ? "CANCELLATION_RECOVERY"
                : run.getErrorMessage();
        return settleLocked(run, reason);
    }

    private boolean settleLocked(WorkflowRun run, String reason) {
        if (TERMINAL_RUN_STATUSES.contains(run.getStatus())) {
            return true;
        }
        if (!"CANCELLING".equals(run.getStatus())) {
            throw conflict("工作流尚未进入取消状态");
        }
        String normalizedReason = normalizedReason(reason);
        boolean billingReconciliationFailure = WorkflowBillingReconciliationState.isIsolation(run);
        List<WorkflowStepAttempt> attempts = attemptMapper.selectByRunId(run.getId());
        boolean hasUncertainAttempt = false;
        for (WorkflowStepAttempt attempt : attempts) {
            AiTask child = billingReconciliationFailure && attempt.getChildTaskId() != null
                    ? taskMapper.selectByIdForUpdate(attempt.getChildTaskId())
                    : null;
            if (attempt.getChildTaskId() != null) {
                taskMapper.cancel(
                        attempt.getChildTaskId(),
                        List.of(TaskStatus.QUEUED.name(), TaskStatus.PROCESSING.name(),
                                TaskStatus.AWAITING_USER.name(), TaskStatus.AWAITING_FUNDS.name())
                );
            }
            if (billingReconciliationFailure) {
                if ("SUCCESS".equals(attempt.getStatus())) {
                    if (attempt.getChildTaskId() == null || child == null) {
                        hasUncertainAttempt = true;
                        continue;
                    }
                    if (!billingService.settleReconciliationSuccess(
                            attempt.getId(), attempt.getChildTaskId()
                    )) {
                        hasUncertainAttempt = true;
                    }
                    continue;
                }
                if (SAFE_ISOLATION_RELEASE_ATTEMPT_STATUSES.contains(attempt.getStatus())) {
                    boolean providerNotCalled = "CREATED".equals(attempt.getStatus())
                            ? attempt.getChildTaskId() == null
                            : child != null && TaskStatus.QUEUED.name().equals(child.getStatus());
                    if (providerNotCalled) {
                        billingService.release(attempt.getId());
                        attemptMapper.cancelIfActive(attempt.getId(), normalizedReason);
                    } else {
                        hasUncertainAttempt = true;
                    }
                    continue;
                }
                boolean providerOutcomeUncertain = "RUNNING".equals(attempt.getStatus())
                        || "LOST".equals(attempt.getStatus())
                        || (attempt.getChildTaskId() != null && child == null)
                        || (child != null && TaskStatus.PROCESSING.name().equals(child.getStatus()));
                if (providerOutcomeUncertain) {
                    hasUncertainAttempt = true;
                }
                continue;
            }
            if (!RELEASABLE_ATTEMPT_STATUSES.contains(attempt.getStatus())) {
                continue;
            }
            if ("SUCCESS".equals(attempt.getStatus())) {
                billingService.releaseDeferredSuccess(attempt.getId(), attempt.getChildTaskId());
            } else {
                billingService.release(attempt.getId());
            }
            attemptMapper.cancelIfActive(attempt.getId(), normalizedReason);
        }
        confirmationMapper.cancelPendingByRunId(run.getId());
        stepMapper.cancelActiveByRunId(run.getId(), normalizedReason);

        boolean hasLostAttempt = attempts.stream().anyMatch(attempt -> "LOST".equals(attempt.getStatus()));
        if (hasUncertainAttempt || hasLostAttempt || chargeMapper.countReserved(run.getId()) > 0) {
            markRootCancelling(run);
            if (runMapper.deferCancellationReconciliation(run.getId(), revision(run)) != 1) {
                throw conflict("工作流待对账状态已变化");
            }
            return false;
        }
        if (billingReconciliationFailure) {
            executionService.finishBillingReconciliationFailure(run.getId());
            return true;
        }
        AiTask rootTask = taskMapper.findById(run.getRootTaskId()).orElse(null);
        if (rootTask != null && !TaskStatus.CANCELLED.name().equals(rootTask.getStatus())) {
            taskMapper.cancel(
                    rootTask.getId(),
                    List.of(TaskStatus.QUEUED.name(), TaskStatus.PROCESSING.name(),
                            TaskStatus.AWAITING_USER.name(), TaskStatus.AWAITING_FUNDS.name())
            );
        }
        if (runMapper.finishCancellation(run.getId(), revision(run)) != 1) {
            throw conflict("工作流取消收敛失败");
        }
        comicWorkflowResultProjector.projectFailed(run.getId());
        delegatedToolCallLifecycleService.finishForWorkflow(
                run.getRootTaskId(),
                run.getId(),
                "CANCELLED",
                normalizedReason
        );
        return true;
    }

    private void beginBillingReconciliationIsolation(WorkflowRun run,
                                                     WorkflowFailureContract failure) {
        long expectedRevision = revision(run);
        if (runMapper.beginBillingReconciliationIsolation(
                run.getId(),
                expectedRevision,
                failure.userMessage(),
                failure.developerMessage(),
                failure.failureTraceId()
        ) != 1) {
            throw conflict("Workflow billing reconciliation isolation compare-and-set failed: " + run.getId());
        }
        run.setStatus("CANCELLING");
        run.setBillingStatus(WorkflowBillingReconciliationState.BILLING_STATUS);
        run.setCancellationGeneration((run.getCancellationGeneration() == null
                ? 0L : run.getCancellationGeneration()) + 1);
        run.setErrorCode(failure.errorCode());
        run.setErrorMessage(failure.developerMessage());
        run.setUserMessage(failure.userMessage());
        run.setDeveloperMessage(failure.developerMessage());
        run.setFailureTraceId(failure.failureTraceId());
        run.setRevision(expectedRevision + 1);
    }

    private void markBillingReconciliationFailed(WorkflowRun run) {
        if (WorkflowBillingReconciliationState.BILLING_STATUS.equals(run.getBillingStatus())) {
            return;
        }
        long expectedRevision = revision(run);
        if (runMapper.markBillingReconciliationFailed(run.getId(), expectedRevision) != 1) {
            throw conflict("Workflow billing reconciliation audit compare-and-set failed: " + run.getId());
        }
        run.setBillingStatus(WorkflowBillingReconciliationState.BILLING_STATUS);
        run.setRevision(expectedRevision + 1);
    }

    private void markRootCancelling(WorkflowRun run) {
        AiTask rootTask = taskMapper.findById(run.getRootTaskId()).orElse(null);
        if (rootTask == null || TaskStatus.CANCELLED.name().equals(rootTask.getStatus())) {
            return;
        }
        taskMapper.markProcessing(
                rootTask.getId(),
                rootTask.getProgress() == null ? 0 : rootTask.getProgress(),
                "正在取消，等待执行和费用收敛",
                List.of(TaskStatus.QUEUED.name(), TaskStatus.PROCESSING.name(),
                        TaskStatus.AWAITING_USER.name(), TaskStatus.AWAITING_FUNDS.name())
        );
    }

    private WorkflowRun requireOwnedRunForUpdate(Long rootTaskId, Long userId) {
        WorkflowRun run = runMapper.selectByRootTaskIdForUpdate(rootTaskId);
        if (run == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "工作流任务不存在");
        }
        if (!Objects.equals(run.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该工作流任务");
        }
        return run;
    }

    private String normalizedReason(String reason) {
        return reason == null || reason.isBlank() ? "USER_CANCELLED" : reason;
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, message);
    }

    private long revision(WorkflowRun run) {
        return run.getRevision() == null ? 0L : run.getRevision();
    }
}
