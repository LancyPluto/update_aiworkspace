package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepChargeMapper;
import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowChargeReconciliation;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowRunStatus;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WorkflowChargeReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowChargeReconciler.class);

    private final WorkflowRunMapper runMapper;
    private final WorkflowStepChargeMapper chargeMapper;
    private final WorkflowMetrics metrics;
    private final WorkflowRuntimeGate runtimeGate;

    private long scanCursor;
    private boolean cycleInconsistent;
    private long cycleFailureGeneration;

    public WorkflowChargeReconciler(WorkflowRunMapper runMapper,
                                    WorkflowStepChargeMapper chargeMapper,
                                    WorkflowMetrics metrics,
                                    WorkflowRuntimeGate runtimeGate) {
        this.runMapper = runMapper;
        this.chargeMapper = chargeMapper;
        this.metrics = metrics;
        this.runtimeGate = runtimeGate;
    }

    @Transactional(readOnly = true)
    public WorkflowChargeReconciliation reconcileRun(Long runId) {
        WorkflowRun run = runId == null ? null : runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Workflow run does not exist");
        }
        return recordResult(reconcile(run));
    }

    private WorkflowChargeReconciliation recordResult(WorkflowChargeReconciliation result) {
        metrics.recordReconciliation(result.consistent());
        if (!result.consistent()) {
            runtimeGate.markReconciliationUnhealthy();
            LOGGER.error(
                    "WORKFLOW_BILLING_RECONCILIATION_INCONSISTENT runId={} captured={} usage={} creditLogs={} reserved={} lostAttempts={} invalidTransitions={} providerCostReservationOverruns={}",
                    result.runId(),
                    result.capturedCredits(),
                    result.usageCredits(),
                    result.creditLogDeductions(),
                    result.reservedCharges(),
                    result.lostAttempts(),
                    result.invalidCreditTransitions(),
                    result.providerCostReservationOverruns()
            );
        }
        return result;
    }

    @Transactional(readOnly = true)
    public synchronized BatchResult reconcileBatch(int limit) {
        int effectiveLimit = Math.max(1, limit);
        if (scanCursor == 0) {
            cycleFailureGeneration = runtimeGate.reconciliationFailureGeneration();
        }
        QueryWrapper<WorkflowRun> query = new QueryWrapper<>();
        query.gt("id", scanCursor)
                .orderByAsc("id")
                .last("LIMIT " + effectiveLimit);
        List<WorkflowRun> runs = runMapper.selectList(query);
        if (runs == null) {
            runs = List.of();
        }

        int inconsistent = 0;
        RuntimeException firstUnexpectedFailure = null;
        for (WorkflowRun run : runs) {
            scanCursor = Math.max(scanCursor, run.getId());
            try {
                if (!recordResult(reconcile(run)).consistent()) {
                    inconsistent++;
                    cycleInconsistent = true;
                }
            } catch (RuntimeException exception) {
                inconsistent++;
                cycleInconsistent = true;
                runtimeGate.markReconciliationUnhealthy();
                metrics.recordReconciliation(false);
                LOGGER.error("WORKFLOW_BILLING_RECONCILIATION_FAILED runId={}", run.getId(), exception);
                if (firstUnexpectedFailure == null) {
                    firstUnexpectedFailure = exception;
                } else {
                    firstUnexpectedFailure.addSuppressed(exception);
                }
            }
        }

        boolean cycleComplete = runs.size() < effectiveLimit;
        if (cycleComplete) {
            if (!cycleInconsistent
                    && !runtimeGate.markReconciliationHealthyAfterFullScan(cycleFailureGeneration)) {
                LOGGER.error("WORKFLOW_BILLING_RECONCILIATION_STALE_CLEAN_SCAN observedFailureGeneration={}",
                        cycleFailureGeneration);
            }
            scanCursor = 0;
            cycleInconsistent = false;
        }
        BatchResult result = new BatchResult(runs.size(), inconsistent, cycleComplete);
        if (firstUnexpectedFailure != null) {
            throw firstUnexpectedFailure;
        }
        return result;
    }

    private WorkflowChargeReconciliation reconcile(WorkflowRun run) {
        Long runId = run.getId();
        int captured = chargeMapper.sumCapturedCredits(runId);
        int usage = chargeMapper.sumUsageCredits(runId);
        int creditLogs = chargeMapper.sumCreditDeductions(runId);
        int reserved = chargeMapper.countReserved(runId);
        int lostAttempts = chargeMapper.countLostAttempts(runId);
        int invalidTransitions = Math.addExact(
                chargeMapper.countInvalidCreditTransitions(runId),
                chargeMapper.countInvalidUsageBindings(runId)
        );
        int providerCostReservationOverruns = chargeMapper.countProviderCostReservationOverruns(runId);
        WorkflowRunStatus runStatus = parseStatus(run.getStatus());
        boolean terminal = isTerminal(runStatus);
        if (runStatus == null) {
            LOGGER.error("WORKFLOW_BILLING_RECONCILIATION_UNKNOWN_RUN_STATUS runId={} status={}",
                    runId, run.getStatus());
        }
        return new WorkflowChargeReconciliation(
                runId,
                captured,
                usage,
                creditLogs,
                reserved,
                lostAttempts,
                invalidTransitions,
                providerCostReservationOverruns,
                runStatus != null
                        && captured == usage
                        && usage == creditLogs
                        && lostAttempts == 0
                        && invalidTransitions == 0
                        && providerCostReservationOverruns == 0
                        && (!terminal || reserved == 0)
        );
    }

    private WorkflowRunStatus parseStatus(String status) {
        try {
            return status == null ? null : WorkflowRunStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean isTerminal(WorkflowRunStatus status) {
        return status == WorkflowRunStatus.SUCCESS
                || status == WorkflowRunStatus.FAILED
                || status == WorkflowRunStatus.TIMEOUT
                || status == WorkflowRunStatus.CANCELLED;
    }

    public record BatchResult(int scanned, int inconsistent, boolean cycleComplete) {
    }
}
