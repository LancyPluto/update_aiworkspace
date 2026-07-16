package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.entity.AgentRunEvent;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolCall;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunEventMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
import com.aiminilab.aitoolmarket.agent.metrics.AgentMetrics;
import com.aiminilab.aitoolmarket.agent.service.AgentDelegatedToolCallLifecycleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDelegatedToolCallLifecycleServiceTest {

    private AgentToolCallMapper toolCallMapper;
    private AgentRunEventMapper eventMapper;
    private AgentMetrics metrics;
    private AgentDelegatedToolCallLifecycleService service;

    @BeforeEach
    void setUp() {
        toolCallMapper = mock(AgentToolCallMapper.class);
        eventMapper = mock(AgentRunEventMapper.class);
        metrics = mock(AgentMetrics.class);
        service = new AgentDelegatedToolCallLifecycleService(toolCallMapper, eventMapper, metrics, new ObjectMapper());
    }

    @Test
    void workflowSuccessCompletesDelegatedCallAndEmitsOneFinishedEvent() {
        AgentToolCall call = delegatedCall();
        when(toolCallMapper.findDelegatedByWorkflow(501L, 601L)).thenReturn(java.util.Optional.of(call));
        when(toolCallMapper.finishDelegated(
                eq(91L), eq(501L), eq("SUCCESS"), any(), eq(null), eq(null), any(LocalDateTime.class)
        )).thenReturn(1);

        service.finishForWorkflow(501L, 601L, "SUCCESS", null);

        ArgumentCaptor<AgentRunEvent> event = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventMapper).insertEvent(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("tool.finished");
        assertThat(event.getValue().getEventJson()).contains("\"workflowStatus\":\"SUCCESS\"")
                .contains("\"errorCode\":\"\"");
        verify(metrics).recordToolCallOutcome(eq("comic_workflow"), eq("SUCCESS"), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "TIMEOUT"})
    void failedWorkflowTerminalMapsToFailedAndDuplicateFinalizationEmitsNothing(String workflowStatus) {
        AgentToolCall call = delegatedCall();
        when(toolCallMapper.findDelegatedByWorkflow(501L, 601L)).thenReturn(java.util.Optional.of(call));
        when(toolCallMapper.finishDelegated(
                eq(91L), eq(501L), eq("FAILED"), any(), eq("WORKFLOW_" + workflowStatus), eq("provider timeout"), any(LocalDateTime.class)
        )).thenReturn(1, 0);

        service.finishForWorkflow(501L, 601L, workflowStatus, "provider timeout");
        service.finishForWorkflow(501L, 601L, workflowStatus, "provider timeout");

        ArgumentCaptor<AgentRunEvent> event = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventMapper).insertEvent(event.capture());
        assertThat(event.getValue().getEventJson()).contains("\"workflowStatus\":\"" + workflowStatus + "\"");
        assertThat(event.getValue().getEventJson()).contains("\"errorCode\":\"WORKFLOW_" + workflowStatus + "\"");
        assertThat(event.getValue().getEventJson()).contains("provider timeout");
        verify(metrics).recordToolCallOutcome(eq("comic_workflow"), eq("FAILED"), any(), any());
    }

    @Test
    void workflowCancellationMapsToCancelledAndDuplicateFinalizationEmitsNothing() {
        AgentToolCall call = delegatedCall();
        when(toolCallMapper.findDelegatedByWorkflow(501L, 601L)).thenReturn(java.util.Optional.of(call));
        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        when(toolCallMapper.finishDelegated(
                eq(91L), eq(501L), eq("CANCELLED"), resultJson.capture(),
                eq("WORKFLOW_CANCELLED"), eq("USER_CANCELLED"), any(LocalDateTime.class)
        )).thenReturn(1, 0);

        service.finishForWorkflow(501L, 601L, "CANCELLED", "USER_CANCELLED");
        service.finishForWorkflow(501L, 601L, "CANCELLED", "USER_CANCELLED");

        assertThat(resultJson.getValue()).contains("\"success\":false")
                .contains("\"status\":\"CANCELLED\"")
                .contains("\"workflowStatus\":\"CANCELLED\"");
        ArgumentCaptor<AgentRunEvent> event = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventMapper).insertEvent(event.capture());
        assertThat(event.getValue().getEventJson()).contains("\"status\":\"CANCELLED\"")
                .contains("\"workflowStatus\":\"CANCELLED\"")
                .contains("\"errorCode\":\"WORKFLOW_CANCELLED\"");
        verify(metrics).recordToolCallOutcome(eq("comic_workflow"), eq("CANCELLED"), any(), any());
    }

    @Test
    void nonTerminalWorkflowStatusDoesNotFinishDelegatedCall() {
        service.finishForWorkflow(501L, 601L, "RUNNING", null);

        verify(toolCallMapper, never()).findDelegatedByWorkflow(anyLong(), anyLong());
        verify(eventMapper, never()).insertEvent(any());
        verify(metrics, never()).recordToolCallOutcome(any(), any(), any(), any());
    }

    @Test
    void bindingDelegatedWorkflowEmitsRunCardEventOnce() {
        AgentToolCall running = delegatedCall();
        running.setStatus("RUNNING");
        running.setTaskId(null);
        when(toolCallMapper.findById(91L)).thenReturn(java.util.Optional.of(running));
        when(toolCallMapper.markDelegated(91L, 501L)).thenReturn(1);

        service.bindDelegated(91L, 501L, 601L, "RUNNING");

        ArgumentCaptor<AgentRunEvent> event = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventMapper).insertEvent(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("tool.task_dispatched");
        assertThat(event.getValue().getEventJson())
                .contains("\"status\":\"DELEGATED\"")
                .contains("\"runUrl\":\"/agents/runs/501\"");
    }

    @Test
    void awaitingFundsEmitsWorkflowProgressWithRunLink() {
        when(toolCallMapper.findDelegatedByWorkflow(501L, 601L))
                .thenReturn(java.util.Optional.of(delegatedCall()));

        service.progressForWorkflow(501L, 601L, "AWAITING_FUNDS");

        ArgumentCaptor<AgentRunEvent> event = ArgumentCaptor.forClass(AgentRunEvent.class);
        verify(eventMapper).insertEvent(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("tool.task_progress");
        assertThat(event.getValue().getEventJson())
                .contains("\"status\":\"AWAITING_FUNDS\"")
                .contains("\"runUrl\":\"/agents/runs/501\"");
    }

    @Test
    void bindingRaceToAnotherTaskIsAnIdempotencyConflict() {
        AgentToolCall running = delegatedCall();
        running.setStatus("RUNNING");
        running.setTaskId(null);
        AgentToolCall boundElsewhere = delegatedCall();
        boundElsewhere.setTaskId(999L);
        when(toolCallMapper.findById(91L)).thenReturn(
                java.util.Optional.of(running),
                java.util.Optional.of(boundElsewhere)
        );
        when(toolCallMapper.markDelegated(91L, 501L)).thenReturn(0);

        assertThatThrownBy(() -> service.bindDelegated(91L, 501L, 601L, "RUNNING"))
                .isInstanceOfSatisfying(
                        com.aiminilab.aitoolmarket.common.exception.BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(com.aiminilab.aitoolmarket.common.enums.ErrorCode.IDEMPOTENCY_CONFLICT)
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED", "CANCELLED"})
    void bindingRetryAllowsSameTaskAfterWorkflowAlreadyFinished(String terminalStatus) {
        AgentToolCall terminal = delegatedCall();
        terminal.setStatus(terminalStatus);
        when(toolCallMapper.findById(91L)).thenReturn(java.util.Optional.of(terminal));

        service.bindDelegated(91L, 501L, 601L, "RUNNING");

        verify(toolCallMapper, never()).markDelegated(91L, 501L);
    }

    private AgentToolCall delegatedCall() {
        AgentToolCall call = new AgentToolCall();
        call.setId(91L);
        call.setRunId(88L);
        call.setUserId(7L);
        call.setToolCode("comic_workflow");
        call.setTaskId(501L);
        call.setStatus("DELEGATED");
        call.setStartedAt(LocalDateTime.now().minusMinutes(1));
        return call;
    }
}
