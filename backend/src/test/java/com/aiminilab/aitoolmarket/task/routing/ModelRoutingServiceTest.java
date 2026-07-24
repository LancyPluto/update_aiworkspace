package com.aiminilab.aitoolmarket.task.routing;

import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.task.dto.RouteFailoverRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.routing.entity.AccountModelRouteState;
import com.aiminilab.aitoolmarket.task.routing.entity.TaskModelRouteAttempt;
import com.aiminilab.aitoolmarket.task.routing.mapper.AccountModelRouteStateMapper;
import com.aiminilab.aitoolmarket.task.routing.mapper.TaskModelRouteAttemptMapper;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRoutingServiceTest {
    private TaskMapper taskMapper;
    private ToolMapper toolMapper;
    private AgentModelConfigMapper modelConfigMapper;
    private ModelVendorAccountMapper accountMapper;
    private TaskModelRouteAttemptMapper attemptMapper;
    private AccountModelRouteStateMapper stateMapper;
    private ModelCapabilityService capabilityService;
    private ModelExecutionSnapshotService snapshotService;
    private ModelRoutingService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(TaskMapper.class);
        toolMapper = mock(ToolMapper.class);
        modelConfigMapper = mock(AgentModelConfigMapper.class);
        accountMapper = mock(ModelVendorAccountMapper.class);
        attemptMapper = mock(TaskModelRouteAttemptMapper.class);
        stateMapper = mock(AccountModelRouteStateMapper.class);
        capabilityService = mock(ModelCapabilityService.class);
        snapshotService = mock(ModelExecutionSnapshotService.class);
        AppProperties properties = new AppProperties();
        properties.getModelRouting().setEnabled(true);
        properties.getModelRouting().setMaxFailovers(2);
        service = new ModelRoutingService(
                taskMapper, toolMapper, modelConfigMapper, accountMapper, attemptMapper, stateMapper,
                capabilityService, snapshotService, new ObjectMapper(), properties
        );
    }

    @Test
    void switchesToAnotherAccountWithoutChangingTheTaskLease() {
        AiTask task = processingTask();
        TaskModelRouteAttempt currentAttempt = activeAttempt(7L, 1L, 10L);
        AgentModelConfig reference = model(1L, 10L);
        AgentModelConfig alternate = model(2L, 20L);
        alternate.setLastTestSuccess(false);
        AgentModelConfig outsidePool = model(3L, 30L);
        outsidePool.setRoutingPoolId(200L);
        ModelVendorAccount source = account(10L, 100);
        ModelVendorAccount target = account(20L, 100);
        ModelVendorAccount outside = account(30L, 100);
        outside.setRoutingPoolId(200L);
        AccountModelRouteState sourceState = state(101L, 1L, 10L);
        AccountModelRouteState targetState = state(102L, 2L, 20L);

        when(taskMapper.selectByIdForUpdate(1L)).thenReturn(task);
        when(attemptMapper.findByIdForUpdate(7L)).thenReturn(currentAttempt);
        when(attemptMapper.countByTaskId(1L)).thenReturn(1);
        when(modelConfigMapper.findActiveById(1L)).thenReturn(reference);
        when(accountMapper.findActiveById(10L)).thenReturn(source);
        when(accountMapper.findActiveByVendorCode("openai")).thenReturn(List.of(source, target, outside));
        when(modelConfigMapper.findRoutingCandidates("openai", "openai_images_gateway", "gpt-image-2"))
                .thenReturn(List.of(reference, alternate, outsidePool));
        when(capabilityService.resolveCapabilities(any())).thenReturn(List.of("IMAGE_GENERATION"));
        when(stateMapper.findByModelConfigIdsForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(sourceState, targetState));
        when(attemptMapper.findAttemptedVendorAccountIds(1L)).thenReturn(List.of(10L));
        when(attemptMapper.closeWithFailureContract(
                eq(7L), eq("SWITCHED"), any(), any(), any(), nullable(String.class))).thenReturn(1);
        when(stateMapper.reserve(102L, 0)).thenReturn(1);
        when(attemptMapper.insertAttempt(any())).thenAnswer(invocation -> {
            TaskModelRouteAttempt attempt = invocation.getArgument(0);
            attempt.setId(8L);
            return 1;
        });
        when(snapshotService.serialize(nullable(ModelExecutionSnapshot.class))).thenReturn("{}");
        when(taskMapper.switchRouteGuarded(
                1L, "claim-1", 7L, 2L, 20L, 8L, "{}"
        )).thenReturn(1);

        ModelRoutingService.FailoverDecision decision = service.failover(1L, safeFailure(7L));

        assertThat(decision.switched()).isTrue();
        assertThat(decision.routeAttemptId()).isEqualTo(8L);
        verify(stateMapper).releaseFailure(101L, "CLOSED", null, 1);
        verify(stateMapper).reserve(102L, 0);
        verify(stateMapper, never()).insertIfAbsent(30L, 3L);
        verify(taskMapper).switchRouteGuarded(1L, "claim-1", 7L, 2L, 20L, 8L, "{}");
    }

    @Test
    void accountModeNeverStartsAutomaticRouting() {
        AiTask task = new AiTask();
        task.setId(1L);
        AgentModelConfig reference = model(1L, 10L);
        reference.setRoutingPoolId(null);

        service.assignInitialRoute(task, reference);

        verify(accountMapper, never()).findActiveById(anyLong());
        verify(taskMapper, never()).assignInitialRoute(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void initialRouteIsIsolatedToTheExplicitPool() {
        AiTask task = new AiTask();
        task.setId(1L);
        AgentModelConfig reference = model(1L, 10L);
        AgentModelConfig samePool = model(2L, 20L);
        AgentModelConfig outsidePool = model(3L, 30L);
        outsidePool.setRoutingPoolId(200L);
        ModelVendorAccount source = account(10L, 100);
        ModelVendorAccount target = account(20L, 100);
        ModelVendorAccount outside = account(30L, 100);
        outside.setRoutingPoolId(200L);
        AccountModelRouteState sourceState = state(101L, 1L, 10L);
        sourceState.setInFlightCount(5);
        AccountModelRouteState targetState = state(102L, 2L, 20L);

        when(accountMapper.findActiveById(10L)).thenReturn(source);
        when(accountMapper.findActiveByVendorCode("openai")).thenReturn(List.of(source, target, outside));
        when(modelConfigMapper.findRoutingCandidates(
                "openai", "openai_images_gateway", "gpt-image-2"))
                .thenReturn(List.of(reference, samePool, outsidePool));
        when(capabilityService.resolveCapabilities(any())).thenReturn(List.of("IMAGE_GENERATION"));
        when(stateMapper.findByModelConfigIdsForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(sourceState, targetState));
        when(stateMapper.reserve(102L, 0)).thenReturn(1);
        prepareInitialAssignment(2L, 20L, 8L);

        service.assignInitialRoute(task, reference);

        assertThat(task.getSelectedModelConfigId()).isEqualTo(2L);
        assertThat(task.getSelectedVendorAccountId()).isEqualTo(20L);
        verify(stateMapper, never()).insertIfAbsent(30L, 3L);
    }

    @Test
    void initialRouteAcceptsCandidateWithAdditionalCapabilitiesWhenToolRequirementsMatch() {
        AiTask task = new AiTask();
        task.setId(1L);
        task.setToolId(99L);
        AiTool tool = new AiTool();
        tool.setId(99L);
        AgentModelConfig reference = model(1L, 10L);
        AgentModelConfig candidate = model(2L, 20L);
        ModelVendorAccount source = account(10L, 100);
        ModelVendorAccount target = account(20L, 100);
        AccountModelRouteState sourceState = state(101L, 1L, 10L);
        sourceState.setInFlightCount(5);
        AccountModelRouteState targetState = state(102L, 2L, 20L);

        when(toolMapper.findById(99L)).thenReturn(Optional.of(tool));
        when(capabilityService.resolveRequiredCapabilities(tool)).thenReturn(List.of("IMAGE_GENERATION"));
        when(accountMapper.findActiveById(10L)).thenReturn(source);
        when(accountMapper.findActiveByVendorCode("openai")).thenReturn(List.of(source, target));
        when(modelConfigMapper.findRoutingCandidates(
                "openai", "openai_images_gateway", "gpt-image-2"))
                .thenReturn(List.of(reference, candidate));
        when(capabilityService.resolveCapabilities(reference)).thenReturn(List.of("IMAGE_GENERATION"));
        when(capabilityService.resolveCapabilities(candidate))
                .thenReturn(List.of("IMAGE_GENERATION", "VISION_INPUT"));
        when(stateMapper.findByModelConfigIdsForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(sourceState, targetState));
        when(stateMapper.reserve(102L, 0)).thenReturn(1);
        prepareInitialAssignment(2L, 20L, 8L);

        service.assignInitialRoute(task, reference);

        assertThat(task.getSelectedModelConfigId()).isEqualTo(2L);
        assertThat(task.getSelectedVendorAccountId()).isEqualTo(20L);
    }

    @Test
    void emptyExplicitPoolDoesNotFallBackToAnOutsideAccount() {
        AiTask task = new AiTask();
        task.setId(1L);
        AgentModelConfig reference = model(1L, 10L);
        AgentModelConfig outsidePool = model(2L, 20L);
        outsidePool.setRoutingPoolId(200L);
        ModelVendorAccount source = account(10L, 100);
        source.setEnabled(false);
        ModelVendorAccount outside = account(20L, 100);
        outside.setRoutingPoolId(200L);

        when(accountMapper.findActiveById(10L)).thenReturn(source);
        when(accountMapper.findActiveByVendorCode("openai")).thenReturn(List.of(source, outside));
        when(modelConfigMapper.findRoutingCandidates(
                "openai", "openai_images_gateway", "gpt-image-2"))
                .thenReturn(List.of(outsidePool));

        assertThatThrownBy(() -> service.assignInitialRoute(task, reference))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no eligible model account");
        verify(taskMapper, never()).assignInitialRoute(
                anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void repeatedCompatibleConfigsDoNotGiveAnAccountExtraWeight() {
        AiTask task = new AiTask();
        task.setId(1L);
        AgentModelConfig reference = model(1L, 10L);
        AgentModelConfig first = model(2L, 20L);
        AgentModelConfig duplicate = model(3L, 20L);
        ModelVendorAccount source = account(10L, 100);
        ModelVendorAccount target = account(20L, 100);
        AccountModelRouteState sourceState = state(101L, 1L, 10L);
        sourceState.setInFlightCount(5);
        AccountModelRouteState targetState = state(102L, 2L, 20L);

        when(accountMapper.findActiveById(10L)).thenReturn(source);
        when(accountMapper.findActiveByVendorCode("openai")).thenReturn(List.of(source, target));
        when(modelConfigMapper.findRoutingCandidates(
                "openai", "openai_images_gateway", "gpt-image-2"))
                .thenReturn(List.of(reference, duplicate, first));
        when(capabilityService.resolveCapabilities(any())).thenReturn(List.of("IMAGE_GENERATION"));
        when(stateMapper.findByModelConfigIdsForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(sourceState, targetState));
        when(stateMapper.reserve(102L, 0)).thenReturn(1);
        prepareInitialAssignment(2L, 20L, 8L);

        service.assignInitialRoute(task, reference);

        assertThat(task.getSelectedModelConfigId()).isEqualTo(2L);
        verify(stateMapper, never()).insertIfAbsent(20L, 3L);
    }

    @Test
    void refusesUnknownDeliveryBeforeChangingAnyRouteState() {
        AiTask task = processingTask();
        when(taskMapper.selectByIdForUpdate(1L)).thenReturn(task);
        when(attemptMapper.findByIdForUpdate(7L)).thenReturn(activeAttempt(7L, 1L, 10L));
        RouteFailoverRequest unknown = new RouteFailoverRequest(
                "claim-1", 7L, "UNKNOWN", "ACCOUNT", "SUBMIT",
                "READ_TIMEOUT", "unknown delivery", null, null, false, null
        );

        ModelRoutingService.FailoverDecision decision = service.failover(1L, unknown);

        assertThat(decision.switched()).isFalse();
        assertThat(decision.reason()).isEqualTo("unsafe_delivery_state");
        verify(attemptMapper, never()).closeWithFailureContract(
                anyLong(), any(), any(), any(), any(), nullable(String.class));
        verify(stateMapper, never()).reserve(anyLong(), anyInt());
    }

    @Test
    void terminalReleaseIsIdempotent() {
        AiTask task = processingTask();
        task.setStatus(TaskStatus.SUCCESS.name());
        TaskModelRouteAttempt attempt = activeAttempt(7L, 1L, 10L);
        AccountModelRouteState state = state(101L, 1L, 10L);
        when(taskMapper.selectByIdForUpdate(1L)).thenReturn(task);
        when(attemptMapper.findByIdForUpdate(7L)).thenReturn(attempt);
        when(attemptMapper.close(7L, TaskStatus.SUCCESS.name())).thenReturn(1, 0);
        when(stateMapper.findByModelConfigIdsForUpdate(List.of(1L))).thenReturn(List.of(state));

        service.completeTask(1L, TaskStatus.SUCCESS.name());
        service.completeTask(1L, TaskStatus.SUCCESS.name());

        verify(stateMapper).releaseSuccess(101L);
    }

    @Test
    void timeoutWithoutWorkerFailurePersistsTheTaskSafeFailureContract() {
        AiTask task = processingTask();
        task.setStatus(TaskStatus.TIMEOUT.name());
        task.setErrorCode("STALE_TASK_TIMEOUT");
        task.setErrorMessage("RAW upstream response body=secret");
        task.setUserMessage("Task timed out, please retry later");
        task.setDeveloperMessage("Task exceeded the configured stale timeout");
        task.setFailureTraceId("trace-timeout-1");
        TaskModelRouteAttempt attempt = activeAttempt(7L, 1L, 10L);
        AccountModelRouteState state = state(101L, 1L, 10L);
        when(taskMapper.selectByIdForUpdate(1L)).thenReturn(task);
        when(attemptMapper.findByIdForUpdate(7L)).thenReturn(attempt);
        when(attemptMapper.closeWithFailureContract(
                eq(7L), eq(TaskStatus.TIMEOUT.name()), any(), any(), any(), any())).thenReturn(1);
        when(stateMapper.findByModelConfigIdsForUpdate(List.of(1L))).thenReturn(List.of(state));

        service.completeTask(1L, TaskStatus.TIMEOUT.name());

        verify(attemptMapper).closeWithFailureContract(
                eq(7L),
                eq(TaskStatus.TIMEOUT.name()),
                argThat(request -> "STALE_TASK_TIMEOUT".equals(request.errorCode())
                        && "Task exceeded the configured stale timeout".equals(request.errorMessage())),
                eq("Task timed out, please retry later"),
                eq("Task exceeded the configured stale timeout"),
                eq("trace-timeout-1")
        );
        verify(attemptMapper, never()).close(anyLong(), any());
        verify(stateMapper).releaseNeutral(101L);
    }

    @Test
    void nonRouteFailureReleasesInFlightWithoutChangingTheCircuit() {
        AiTask task = processingTask();
        task.setStatus(TaskStatus.FAILED.name());
        task.setUserMessage("Please retry later");
        task.setDeveloperMessage("Object storage request failed");
        task.setFailureTraceId("trace-route-1");
        TaskModelRouteAttempt attempt = activeAttempt(7L, 1L, 10L);
        AccountModelRouteState state = state(101L, 1L, 10L);
        state.setCircuitStatus("OPEN");
        state.setConsecutiveFailures(2);
        state.setCooldownUntil(LocalDateTime.now().plusMinutes(5));
        WorkerFailedRequest failure = new WorkerFailedRequest(
                "MEDIA_PERSIST_FAILED", "object storage unavailable", "MEDIA_PERSIST", false,
                null, null, null, null, null, null, null, "claim-1"
        );
        when(taskMapper.selectByIdForUpdate(1L)).thenReturn(task);
        when(attemptMapper.findByIdForUpdate(7L)).thenReturn(attempt);
        when(attemptMapper.closeWithFailureContract(
                eq(7L), eq(TaskStatus.FAILED.name()), any(), any(), any(), any())).thenReturn(1);
        when(stateMapper.findByModelConfigIdsForUpdate(List.of(1L))).thenReturn(List.of(state));

        service.completeTask(1L, TaskStatus.FAILED.name(), failure);

        verify(attemptMapper).closeWithFailureContract(
                eq(7L), eq(TaskStatus.FAILED.name()), any(),
                eq("Please retry later"), eq("Object storage request failed"), eq("trace-route-1"));
        verify(stateMapper).releaseNeutral(101L);
        verify(stateMapper, never()).releaseFailure(anyLong(), any(), any(), anyInt());
    }

    @Test
    void firstTerminalNotSentFailureDoesNotOpenTheRuntimeCircuit() throws Exception {
        AiTask task = processingTask();
        task.setStatus(TaskStatus.FAILED.name());
        TaskModelRouteAttempt attempt = activeAttempt(7L, 1L, 10L);
        AccountModelRouteState state = state(101L, 1L, 10L);
        WorkerFailedRequest failure = notSentFailure();
        when(taskMapper.selectByIdForUpdate(1L)).thenReturn(task);
        when(attemptMapper.findByIdForUpdate(7L)).thenReturn(attempt);
        when(attemptMapper.closeWithFailureContract(
                eq(7L), eq(TaskStatus.FAILED.name()), any(), any(), any(), nullable(String.class))).thenReturn(1);
        when(stateMapper.findByModelConfigIdsForUpdate(List.of(1L))).thenReturn(List.of(state));

        service.completeTask(1L, TaskStatus.FAILED.name(), failure);

        verify(stateMapper).releaseFailure(101L, "CLOSED", null, 1);
    }

    @Test
    void secondTerminalNotSentFailureOpensTheRuntimeCircuit() throws Exception {
        AiTask task = processingTask();
        task.setStatus(TaskStatus.FAILED.name());
        TaskModelRouteAttempt attempt = activeAttempt(7L, 1L, 10L);
        AccountModelRouteState state = state(101L, 1L, 10L);
        state.setConsecutiveFailures(1);
        when(taskMapper.selectByIdForUpdate(1L)).thenReturn(task);
        when(attemptMapper.findByIdForUpdate(7L)).thenReturn(attempt);
        when(attemptMapper.closeWithFailureContract(
                eq(7L), eq(TaskStatus.FAILED.name()), any(), any(), any(), nullable(String.class))).thenReturn(1);
        when(stateMapper.findByModelConfigIdsForUpdate(List.of(1L))).thenReturn(List.of(state));

        service.completeTask(1L, TaskStatus.FAILED.name(), notSentFailure());

        verify(stateMapper).releaseFailure(eq(101L), eq("OPEN"), any(LocalDateTime.class), eq(2));
    }

    private static AiTask processingTask() {
        AiTask task = new AiTask();
        task.setId(1L);
        task.setStatus(TaskStatus.PROCESSING.name());
        task.setClaimToken("claim-1");
        task.setModelConfigId(1L);
        task.setSelectedModelConfigId(1L);
        task.setSelectedVendorAccountId(10L);
        task.setCurrentRouteAttemptId(7L);
        return task;
    }

    private static TaskModelRouteAttempt activeAttempt(Long id, Long modelId, Long accountId) {
        TaskModelRouteAttempt attempt = new TaskModelRouteAttempt();
        attempt.setId(id);
        attempt.setTaskId(1L);
        attempt.setAttemptNo(1);
        attempt.setModelConfigId(modelId);
        attempt.setVendorAccountId(accountId);
        attempt.setStatus("ACTIVE");
        return attempt;
    }

    private static AgentModelConfig model(Long id, Long accountId) {
        AgentModelConfig model = new AgentModelConfig();
        model.setId(id);
        model.setVendorAccountId(accountId);
        model.setProvider("openai_images_gateway");
        model.setModelName("gpt-image-2");
        model.setExecutionTask("IMAGE_GENERATION");
        model.setBillingUnit("IMAGE");
        model.setUnitPrice(BigDecimal.ONE);
        model.setEnabled(true);
        model.setRoutingPoolId(100L);
        return model;
    }

    private static ModelVendorAccount account(Long id, int weight) {
        ModelVendorAccount account = new ModelVendorAccount();
        account.setId(id);
        account.setVendorCode("openai");
        account.setEnabled(true);
        account.setLoadBalanceEnabled(true);
        account.setLoadBalanceWeight(weight);
        account.setRoutingPoolId(100L);
        return account;
    }

    private void prepareInitialAssignment(Long modelConfigId, Long accountId, Long attemptId) {
        when(attemptMapper.countByTaskId(1L)).thenReturn(0);
        when(attemptMapper.insertAttempt(any())).thenAnswer(invocation -> {
            TaskModelRouteAttempt attempt = invocation.getArgument(0);
            attempt.setId(attemptId);
            return 1;
        });
        when(snapshotService.serialize(nullable(ModelExecutionSnapshot.class))).thenReturn("{}");
        when(taskMapper.assignInitialRoute(
                1L, modelConfigId, accountId, attemptId, "{}"))
                .thenReturn(1);
    }

    private static AccountModelRouteState state(Long id, Long modelId, Long accountId) {
        AccountModelRouteState state = new AccountModelRouteState();
        state.setId(id);
        state.setModelConfigId(modelId);
        state.setVendorAccountId(accountId);
        state.setInFlightCount(0);
        state.setConsecutiveFailures(0);
        state.setCircuitStatus("CLOSED");
        state.setVersion(0);
        return state;
    }

    private static RouteFailoverRequest safeFailure(Long routeAttemptId) {
        return new RouteFailoverRequest(
                "claim-1", routeAttemptId, "NOT_SENT", "ACCOUNT", "CONNECT",
                "CONNECTION_REFUSED", "connection refused", null, null, false, null
        );
    }

    private static WorkerFailedRequest notSentFailure() throws Exception {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue("""
                        {
                          "errorCode": "MODEL_PROVIDER_UNAVAILABLE",
                          "errorMessage": "connection refused",
                          "failureStage": "BEFORE_PROVIDER",
                          "providerCharged": false,
                          "deliveryState": "NOT_SENT",
                          "retryScope": "ACCOUNT",
                          "retryAfterSeconds": 17,
                          "claimToken": "claim-1"
                        }
                        """, WorkerFailedRequest.class);
    }
}
