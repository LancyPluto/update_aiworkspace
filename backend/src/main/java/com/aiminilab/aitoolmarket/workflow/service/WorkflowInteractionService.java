package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.workflow.dto.WorkflowConfirmationRequest;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowConfirmation;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowConfirmationMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class WorkflowInteractionService {

    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of(
            "SUCCESS", "FAILED", "TIMEOUT", "CANCELLED"
    );
    private static final Set<String> SUPPORTED_CONFIRMATION_ACTIONS = Set.of(
            "APPROVE", "REJECT", "CONTINUE_WITH_FEEDBACK", "CANCEL"
    );

    private final WorkflowRunMapper runMapper;
    private final WorkflowRunStepMapper stepMapper;
    private final WorkflowConfirmationMapper confirmationMapper;
    private final WorkflowConfirmationTokenService tokenService;
    private final WorkflowStepScheduler stepScheduler;
    private final WorkflowCancellationService cancellationService;
    private final WorkflowExecutionService executionService;
    private final TaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    public WorkflowInteractionService(WorkflowRunMapper runMapper,
                                      WorkflowRunStepMapper stepMapper,
                                      WorkflowConfirmationMapper confirmationMapper,
                                      WorkflowConfirmationTokenService tokenService,
                                      WorkflowStepScheduler stepScheduler,
                                      WorkflowCancellationService cancellationService,
                                      WorkflowExecutionService executionService,
                                      TaskMapper taskMapper,
                                      ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.confirmationMapper = confirmationMapper;
        this.tokenService = tokenService;
        this.stepScheduler = stepScheduler;
        this.cancellationService = cancellationService;
        this.executionService = executionService;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void confirm(Long rootTaskId, Long userId, WorkflowConfirmationRequest request) {
        WorkflowRun run = requireOwnedRunForUpdate(rootTaskId, userId);
        if (!"AWAITING_USER".equals(run.getStatus())) {
            throw conflict("当前工作流不在等待确认状态");
        }
        WorkflowConfirmation confirmation = confirmationMapper.selectByTokenHashForUpdate(
                tokenService.tokenHash(request.confirmationToken())
        );
        if (confirmation == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "确认凭证无效");
        }
        WorkflowRunStep step = stepMapper.selectById(request.stepId());
        validateConfirmationBinding(run, step, confirmation, userId, request.stepId());
        if (!tokenService.verify(request.confirmationToken(), confirmation, run, step)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "确认凭证签名无效");
        }
        if (!"PENDING".equals(confirmation.getStatus())) {
            throw conflict("确认凭证已处理");
        }
        if (!confirmation.getExpiresAt().isAfter(LocalDateTime.now())) {
            confirmationMapper.expireIfPending(confirmation.getId());
            throw conflict("确认凭证已过期");
        }
        if (!Objects.equals(confirmation.getParameterHash(), tokenService.parameterHash(run, step))) {
            throw conflict("待确认参数已经变化，请刷新后重新确认");
        }

        String action = request.action().trim().toUpperCase(Locale.ROOT);
        List<String> allowedActions = tokenService.readAllowedActions(confirmation);
        if (!SUPPORTED_CONFIRMATION_ACTIONS.contains(action) || !allowedActions.contains(action)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "当前确认凭证不允许该操作");
        }
        String feedbackJson = writeJson(Map.of(
                "idempotencyKey", request.idempotencyKey(),
                "fields", request.fields()
        ));
        if (confirmationMapper.consumeIfPending(confirmation.getId(), action, feedbackJson) != 1) {
            throw conflict("确认凭证已处理");
        }

        if ("REJECT".equals(action) || "CANCEL".equals(action)) {
            String reason = "REJECT".equals(action) ? "USER_REJECTED" : "USER_CANCELLED";
            cancellationService.begin(rootTaskId, userId, reason);
            scheduleCancellationSettlementAfterCommit(rootTaskId, userId, reason);
            return;
        }
        resumeAfterConfirmation(run, step, action, request.fields());
        executionService.advanceRun(run.getId());
    }

    @Transactional
    public void resume(Long rootTaskId, Long userId) {
        WorkflowRun run = requireOwnedRunForUpdate(rootTaskId, userId);
        if ("RUNNING".equals(run.getStatus()) || TERMINAL_RUN_STATUSES.contains(run.getStatus())) {
            return;
        }
        if (!"AWAITING_FUNDS".equals(run.getStatus()) || run.getCurrentStepId() == null) {
            throw conflict("当前工作流不在等待充值状态");
        }
        WorkflowRunStep step = stepMapper.selectById(run.getCurrentStepId());
        if (step == null || !run.getId().equals(step.getRunId()) || !"READY".equals(step.getStatus())) {
            throw conflict("等待充值的步骤已经变化，请刷新后重试");
        }
        stepScheduler.dispatch(step.getId());
    }

    public void cancel(Long rootTaskId, Long userId, String reason) {
        cancellationService.begin(rootTaskId, userId, reason);
        scheduleCancellationSettlementAfterCommit(rootTaskId, userId, reason);
    }

    private void resumeAfterConfirmation(WorkflowRun run,
                                         WorkflowRunStep step,
                                         String action,
                                         Map<String, Object> fields) {
        ObjectNode input = readObject(run.getInputJson());
        if ("CONTINUE_WITH_FEEDBACK".equals(action)) {
            ObjectNode revisions = input.path("userRevisions") instanceof ObjectNode existing
                    ? existing.deepCopy()
                    : objectMapper.createObjectNode();
            fields.forEach((key, value) -> {
                if (key != null && !key.isBlank()) {
                    input.set(key, objectMapper.valueToTree(value));
                    revisions.set(key, objectMapper.valueToTree(value));
                }
            });
            input.set("userRevisions", revisions);
        }
        ObjectNode context = readObject(run.getContextJson());
        ObjectNode decision = objectMapper.createObjectNode();
        decision.put("decision", action);
        decision.set("feedback", objectMapper.valueToTree(fields));
        context.set(step.getNodeId(), decision);
        String outputJson = writeJson(decision);
        if (stepMapper.completeAwaitingUser(step.getId(), revision(step), outputJson) != 1) {
            throw conflict("确认步骤已被其他操作处理");
        }
        String inputJson = writeJson(input);
        String contextJson = writeJson(context);
        if (runMapper.resumeAwaitingUser(
                run.getId(), revision(run), step.getId(), step.getNodeId(), inputJson, contextJson
        ) != 1) {
            throw conflict("工作流确认状态已变化");
        }
        taskMapper.updateParamsJson(run.getRootTaskId(), inputJson);
        if (taskMapper.markProcessing(
                run.getRootTaskId(), 20, "已确认，继续执行", List.of(TaskStatus.AWAITING_USER.name())
        ) != 1) {
            throw conflict("根任务确认状态已变化");
        }
    }

    private void scheduleCancellationSettlementAfterCommit(Long rootTaskId, Long userId, String reason) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cancellationService.settle(rootTaskId, userId, reason);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cancellationService.settle(rootTaskId, userId, reason);
            }
        });
    }

    private WorkflowRun requireOwnedRunForUpdate(Long rootTaskId, Long userId) {
        WorkflowRun run = runMapper.selectByRootTaskIdForUpdate(rootTaskId);
        if (run == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "工作流任务不存在");
        }
        if (!Objects.equals(run.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该工作流任务");
        }
        return run;
    }

    private void validateConfirmationBinding(WorkflowRun run,
                                             WorkflowRunStep step,
                                             WorkflowConfirmation confirmation,
                                             Long userId,
                                             Long requestedStepId) {
        boolean matches = step != null
                && Objects.equals(step.getRunId(), run.getId())
                && Objects.equals(run.getCurrentStepId(), requestedStepId)
                && Objects.equals(confirmation.getRunId(), run.getId())
                && Objects.equals(confirmation.getStepId(), requestedStepId)
                && Objects.equals(confirmation.getUserId(), userId);
        if (!matches) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "确认凭证与当前任务不匹配");
        }
    }

    private ObjectNode readObject(String json) {
        try {
            JsonNode node = json == null || json.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(json);
            return node instanceof ObjectNode objectNode ? objectNode.deepCopy() : objectMapper.createObjectNode();
        } catch (Exception exception) {
            throw new IllegalStateException("Workflow interaction data is not valid JSON", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize workflow interaction data", exception);
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, message);
    }

    private long revision(WorkflowRun run) {
        return run.getRevision() == null ? 0L : run.getRevision();
    }

    private long revision(WorkflowRunStep step) {
        return step.getRevision() == null ? 0L : step.getRevision();
    }
}
