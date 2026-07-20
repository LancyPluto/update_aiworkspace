package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.agent.service.AgentDelegatedToolCallLifecycleService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.task.dto.TaskStatusResponse;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.service.TaskService;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflow;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDsl;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowNodeDef;
import com.aiminilab.aitoolmarket.workflow.dto.CreateWorkflowRunCommand;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowRunCreated;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowRunStatus;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowStepStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WorkflowRunApplicationService {

    private final ToolMapper toolMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowRunStepMapper stepMapper;
    private final WorkflowRuntimeAdmissionService admissionService;
    private final WorkflowExecutionService executionService;
    private final TaskService taskService;
    private final TaskMapper taskMapper;
    private final AgentDelegatedToolCallLifecycleService delegatedToolCallLifecycleService;
    private final WorkflowInputSchemaValidator inputSchemaValidator;

    public WorkflowRunApplicationService(ToolMapper toolMapper,
                                         WorkflowRunMapper runMapper,
                                         WorkflowRunStepMapper stepMapper,
                                         WorkflowRuntimeAdmissionService admissionService,
                                         WorkflowExecutionService executionService,
                                          TaskService taskService,
                                          TaskMapper taskMapper,
                                          AgentDelegatedToolCallLifecycleService delegatedToolCallLifecycleService,
                                          WorkflowInputSchemaValidator inputSchemaValidator) {
        this.toolMapper = toolMapper;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.admissionService = admissionService;
        this.executionService = executionService;
        this.taskService = taskService;
        this.taskMapper = taskMapper;
        this.delegatedToolCallLifecycleService = delegatedToolCallLifecycleService;
        this.inputSchemaValidator = inputSchemaValidator;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public WorkflowRunCreated create(CreateWorkflowRunCommand command) {
        return createInternal(command);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WorkflowRunCreated createInCurrentTransaction(CreateWorkflowRunCommand command) {
        return createInternal(command);
    }

    private WorkflowRunCreated createInternal(CreateWorkflowRunCommand command) {
        validate(command);
        WorkflowRun existing = runMapper.selectByUserAndClientRequestId(
                command.userId(),
                command.clientRequestId()
        );
        if (existing != null) {
            return reuseExisting(command, existing);
        }

        AiTool tool = java.util.Optional.ofNullable(toolMapper.selectAnyByCodeForUpdate(command.toolCode()))
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工作流工具不存在或未上线"));
        WorkflowRun concurrentAfterToolLock = runMapper.selectByUserAndClientRequestIdAfterAdmissionLock(
                command.userId(),
                command.clientRequestId()
        );
        if (concurrentAfterToolLock != null) {
            return reuseExisting(command, concurrentAfterToolLock);
        }
        if (!"ONLINE".equalsIgnoreCase(tool.getStatus())
                || !"WORKFLOW".equalsIgnoreCase(tool.getExecutionMode())
                || !"WORKFLOW_STEP".equalsIgnoreCase(tool.getBillingMode())
                || !Boolean.TRUE.equals(tool.getAgentSurfaceEnabled())) {
            throw new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工作流工具不存在或未上线");
        }

        admissionService.lockNewRunAdmission(command.userId());
        // A distinct statement bypasses MyBatis' transaction-local cached null from the first lookup.
        WorkflowRun concurrentAfterAdmissionLock = runMapper.selectByUserAndClientRequestIdAfterAdmissionLock(
                command.userId(),
                command.clientRequestId()
        );
        if (concurrentAfterAdmissionLock != null) {
            return reuseExisting(command, concurrentAfterAdmissionLock);
        }

        WorkflowRuntimeAdmission admission = admissionService.admitNewRun(command.userId(), tool.getId());
        ToolWorkflow workflow = admission.workflow();
        ToolWorkflowVersion version = admission.version();
        WorkflowDsl dsl = admission.dsl();
        inputSchemaValidator.validate(version, command.input());

        TaskStatusResponse rootTask = taskService.createWorkflowRoot(
                command.userId(),
                command.toolCode(),
                command.input(),
                command.clientRequestId()
        );
        AiTask persistedRootTask = taskMapper.findById(rootTask.taskId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND, "root task 不存在"));
        WorkflowRun run = newRun(
                command,
                tool,
                workflow,
                version,
                rootTask.taskId(),
                persistedRootTask.getParamsJson(),
                admission.providerCostReservedCny()
        );
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException duplicate) {
            WorkflowRun concurrent = runMapper.selectByUserAndClientRequestId(
                    command.userId(),
                    command.clientRequestId()
            );
            if (concurrent == null) {
                throw duplicate;
            }
            return reuseExisting(command, concurrent);
        }

        initializeSteps(run.getId(), dsl);
        if (taskMapper.markProcessing(
                rootTask.taskId(),
                5,
                "工作流已创建",
                List.of(TaskStatus.QUEUED.name())
        ) != 1) {
            throw new BusinessException(ErrorCode.TASK_STATUS_INVALID, "root task 无法进入执行状态");
        }
        bindAgentToolCall(command, run);
        executionService.advanceRun(run.getId());
        return toCreated(runMapper.selectById(run.getId()));
    }

    private void bindAgentToolCall(CreateWorkflowRunCommand command, WorkflowRun run) {
        if (command.agentToolCallId() != null) {
            delegatedToolCallLifecycleService.bindDelegated(
                    command.agentToolCallId(), run.getRootTaskId(), run.getId(), run.getStatus()
            );
        }
    }

    private WorkflowRunCreated reuseExisting(CreateWorkflowRunCommand command, WorkflowRun existing) {
        AiTool requestedTool = toolMapper.findAnyByCode(command.toolCode()).orElse(null);
        if (requestedTool == null || !requestedTool.getId().equals(existing.getToolId())) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "clientRequestId 已用于其他工具"
            );
        }
        ensureAgentLaunchIdentity(command, existing);
        bindAgentToolCall(command, existing);
        return toCreated(existing);
    }

    private void ensureAgentLaunchIdentity(CreateWorkflowRunCommand command, WorkflowRun run) {
        if (command.agentToolCallId() != null && !"AGENT_CHAT".equals(run.getLaunchSource())) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Agent workflow clientRequestId belongs to another launch source"
            );
        }
    }

    private WorkflowRun newRun(CreateWorkflowRunCommand command,
                               AiTool tool,
                               ToolWorkflow workflow,
                               ToolWorkflowVersion version,
                               Long rootTaskId,
                               String persistedInputJson,
                               java.math.BigDecimal providerCostReservedCny) {
        LocalDateTime now = LocalDateTime.now();
        WorkflowRun run = new WorkflowRun();
        run.setUserId(command.userId());
        run.setToolId(tool.getId());
        run.setWorkflowId(workflow.getId());
        run.setWorkflowVersion(version.getVersion());
        run.setWorkflowVersionId(version.getId());
        run.setRootTaskId(rootTaskId);
        run.setLaunchSource(command.launchSource());
        run.setClientRequestId(command.clientRequestId());
        run.setStatus(WorkflowRunStatus.RUNNING.name());
        run.setRevision(0L);
        run.setCancellationGeneration(0L);
        run.setBillingStatus("CLEAR");
        run.setProviderCostReservedCny(providerCostReservedCny);
        run.setInputJson(persistedInputJson);
        run.setContextJson("{}");
        run.setStartedAt(now);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return run;
    }

    private void initializeSteps(Long runId, WorkflowDsl dsl) {
        int sequence = 0;
        for (String nodeId : dsl.executionOrder()) {
            WorkflowNodeDef node = dsl.requireNode(nodeId);
            WorkflowRunStep step = new WorkflowRunStep();
            step.setRunId(runId);
            step.setNodeId(node.id());
            step.setSequenceNo(++sequence);
            step.setNodeDefType(node.type().name());
            step.setStatus(WorkflowStepStatus.PENDING.name());
            step.setRevision(0L);
            step.setAttempt(0);
            step.setAttemptCount(0);
            step.setMaxAttempts(2);
            stepMapper.insert(step);
        }
    }

    private WorkflowRunCreated toCreated(WorkflowRun run) {
        return new WorkflowRunCreated(
                run.getRootTaskId(),
                run.getId(),
                run.getWorkflowVersionId(),
                run.getStatus()
        );
    }

    private void validate(CreateWorkflowRunCommand command) {
        if (command == null || command.userId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "userId 不能为空");
        }
        if (command.toolCode() == null || command.toolCode().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "toolCode 不能为空");
        }
        if (command.clientRequestId() == null || command.clientRequestId().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "clientRequestId 不能为空");
        }
        if (command.launchSource() == null || command.launchSource().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "launchSource 不能为空");
        }
    }
}
