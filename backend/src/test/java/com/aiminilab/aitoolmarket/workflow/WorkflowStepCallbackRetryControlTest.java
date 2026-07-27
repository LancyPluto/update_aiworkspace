package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepAttempt;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowBillingService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowExecutionService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowStepCallbackService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowStepScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowStepCallbackRetryControlTest {

    @Test
    void disabledAutoRetryFailsStepInsteadOfDispatchingAnotherAttempt() {
        WorkflowStepAttemptMapper attemptMapper = mock(WorkflowStepAttemptMapper.class);
        WorkflowRunStepMapper stepMapper = mock(WorkflowRunStepMapper.class);
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowExecutionService executionService = mock(WorkflowExecutionService.class);
        WorkflowBillingService billingService = mock(WorkflowBillingService.class);
        WorkflowStepScheduler stepScheduler = mock(WorkflowStepScheduler.class);
        WorkflowMetrics metrics = mock(WorkflowMetrics.class);
        WorkflowRuntimeProperties runtimeProperties = new WorkflowRuntimeProperties();
        runtimeProperties.setAutoRetryEnabled(false);
        WorkflowStepCallbackService service = new WorkflowStepCallbackService(
                attemptMapper,
                stepMapper,
                runMapper,
                executionService,
                billingService,
                stepScheduler,
                runtimeProperties,
                new ObjectMapper(),
                metrics
        );

        WorkflowStepAttempt attempt = new WorkflowStepAttempt();
        attempt.setId(11L);
        attempt.setStepId(21L);
        attempt.setChildTaskId(31L);
        attempt.setAttemptNo(1);
        attempt.setCancellationGeneration(0L);
        attempt.setStatus("RUNNING");
        WorkflowRunStep step = new WorkflowRunStep();
        step.setId(21L);
        step.setRunId(41L);
        step.setStatus("RUNNING");
        step.setRevision(0L);
        step.setCurrentAttemptId(11L);
        step.setMaxAttempts(3);
        WorkflowRun run = new WorkflowRun();
        run.setId(41L);
        run.setStatus("RUNNING");
        run.setCancellationGeneration(0L);
        WorkerFailedRequest failure = new WorkerFailedRequest(
                "PROVIDER_FAILED", "provider failed", "PROVIDER_CALL", false,
                null, null, "provider-request", 0, 0, 0, "claim"
        );

        when(attemptMapper.selectByChildTaskId(31L)).thenReturn(attempt);
        when(stepMapper.selectById(21L)).thenReturn(step);
        when(runMapper.selectByIdForUpdate(41L)).thenReturn(run);
        when(attemptMapper.attachProviderRequestId(11L, "provider-request")).thenReturn(1);
        when(attemptMapper.markFailedWithContract(
                eq(11L), eq("PROVIDER_FAILED"), eq("工作流执行失败，请稍后重试"),
                eq("provider failed"), eq(null), eq("provider-request"), anyList()
        )).thenReturn(1);
        when(stepMapper.failActiveAttemptWithContract(
                eq(21L), eq(0L), eq(11L), eq("PROVIDER_FAILED"),
                eq("工作流执行失败，请稍后重试"), eq("provider failed"), eq(null), anyList()
        )).thenReturn(1);

        assertThat(service.failed(31L, failure)).isTrue();

        verify(stepMapper, never()).releaseActiveAttemptForRetry(
                eq(21L), eq(0L), eq(11L), eq("provider failed"), anyList()
        );
        verify(stepScheduler, never()).retry(21L);
        verify(executionService).onStepAttemptsExhausted(21L, failure);
    }

    @Test
    void terminalRunCallbackRecordsBoundedRejectionReason() {
        CallbackFixture fixture = callbackFixture();
        fixture.run().setStatus("CANCELLED");

        assertThat(fixture.service().failed(31L, failure())).isFalse();

        verify(fixture.metrics()).recordLateCallback(WorkflowMetrics.LateCallbackResult.REJECTED_TERMINAL_RUN);
        verifyProviderRequestIdWasNotAttached(fixture);
    }

    @Test
    void billingReconciliationFailedRunRejectsLateCallbackWithoutAdvancing() {
        CallbackFixture fixture = callbackFixture();
        fixture.run().setStatus("FAILED");
        fixture.run().setBillingStatus("RECONCILIATION_FAILED");
        WorkerFailedRequest failure = failure();

        assertThat(fixture.service().failed(31L, failure)).isFalse();

        verify(fixture.metrics()).recordLateCallback(WorkflowMetrics.LateCallbackResult.REJECTED_TERMINAL_RUN);
        verify(fixture.billingService()).releaseLateFailure(11L, 31L, failure);
        verifyProviderRequestIdWasNotAttached(fixture);
    }

    @Test
    void staleGenerationCallbackRecordsBoundedRejectionReason() {
        CallbackFixture fixture = callbackFixture();
        fixture.run().setCancellationGeneration(2L);

        assertThat(fixture.service().failed(31L, failure())).isFalse();

        verify(fixture.metrics()).recordLateCallback(WorkflowMetrics.LateCallbackResult.REJECTED_GENERATION);
        verifyProviderRequestIdWasNotAttached(fixture);
    }

    @Test
    void staleClaimCallbackRecordsBoundedRejectionReason() {
        CallbackFixture fixture = callbackFixture();
        fixture.step().setCurrentAttemptId(12L);

        assertThat(fixture.service().failed(31L, failure())).isFalse();

        verify(fixture.metrics()).recordLateCallback(WorkflowMetrics.LateCallbackResult.REJECTED_CLAIM);
        verifyProviderRequestIdWasNotAttached(fixture);
    }

    @Test
    void activeCallbackWithDifferentProviderRequestIdStillFailsClosed() {
        CallbackFixture fixture = callbackFixture();
        when(fixture.attemptMapper().selectProviderRequestId(11L)).thenReturn("original-request");

        assertThatThrownBy(() -> fixture.service().failed(31L, failure()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Provider request id conflicts with the workflow attempt");

        verify(fixture.billingService(), never()).releaseLateFailure(eq(11L), eq(31L), eq(failure()));
    }

    private void verifyProviderRequestIdWasNotAttached(CallbackFixture fixture) {
        verify(fixture.attemptMapper(), never()).selectProviderRequestId(11L);
        verify(fixture.attemptMapper(), never()).attachProviderRequestId(11L, "provider-request");
    }

    private CallbackFixture callbackFixture() {
        WorkflowStepAttemptMapper attemptMapper = mock(WorkflowStepAttemptMapper.class);
        WorkflowRunStepMapper stepMapper = mock(WorkflowRunStepMapper.class);
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowBillingService billingService = mock(WorkflowBillingService.class);
        WorkflowMetrics metrics = mock(WorkflowMetrics.class);
        WorkflowRuntimeProperties runtimeProperties = new WorkflowRuntimeProperties();
        WorkflowStepCallbackService service = new WorkflowStepCallbackService(
                attemptMapper,
                stepMapper,
                runMapper,
                mock(WorkflowExecutionService.class),
                billingService,
                mock(WorkflowStepScheduler.class),
                runtimeProperties,
                new ObjectMapper(),
                metrics
        );
        WorkflowStepAttempt attempt = new WorkflowStepAttempt();
        attempt.setId(11L);
        attempt.setStepId(21L);
        attempt.setChildTaskId(31L);
        attempt.setAttemptNo(1);
        attempt.setCancellationGeneration(1L);
        attempt.setStatus("RUNNING");
        WorkflowRunStep step = new WorkflowRunStep();
        step.setId(21L);
        step.setRunId(41L);
        step.setStatus("RUNNING");
        step.setRevision(0L);
        step.setCurrentAttemptId(11L);
        step.setMaxAttempts(1);
        WorkflowRun run = new WorkflowRun();
        run.setId(41L);
        run.setStatus("RUNNING");
        run.setCancellationGeneration(1L);
        when(attemptMapper.selectByChildTaskId(31L)).thenReturn(attempt);
        when(stepMapper.selectById(21L)).thenReturn(step);
        when(runMapper.selectByIdForUpdate(41L)).thenReturn(run);
        return new CallbackFixture(service, attemptMapper, billingService, metrics, step, run);
    }

    private WorkerFailedRequest failure() {
        return new WorkerFailedRequest(
                "PROVIDER_FAILED", "provider failed", "PROVIDER_CALL", true,
                java.math.BigDecimal.ONE, null, "provider-request", 1, 1, 1, "claim"
        );
    }

    private record CallbackFixture(WorkflowStepCallbackService service,
                                   WorkflowStepAttemptMapper attemptMapper,
                                   WorkflowBillingService billingService,
                                   WorkflowMetrics metrics,
                                   WorkflowRunStep step,
                                   WorkflowRun run) {
    }
}
