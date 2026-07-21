package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepAttempt;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowAttemptStatus;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowStepStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WorkflowStepCallbackService {

    private static final List<String> CLAIMABLE_ATTEMPT_STATUSES = List.of(
            WorkflowAttemptStatus.DISPATCHED.name(),
            WorkflowAttemptStatus.QUEUED.name(),
            WorkflowAttemptStatus.RUNNING.name()
    );
    private static final List<String> RUNNING_ATTEMPT_STATUS = List.of(
            WorkflowAttemptStatus.RUNNING.name()
    );
    private static final List<String> ACTIVE_STEP_STATUSES = List.of(
            WorkflowStepStatus.QUEUED.name(),
            WorkflowStepStatus.RUNNING.name()
    );

    private final WorkflowStepAttemptMapper attemptMapper;
    private final WorkflowRunStepMapper stepMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowExecutionService executionService;
    private final WorkflowBillingService billingService;
    private final WorkflowStepScheduler stepScheduler;
    private final WorkflowRuntimeProperties runtimeProperties;
    private final WorkflowMetrics metrics;
    private final ObjectMapper objectMapper;

    public WorkflowStepCallbackService(WorkflowStepAttemptMapper attemptMapper,
                                       WorkflowRunStepMapper stepMapper,
                                       WorkflowRunMapper runMapper,
                                       WorkflowExecutionService executionService,
                                       WorkflowBillingService billingService,
                                       WorkflowStepScheduler stepScheduler,
                                       WorkflowRuntimeProperties runtimeProperties,
                                       ObjectMapper objectMapper,
                                       WorkflowMetrics metrics) {
        this.attemptMapper = attemptMapper;
        this.stepMapper = stepMapper;
        this.runMapper = runMapper;
        this.executionService = executionService;
        this.billingService = billingService;
        this.stepScheduler = stepScheduler;
        this.runtimeProperties = runtimeProperties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public boolean succeeded(Long childTaskId, WorkerSuccessRequest request) {
        WorkflowStepAttempt attempt = attemptMapper.selectByChildTaskId(childTaskId);
        if (attempt == null) {
            metrics.recordLateCallback(WorkflowMetrics.LateCallbackResult.REJECTED_CLAIM);
            return false;
        }
        WorkflowRunStep step = stepMapper.selectById(attempt.getStepId());
        WorkflowRun run = lockRun(step);
        String providerRequestId = providerRequestId(request.providerRequestId());
        attachProviderRequestId(attempt.getId(), providerRequestId);
        if (isDeferredSettlement(run, step, attempt)) {
            if (!sameOutput(attempt.getOutputJson(), outputJson(request))) {
                throw new IllegalStateException("Deferred workflow success output conflicts with persisted output");
            }
            billingService.validateDeferredSettlement(attempt.getId(), request);
            return true;
        }
        WorkflowMetrics.LateCallbackResult rejection = rejection(run, step, attempt, true);
        if (rejection != null) {
            metrics.recordLateCallback(rejection);
            billingService.releaseLateSuccess(attempt.getId(), childTaskId, request);
            return false;
        }

        String outputJson = outputJson(request);
        if (attemptMapper.markSuccess(
                attempt.getId(), outputJson, providerRequestId, RUNNING_ATTEMPT_STATUS
        ) != 1) {
            return false;
        }
        WorkflowBillingService.SettlementResult settlement = billingService.capture(
                attempt.getId(), childTaskId, request
        );
        if (settlement == WorkflowBillingService.SettlementResult.AWAITING_FUNDS) {
            if (stepMapper.markActiveAttemptAwaitingFunds(
                    step.getId(),
                    revision(step),
                    attempt.getId(),
                    outputJson,
                    "Provider succeeded; recharge to settle actual usage"
            ) != 1) {
                throw new IllegalStateException("Active workflow step awaiting-funds compare-and-set failed");
            }
            billingService.pauseForSettlementFunds(run, step);
            return true;
        }
        if (stepMapper.completeActiveAttempt(
                step.getId(),
                revision(step),
                attempt.getId(),
                outputJson,
                List.of(WorkflowStepStatus.RUNNING.name())
        ) != 1) {
            throw new IllegalStateException("Active workflow step success compare-and-set failed");
        }
        executionService.onStepAttemptSucceeded(step.getId(), childTaskId, request);
        return true;
    }

    @Transactional
    public boolean resumeSettlement(WorkflowRun run, WorkflowRunStep requestedStep) {
        if (run == null
                || requestedStep == null
                || !"AWAITING_FUNDS".equals(run.getStatus())
                || !requestedStep.getId().equals(run.getCurrentStepId())) {
            throw new IllegalStateException("Workflow run is not awaiting settlement funds for this step");
        }
        WorkflowRunStep step = stepMapper.selectByIdForUpdate(requestedStep.getId());
        if (step == null
                || !run.getId().equals(step.getRunId())
                || !WorkflowStepStatus.AWAITING_FUNDS.name().equals(step.getStatus())
                || step.getCurrentAttemptId() == null) {
            throw new IllegalStateException("Workflow settlement step is no longer recoverable");
        }
        WorkflowStepAttempt attempt = attemptMapper.selectByIdForUpdate(step.getCurrentAttemptId());
        if (attempt == null
                || !step.getId().equals(attempt.getStepId())
                || !WorkflowAttemptStatus.SUCCESS.name().equals(attempt.getStatus())
                || attempt.getChildTaskId() == null) {
            throw new IllegalStateException("Workflow settlement attempt has no persisted provider success");
        }
        WorkerSuccessRequest request = billingService.deferredSettlementRequest(
                attempt.getId(),
                step.getOutputJson()
        );
        if (billingService.capture(attempt.getId(), attempt.getChildTaskId(), request)
                == WorkflowBillingService.SettlementResult.AWAITING_FUNDS) {
            return false;
        }
        if (stepMapper.completeActiveAttempt(
                step.getId(),
                revision(step),
                attempt.getId(),
                step.getOutputJson(),
                List.of(WorkflowStepStatus.AWAITING_FUNDS.name())
        ) != 1) {
            throw new IllegalStateException("Workflow settlement step completion compare-and-set failed");
        }
        billingService.resumeAfterSettlement(run, step);
        executionService.onStepAttemptSucceeded(step.getId(), attempt.getChildTaskId(), request);
        return true;
    }

    @Transactional
    public boolean running(Long childTaskId, LocalDateTime leaseExpiresAt) {
        WorkflowStepAttempt attempt = attemptMapper.selectByChildTaskId(childTaskId);
        if (attempt == null) {
            metrics.recordLateCallback(WorkflowMetrics.LateCallbackResult.REJECTED_CLAIM);
            return false;
        }
        WorkflowRunStep step = stepMapper.selectById(attempt.getStepId());
        WorkflowRun run = lockRun(step);
        if (!isActiveGeneration(run, attempt)
                || !isCurrent(step, attempt)
                || !ACTIVE_STEP_STATUSES.contains(step.getStatus())) {
            recordRejection(rejection(run, step, attempt, false));
            return false;
        }
        if (attemptMapper.markRunning(attempt.getId(), leaseExpiresAt, CLAIMABLE_ATTEMPT_STATUSES) != 1) {
            return false;
        }
        if (WorkflowStepStatus.QUEUED.name().equals(step.getStatus())
                && stepMapper.markActiveAttemptRunning(step.getId(), revision(step), attempt.getId()) != 1) {
            throw new IllegalStateException("Active workflow step running compare-and-set failed");
        }
        return true;
    }

    @Transactional
    public boolean leaseRenewed(Long childTaskId, LocalDateTime leaseExpiresAt) {
        WorkflowStepAttempt attempt = attemptMapper.selectByChildTaskId(childTaskId);
        if (attempt == null || !WorkflowAttemptStatus.RUNNING.name().equals(attempt.getStatus())) {
            metrics.recordLateCallback(WorkflowMetrics.LateCallbackResult.REJECTED_CLAIM);
            return false;
        }
        WorkflowRunStep step = stepMapper.selectById(attempt.getStepId());
        WorkflowRun run = lockRun(step);
        if (!isActiveGeneration(run, attempt)
                || !isCurrent(step, attempt)
                || !WorkflowStepStatus.RUNNING.name().equals(step.getStatus())) {
            recordRejection(rejection(run, step, attempt, false));
            return false;
        }
        return attemptMapper.markRunning(
                attempt.getId(),
                leaseExpiresAt,
                List.of(WorkflowAttemptStatus.RUNNING.name())
        ) == 1;
    }

    @Transactional
    public boolean failed(Long childTaskId, WorkerFailedRequest request) {
        WorkflowStepAttempt attempt = attemptMapper.selectByChildTaskId(childTaskId);
        if (attempt == null) {
            metrics.recordLateCallback(WorkflowMetrics.LateCallbackResult.REJECTED_CLAIM);
            return false;
        }
        WorkflowRunStep step = stepMapper.selectById(attempt.getStepId());
        WorkflowRun run = lockRun(step);
        String providerRequestId = providerRequestId(request.providerRequestId());
        WorkflowMetrics.LateCallbackResult rejection = rejection(run, step, attempt, true);
        if (rejection != null) {
            metrics.recordLateCallback(rejection);
            billingService.releaseLateFailure(attempt.getId(), childTaskId, request);
            return false;
        }
        attachProviderRequestId(attempt.getId(), providerRequestId);

        String errorCode = request.errorCode() == null || request.errorCode().isBlank()
                ? "MODEL_CALL_FAILED"
                : request.errorCode();
        String errorMessage = limit(request.errorMessage(), 1900, "Workflow step failed");
        if (attemptMapper.markFailed(
                attempt.getId(),
                errorCode,
                errorMessage,
                providerRequestId,
                RUNNING_ATTEMPT_STATUS
        ) != 1) {
            return false;
        }
        billingService.release(attempt.getId(), childTaskId, request);

        int attemptNo = attempt.getAttemptNo() == null ? 0 : attempt.getAttemptNo();
        int maxAttempts = step.getMaxAttempts() == null ? 1 : Math.max(1, step.getMaxAttempts());
        if (runtimeProperties.isAutoRetryEnabled() && attemptNo < maxAttempts) {
            if (stepMapper.releaseActiveAttemptForRetry(
                    step.getId(),
                    revision(step),
                    attempt.getId(),
                    errorMessage,
                    List.of(WorkflowStepStatus.RUNNING.name())
            ) != 1) {
                throw new IllegalStateException("Active workflow step retry compare-and-set failed");
            }
            stepScheduler.retry(step.getId());
            return true;
        }
        if (stepMapper.failActiveAttempt(
                step.getId(),
                revision(step),
                attempt.getId(),
                errorMessage,
                List.of(WorkflowStepStatus.RUNNING.name())
        ) != 1) {
            throw new IllegalStateException("Active workflow step failure compare-and-set failed");
        }
        executionService.onStepAttemptsExhausted(step.getId(), request);
        return true;
    }

    private boolean isCurrent(WorkflowRunStep step, WorkflowStepAttempt attempt) {
        return step != null
                && step.getCurrentAttemptId() != null
                && step.getCurrentAttemptId().equals(attempt.getId());
    }

    private boolean isDeferredSettlement(WorkflowRun run,
                                         WorkflowRunStep step,
                                         WorkflowStepAttempt attempt) {
        return run != null
                && "AWAITING_FUNDS".equals(run.getStatus())
                && run.getCurrentStepId() != null
                && step != null
                && run.getCurrentStepId().equals(step.getId())
                && WorkflowStepStatus.AWAITING_FUNDS.name().equals(step.getStatus())
                && isCurrent(step, attempt)
                && WorkflowAttemptStatus.SUCCESS.name().equals(attempt.getStatus());
    }

    private boolean sameOutput(String persisted, String received) {
        if (persisted == null || received == null) {
            return persisted == null && received == null;
        }
        try {
            return objectMapper.readTree(persisted).equals(objectMapper.readTree(received));
        } catch (Exception ignored) {
            return persisted.equals(received);
        }
    }

    private boolean isRunningCurrent(WorkflowRunStep step, WorkflowStepAttempt attempt) {
        return isCurrent(step, attempt)
                && WorkflowAttemptStatus.RUNNING.name().equals(attempt.getStatus())
                && WorkflowStepStatus.RUNNING.name().equals(step.getStatus());
    }

    private WorkflowRun lockRun(WorkflowRunStep step) {
        return step == null ? null : runMapper.selectByIdForUpdate(step.getRunId());
    }

    private boolean isActiveGeneration(WorkflowRun run, WorkflowStepAttempt attempt) {
        long runGeneration = run == null || run.getCancellationGeneration() == null
                ? 0L
                : run.getCancellationGeneration();
        long attemptGeneration = attempt.getCancellationGeneration() == null
                ? 0L
                : attempt.getCancellationGeneration();
        return run != null
                && "RUNNING".equals(run.getStatus())
                && runGeneration == attemptGeneration;
    }

    private WorkflowMetrics.LateCallbackResult rejection(WorkflowRun run,
                                                          WorkflowRunStep step,
                                                          WorkflowStepAttempt attempt,
                                                          boolean requireRunningStep) {
        long runGeneration = run == null || run.getCancellationGeneration() == null
                ? 0L
                : run.getCancellationGeneration();
        long attemptGeneration = attempt.getCancellationGeneration() == null
                ? 0L
                : attempt.getCancellationGeneration();
        if (run != null && runGeneration != attemptGeneration) {
            return WorkflowMetrics.LateCallbackResult.REJECTED_GENERATION;
        }
        if (run == null || !"RUNNING".equals(run.getStatus())) {
            return WorkflowMetrics.LateCallbackResult.REJECTED_TERMINAL_RUN;
        }
        boolean validClaim = requireRunningStep
                ? isRunningCurrent(step, attempt)
                : isCurrent(step, attempt);
        return validClaim ? null : WorkflowMetrics.LateCallbackResult.REJECTED_CLAIM;
    }

    private void recordRejection(WorkflowMetrics.LateCallbackResult result) {
        metrics.recordLateCallback(result == null
                ? WorkflowMetrics.LateCallbackResult.REJECTED_CLAIM
                : result);
    }

    private String outputJson(WorkerSuccessRequest request) {
        JsonNode output;
        try {
            String content = request.contentText();
            output = content != null && content.stripLeading().startsWith("{")
                    ? objectMapper.readTree(content)
                    : wrappedOutput(request);
        } catch (Exception ignored) {
            output = wrappedOutput(request);
        }
        try {
            return objectMapper.writeValueAsString(output);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize workflow step output", exception);
        }
    }

    private ObjectNode wrappedOutput(WorkerSuccessRequest request) {
        ObjectNode output = objectMapper.createObjectNode();
        output.put("contentText", request.contentText());
        output.put("resourceType", request.resourceType());
        return output;
    }

    private String limit(String value, int maxLength, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String providerRequestId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("Provider request id cannot exceed 128 characters");
        }
        return normalized;
    }

    private void attachProviderRequestId(Long attemptId, String providerRequestId) {
        if (providerRequestId == null) {
            return;
        }
        String existing = attemptMapper.selectProviderRequestId(attemptId);
        if (existing != null) {
            if (!existing.equals(providerRequestId)) {
                throw new IllegalStateException("Provider request id conflicts with the workflow attempt");
            }
            return;
        }
        if (attemptMapper.attachProviderRequestId(attemptId, providerRequestId) == 1) {
            return;
        }
        existing = attemptMapper.selectProviderRequestId(attemptId);
        if (!providerRequestId.equals(existing)) {
            throw new IllegalStateException("Provider request id conflicts with the workflow attempt");
        }
    }

    private long revision(WorkflowRunStep step) {
        return step.getRevision() == null ? 0L : step.getRevision();
    }
}
