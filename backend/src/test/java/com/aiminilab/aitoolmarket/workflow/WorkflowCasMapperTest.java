package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.workflow.entity.WorkflowConfirmation;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepAttempt;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepCharge;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowConfirmationMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepAttemptMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepChargeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_cas_mapper_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class WorkflowCasMapperTest {

    @Autowired
    private WorkflowRunMapper runMapper;

    @Autowired
    private WorkflowRunStepMapper stepMapper;

    @Autowired
    private WorkflowStepAttemptMapper attemptMapper;

    @Autowired
    private WorkflowStepChargeMapper chargeMapper;

    @Autowired
    private WorkflowConfirmationMapper confirmationMapper;

    @Test
    void runAndStepOnlyAdvanceFromExpectedRevisionAndStatus() {
        WorkflowRun run = insertRun(7001L);
        assertThat(runMapper.casStatus(run.getId(), 0L, "RUNNING", "AWAITING_USER", null)).isEqualTo(1);
        assertThat(runMapper.casStatus(run.getId(), 0L, "RUNNING", "FAILED", "late callback")).isZero();
        WorkflowRun reloadedRun = runMapper.selectById(run.getId());
        assertThat(reloadedRun.getStatus()).isEqualTo("AWAITING_USER");
        assertThat(reloadedRun.getRevision()).isEqualTo(1L);

        WorkflowRunStep step = insertStep(run.getId(), "confirm");
        assertThat(stepMapper.casStatus(step.getId(), 0L, "PENDING", "READY")).isEqualTo(1);
        assertThat(stepMapper.casStatus(step.getId(), 0L, "PENDING", "FAILED")).isZero();
        WorkflowRunStep reloadedStep = stepMapper.selectById(step.getId());
        assertThat(reloadedStep.getStatus()).isEqualTo("READY");
        assertThat(reloadedStep.getRevision()).isEqualTo(1L);
    }

    @Test
    void inlineStepCompletionRejectsStaleRevisionAndStatus() {
        WorkflowRun run = insertRun(7003L);
        WorkflowRunStep step = insertStep(run.getId(), "inline");
        LocalDateTime finishedAt = LocalDateTime.now();

        assertThat(stepMapper.completeInlineStep(
                step.getId(),
                0L,
                "PENDING",
                null,
                "{\"winner\":true}",
                finishedAt
        )).isEqualTo(1);
        assertThat(stepMapper.completeInlineStep(
                step.getId(),
                0L,
                "PENDING",
                null,
                "{\"late\":true}",
                finishedAt.plusSeconds(1)
        )).isZero();

        WorkflowRunStep completed = stepMapper.selectById(step.getId());
        assertThat(completed.getStatus()).isEqualTo("SUCCESS");
        assertThat(completed.getRevision()).isEqualTo(1L);
        assertThat(completed.getOutputJson()).isEqualTo("{\"winner\":true}");
    }

    @Test
    void runContextUpdateRejectsStaleRevision() {
        WorkflowRun run = insertRun(7004L);

        assertThat(runMapper.updateRunState(
                run.getId(),
                0L,
                "RUNNING",
                "RUNNING",
                "{\"winner\":true}",
                "start",
                null,
                null
        )).isEqualTo(1);
        assertThat(runMapper.updateRunState(
                run.getId(),
                0L,
                "RUNNING",
                "FAILED",
                "{\"late\":true}",
                "late",
                "late failure",
                LocalDateTime.now()
        )).isZero();

        WorkflowRun updated = runMapper.selectById(run.getId());
        assertThat(updated.getStatus()).isEqualTo("RUNNING");
        assertThat(updated.getRevision()).isEqualTo(1L);
        assertThat(updated.getContextJson()).isEqualTo("{\"winner\":true}");
        assertThat(updated.getCurrentNodeId()).isEqualTo("start");
    }

    @Test
    void attemptChargeAndConfirmationHaveStableUniqueIdentity() {
        WorkflowRun run = insertRun(7002L);
        WorkflowRunStep step = insertStep(run.getId(), "model");

        WorkflowStepAttempt attempt = new WorkflowStepAttempt();
        attempt.setStepId(step.getId());
        attempt.setAttemptNo(1);
        attempt.setChildTaskId(9001L);
        attempt.setStatus("CREATED");
        attempt.setClaimToken("claim-7002-model-1");
        attemptMapper.insert(attempt);
        assertThat(attemptMapper.selectByChildTaskId(9001L).getId()).isEqualTo(attempt.getId());

        WorkflowStepAttempt duplicate = new WorkflowStepAttempt();
        duplicate.setStepId(step.getId());
        duplicate.setAttemptNo(1);
        duplicate.setChildTaskId(9002L);
        duplicate.setStatus("CREATED");
        duplicate.setClaimToken("claim-7002-model-2");
        assertThatThrownBy(() -> attemptMapper.insert(duplicate)).isInstanceOf(DuplicateKeyException.class);

        WorkflowStepCharge charge = new WorkflowStepCharge();
        charge.setRunId(run.getId());
        charge.setStepId(step.getId());
        charge.setAttemptId(attempt.getId());
        charge.setUserId(run.getUserId());
        charge.setStatus("RESERVED");
        charge.setReservedCredits(12);
        charge.setChargedCredits(0);
        charge.setIdempotencyKey("workflow:7002:model:1");
        chargeMapper.insert(charge);
        assertThat(chargeMapper.selectByAttemptId(attempt.getId()).getReservedCredits()).isEqualTo(12);

        WorkflowConfirmation confirmation = new WorkflowConfirmation();
        confirmation.setRunId(run.getId());
        confirmation.setStepId(step.getId());
        confirmation.setUserId(run.getUserId());
        confirmation.setTokenHash("a".repeat(64));
        confirmation.setParameterHash("b".repeat(64));
        confirmation.setAllowedActionsJson("[\"APPROVE\",\"REJECT\"]");
        confirmation.setStatus("PENDING");
        confirmation.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        confirmationMapper.insert(confirmation);
        assertThat(confirmationMapper.selectByTokenHash("a".repeat(64)).getId()).isEqualTo(confirmation.getId());
    }

    private WorkflowRun insertRun(long rootTaskId) {
        WorkflowRun run = new WorkflowRun();
        run.setUserId(7L);
        run.setToolId(11L);
        run.setWorkflowId(13L);
        run.setWorkflowVersion(1);
        run.setWorkflowVersionId(17L);
        run.setRootTaskId(rootTaskId);
        run.setLaunchSource("AGENTS_PAGE");
        run.setClientRequestId("request-" + rootTaskId);
        run.setStatus("RUNNING");
        run.setRevision(0L);
        run.setCancellationGeneration(0L);
        run.setBillingStatus("CLEAR");
        run.setInputJson("{}");
        run.setContextJson("{}");
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runMapper.insert(run);
        return run;
    }

    private WorkflowRunStep insertStep(long runId, String nodeId) {
        WorkflowRunStep step = new WorkflowRunStep();
        step.setRunId(runId);
        step.setNodeId(nodeId);
        step.setSequenceNo(1);
        step.setNodeDefType("MODEL_CALL");
        step.setStatus("PENDING");
        step.setRevision(0L);
        step.setAttempt(0);
        step.setAttemptCount(0);
        step.setMaxAttempts(2);
        stepMapper.insert(step);
        return step;
    }
}
