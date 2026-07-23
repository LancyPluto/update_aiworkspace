package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolCategoryMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowVersionMapper;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunDetailResponse;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowArtifactQueryMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowConfirmationMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepChargeMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowToolSurfaceMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowConfirmationTokenService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowToolQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowToolQueryFailureProjectionTest {

    @Test
    void userDetailNeverFallsBackToLegacyDeveloperMessage() {
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowRunStepMapper stepMapper = mock(WorkflowRunStepMapper.class);
        WorkflowStepChargeMapper chargeMapper = mock(WorkflowStepChargeMapper.class);
        TaskMapper taskMapper = mock(TaskMapper.class);
        WorkflowArtifactQueryMapper artifactMapper = mock(WorkflowArtifactQueryMapper.class);
        ToolMapper toolMapper = mock(ToolMapper.class);

        WorkflowRun run = new WorkflowRun();
        run.setId(10L);
        run.setRootTaskId(20L);
        run.setUserId(7L);
        run.setToolId(30L);
        run.setStatus("FAILED");
        run.setErrorCode("PROVIDER_FAILED");
        run.setErrorMessage("provider response password=secret-value");

        WorkflowRunStep step = new WorkflowRunStep();
        step.setId(40L);
        step.setRunId(10L);
        step.setNodeId("model");
        step.setNodeDefType("MODEL_CALL");
        step.setSequenceNo(1);
        step.setStatus("FAILED");
        step.setErrorCode("PROVIDER_FAILED");
        step.setErrorMessage("raw provider payload password=step-secret");

        AiTask task = new AiTask();
        task.setId(20L);
        task.setTaskNo("TASK-20");
        task.setToolCode("workflow-tool");
        task.setToolName("Workflow tool");
        task.setProgress(100);
        task.setProgressMessage("raw root progress password=task-secret");
        task.setErrorCode("MODEL_CALL_FAILED");
        task.setErrorMessage("raw task error password=task-secret");

        when(runMapper.selectByRootTaskId(20L)).thenReturn(run);
        when(taskMapper.findById(20L)).thenReturn(Optional.of(task));
        when(toolMapper.selectById(30L)).thenReturn(null);
        when(stepMapper.selectByRunId(10L)).thenReturn(List.of(step));
        when(chargeMapper.selectList(any())).thenReturn(List.of());
        when(artifactMapper.selectByTaskIds(anyList())).thenReturn(List.of());

        WorkflowToolQueryService service = new WorkflowToolQueryService(
                toolMapper,
                mock(ToolCategoryMapper.class),
                mock(ToolWorkflowMapper.class),
                mock(ToolWorkflowVersionMapper.class),
                runMapper,
                stepMapper,
                chargeMapper,
                taskMapper,
                mock(WorkflowToolSurfaceMapper.class),
                artifactMapper,
                new ObjectMapper(),
                mock(WorkflowConfirmationMapper.class),
                mock(WorkflowConfirmationTokenService.class)
        );

        WorkflowRunDetailResponse response = service.runDetail(20L, 7L);

        assertThat(response.errorCode()).isEqualTo("PROVIDER_FAILED");
        assertThat(response.errorMessage()).isEqualTo("工作流执行失败，请稍后重试");
        assertThat(response.steps()).singleElement().satisfies(projected -> {
            assertThat(projected.errorCode()).isEqualTo("PROVIDER_FAILED");
            assertThat(projected.errorMessage()).isEqualTo("工作流执行失败，请稍后重试");
            assertThat(projected.progressMessage()).isEqualTo("工作流执行失败，请稍后重试");
        });
        assertThat(response.toString())
                .doesNotContain("secret-value", "step-secret", "task-secret", "provider response", "raw provider");
    }
}
