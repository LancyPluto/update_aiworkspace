package com.aiminilab.aitoolmarket.task.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentAttachmentUrlResolver;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.community.mapper.CommunityEventMapper;
import com.aiminilab.aitoolmarket.community.mapper.CommunityPostMapper;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import com.aiminilab.aitoolmarket.task.dto.CreateTaskRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.metrics.TaskMetrics;
import com.aiminilab.aitoolmarket.task.routing.ModelRoutingService;
import com.aiminilab.aitoolmarket.task.service.TaskCreditDispatchService;
import com.aiminilab.aitoolmarket.task.service.TaskOutboxService;
import com.aiminilab.aitoolmarket.task.service.ModelRequestSchemaService;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowExecutionService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowInteractionService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRuntimeAdmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskServiceImplCapabilityTest {

    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final ToolMapper toolMapper = mock(ToolMapper.class);
    private final ModelCapabilityService capabilityService = mock(ModelCapabilityService.class);
    private final ModelExecutionSnapshotService snapshotService = mock(ModelExecutionSnapshotService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private TaskServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TaskServiceImpl(
                taskMapper,
                toolMapper,
                mock(AgentModelConfigMapper.class),
                mock(AgentToolCallMapper.class),
                capabilityService,
                snapshotService,
                mock(ModelRequestSchemaService.class),
                mock(CreditService.class),
                objectMapper,
                mock(TaskOutboxService.class),
                mock(TaskMetrics.class),
                mock(TaskCreditEstimateService.class),
                mock(TaskCreditDispatchService.class),
                mock(CommunityEventMapper.class),
                mock(CommunityPostMapper.class),
                mock(AgentAttachmentUrlResolver.class),
                mock(AssetStorageService.class),
                mock(WorkflowExecutionService.class),
                mock(WorkflowRunMapper.class),
                mock(WorkflowStepAttemptMapper.class),
                mock(WorkflowInteractionService.class),
                mock(WorkflowRuntimeAdmissionService.class),
                mock(TaskIdempotencyRecoveryService.class),
                mock(ModelRoutingService.class),
                mock(TransactionTemplate.class)
        );
    }

    @Test
    void createRejectsSnapshotMissingAnyRequiredCapabilityBeforePersistingTask() {
        AiTool tool = tool(9L, "multi-capability", 3L,
                "[\"TEXT_GENERATION\",\"VISION_INPUT\"]");
        AgentModelConfig modelConfig = new AgentModelConfig();
        modelConfig.setId(3L);
        modelConfig.setProvider("test-provider");
        modelConfig.setModelName("test-model");
        ModelExecutionSnapshot snapshot = ModelExecutionSnapshot.from(
                modelConfig, List.of(" text_generation "), "test-v1");

        when(taskMapper.findByUserIdAndIdempotencyKeyIncludingDeleted(7L, "request-1"))
                .thenReturn(Optional.empty());
        when(toolMapper.findOnlineByCode("multi-capability")).thenReturn(Optional.of(tool));
        when(capabilityService.resolveModelConfigForTool(tool, null)).thenReturn(modelConfig);
        when(snapshotService.create(modelConfig)).thenReturn(snapshot);

        assertThatThrownBy(() -> service.create(7L, new CreateTaskRequest(
                "multi-capability", objectMapper.createObjectNode(), "request-1", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VISION_INPUT");

        verify(capabilityService).validateExecution(tool, modelConfig);
        verify(taskMapper, never()).insertTask(any(AiTask.class));
    }

    @Test
    void createWorkflowRootValidatesExplicitBindingBeforePersistingTask() {
        AiTool tool = tool(9L, "workflow-bound", 3L, "[\"VIDEO_GENERATION\"]");
        when(taskMapper.findByUserIdAndIdempotencyKeyIncludingDeleted(7L, "workflow-request"))
                .thenReturn(Optional.empty());
        when(toolMapper.findOnlineByCode("workflow-bound")).thenReturn(Optional.of(tool));
        doThrow(new BusinessException(ErrorCode.PARAM_ERROR, "missing VIDEO_GENERATION"))
                .when(capabilityService).validateToolModelBinding(tool);

        assertThatThrownBy(() -> service.createWorkflowRoot(
                7L, "workflow-bound", objectMapper.createObjectNode(), "workflow-request"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VIDEO_GENERATION");

        verify(taskMapper, never()).insertTask(any(AiTask.class));
        verify(capabilityService, never()).resolveModelConfigForTool(any(), any());
    }

    @Test
    void createWorkflowRootDoesNotAutoSelectModelWhenToolIsUnbound() {
        AiTool tool = tool(9L, "workflow-unbound", null, "[\"VIDEO_GENERATION\"]");
        AtomicReference<AiTask> inserted = new AtomicReference<>();
        when(taskMapper.findByUserIdAndIdempotencyKeyIncludingDeleted(7L, "workflow-request"))
                .thenReturn(Optional.empty());
        when(toolMapper.findOnlineByCode("workflow-unbound")).thenReturn(Optional.of(tool));
        when(taskMapper.insertTask(any(AiTask.class))).thenAnswer(invocation -> {
            AiTask task = invocation.getArgument(0);
            task.setId(42L);
            task.setStatus("QUEUED");
            task.setProgress(0);
            inserted.set(task);
            return 42L;
        });
        when(taskMapper.findByIdAndUserId(42L, 7L)).thenAnswer(invocation -> Optional.of(inserted.get()));

        var response = service.createWorkflowRoot(
                7L, "workflow-unbound", objectMapper.createObjectNode(), "workflow-request");

        assertThat(response.taskId()).isEqualTo(42L);
        verify(capabilityService, never()).validateToolModelBinding(any());
        verify(capabilityService, never()).resolveModelConfigForTool(any(), any());
        verify(snapshotService, never()).create(any());
    }

    private static AiTool tool(Long id, String code, Long modelConfigId, String capabilities) {
        AiTool tool = new AiTool();
        tool.setId(id);
        tool.setToolCode(code);
        tool.setModelConfigId(modelConfigId);
        tool.setRequiredModelCapabilities(capabilities);
        return tool;
    }
}
