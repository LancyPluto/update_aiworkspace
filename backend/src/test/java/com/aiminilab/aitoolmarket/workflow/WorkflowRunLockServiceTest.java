package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRunLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowRunLockServiceTest {

    private WorkflowRunMapper runMapper;
    private WorkflowRunStepMapper stepMapper;
    private WorkflowStepAttemptMapper attemptMapper;
    private WorkflowRunLockService service;
    private WorkflowRun run;

    @BeforeEach
    void setUp() {
        runMapper = mock(WorkflowRunMapper.class);
        stepMapper = mock(WorkflowRunStepMapper.class);
        attemptMapper = mock(WorkflowStepAttemptMapper.class);
        service = new WorkflowRunLockService(runMapper, stepMapper, attemptMapper);
        run = new WorkflowRun();
        run.setId(41L);
        when(runMapper.selectByIdForUpdate(41L)).thenReturn(run);
    }

    @Test
    void childTaskLocatesRunBeforeTakingPrimaryRunLock() {
        when(attemptMapper.selectRunIdByChildTaskId(31L)).thenReturn(41L);

        assertThat(service.requireByChildTaskId(31L)).isSameAs(run);

        var order = inOrder(attemptMapper, runMapper);
        order.verify(attemptMapper).selectRunIdByChildTaskId(31L);
        order.verify(runMapper).selectByIdForUpdate(41L);
    }

    @Test
    void stepLocatesRunBeforeTakingPrimaryRunLock() {
        when(stepMapper.selectRunIdById(21L)).thenReturn(41L);

        assertThat(service.requireByStepId(21L)).isSameAs(run);

        var order = inOrder(stepMapper, runMapper);
        order.verify(stepMapper).selectRunIdById(21L);
        order.verify(runMapper).selectByIdForUpdate(41L);
    }
}
