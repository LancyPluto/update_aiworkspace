package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepChargeMapper;
import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowChargeReconciler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowChargeReconcilerTest {

    @Test
    void mismatchRecordsMetricAndImmediatelyClosesGateWithoutChangingBalances() {
        Fixture fixture = fixture();
        fixture.gate.markReconciliationHealthyAfterFullScan(fixture.gate.reconciliationFailureGeneration());
        WorkflowRun run = run(1L, "SUCCESS");
        when(fixture.runMapper.selectById(1L)).thenReturn(run);
        when(fixture.chargeMapper.sumCapturedCredits(1L)).thenReturn(10);
        when(fixture.chargeMapper.sumUsageCredits(1L)).thenReturn(9);
        when(fixture.chargeMapper.sumCreditDeductions(1L)).thenReturn(10);

        assertThat(fixture.reconciler.reconcileRun(1L).consistent()).isFalse();

        assertThat(fixture.gate.isReconciliationHealthy()).isFalse();
        assertThat(fixture.registry.counter("workflow_reconciliation_total", "result", "inconsistent").count())
                .isEqualTo(1);
    }

    @Test
    void lostAttemptKeepsGateClosedEvenWhenItsChargeLedgerOtherwiseBalances() {
        Fixture fixture = fixture();
        fixture.gate.markReconciliationHealthyAfterFullScan(fixture.gate.reconciliationFailureGeneration());
        WorkflowRun run = run(1L, "CANCELLING");
        when(fixture.runMapper.selectById(1L)).thenReturn(run);
        when(fixture.chargeMapper.countLostAttempts(1L)).thenReturn(1);

        var result = fixture.reconciler.reconcileRun(1L);

        assertThat(result.consistent()).isFalse();
        assertThat(result.lostAttempts()).isOne();
        assertThat(fixture.gate.isReconciliationHealthy()).isFalse();
    }

    @Test
    void actualProviderCostAboveRunReservationIsInconsistentAndClosesGate() {
        Fixture fixture = fixture();
        fixture.gate.markReconciliationHealthyAfterFullScan(fixture.gate.reconciliationFailureGeneration());
        WorkflowRun run = run(1L, "RUNNING");
        when(fixture.runMapper.selectById(1L)).thenReturn(run);
        when(fixture.chargeMapper.countProviderCostReservationOverruns(1L)).thenReturn(1);

        var result = fixture.reconciler.reconcileRun(1L);

        assertThat(result.consistent()).isFalse();
        assertThat(result.providerCostReservationOverruns()).isOne();
        assertThat(fixture.gate.isReconciliationHealthy()).isFalse();
    }

    @Test
    void lostAttemptWithReleasedChargeStillCannotReopenGate() {
        Fixture fixture = fixture();
        WorkflowRun run = run(1L, "CANCELLING");
        when(fixture.runMapper.selectList(any())).thenReturn(List.of(run));
        when(fixture.chargeMapper.countLostAttempts(1L)).thenReturn(1);

        WorkflowChargeReconciler.BatchResult result = fixture.reconciler.reconcileBatch(10);

        assertThat(result.inconsistent()).isOne();
        assertThat(result.cycleComplete()).isTrue();
        assertThat(fixture.gate.isReconciliationHealthy()).isFalse();
    }

    @Test
    void unknownRunStatusIsInconsistentAndClosesGate() {
        Fixture fixture = fixture();
        fixture.gate.markReconciliationHealthyAfterFullScan(fixture.gate.reconciliationFailureGeneration());
        WorkflowRun run = run(1L, "BROKEN_STATUS");
        when(fixture.runMapper.selectById(1L)).thenReturn(run);

        assertThat(fixture.reconciler.reconcileRun(1L).consistent()).isFalse();

        assertThat(fixture.gate.isReconciliationHealthy()).isFalse();
        assertThat(fixture.registry.counter("workflow_reconciliation_total", "result", "inconsistent").count())
                .isEqualTo(1);
    }

    @Test
    void completeCleanBatchOpensStartupGate() {
        Fixture fixture = fixture();
        WorkflowRun run = run(1L, "SUCCESS");
        when(fixture.runMapper.selectList(any())).thenReturn(List.of(run));
        when(fixture.chargeMapper.sumCapturedCredits(1L)).thenReturn(10);
        when(fixture.chargeMapper.sumUsageCredits(1L)).thenReturn(10);
        when(fixture.chargeMapper.sumCreditDeductions(1L)).thenReturn(10);

        WorkflowChargeReconciler.BatchResult result = fixture.reconciler.reconcileBatch(10);

        assertThat(result.scanned()).isOne();
        assertThat(result.inconsistent()).isZero();
        assertThat(result.cycleComplete()).isTrue();
        assertThat(fixture.gate.isReconciliationHealthy()).isTrue();
    }

    @Test
    void inconsistentBatchStaysClosedAtCycleCompletion() {
        Fixture fixture = fixture();
        WorkflowRun run = run(1L, "SUCCESS");
        when(fixture.runMapper.selectList(any())).thenReturn(List.of(run));
        when(fixture.chargeMapper.sumCapturedCredits(1L)).thenReturn(10);
        when(fixture.chargeMapper.sumUsageCredits(1L)).thenReturn(9);
        when(fixture.chargeMapper.sumCreditDeductions(1L)).thenReturn(10);

        WorkflowChargeReconciler.BatchResult result = fixture.reconciler.reconcileBatch(10);

        assertThat(result.inconsistent()).isOne();
        assertThat(result.cycleComplete()).isTrue();
        assertThat(fixture.gate.isReconciliationHealthy()).isFalse();
    }

    @Test
    void inconsistencyOnFullPageRemainsLatchedUntilNextPageCompletesCycle() {
        Fixture fixture = fixture();
        WorkflowRun run = run(1L, "SUCCESS");
        when(fixture.runMapper.selectList(any()))
                .thenReturn(List.of(run))
                .thenReturn(List.of());
        when(fixture.chargeMapper.sumCapturedCredits(1L)).thenReturn(10);
        when(fixture.chargeMapper.sumUsageCredits(1L)).thenReturn(9);
        when(fixture.chargeMapper.sumCreditDeductions(1L)).thenReturn(10);

        assertThat(fixture.reconciler.reconcileBatch(1).cycleComplete()).isFalse();
        assertThat(fixture.reconciler.reconcileBatch(1).cycleComplete()).isTrue();

        assertThat(fixture.gate.isReconciliationHealthy()).isFalse();
    }

    @Test
    void batchProcessesRecordsAfterPoisonThenPropagatesForAlerting() {
        Fixture fixture = fixture();
        WorkflowRun poisoned = run(1L, "SUCCESS");
        WorkflowRun healthy = run(2L, "SUCCESS");
        when(fixture.runMapper.selectList(any())).thenReturn(List.of(poisoned, healthy));
        when(fixture.chargeMapper.sumCapturedCredits(1L)).thenThrow(new IllegalStateException("db read failed"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.reconciler.reconcileBatch(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db read failed");

        verify(fixture.chargeMapper).sumCapturedCredits(2L);
        assertThat(fixture.gate.isReconciliationHealthy()).isFalse();
    }

    private Fixture fixture() {
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowStepChargeMapper chargeMapper = mock(WorkflowStepChargeMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowMetrics metrics = new WorkflowMetrics(registry);
        WorkflowRuntimeGate gate = new WorkflowRuntimeGate(new WorkflowRuntimeProperties());
        WorkflowChargeReconciler reconciler = new WorkflowChargeReconciler(runMapper, chargeMapper, metrics, gate);
        return new Fixture(runMapper, chargeMapper, registry, gate, reconciler);
    }

    private WorkflowRun run(Long id, String status) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setStatus(status);
        return run;
    }

    private record Fixture(WorkflowRunMapper runMapper,
                           WorkflowStepChargeMapper chargeMapper,
                           SimpleMeterRegistry registry,
                           WorkflowRuntimeGate gate,
                           WorkflowChargeReconciler reconciler) {
    }
}
