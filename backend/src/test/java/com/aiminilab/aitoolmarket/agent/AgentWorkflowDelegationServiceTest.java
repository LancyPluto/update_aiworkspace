package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.dto.AgentToolDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentRun;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolCall;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.agent.service.AgentWorkflowDelegationService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.comic.dto.ComicDtos;
import com.aiminilab.aitoolmarket.comic.service.ComicProjectApplicationService;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunApplicationService;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkflowDelegationServiceTest {

    private AgentToolCallMapper toolCallMapper;
    private AgentRunMapper runMapper;
    private AgentToolDescriptorService descriptorService;
    private WorkflowRunApplicationService workflowService;
    private WorkflowRunMapper workflowRunMapper;
    private ToolMapper toolMapper;
    private ComicProjectApplicationService comicProjectApplicationService;
    private AgentWorkflowDelegationService service;

    @BeforeEach
    void setUp() {
        toolCallMapper = mock(AgentToolCallMapper.class);
        runMapper = mock(AgentRunMapper.class);
        descriptorService = mock(AgentToolDescriptorService.class);
        workflowService = mock(WorkflowRunApplicationService.class);
        workflowRunMapper = mock(WorkflowRunMapper.class);
        toolMapper = mock(ToolMapper.class);
        comicProjectApplicationService = mock(ComicProjectApplicationService.class);
        AiTool workflowTool = new AiTool();
        workflowTool.setId(19L);
        workflowTool.setToolCode("comic_workflow");
        when(toolMapper.findAnyByCode("comic_workflow")).thenReturn(Optional.of(workflowTool));
        service = new AgentWorkflowDelegationService(
                toolCallMapper,
                runMapper,
                descriptorService,
                workflowService,
                workflowRunMapper,
                toolMapper,
                new ObjectMapper(),
                comicProjectApplicationService
        );
    }

    @Test
    void derivesWorkflowCommandOnlyFromPersistedToolCallAndRun() {
        AgentToolCall call = runningCall("{\"prompt\":\"city night\"}");
        AgentRun run = runningRun(7L);
        when(toolCallMapper.findByIdForUpdate(91L)).thenReturn(Optional.of(call));
        when(runMapper.findById(88L)).thenReturn(Optional.of(run));
        when(descriptorService.getToolForAgent(7L, "comic_workflow")).thenReturn(workflowDescriptor());
        when(workflowService.createInCurrentTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new WorkflowRunCreated(501L, 601L, 701L, "RUNNING"));

        var response = service.delegate(91L);

        ArgumentCaptor<CreateWorkflowRunCommand> command = ArgumentCaptor.forClass(CreateWorkflowRunCommand.class);
        verify(workflowService).createInCurrentTransaction(command.capture());
        assertThat(command.getValue().userId()).isEqualTo(7L);
        assertThat(command.getValue().toolCode()).isEqualTo("comic_workflow");
        assertThat(command.getValue().input().path("prompt").asText()).isEqualTo("city night");
        assertThat(command.getValue().clientRequestId()).isEqualTo("agent-run-88-tool-call-91");
        assertThat(command.getValue().launchSource()).isEqualTo("AGENT_CHAT");
        assertThat(command.getValue().agentToolCallId()).isEqualTo(91L);
        assertThat(response.taskId()).isEqualTo(501L);
        assertThat(response.runId()).isEqualTo(601L);
        assertThat(response.runUrl()).isEqualTo("/agents/runs/501");
    }

    @Test
    void delegatedRetryReturnsPersistedWorkflowWithoutCreatingAgain() {
        AgentToolCall call = runningCall("{}");
        call.setStatus("DELEGATED");
        call.setTaskId(501L);
        AgentRun run = runningRun(7L);
        run.setStatus("SUCCESS");
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setId(601L);
        workflowRun.setRootTaskId(501L);
        workflowRun.setToolId(19L);
        workflowRun.setUserId(7L);
        workflowRun.setLaunchSource("AGENT_CHAT");
        workflowRun.setClientRequestId("agent-run-88-tool-call-91");
        workflowRun.setStatus("RUNNING");
        when(toolCallMapper.findByIdForUpdate(91L)).thenReturn(Optional.of(call));
        when(runMapper.findById(88L)).thenReturn(Optional.of(run));
        when(workflowRunMapper.findByRootTaskId(501L)).thenReturn(Optional.of(workflowRun));

        var response = service.delegate(91L);

        assertThat(response.taskId()).isEqualTo(501L);
        assertThat(response.runId()).isEqualTo(601L);
        verify(workflowService, never()).createInCurrentTransaction(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void comicDelegationReturnsProjectWorkspaceUrl() {
        AgentToolCall call = runningCall("{\"prompt\":\"city night\"}");
        call.setToolCode("ai_comic_drama_agent");
        AgentRun run = runningRun(7L);
        when(toolCallMapper.findByIdForUpdate(91L)).thenReturn(Optional.of(call));
        when(runMapper.findById(88L)).thenReturn(Optional.of(run));
        when(descriptorService.getToolForAgent(7L, "ai_comic_drama_agent")).thenReturn(workflowDescriptor());
        when(workflowService.createInCurrentTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new WorkflowRunCreated(501L, 601L, 701L, "RUNNING"));
        when(comicProjectApplicationService.workspaceByRootTaskIdInternal(501L))
                .thenReturn(new ComicDtos.WorkspaceBinding(
                        71L, 81L, null, 501L, 601L, "AGENT_CHAT",
                        "/agents/comic-projects/71?episode=81"
                ));

        var response = service.delegate(91L);

        assertThat(response.runUrl()).isEqualTo("/agents/comic-projects/71?episode=81");
    }

    @ParameterizedTest
    @CsvSource({
            "SUCCESS,SUCCESS",
            "FAILED,FAILED",
            "FAILED,TIMEOUT",
            "CANCELLED,CANCELLED"
    })
    void terminalWorkflowRetryReturnsPersistedDelegation(String toolCallStatus, String workflowStatus) {
        AgentToolCall call = runningCall("{}");
        call.setStatus(toolCallStatus);
        call.setTaskId(501L);
        AgentRun run = runningRun(7L);
        run.setStatus("SUCCESS");
        WorkflowRun workflowRun = persistedAgentWorkflow(workflowStatus);
        when(toolCallMapper.findByIdForUpdate(91L)).thenReturn(Optional.of(call));
        when(runMapper.findById(88L)).thenReturn(Optional.of(run));
        when(workflowRunMapper.findByRootTaskId(501L)).thenReturn(Optional.of(workflowRun));

        var response = service.delegate(91L);

        assertThat(response.taskId()).isEqualTo(501L);
        assertThat(response.runId()).isEqualTo(601L);
        assertThat(response.status()).isEqualTo(workflowStatus);
        verify(workflowService, never()).createInCurrentTransaction(org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @CsvSource({
            "FAILED,CANCELLED",
            "CANCELLED,FAILED",
            "CANCELLED,TIMEOUT",
            "SUCCESS,CANCELLED"
    })
    void terminalWorkflowRetryRejectsMismatchedToolCallAndWorkflowStatuses(String toolCallStatus,
                                                                            String workflowStatus) {
        AgentToolCall call = runningCall("{}");
        call.setStatus(toolCallStatus);
        call.setTaskId(501L);
        AgentRun run = runningRun(7L);
        run.setStatus("SUCCESS");
        when(toolCallMapper.findByIdForUpdate(91L)).thenReturn(Optional.of(call));
        when(runMapper.findById(88L)).thenReturn(Optional.of(run));
        when(workflowRunMapper.findByRootTaskId(501L)).thenReturn(Optional.of(persistedAgentWorkflow(workflowStatus)));

        assertError(ErrorCode.IDEMPOTENCY_CONFLICT);

        verify(workflowService, never()).createInCurrentTransaction(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void concurrentRetryReusesWorkflowThatReachedTerminalBeforeFirstResponseReturned() throws Exception {
        AgentToolCall call = runningCall("{}");
        AgentRun run = runningRun(7L);
        WorkflowRun workflowRun = persistedAgentWorkflow("SUCCESS");
        CountDownLatch terminalPublished = new CountDownLatch(1);
        CountDownLatch releaseFirstResponse = new CountDownLatch(1);
        when(toolCallMapper.findByIdForUpdate(91L)).thenAnswer(ignored -> Optional.of(call));
        when(runMapper.findById(88L)).thenReturn(Optional.of(run));
        when(descriptorService.getToolForAgent(7L, "comic_workflow")).thenReturn(workflowDescriptor());
        when(workflowRunMapper.findByRootTaskId(501L)).thenReturn(Optional.of(workflowRun));
        when(workflowService.createInCurrentTransaction(org.mockito.ArgumentMatchers.any())).thenAnswer(ignored -> {
            call.setTaskId(501L);
            call.setStatus("SUCCESS");
            terminalPublished.countDown();
            if (!releaseFirstResponse.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent retry");
            }
            return new WorkflowRunCreated(501L, 601L, 701L, "SUCCESS");
        });

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.delegate(91L));
            assertThat(terminalPublished.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.delegate(91L));
            var repeated = second.get(5, TimeUnit.SECONDS);
            releaseFirstResponse.countDown();
            var created = first.get(5, TimeUnit.SECONDS);

            assertThat(repeated.taskId()).isEqualTo(created.taskId()).isEqualTo(501L);
            assertThat(repeated.runId()).isEqualTo(created.runId()).isEqualTo(601L);
            verify(workflowService).createInCurrentTransaction(org.mockito.ArgumentMatchers.any());
        } finally {
            releaseFirstResponse.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void terminalDirectCallCannotReuseWorkflowWithDifferentToolIdentity() {
        AgentToolCall call = runningCall("{}");
        call.setStatus("SUCCESS");
        call.setTaskId(501L);
        AgentRun run = runningRun(7L);
        WorkflowRun workflowRun = persistedAgentWorkflow("SUCCESS");
        workflowRun.setToolId(20L);
        when(toolCallMapper.findByIdForUpdate(91L)).thenReturn(Optional.of(call));
        when(runMapper.findById(88L)).thenReturn(Optional.of(run));
        when(workflowRunMapper.findByRootTaskId(501L)).thenReturn(Optional.of(workflowRun));

        assertError(ErrorCode.IDEMPOTENCY_CONFLICT);

        verify(workflowService, never()).createInCurrentTransaction(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsDirectToolUserMismatchTerminalCallAndInvalidArguments() {
        AgentToolCall direct = runningCall("{}");
        AgentRun run = runningRun(7L);
        when(toolCallMapper.findByIdForUpdate(91L)).thenReturn(Optional.of(direct));
        when(runMapper.findById(88L)).thenReturn(Optional.of(run));
        when(descriptorService.getToolForAgent(7L, "comic_workflow")).thenReturn(directDescriptor());
        assertError(ErrorCode.PARAM_ERROR);

        AgentRun wrongUser = runningRun(8L);
        when(runMapper.findById(88L)).thenReturn(Optional.of(wrongUser));
        assertError(ErrorCode.FORBIDDEN);

        AgentRun terminalRun = runningRun(7L);
        terminalRun.setStatus("SUCCESS");
        when(runMapper.findById(88L)).thenReturn(Optional.of(terminalRun));
        assertError(ErrorCode.TASK_STATUS_INVALID);

        AgentToolCall terminal = runningCall("{}");
        terminal.setStatus("FAILED");
        when(toolCallMapper.findByIdForUpdate(91L)).thenReturn(Optional.of(terminal));
        when(runMapper.findById(88L)).thenReturn(Optional.of(run));
        assertError(ErrorCode.TASK_STATUS_INVALID);

        AgentToolCall invalidJson = runningCall("not-json");
        when(toolCallMapper.findByIdForUpdate(91L)).thenReturn(Optional.of(invalidJson));
        when(descriptorService.getToolForAgent(7L, "comic_workflow")).thenReturn(workflowDescriptor());
        assertError(ErrorCode.PARAM_ERROR);

        AgentToolCall boundElsewhere = runningCall("{}");
        boundElsewhere.setTaskId(999L);
        when(toolCallMapper.findByIdForUpdate(91L)).thenReturn(Optional.of(boundElsewhere));
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    private void assertError(ErrorCode errorCode) {
        assertThatThrownBy(() -> service.delegate(91L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }

    private AgentToolCall runningCall(String argumentsJson) {
        AgentToolCall call = new AgentToolCall();
        call.setId(91L);
        call.setRunId(88L);
        call.setUserId(7L);
        call.setToolCode("comic_workflow");
        call.setStatus("RUNNING");
        call.setArgumentsJson(argumentsJson);
        return call;
    }

    private AgentRun runningRun(Long userId) {
        AgentRun run = new AgentRun();
        run.setId(88L);
        run.setUserId(userId);
        run.setStatus("RUNNING");
        return run;
    }

    private WorkflowRun persistedAgentWorkflow(String status) {
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setId(601L);
        workflowRun.setRootTaskId(501L);
        workflowRun.setToolId(19L);
        workflowRun.setUserId(7L);
        workflowRun.setLaunchSource("AGENT_CHAT");
        workflowRun.setClientRequestId("agent-run-88-tool-call-91");
        workflowRun.setStatus(status);
        return workflowRun;
    }

    private AgentToolDescriptorResponse workflowDescriptor() {
        return new AgentToolDescriptorResponse(
                "comic_workflow", "Comic", "", 0, Map.of(), false, List.of(), Map.of(),
                "WORKFLOW", "WORKFLOW_STEP", 0, "/agents/runs/{taskId}", "low", "auto"
        );
    }

    private AgentToolDescriptorResponse directDescriptor() {
        return new AgentToolDescriptorResponse(
                "comic_workflow", "Comic", "", 0, Map.of(), false, List.of(), Map.of()
        );
    }
}
