package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.agent.service.AgentDelegatedToolCallLifecycleService;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.community.service.CommunityService;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.metrics.TaskMetrics;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowVersionMapper;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRootTaskFinalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRootTaskFinalizerTest {

    @Test
    void fixedWorkflowBillingPolicyCompletesAfterToolWasRemovedWithoutFallbackCharge() {
        TaskMapper taskMapper = mock(TaskMapper.class);
        ToolMapper toolMapper = mock(ToolMapper.class);
        CreditService creditService = mock(CreditService.class);
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        ToolWorkflowVersionMapper versionMapper = mock(ToolWorkflowVersionMapper.class);
        AgentToolDescriptorService descriptors = mock(AgentToolDescriptorService.class);
        TaskMetrics metrics = mock(TaskMetrics.class);
        AgentDelegatedToolCallLifecycleService delegatedLifecycle = mock(AgentDelegatedToolCallLifecycleService.class);
        WorkflowRootTaskFinalizer finalizer = new WorkflowRootTaskFinalizer(
                taskMapper,
                toolMapper,
                mock(ModelCapabilityService.class),
                mock(ModelExecutionSnapshotService.class),
                creditService,
                mock(TaskCreditEstimateService.class),
                mock(BillingService.class),
                mock(CommunityService.class),
                descriptors,
                metrics,
                runMapper,
                versionMapper,
                new ObjectMapper(),
                delegatedLifecycle
        );
        AiTask task = new AiTask();
        task.setId(11L);
        task.setUserId(7L);
        task.setToolId(99L);
        task.setToolCode("deleted-workflow-tool");
        task.setStatus("PROCESSING");
        task.setEstimatedCreditCost(0);
        task.setCreatedAt(LocalDateTime.now());
        when(taskMapper.findById(11L)).thenReturn(Optional.of(task));
        when(taskMapper.markSuccess(any(), anyList())).thenReturn(1);
        when(taskMapper.sumConsumedCreditsByTaskId(11L)).thenReturn(0);
        WorkflowRun run = new WorkflowRun();
        run.setId(33L);
        run.setWorkflowVersionId(22L);
        run.setStatus("SUCCESS");
        when(runMapper.selectByRootTaskId(11L)).thenReturn(run);
        ToolWorkflowVersion version = new ToolWorkflowVersion();
        version.setBillingPolicyJson("{\"mode\":\"WORKFLOW_STEP\",\"fallbackMaxCreditCost\":20}");
        when(versionMapper.selectById(22L)).thenReturn(version);

        finalizer.finalizeSuccess(11L, "JSON", "{}");

        verify(toolMapper, never()).selectById(any());
        verify(creditService, never()).settleCompleted(any(), any(), any(), any(Integer.class));
        verify(taskMapper).insertResult(11L, 7L, "JSON", "{}");
        verify(delegatedLifecycle).finishForWorkflow(11L, 33L, "SUCCESS", null);
    }
}
