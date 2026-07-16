package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import org.springframework.stereotype.Service;

@Service
public class WorkflowRunLockService {

    private final WorkflowRunMapper runMapper;
    private final WorkflowRunStepMapper stepMapper;
    private final WorkflowStepAttemptMapper attemptMapper;

    public WorkflowRunLockService(WorkflowRunMapper runMapper,
                                  WorkflowRunStepMapper stepMapper,
                                  WorkflowStepAttemptMapper attemptMapper) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.attemptMapper = attemptMapper;
    }

    public WorkflowRun requireByChildTaskId(Long childTaskId) {
        return require(attemptMapper.selectRunIdByChildTaskId(childTaskId), "workflow child task", childTaskId);
    }

    public WorkflowRun requireByStepId(Long stepId) {
        return require(stepMapper.selectRunIdById(stepId), "workflow step", stepId);
    }

    private WorkflowRun require(Long runId, String source, Long sourceId) {
        if (runId == null) {
            throw new IllegalStateException("No workflow run is bound to " + source + ": " + sourceId);
        }
        WorkflowRun run = runMapper.selectByIdForUpdate(runId);
        if (run == null) {
            throw new IllegalStateException("Workflow run does not exist: " + runId);
        }
        return run;
    }
}
