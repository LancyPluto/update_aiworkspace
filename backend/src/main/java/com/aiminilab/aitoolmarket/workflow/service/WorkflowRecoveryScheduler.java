package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepAttempt;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class WorkflowRecoveryScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowRecoveryScheduler.class);

    private final WorkflowStepAttemptMapper attemptMapper;
    private final WorkflowAttemptRecoveryService recoveryService;
    private final WorkflowChargeReconciler chargeReconciler;
    private final WorkflowRunMapper runMapper;
    private final WorkflowCancellationService cancellationService;
    private final WorkflowRuntimeProperties properties;
    private final WorkflowMetrics metrics;

    public WorkflowRecoveryScheduler(WorkflowStepAttemptMapper attemptMapper,
                                     WorkflowAttemptRecoveryService recoveryService,
                                     WorkflowChargeReconciler chargeReconciler,
                                     WorkflowRunMapper runMapper,
                                     WorkflowCancellationService cancellationService,
                                     WorkflowRuntimeProperties properties,
                                     WorkflowMetrics metrics) {
        this.attemptMapper = attemptMapper;
        this.recoveryService = recoveryService;
        this.chargeReconciler = chargeReconciler;
        this.runMapper = runMapper;
        this.cancellationService = cancellationService;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${workflow.runtime.recovery-interval:PT1M}")
    public void recover() {
        if (!properties.isEnabled()) {
            return;
        }
        int batchSize = positiveBatch(properties.getRecoveryBatchSize());
        RuntimeException firstFailure = null;
        Duration timeout = positiveDuration(properties.getAttemptTimeout(), Duration.ofMinutes(15));
        try {
            recoverExpiredAttempts(LocalDateTime.now().minus(timeout), batchSize);
        } catch (RuntimeException exception) {
            firstFailure = exception;
        }
        try {
            recoverCancellingRuns(batchSize);
        } catch (RuntimeException exception) {
            if (firstFailure == null) {
                firstFailure = exception;
            } else {
                firstFailure.addSuppressed(exception);
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    @Scheduled(fixedDelayString = "${workflow.runtime.reconciliation-interval:PT15M}")
    public void reconcile() {
        if (!properties.isEnabled()) {
            return;
        }
        chargeReconciler.reconcileBatch(positiveBatch(properties.getReconciliationBatchSize()));
    }

    public int recoverExpiredAttempts(LocalDateTime cutoff, int limit) {
        LocalDateTime effectiveCutoff = cutoff == null ? LocalDateTime.now() : cutoff;
        int recovered = 0;
        RuntimeException firstUnexpectedFailure = null;
        for (WorkflowStepAttempt attempt : attemptMapper.selectExpiredActive(
                effectiveCutoff,
                positiveBatch(limit)
        )) {
            try {
                if (recoveryService.recoverOne(attempt.getId(), effectiveCutoff)) {
                    recovered++;
                    metrics.recordRecovery(WorkflowMetrics.RecoveryResult.RECOVERED);
                } else {
                    metrics.recordRecovery(WorkflowMetrics.RecoveryResult.NO_ACTION);
                }
            } catch (WorkflowRecoveryConflictException exception) {
                metrics.recordRecovery(WorkflowMetrics.RecoveryResult.CAS_CONFLICT);
                metrics.recordCasConflict(WorkflowMetrics.CasOperation.RECOVERY);
                LOGGER.warn("Workflow attempt recovery skipped after CAS conflict attemptId={}",
                        attempt.getId(), exception);
            } catch (RuntimeException exception) {
                metrics.recordRecovery(WorkflowMetrics.RecoveryResult.FAILED);
                LOGGER.error("Workflow attempt recovery failed attemptId={}", attempt.getId(), exception);
                if (firstUnexpectedFailure == null) {
                    firstUnexpectedFailure = exception;
                } else {
                    firstUnexpectedFailure.addSuppressed(exception);
                }
            }
        }
        if (firstUnexpectedFailure != null) {
            throw firstUnexpectedFailure;
        }
        return recovered;
    }

    public int recoverCancellingRuns(int limit) {
        int effectiveLimit = positiveBatch(limit);
        QueryWrapper<WorkflowRun> query = new QueryWrapper<>();
        query.eq("status", "CANCELLING")
                .orderByAsc("updated_at", "id")
                .last("LIMIT " + effectiveLimit);
        List<WorkflowRun> runs = runMapper.selectList(query);
        if (runs == null) {
            runs = List.of();
        }

        int settled = 0;
        RuntimeException firstUnexpectedFailure = null;
        for (WorkflowRun run : runs) {
            try {
                if (cancellationService.settlePersisted(run.getId())) {
                    settled++;
                    metrics.recordRecovery(WorkflowMetrics.RecoveryResult.CANCELLATION_SETTLED);
                } else {
                    metrics.recordRecovery(
                            WorkflowMetrics.RecoveryResult.CANCELLATION_PENDING_RECONCILIATION
                    );
                }
            } catch (BusinessException exception) {
                if (exception.getErrorCode() == ErrorCode.IDEMPOTENCY_CONFLICT) {
                    metrics.recordRecovery(WorkflowMetrics.RecoveryResult.CANCELLATION_CAS_CONFLICT);
                    metrics.recordCasConflict(WorkflowMetrics.CasOperation.CANCELLATION);
                    LOGGER.warn("Workflow cancellation recovery skipped after CAS conflict runId={}",
                            run.getId(), exception);
                } else {
                    firstUnexpectedFailure = collectUnexpectedCancellationFailure(
                            firstUnexpectedFailure, run.getId(), exception
                    );
                }
            } catch (RuntimeException exception) {
                firstUnexpectedFailure = collectUnexpectedCancellationFailure(
                        firstUnexpectedFailure, run.getId(), exception
                );
            }
        }
        if (firstUnexpectedFailure != null) {
            throw firstUnexpectedFailure;
        }
        return settled;
    }

    private RuntimeException collectUnexpectedCancellationFailure(RuntimeException first,
                                                                  Long runId,
                                                                  RuntimeException exception) {
        metrics.recordRecovery(WorkflowMetrics.RecoveryResult.CANCELLATION_FAILED);
        LOGGER.error("Workflow cancellation recovery failed runId={}", runId, exception);
        if (first == null) {
            return exception;
        }
        first.addSuppressed(exception);
        return first;
    }

    private int positiveBatch(int configured) {
        return Math.max(1, configured);
    }

    private Duration positiveDuration(Duration configured, Duration fallback) {
        return configured == null || configured.isZero() || configured.isNegative() ? fallback : configured;
    }
}
