package com.aiminilab.aitoolmarket.task.routing;

import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.entity.ModelVendorAccount;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.ModelVendorAccountMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.config.AppProperties;
import com.aiminilab.aitoolmarket.task.dto.RouteFailoverRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.routing.entity.AccountModelRouteState;
import com.aiminilab.aitoolmarket.task.routing.entity.TaskModelRouteAttempt;
import com.aiminilab.aitoolmarket.task.routing.mapper.AccountModelRouteStateMapper;
import com.aiminilab.aitoolmarket.task.routing.mapper.TaskModelRouteAttemptMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRoutingServiceTest {
    private TaskMapper taskMapper;
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
                taskMapper, modelConfigMapper, accountMapper, attemptMapper, stateMapper,
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
        ModelVendorAccount source = account(10L, 100);
        ModelVendorAccount target = account(20L, 100);
        AccountModelRouteState sourceState = state(101L, 1L, 10L);
        AccountModelRouteState targetState = state(102L, 2L, 20L);

        when(taskMapper.selectByIdForUpdate(1L)).thenReturn(task);
        when(attemptMapper.findByIdForUpdate(7L)).thenReturn(currentAttempt);
        when(attemptMapper.countByTaskId(1L)).thenReturn(1);
        when(modelConfigMapper.findActiveById(1L)).thenReturn(reference);
        when(accountMapper.findActiveById(10L)).thenReturn(source);
        when(accountMapper.findActiveByVendorCode("openai")).thenReturn(List.of(source, target));
        when(modelConfigMapper.findRoutingCandidates("openai", "openai_images_gateway", "gpt-image-2"))
                .thenReturn(List.of(reference, alternate));
        when(capabilityService.resolveCapabilities(any())).thenReturn(List.of("IMAGE_GENERATION"));
        when(stateMapper.findByModelConfigIdsForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(sourceState, targetState));
        when(attemptMapper.findAttemptedVendorAccountIds(1L)).thenReturn(List.of(10L));
        when(attemptMapper.closeWithFailure(eq(7L), eq("SWITCHED"), any())).thenReturn(1);
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
        verify(taskMapper).switchRouteGuarded(1L, "claim-1", 7L, 2L, 20L, 8L, "{}");
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
        verify(attemptMapper, never()).closeWithFailure(anyLong(), any(), any());
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
    void genericModelFailureResetsTheNotSentFailureStreak() {
        AiTask task = processingTask();
        task.setStatus(TaskStatus.FAILED.name());
        TaskModelRouteAttempt attempt = activeAttempt(7L, 1L, 10L);
        AccountModelRouteState state = state(101L, 1L, 10L);
        state.setConsecutiveFailures(1);
        WorkerFailedRequest failure = new WorkerFailedRequest(
                "MODEL_CALL_FAILED", "invalid request parameter", "SUBMIT", false,
                null, null, null, null, null, null, null, "claim-1"
        );
        when(taskMapper.selectByIdForUpdate(1L)).thenReturn(task);
        when(attemptMapper.findByIdForUpdate(7L)).thenReturn(attempt);
        when(attemptMapper.closeWithFailure(eq(7L), eq(TaskStatus.FAILED.name()), any())).thenReturn(1);
        when(stateMapper.findByModelConfigIdsForUpdate(List.of(1L))).thenReturn(List.of(state));

        service.completeTask(1L, TaskStatus.FAILED.name(), failure);

        verify(stateMapper).releaseFailure(101L, "CLOSED", null, 0);
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
        when(attemptMapper.closeWithFailure(eq(7L), eq(TaskStatus.FAILED.name()), any())).thenReturn(1);
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
        when(attemptMapper.closeWithFailure(eq(7L), eq(TaskStatus.FAILED.name()), any())).thenReturn(1);
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
        return model;
    }

    private static ModelVendorAccount account(Long id, int weight) {
        ModelVendorAccount account = new ModelVendorAccount();
        account.setId(id);
        account.setVendorCode("openai");
        account.setEnabled(true);
        account.setLoadBalanceEnabled(true);
        account.setLoadBalanceWeight(weight);
        return account;
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
