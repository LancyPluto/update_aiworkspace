package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.service.AgentToolDescriptorService;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.community.service.CommunityService;
import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.metrics.TaskMetrics;
import com.aiminilab.aitoolmarket.task.service.TaskStateMachine;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WorkflowRootTaskFinalizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowRootTaskFinalizer.class);

    private final TaskMapper taskMapper;
    private final ToolMapper toolMapper;
    private final ModelCapabilityService modelCapabilityService;
    private final ModelExecutionSnapshotService modelExecutionSnapshotService;
    private final CreditService creditService;
    private final TaskCreditEstimateService taskCreditEstimateService;
    private final BillingService billingService;
    private final CommunityService communityService;
    private final AgentToolDescriptorService agentToolDescriptorService;
    private final TaskMetrics taskMetrics;

    public WorkflowRootTaskFinalizer(TaskMapper taskMapper,
                                     ToolMapper toolMapper,
                                     ModelCapabilityService modelCapabilityService,
                                     ModelExecutionSnapshotService modelExecutionSnapshotService,
                                     CreditService creditService,
                                     TaskCreditEstimateService taskCreditEstimateService,
                                     BillingService billingService,
                                     CommunityService communityService,
                                     AgentToolDescriptorService agentToolDescriptorService,
                                     TaskMetrics taskMetrics) {
        this.taskMapper = taskMapper;
        this.toolMapper = toolMapper;
        this.modelCapabilityService = modelCapabilityService;
        this.modelExecutionSnapshotService = modelExecutionSnapshotService;
        this.creditService = creditService;
        this.taskCreditEstimateService = taskCreditEstimateService;
        this.billingService = billingService;
        this.communityService = communityService;
        this.agentToolDescriptorService = agentToolDescriptorService;
        this.taskMetrics = taskMetrics;
    }

    @Transactional
    public void finalizeSuccess(Long rootTaskId, String markdown) {
        AiTask task = taskMapper.findById(rootTaskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND, "任务不存在"));
        if (TaskStatus.SUCCESS.name().equals(task.getStatus())) {
            return;
        }
        TaskStateMachine.ensureTransition(task.getStatus(), TaskStatus.SUCCESS.name());
        if (taskMapper.markSuccess(rootTaskId, List.of(TaskStatus.PROCESSING.name())) == 0) {
            AiTask current = taskMapper.findById(rootTaskId).orElseThrow();
            if (!TaskStatus.SUCCESS.name().equals(current.getStatus())) {
                TaskStateMachine.ensureTransition(current.getStatus(), TaskStatus.SUCCESS.name());
            }
        }

        int frozenCredits = task.getEstimatedCreditCost() == null ? 0 : Math.max(0, task.getEstimatedCreditCost());
        if (frozenCredits > 0) {
            creditService.release(task.getUserId(), CreditSourceType.TASK, rootTaskId, frozenCredits);
        }
        int consumedCredits = taskMapper.sumConsumedCreditsByTaskId(rootTaskId);
        if (consumedCredits <= 0) {
            AiTool billingTool = toolMapper.findById(task.getToolId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
            ModelExecutionSnapshot snapshot = modelExecutionSnapshotService.parse(task.getModelSnapshotJson());
            AgentModelConfig modelConfig = snapshot != null
                    ? snapshot.toModelConfig()
                    : modelCapabilityService.resolveModelConfigForTool(billingTool);
            int fallbackCredits = taskCreditEstimateService.estimateUserFacingTaskCredits(billingTool, modelConfig);
            if (fallbackCredits > 0) {
                int chargedCredits = creditService.settleCompleted(
                        task.getUserId(),
                        CreditSourceType.TASK,
                        rootTaskId,
                        fallbackCredits
                );
                if (chargedCredits > 0) {
                    billingService.recordUsage(
                            "TASK",
                            rootTaskId,
                            task.getUserId(),
                            modelConfig,
                            null,
                            null,
                            1,
                            chargedCredits
                    );
                }
            }
        }
        taskMapper.insertResult(rootTaskId, task.getUserId(), "MARKDOWN", markdown);
        try {
            communityService.autoPublishTask(taskMapper.findById(rootTaskId).orElse(task), "MARKDOWN", markdown);
        } catch (Exception exception) {
            LOGGER.warn("community auto-publish skipped after workflow root success taskId={}", rootTaskId, exception);
        }
        agentToolDescriptorService.markToolHealth(task.getToolCode(), "HEALTHY", null);
        taskMetrics.recordTaskOutcome(task.getToolCode(), "SUCCESS", task.getCreatedAt(), taskMapper.findById(rootTaskId).orElse(task).getFinishedAt());
    }
}
