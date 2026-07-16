package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepAttempt;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowAttemptRecoveryService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowChargeReconciler;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowCancellationService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRecoveryConflictException;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRecoveryScheduler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRecoverySchedulerTest {

    @Test
    void scheduledJobsDoNothingWhileRuntimeIsDisabled() {
        Fixture fixture = fixture(false, false);

        fixture.scheduler.recover();
        fixture.scheduler.reconcile();

        verify(fixture.mapper, never()).selectExpiredActive(any(), anyInt());
        verify(fixture.runMapper, never()).selectList(any());
        verify(fixture.cancellationService, never()).settlePersisted(any());
        verify(fixture.reconciler, never()).reconcileBatch(anyInt());
    }

    @Test
    void recoveryStillRunsWithoutAutoRetryAndUsesConfiguredTimeoutAndBatch() {
        Fixture fixture = fixture(true, false);
        fixture.properties.setAttemptTimeout(Duration.ofMinutes(20));
        fixture.properties.setRecoveryBatchSize(7);
        when(fixture.mapper.selectExpiredActive(any(), anyInt())).thenReturn(List.of(attempt(1L)));
        when(fixture.recovery.recoverOne(any(), any())).thenReturn(true);

        LocalDateTime before = LocalDateTime.now().minusMinutes(20).minusSeconds(1);
        fixture.scheduler.recover();
        LocalDateTime after = LocalDateTime.now().minusMinutes(20).plusSeconds(1);

        var cutoffCaptor = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fixture.mapper).selectExpiredActive(cutoffCaptor.capture(), org.mockito.ArgumentMatchers.eq(7));
        assertThat(cutoffCaptor.getValue()).isBetween(before, after);
        assertThat(fixture.registry.counter("workflow_recovery_total", "result", "recovered").count())
                .isEqualTo(1);
    }

    @Test
    void scannerSkipsOnlyExplicitCasConflictsAndContinuesBatch() {
        Fixture fixture = fixture(true, true);
        WorkflowStepAttempt conflicted = attempt(1L);
        WorkflowStepAttempt recovered = attempt(2L);
        LocalDateTime cutoff = LocalDateTime.now();
        when(fixture.mapper.selectExpiredActive(cutoff, 10)).thenReturn(List.of(conflicted, recovered));
        when(fixture.recovery.recoverOne(1L, cutoff))
                .thenThrow(new WorkflowRecoveryConflictException("lost CAS"));
        when(fixture.recovery.recoverOne(2L, cutoff)).thenReturn(true);

        assertThat(fixture.scheduler.recoverExpiredAttempts(cutoff, 10)).isOne();
        verify(fixture.recovery).recoverOne(2L, cutoff);
        assertThat(fixture.registry.counter("workflow_cas_conflict_total", "operation", "recovery").count())
                .isEqualTo(1);
        assertThat(fixture.registry.counter("workflow_recovery_total", "result", "cas_conflict").count())
                .isEqualTo(1);
    }

    @Test
    void scannerProcessesRemainingRecordsThenPropagatesUnexpectedFailures() {
        Fixture fixture = fixture(true, true);
        WorkflowStepAttempt poisoned = attempt(1L);
        WorkflowStepAttempt recovered = attempt(2L);
        LocalDateTime cutoff = LocalDateTime.now();
        when(fixture.mapper.selectExpiredActive(cutoff, 10)).thenReturn(List.of(poisoned, recovered));
        when(fixture.recovery.recoverOne(1L, cutoff))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(fixture.recovery.recoverOne(2L, cutoff)).thenReturn(true);

        assertThatThrownBy(() -> fixture.scheduler.recoverExpiredAttempts(cutoff, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        verify(fixture.recovery).recoverOne(2L, cutoff);
        assertThat(fixture.registry.counter("workflow_recovery_total", "result", "failed").count())
                .isEqualTo(1);
        assertThat(fixture.registry.counter("workflow_recovery_total", "result", "recovered").count())
                .isEqualTo(1);
    }

    @Test
    void reconciliationRunsOnlyWhenRuntimeIsEnabled() {
        Fixture fixture = fixture(true, false);
        fixture.properties.setReconciliationBatchSize(23);

        fixture.scheduler.reconcile();

        verify(fixture.reconciler).reconcileBatch(23);
    }

    @Test
    void cancellationRecoveryRunsWhenEnabledEvenIfAutoRetryIsDisabled() {
        Fixture fixture = fixture(true, false);
        when(fixture.runMapper.selectList(any())).thenReturn(List.of(run(1L)));
        when(fixture.cancellationService.settlePersisted(1L)).thenReturn(true);

        fixture.scheduler.recover();

        verify(fixture.cancellationService).settlePersisted(1L);
        assertThat(fixture.registry.counter(
                "workflow_recovery_total", "result", "cancellation_settled").count()).isEqualTo(1);
    }

    @Test
    void pendingLostCancellationDoesNotStarveLaterRunInBatch() {
        Fixture fixture = fixture(true, false);
        when(fixture.runMapper.selectList(any())).thenReturn(List.of(run(1L), run(2L)));
        when(fixture.cancellationService.settlePersisted(1L)).thenReturn(false);
        when(fixture.cancellationService.settlePersisted(2L)).thenReturn(true);

        assertThat(fixture.scheduler.recoverCancellingRuns(10)).isOne();

        verify(fixture.cancellationService).settlePersisted(2L);
        assertThat(fixture.registry.counter(
                "workflow_recovery_total", "result", "cancellation_pending_reconciliation").count())
                .isEqualTo(1);
        assertThat(fixture.registry.counter(
                "workflow_recovery_total", "result", "cancellation_settled").count()).isEqualTo(1);
    }

    @Test
    void deferredPendingRunDoesNotStarveLaterBatch() {
        Fixture fixture = fixture(true, false);
        when(fixture.runMapper.selectList(any()))
                .thenReturn(List.of(run(1L)))
                .thenReturn(List.of(run(2L)));
        when(fixture.cancellationService.settlePersisted(1L)).thenReturn(false);
        when(fixture.cancellationService.settlePersisted(2L)).thenReturn(true);

        assertThat(fixture.scheduler.recoverCancellingRuns(1)).isZero();
        assertThat(fixture.scheduler.recoverCancellingRuns(1)).isOne();

        verify(fixture.cancellationService).settlePersisted(2L);
        var queryCaptor = org.mockito.ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class
        );
        verify(fixture.runMapper, times(2)).selectList(queryCaptor.capture());
        assertThat(queryCaptor.getAllValues()).allSatisfy(query -> assertThat(
                ((com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?>) query)
                        .getSqlSegment().toLowerCase()
        ).contains("order by updated_at asc,id asc"));
    }

    @Test
    void cancellationScannerProcessesRemainingRecordsThenPropagatesUnexpectedFailure() {
        Fixture fixture = fixture(true, false);
        when(fixture.runMapper.selectList(any())).thenReturn(List.of(run(1L), run(2L)));
        when(fixture.cancellationService.settlePersisted(1L))
                .thenThrow(new IllegalStateException("cancellation database unavailable"));
        when(fixture.cancellationService.settlePersisted(2L)).thenReturn(true);

        assertThatThrownBy(() -> fixture.scheduler.recoverCancellingRuns(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cancellation database unavailable");

        verify(fixture.cancellationService).settlePersisted(2L);
        assertThat(fixture.registry.counter(
                "workflow_recovery_total", "result", "cancellation_failed").count()).isEqualTo(1);
    }

    @Test
    void cancellationScannerSwallowsOnlyExplicitCasConflict() {
        Fixture fixture = fixture(true, false);
        when(fixture.runMapper.selectList(any())).thenReturn(List.of(run(1L), run(2L)));
        when(fixture.cancellationService.settlePersisted(1L))
                .thenThrow(new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "run changed"));
        when(fixture.cancellationService.settlePersisted(2L)).thenReturn(true);

        assertThat(fixture.scheduler.recoverCancellingRuns(10)).isOne();

        verify(fixture.cancellationService).settlePersisted(2L);
        assertThat(fixture.registry.counter(
                "workflow_cas_conflict_total", "operation", "cancellation").count()).isEqualTo(1);
    }

    @Test
    void scheduledRecoveryStillScansCancellationsAfterAttemptFailure() {
        Fixture fixture = fixture(true, true);
        WorkflowStepAttempt poisoned = attempt(1L);
        when(fixture.mapper.selectExpiredActive(any(), anyInt())).thenReturn(List.of(poisoned));
        when(fixture.recovery.recoverOne(any(), any()))
                .thenThrow(new IllegalStateException("attempt recovery failed"));
        when(fixture.runMapper.selectList(any())).thenReturn(List.of(run(2L)));
        when(fixture.cancellationService.settlePersisted(2L)).thenReturn(true);

        assertThatThrownBy(fixture.scheduler::recover)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("attempt recovery failed");

        verify(fixture.cancellationService).settlePersisted(2L);
    }

    private Fixture fixture(boolean enabled, boolean autoRetryEnabled) {
        WorkflowStepAttemptMapper mapper = mock(WorkflowStepAttemptMapper.class);
        WorkflowAttemptRecoveryService recovery = mock(WorkflowAttemptRecoveryService.class);
        WorkflowChargeReconciler reconciler = mock(WorkflowChargeReconciler.class);
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowCancellationService cancellationService = mock(WorkflowCancellationService.class);
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setEnabled(enabled);
        properties.setAutoRetryEnabled(autoRetryEnabled);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowMetrics metrics = new WorkflowMetrics(registry);
        WorkflowRecoveryScheduler scheduler = new WorkflowRecoveryScheduler(
                mapper,
                recovery,
                reconciler,
                runMapper,
                cancellationService,
                properties,
                metrics
        );
        return new Fixture(mapper, recovery, reconciler, runMapper, cancellationService,
                properties, registry, scheduler);
    }

    private WorkflowStepAttempt attempt(Long id) {
        WorkflowStepAttempt attempt = new WorkflowStepAttempt();
        attempt.setId(id);
        return attempt;
    }

    private WorkflowRun run(Long id) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setStatus("CANCELLING");
        return run;
    }

    private record Fixture(WorkflowStepAttemptMapper mapper,
                           WorkflowAttemptRecoveryService recovery,
                           WorkflowChargeReconciler reconciler,
                           WorkflowRunMapper runMapper,
                           WorkflowCancellationService cancellationService,
                           WorkflowRuntimeProperties properties,
                           SimpleMeterRegistry registry,
                           WorkflowRecoveryScheduler scheduler) {
    }
}
