package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.agent.service.AgentDelegatedToolCallLifecycleService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.comic.service.ComicWorkflowResultProjector;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.service.TaskStateMachine;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflow;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowVersionMapper;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDsl;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowEdgeDef;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowNodeDef;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowNodeDefType;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.support.WorkflowFailureContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class WorkflowExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowExecutionService.class);
    private static final String RUN_RUNNING = "RUNNING";
    private static final String RUN_AWAITING_USER = "AWAITING_USER";
    private static final String RUN_AWAITING_FUNDS = "AWAITING_FUNDS";
    private static final String RUN_CANCELLING = "CANCELLING";
    private static final String RUN_SUCCESS = "SUCCESS";
    private static final String RUN_FAILED = "FAILED";
    private static final String RUN_TIMEOUT = "TIMEOUT";
    private static final String RUN_CANCELLED = "CANCELLED";
    private static final String BILLING_RECONCILIATION_FAILED = "RECONCILIATION_FAILED";
    private static final String BILLING_RECONCILIATION_ERROR_CODE =
            "WORKFLOW_BILLING_RECONCILIATION_FAILED";
    private static final String BILLING_RECONCILIATION_ERROR_MESSAGE =
            "Workflow billing reconciliation failed";
    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of(
            RUN_SUCCESS,
            RUN_FAILED,
            RUN_TIMEOUT,
            RUN_CANCELLED
    );
    private static final Set<String> FAILABLE_RUN_STATUSES = Set.of(
            RUN_RUNNING,
            RUN_AWAITING_USER,
            RUN_AWAITING_FUNDS
    );
    private static final String STEP_PENDING = "PENDING";
    private static final String STEP_RUNNING = "RUNNING";
    private static final String STEP_SUCCESS = "SUCCESS";
    private static final String STEP_FAILED = "FAILED";
    private static final String STEP_QUEUED = "QUEUED";

    private final WorkflowDslService workflowDslService;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowRunStepMapper workflowRunStepMapper;
    private final TaskMapper taskMapper;
    private final ToolMapper toolMapper;
    private final ToolWorkflowVersionMapper toolWorkflowVersionMapper;
    private final WorkflowStepScheduler workflowStepScheduler;
    private final CreditService creditService;
    private final ObjectMapper objectMapper;
    private final WorkflowRootTaskFinalizer workflowRootTaskFinalizer;
    private final WorkflowConfirmationTokenService confirmationTokenService;
    private final AgentDelegatedToolCallLifecycleService delegatedToolCallLifecycleService;
    private final ComicWorkflowResultProjector comicWorkflowResultProjector;

    public WorkflowExecutionService(WorkflowDslService workflowDslService,
                                    WorkflowRunMapper workflowRunMapper,
                                    WorkflowRunStepMapper workflowRunStepMapper,
                                    TaskMapper taskMapper,
                                    ToolMapper toolMapper,
                                    ToolWorkflowVersionMapper toolWorkflowVersionMapper,
                                    WorkflowStepScheduler workflowStepScheduler,
                                    CreditService creditService,
                                    ObjectMapper objectMapper,
                                    @Lazy WorkflowRootTaskFinalizer workflowRootTaskFinalizer,
                                    WorkflowConfirmationTokenService confirmationTokenService,
                                    AgentDelegatedToolCallLifecycleService delegatedToolCallLifecycleService,
                                    ComicWorkflowResultProjector comicWorkflowResultProjector) {
        this.workflowDslService = workflowDslService;
        this.workflowRunMapper = workflowRunMapper;
        this.workflowRunStepMapper = workflowRunStepMapper;
        this.taskMapper = taskMapper;
        this.toolMapper = toolMapper;
        this.toolWorkflowVersionMapper = toolWorkflowVersionMapper;
        this.workflowStepScheduler = workflowStepScheduler;
        this.creditService = creditService;
        this.objectMapper = objectMapper;
        this.workflowRootTaskFinalizer = workflowRootTaskFinalizer;
        this.confirmationTokenService = confirmationTokenService;
        this.delegatedToolCallLifecycleService = delegatedToolCallLifecycleService;
        this.comicWorkflowResultProjector = comicWorkflowResultProjector;
    }

    @Transactional
    public void submitUserFeedback(Long rootTaskId, Long userId, Map<String, String> feedbackFields) {
        WorkflowRun run = workflowRunMapper.selectByRootTaskIdForUpdate(rootTaskId);
        if (run == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流运行不存在");
        }
        if (!RUN_AWAITING_USER.equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "当前任务不在等待用户输入状态");
        }
        if (!Objects.equals(run.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该任务");
        }
        WorkflowNodeDef awaitingNode = requireLegacyUserInputNode(run);
        WorkflowRunStep awaitingStep = workflowRunStepMapper.selectByRunIdAndNodeId(
                run.getId(), awaitingNode.id()
        );
        if (awaitingStep == null || !STEP_PENDING.equals(awaitingStep.getStatus())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "等待输入的步骤已经变化");
        }
        String fieldKey = text(awaitingNode.parameters(), "fieldKey");
        if (fieldKey == null || fieldKey.isBlank()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Workflow user input field is not configured");
        }
        if (feedbackFields != null
                && !feedbackFields.isEmpty()
                && (feedbackFields.size() != 1 || !feedbackFields.containsKey(fieldKey))) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "Legacy workflow feedback only accepts the current user input field"
            );
        }
        String feedbackValue = feedbackFields == null ? null : feedbackFields.get(fieldKey);
        Map<String, String> normalizedFeedback = Map.of(
                fieldKey,
                feedbackValue == null ? "" : feedbackValue
        );
        AiTask rootTask = taskMapper.findById(rootTaskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND, "任务不存在"));
        if (!Objects.equals(rootTask.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该任务");
        }
        JsonNode rawInput = readInput(run);
        ObjectNode params = rawInput instanceof ObjectNode objectNode
                ? objectNode.deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode revisions = params.has("userRevisions") && params.get("userRevisions").isObject()
                ? (ObjectNode) params.get("userRevisions")
                : objectMapper.createObjectNode();
        normalizedFeedback.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                return;
            }
            String normalized = value == null ? "" : value;
            revisions.put(key, normalized);
            params.put(key, normalized);
        });
        params.set("userRevisions", revisions);
        String paramsJson = writeJson(params);
        run.setInputJson(paramsJson);
        rootTask.setParamsJson(paramsJson);
        taskMapper.updateParamsJson(rootTaskId, paramsJson);
        persistRunStateWithInput(run, RUN_AWAITING_USER, RUN_RUNNING, paramsJson);
        taskMapper.markProcessing(
                rootTaskId,
                rootTask.getProgress() == null ? 12 : rootTask.getProgress(),
                "已收到您的意见，继续生成",
                List.of(TaskStatus.AWAITING_USER.name(), TaskStatus.PROCESSING.name())
        );
        delegatedToolCallLifecycleService.progressForWorkflow(
                run.getRootTaskId(), run.getId(), RUN_RUNNING
        );
        advanceRun(run.getId());
    }

    private WorkflowNodeDef requireLegacyUserInputNode(WorkflowRun run) {
        if (run.getCurrentNodeId() == null || run.getCurrentNodeId().isBlank()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT, "Workflow is not waiting on a user input node");
        }
        WorkflowNodeDef node = loadDsl(run).requireNode(run.getCurrentNodeId());
        if (node.type() != WorkflowNodeDefType.USER_INPUT) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "This workflow action requires the confirmation-token API"
            );
        }
        return node;
    }

    public boolean shouldUseWorkflow(AiTool tool) {
        if (tool == null || tool.getToolCode() == null) {
            return false;
        }
        if (!"ai_comic_drama_agent".equals(tool.getToolCode())) {
            return false;
        }
        return workflowDslService.findPublishedWorkflow(tool.getId()).isPresent();
    }

    @Transactional
    public void startForRootTask(Long rootTaskId) {
        AiTask rootTask = taskMapper.findById(rootTaskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND, "任务不存在"));
        AiTool tool = toolMapper.findById(rootTask.getToolId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TOOL_NOT_FOUND, "工具不存在"));
        ToolWorkflow workflow = workflowDslService.findPublishedWorkflow(tool.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "工具工作流未发布"));
        ToolWorkflowVersion version = requireWorkflowVersion(workflow.getPublishedVersionId());
        WorkflowDsl dsl = parseVersion(version);

        WorkflowRun run = new WorkflowRun();
        run.setUserId(rootTask.getUserId());
        run.setToolId(tool.getId());
        run.setWorkflowId(workflow.getId());
        run.setWorkflowVersion(version.getVersion());
        run.setWorkflowVersionId(version.getId());
        run.setRootTaskId(rootTaskId);
        run.setStatus(RUN_RUNNING);
        run.setInputJson(rootTask.getParamsJson());
        run.setContextJson(writeJson(objectMapper.createObjectNode()));
        workflowRunMapper.insert(run);

        for (String nodeId : dsl.executionOrder()) {
            WorkflowNodeDef node = dsl.requireNode(nodeId);
            WorkflowRunStep step = new WorkflowRunStep();
            step.setRunId(run.getId());
            step.setNodeId(node.id());
            step.setNodeTitle(node.title());
            step.setNodeDefType(node.type().name());
            step.setStatus(STEP_PENDING);
            step.setAttempt(0);
            step.setMaxAttempts(2);
            workflowRunStepMapper.insert(step);
        }

        if (taskMapper.markProcessing(rootTaskId, 5, "AI 漫剧工作流已启动", List.of(TaskStatus.QUEUED.name())) == 0) {
            TaskStateMachine.ensureTransition(taskMapper.findById(rootTaskId).orElseThrow().getStatus(), TaskStatus.PROCESSING.name());
        }
        advanceRun(run.getId());
    }

    @Transactional
    public void onStepAttemptSucceeded(Long stepId, Long stepTaskId, WorkerSuccessRequest request) {
        WorkflowRunStep step = workflowRunStepMapper.selectById(stepId);
        if (step == null || !STEP_SUCCESS.equals(step.getStatus())
                || step.getTaskId() == null || !step.getTaskId().equals(stepTaskId)) {
            return;
        }
        JsonNode output = readStepOutput(step, request);
        WorkflowRun run = requireRun(step.getRunId());
        ObjectNode context = readContext(run);
        context.set(step.getNodeId(), output);
        saveContext(run, context, step.getNodeId());
        updateRootProgress(run, step.getNodeId(), 90, "节点完成：" + step.getNodeId());
        advanceRun(run.getId());
    }

    @Transactional
    public void onStepAttemptsExhausted(Long stepId, WorkerFailedRequest request) {
        WorkflowRunStep step = workflowRunStepMapper.selectById(stepId);
        if (step == null || !STEP_FAILED.equals(step.getStatus())) {
            return;
        }
        failRun(step.getRunId(), WorkflowFailureContract.from(
                request.errorCode(),
                request.errorMessage(),
                request.developerMessage()
        ));
    }

    @Transactional
    public void finishBillingReconciliationFailure(Long runId) {
        WorkflowRun run = workflowRunMapper.selectByIdForUpdate(runId);
        if (run == null) {
            throw new IllegalStateException("Workflow run not found during billing reconciliation: " + runId);
        }
        if (TERMINAL_RUN_STATUSES.contains(run.getStatus())) {
            return;
        }
        if (!RUN_CANCELLING.equals(run.getStatus())
                || !BILLING_RECONCILIATION_FAILED.equals(run.getBillingStatus())) {
            throw new IllegalStateException(
                    "Workflow run is not isolated for billing reconciliation: " + runId
            );
        }
        failRun(run, WorkflowFailureContract.from(
                BILLING_RECONCILIATION_ERROR_CODE,
                BILLING_RECONCILIATION_ERROR_MESSAGE
        ), true);
    }

    public boolean isWorkflowStepTask(JsonNode params) {
        return params != null && params.hasNonNull("workflowStepId");
    }

    @Transactional
    protected void advanceRun(Long runId) {
        WorkflowRun run = requireRun(runId);
        if (!RUN_RUNNING.equals(run.getStatus())) {
            return;
        }
        WorkflowDsl dsl = loadDsl(run);
        Map<String, WorkflowRunStep> steps = stepMap(runId);
        ObjectNode context = readContext(run);
        Set<String> operationHandlerKeys = operationHandlerKeys(readInput(run));
        boolean operationScoped = !operationHandlerKeys.isEmpty();

        for (String nodeId : dsl.executionOrder()) {
            WorkflowRunStep step = steps.get(nodeId);
            if (step == null || !STEP_PENDING.equals(step.getStatus())) {
                continue;
            }
            WorkflowNodeDef node = dsl.requireNode(nodeId);
            if (operationScoped && !operationHandlerKeys.contains(handlerKey(node))) {
                throw new IllegalStateException("Operation-scoped run contains a mismatched step: " + nodeId);
            }
            if (!dependenciesSucceeded(dsl, steps, nodeId, operationScoped)) {
                continue;
            }
            if (node.type().isInline()) {
                executeInlineStep(run, dsl, step, node, context);
                run = requireRun(runId);
                if (RUN_AWAITING_USER.equals(run.getStatus())) {
                    return;
                }
                steps = stepMap(runId);
                context = readContext(run);
                continue;
            }
            if (STEP_QUEUED.equals(step.getStatus()) || STEP_RUNNING.equals(step.getStatus())) {
                return;
            }
            dispatchWorkerStep(run, dsl, step, node, context);
            return;
        }

        boolean allSuccess = steps.values().stream().allMatch(item -> STEP_SUCCESS.equals(item.getStatus()));
        if (allSuccess) {
            completeRun(run);
        }
    }

    private void executeInlineStep(WorkflowRun run,
                                   WorkflowDsl dsl,
                                   WorkflowRunStep step,
                                   WorkflowNodeDef node,
                                   ObjectNode context) {
        ObjectNode output = objectMapper.createObjectNode();
        switch (node.type()) {
            case START -> output.put("status", "ok");
            case FIELD_INPUT -> output.set("fields", readInput(run));
            case USER_INPUT -> {
                String fieldKey = text(node.parameters(), "fieldKey");
                String stageLabel = text(node.parameters(), "stageLabel");
                String feedback = resolveUserFeedback(run, fieldKey);
                if (fieldKey != null && !fieldKey.isBlank() && !hasUserStageResponse(run, fieldKey)) {
                    pauseForUserFeedback(run, step, node, stageLabel, fieldKey);
                    return;
                }
                ObjectNode fields = objectMapper.createObjectNode();
                if (fieldKey != null && feedback != null && !feedback.isBlank()) {
                    fields.put(fieldKey, feedback);
                }
                output.set("fields", fields);
            }
            case USER_CONFIRM -> {
                pauseForConfirmation(run, step, node, context);
                return;
            }
            case CONDITION -> output.set("branch", evaluateConditionBranch(run, context, node));
            case SCENE_LOOP -> output.setAll(buildSceneLoopOutput(run, node));
            case VIDEO_OUTPUT -> output.set("artifacts", collectArtifacts(context));
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Unsupported inline node: " + node.type());
        }
        markStepSuccess(step, output);
        context.set(node.id(), output);
        saveContext(run, context, node.id());
        if (node.type() == WorkflowNodeDefType.VIDEO_OUTPUT) {
            if (markRunSuccess(run)) {
                comicWorkflowResultProjector.projectSucceeded(run.getId(), context);
                finalizeRootTask(run, context);
            }
            return;
        }
        updateRootProgress(run, node.id(), progressForNode(node.type()), "执行节点：" + node.title());
    }

    private void dispatchWorkerStep(WorkflowRun run,
                                    WorkflowDsl dsl,
                                    WorkflowRunStep step,
                                    WorkflowNodeDef node,
                                    ObjectNode context) {
        ObjectNode inputs = buildNodeInputs(dsl, node, context, readInput(run));
        int ready = workflowRunStepMapper.markReadyForDispatch(
                step.getId(),
                step.getRevision() == null ? 0L : step.getRevision(),
                STEP_PENDING,
                step.getCurrentAttemptId(),
                writeJson(inputs),
                LocalDateTime.now()
        );
        if (ready == 0) {
            WorkflowRunStep current = workflowRunStepMapper.selectById(step.getId());
            if (current != null && (STEP_QUEUED.equals(current.getStatus()) || STEP_RUNNING.equals(current.getStatus()))) {
                return;
            }
            throw new IllegalStateException("Workflow step readiness compare-and-set failed: " + step.getId());
        }
        if (workflowStepScheduler.dispatch(step.getId()) == null) {
            return;
        }
        saveContext(run, context, node.id());
        updateRootProgress(run, node.id(), progressForNode(node.type()), "排队执行：" + node.title());
    }

    private void finalizeRootTask(WorkflowRun run, ObjectNode context) {
        AiTask rootTask = taskMapper.findById(run.getRootTaskId()).orElse(null);
        if (rootTask != null && TaskStatus.SUCCESS.name().equals(rootTask.getStatus())) {
            return;
        }
        JsonNode compose = context.path("compose");
        if (compose.isMissingNode()) {
            compose = context.path("subtitle");
        }
        String finalVideoUrl = text(compose, "finalVideoUrl");
        if (finalVideoUrl == null) {
            finalVideoUrl = text(compose, "videoUrl");
        }
        if (finalVideoUrl == null) {
            JsonNode clipVideo = context.path("clip-video");
            if (!clipVideo.isMissingNode()) {
                finalVideoUrl = text(clipVideo, "videoUrl");
                if (finalVideoUrl == null) {
                    JsonNode clips = clipVideo.path("clips");
                    if (clips.isArray() && !clips.isEmpty()) {
                        finalVideoUrl = text(clips.get(0), "videoUrl");
                    }
                }
            }
        }
        String markdown = text(compose, "markdown");
        if (markdown == null || markdown.isBlank()) {
            markdown = buildFallbackMarkdown(context, finalVideoUrl);
        }
        if (finalVideoUrl != null && !finalVideoUrl.isBlank()) {
            workflowRootTaskFinalizer.finalizeSuccess(
                    run.getRootTaskId(),
                    "VIDEO",
                    buildWorkflowVideoResultContent(context, compose, finalVideoUrl, markdown)
            );
            return;
        }
        workflowRootTaskFinalizer.finalizeSuccess(run.getRootTaskId(), markdown);
    }

    private void completeRun(WorkflowRun run) {
        if (RUN_SUCCESS.equals(run.getStatus())) {
            return;
        }
        ObjectNode context = readContext(run);
        if (markRunSuccess(run)) {
            comicWorkflowResultProjector.projectSucceeded(run.getId(), context);
            finalizeRootTask(run, context);
        }
    }

    private void failRun(Long runId, WorkflowFailureContract failure) {
        failRun(requireRun(runId), failure, false);
    }

    private void failRun(WorkflowRun run,
                         WorkflowFailureContract failure,
                         boolean allowCancelling) {
        if (TERMINAL_RUN_STATUSES.contains(run.getStatus())
                || (RUN_CANCELLING.equals(run.getStatus()) && !allowCancelling)) {
            return;
        }
        if (!FAILABLE_RUN_STATUSES.contains(run.getStatus())
                && !(allowCancelling && RUN_CANCELLING.equals(run.getStatus()))) {
            throw new IllegalStateException("Workflow run cannot fail from status: " + run.getStatus());
        }
        String expectedStatus = run.getStatus();
        run.setErrorCode(failure.errorCode());
        run.setErrorMessage(failure.developerMessage());
        run.setUserMessage(failure.userMessage());
        run.setDeveloperMessage(failure.developerMessage());
        run.setFailureTraceId(failure.failureTraceId());
        run.setFinishedAt(LocalDateTime.now());
        persistRunState(run, expectedStatus, RUN_FAILED);
        comicWorkflowResultProjector.projectFailed(run.getId());
        AiTask rootTask = taskMapper.findById(run.getRootTaskId())
                .orElseThrow(() -> new IllegalStateException("Workflow root task not found: " + run.getRootTaskId()));
        creditService.release(
                rootTask.getUserId(),
                CreditSourceType.TASK,
                rootTask.getId(),
                rootTask.getEstimatedCreditCost()
        );
        if (taskMapper.markFailedWithContract(
                rootTask.getId(),
                TaskStatus.FAILED.name(),
                failure.errorCode(),
                failure.userMessage(),
                failure.developerMessage(),
                failure.failureTraceId(),
                null,
                null,
                List.of(
                        TaskStatus.QUEUED.name(),
                        TaskStatus.PROCESSING.name(),
                        TaskStatus.AWAITING_USER.name(),
                        TaskStatus.AWAITING_FUNDS.name()
                )
        ) != 1) {
            throw new IllegalStateException("Workflow root task could not fail: " + rootTask.getId());
        }
        delegatedToolCallLifecycleService.finishForWorkflow(
                run.getRootTaskId(),
                run.getId(),
                RUN_FAILED,
                run.getErrorMessage()
        );
    }

    private boolean markRunSuccess(WorkflowRun run) {
        if (RUN_SUCCESS.equals(run.getStatus())) {
            return false;
        }
        if (TERMINAL_RUN_STATUSES.contains(run.getStatus()) || RUN_CANCELLING.equals(run.getStatus())) {
            return false;
        }
        if (!RUN_RUNNING.equals(run.getStatus())) {
            throw new IllegalStateException("Workflow run cannot succeed from status: " + run.getStatus());
        }
        run.setFinishedAt(LocalDateTime.now());
        run.setErrorCode(null);
        run.setErrorMessage(null);
        run.setUserMessage(null);
        run.setDeveloperMessage(null);
        run.setFailureTraceId(null);
        persistRunState(run, RUN_RUNNING, RUN_SUCCESS);
        return true;
    }

    private ObjectNode buildNodeInputs(WorkflowDsl dsl, WorkflowNodeDef node, ObjectNode context, JsonNode formInput) {
        JsonNode workerFormInput = workerVisibleInput(formInput);
        ObjectNode inputs = objectMapper.createObjectNode();
        inputs.set("form", workerFormInput);
        ObjectNode operationInput = workerFormInput != null && workerFormInput.isObject()
                ? ((ObjectNode) workerFormInput).deepCopy()
                : objectMapper.createObjectNode();
        if (workerFormInput != null && !workerFormInput.isNull() && !workerFormInput.isObject()) {
            operationInput.set("value", workerFormInput);
        }
        for (WorkflowEdgeDef edge : dsl.edges()) {
            if (!node.id().equals(edge.target())) {
                continue;
            }
            JsonNode upstream = context.get(edge.source());
            if (upstream != null && !upstream.isMissingNode()) {
                inputs.set(edge.source(), upstream);
                operationInput.set(edge.source(), upstream);
            }
        }
        // Stable handlers receive the project item plus outputs from selected upstream operations.
        inputs.set("operationInput", operationInput);
        if (node.parameters() != null && !node.parameters().isMissingNode()) {
            inputs.set("parameters", node.parameters());
        }
        removeInternalRequestIdentity(inputs);
        return inputs;
    }

    private JsonNode workerVisibleInput(JsonNode input) {
        if (input == null || !input.isObject()) {
            return input;
        }
        ObjectNode visible = ((ObjectNode) input).deepCopy();
        visible.remove("__workflowRequestIdentity");
        return visible;
    }

    private void removeInternalRequestIdentity(JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isObject()) {
            ObjectNode object = (ObjectNode) value;
            object.remove("__workflowRequestIdentity");
            object.elements().forEachRemaining(this::removeInternalRequestIdentity);
            return;
        }
        if (value.isArray()) {
            value.elements().forEachRemaining(this::removeInternalRequestIdentity);
        }
    }

    private boolean dependenciesSucceeded(WorkflowDsl dsl,
                                          Map<String, WorkflowRunStep> steps,
                                          String nodeId,
                                          boolean operationScoped) {
        List<WorkflowEdgeDef> incoming = dsl.edges().stream().filter(edge -> nodeId.equals(edge.target())).toList();
        if (incoming.isEmpty()) {
            return true;
        }
        for (WorkflowEdgeDef edge : incoming) {
            WorkflowRunStep upstream = steps.get(edge.source());
            if (upstream == null && operationScoped) {
                continue;
            }
            if (upstream == null || !STEP_SUCCESS.equals(upstream.getStatus())) {
                return false;
            }
        }
        return true;
    }

    private Set<String> operationHandlerKeys(JsonNode input) {
        if (input == null || input.isNull()) {
            return Set.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        JsonNode array = input.get("operationHandlerKeys");
        if (array != null && array.isArray()) {
            array.forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) {
                    keys.add(value.asText().trim());
                }
            });
        }
        String single = text(input, "operationHandlerKey");
        if (single != null) {
            keys.add(single);
        }
        return Set.copyOf(keys);
    }

    private String handlerKey(WorkflowNodeDef node) {
        String key = text(node.parameters(), "handlerKey");
        return key == null ? text(node.parameters(), "operation") : key;
    }

    private Map<String, WorkflowRunStep> stepMap(Long runId) {
        Map<String, WorkflowRunStep> map = new HashMap<>();
        for (WorkflowRunStep step : workflowRunStepMapper.selectByRunId(runId)) {
            map.put(step.getNodeId(), step);
        }
        return map;
    }

    private WorkflowRun requireRun(Long runId) {
        WorkflowRun run = workflowRunMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工作流运行不存在");
        }
        return run;
    }

    private WorkflowDsl loadDsl(WorkflowRun run) {
        if (run.getWorkflowVersionId() != null) {
            return parseVersion(requireWorkflowVersion(run.getWorkflowVersionId()));
        }
        ToolWorkflowVersion historicalVersion = run.getWorkflowId() == null || run.getWorkflowVersion() == null
                ? null
                : toolWorkflowVersionMapper.selectByWorkflowIdAndVersion(
                        run.getWorkflowId(),
                        run.getWorkflowVersion()
                );
        if (historicalVersion == null) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "Workflow version snapshot does not exist: runId=%s, workflowId=%s, version=%s"
                            .formatted(run.getId(), run.getWorkflowId(), run.getWorkflowVersion())
            );
        }
        return parseVersion(historicalVersion);
    }

    private ToolWorkflowVersion requireWorkflowVersion(Long versionId) {
        ToolWorkflowVersion version = versionId == null ? null : toolWorkflowVersionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Workflow version does not exist");
        }
        return version;
    }

    private WorkflowDsl parseVersion(ToolWorkflowVersion version) {
        return workflowDslService.parse(
                version.getNodesJson(),
                version.getEdgesJson(),
                version.getConfigJson()
        );
    }

    private JsonNode readInput(WorkflowRun run) {
        try {
            JsonNode parsed = objectMapper.readTree(run.getInputJson());
            return parsed != null && parsed.isTextual()
                    ? objectMapper.readTree(parsed.asText())
                    : parsed;
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private ObjectNode readContext(WorkflowRun run) {
        try {
            JsonNode node = objectMapper.readTree(run.getContextJson());
            if (node instanceof ObjectNode objectNode) {
                return objectNode;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return objectMapper.createObjectNode();
    }

    private void saveContext(WorkflowRun run, ObjectNode context, String currentNodeId) {
        if (!RUN_RUNNING.equals(run.getStatus()) && !RUN_AWAITING_USER.equals(run.getStatus())) {
            throw new IllegalStateException("Workflow run context is immutable in status: " + run.getStatus());
        }
        run.setContextJson(writeJson(context));
        run.setCurrentNodeId(currentNodeId);
        if (RUN_AWAITING_USER.equals(run.getStatus())) {
            persistRunStateWithInput(run, RUN_AWAITING_USER, RUN_AWAITING_USER, run.getInputJson());
            return;
        }
        persistRunState(run, RUN_RUNNING, RUN_RUNNING);
    }

    private void markStepSuccess(WorkflowRunStep step, JsonNode output) {
        if (!STEP_PENDING.equals(step.getStatus())) {
            throw new IllegalStateException("Inline workflow step cannot succeed from status: " + step.getStatus());
        }
        long expectedRevision = step.getRevision() == null ? 0L : step.getRevision();
        String outputJson = writeJson(output);
        LocalDateTime finishedAt = LocalDateTime.now();
        if (workflowRunStepMapper.completeInlineStep(
                step.getId(),
                expectedRevision,
                STEP_PENDING,
                step.getCurrentAttemptId(),
                outputJson,
                finishedAt
        ) != 1) {
            throw new IllegalStateException("Inline workflow step completion compare-and-set failed: " + step.getId());
        }
        step.setStatus(STEP_SUCCESS);
        step.setRevision(expectedRevision + 1);
        step.setOutputJson(outputJson);
        step.setErrorMessage(null);
        step.setFinishedAt(finishedAt);
    }

    private void persistRunState(WorkflowRun run, String expectedStatus, String nextStatus) {
        long expectedRevision = run.getRevision() == null ? 0L : run.getRevision();
        if (workflowRunMapper.updateRunStateWithContract(
                run.getId(),
                expectedRevision,
                expectedStatus,
                nextStatus,
                run.getContextJson(),
                run.getCurrentNodeId(),
                run.getErrorCode(),
                run.getUserMessage(),
                run.getDeveloperMessage(),
                run.getFailureTraceId(),
                run.getFinishedAt()
        ) != 1) {
            throw new IllegalStateException("Workflow run state compare-and-set failed: " + run.getId());
        }
        run.setStatus(nextStatus);
        run.setRevision(expectedRevision + 1);
    }

    private void persistRunStateWithInput(WorkflowRun run,
                                          String expectedStatus,
                                          String nextStatus,
                                          String inputJson) {
        long expectedRevision = run.getRevision() == null ? 0L : run.getRevision();
        if (workflowRunMapper.updateRunStateWithInputContract(
                run.getId(),
                expectedRevision,
                expectedStatus,
                nextStatus,
                inputJson,
                run.getContextJson(),
                run.getCurrentNodeId(),
                run.getErrorCode(),
                run.getUserMessage(),
                run.getDeveloperMessage(),
                run.getFailureTraceId(),
                run.getFinishedAt()
        ) != 1) {
            throw new IllegalStateException("Workflow run input compare-and-set failed: " + run.getId());
        }
        run.setStatus(nextStatus);
        run.setRevision(expectedRevision + 1);
        run.setInputJson(inputJson);
    }

    private JsonNode parseWorkflowOutput(WorkerSuccessRequest request) {
        try {
            if (request.contentText() != null && request.contentText().startsWith("{")) {
                return objectMapper.readTree(request.contentText());
            }
        } catch (Exception exception) {
            LOGGER.warn("workflow step output is not JSON, wrapping as text");
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("contentText", request.contentText());
        node.put("resourceType", request.resourceType());
        return node;
    }

    private JsonNode readStepOutput(WorkflowRunStep step, WorkerSuccessRequest request) {
        try {
            if (step.getOutputJson() != null && !step.getOutputJson().isBlank()) {
                return objectMapper.readTree(step.getOutputJson());
            }
        } catch (Exception exception) {
            LOGGER.warn("workflow step output is not JSON, rebuilding from callback stepId={}", step.getId());
        }
        return parseWorkflowOutput(request);
    }

    /**
     * Snapshot upstream node outputs for the terminal video_output node.
     * Must not return {@code context} itself — that creates a self-reference
     * (artifacts → context → video-output → artifacts) and Jackson fails at ~1000 nesting depth.
     */
    private JsonNode collectArtifacts(ObjectNode context) {
        ObjectNode artifacts = objectMapper.createObjectNode();
        context.fields().forEachRemaining(entry -> {
            if (!"video-output".equals(entry.getKey())) {
                artifacts.set(entry.getKey(), entry.getValue());
            }
        });
        return artifacts;
    }

    private JsonNode readUserInputStage(WorkflowRun run, WorkflowNodeDef node) {
        JsonNode input = readInput(run);
        String fieldKey = text(node.parameters(), "fieldKey");
        if (fieldKey == null || fieldKey.isBlank()) {
            return input;
        }
        ObjectNode staged = objectMapper.createObjectNode();
        if (input != null && input.isObject()) {
            staged.setAll((ObjectNode) input);
        }
        JsonNode revisions = input == null ? null : input.path("userRevisions");
        if (revisions != null && revisions.isObject() && revisions.has(fieldKey)) {
            staged.set(fieldKey, revisions.get(fieldKey));
        }
        return staged;
    }

    private JsonNode collectConfirmPreview(ObjectNode context, WorkflowNodeDef node) {
        String sourceNodeId = text(node.parameters(), "sourceNodeId");
        if (sourceNodeId != null && context.has(sourceNodeId)) {
            return context.get(sourceNodeId);
        }
        if (context.has("script-planner")) {
            return context.get("script-planner");
        }
        if (context.has("keyframe")) {
            return context.get("keyframe");
        }
        return objectMapper.createObjectNode();
    }

    /**
     * 分镜循环节点（scene_loop）：根据用户时长字段自动计算分镜数。
     * 例如 30s / 每镜 5s = 6 镜。输出 sceneCount 与 indices，
     * 下游模型节点（关键帧/配音/图生视频）按该数量逐镜生成并最终合成。
     */
    private ObjectNode buildSceneLoopOutput(WorkflowRun run, WorkflowNodeDef node) {
        String durationField = text(node.parameters(), "durationField");
        if (durationField == null || durationField.isBlank()) {
            durationField = "episodeLength";
        }
        int secondsPerScene = intParam(node.parameters(), "secondsPerScene", 5);
        int maxScenes = intParam(node.parameters(), "maxScenes", 18);
        JsonNode input = readInput(run);
        String rawDuration = text(input, durationField);
        double seconds = parseSeconds(rawDuration, 30);
        int sceneCount = (int) Math.round(seconds / Math.max(secondsPerScene, 1));
        sceneCount = Math.max(1, Math.min(sceneCount, Math.max(maxScenes, 1)));
        ObjectNode output = objectMapper.createObjectNode();
        output.put("sceneCount", sceneCount);
        output.put("secondsPerScene", secondsPerScene);
        output.put("totalSeconds", sceneCount * secondsPerScene);
        output.put("durationField", durationField);
        var indices = objectMapper.createArrayNode();
        for (int index = 1; index <= sceneCount; index++) {
            indices.add(index);
        }
        output.set("indices", indices);
        return output;
    }

    private int intParam(JsonNode parameters, String field, int fallback) {
        if (parameters == null || parameters.isMissingNode()) {
            return fallback;
        }
        JsonNode value = parameters.get(field);
        if (value != null && value.isNumber()) {
            return value.asInt(fallback);
        }
        if (value != null && value.isTextual()) {
            try {
                return Integer.parseInt(value.asText().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private double parseSeconds(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String digits = raw.replaceAll("[^0-9.]", "");
        if (digits.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(digits);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private JsonNode evaluateConditionBranch(WorkflowRun run, ObjectNode context, WorkflowNodeDef node) {
        String revisionField = text(node.parameters(), "revisionField");
        if (revisionField == null || revisionField.isBlank()) {
            revisionField = "scriptRevision";
        }
        String revisionText = text(context.path("user-input-script").path("fields"), revisionField);
        if (revisionText == null || revisionText.isBlank()) {
            revisionText = text(readInput(run), revisionField);
        }
        ObjectNode result = objectMapper.createObjectNode();
        boolean needsRevision = revisionText != null && !revisionText.isBlank();
        result.put("matched", needsRevision);
        result.put("branch", needsRevision ? "revise" : "continue");
        return result;
    }

    private String buildFallbackMarkdown(ObjectNode context, String finalVideoUrl) {
        JsonNode script = context.path("script-planner");
        String title = text(script, "sceneTitle");
        if (title == null) {
            title = text(context.path("field-input").path("fields"), "storyTheme");
        }
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(title == null ? "AI 漫剧成片" : title).append("\n\n");
        if (finalVideoUrl != null && !finalVideoUrl.isBlank()) {
            builder.append("[video](").append(finalVideoUrl).append(")\n\n");
        }
        builder.append("> AI 制作\n");
        return builder.toString();
    }

    private String buildWorkflowVideoResultContent(ObjectNode context,
                                                   JsonNode compose,
                                                   String finalVideoUrl,
                                                   String markdown) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("resourceType", "VIDEO");
        result.put("finalVideoUrl", finalVideoUrl);
        result.put("videoUrl", finalVideoUrl);
        String coverUrl = firstNonBlank(
                text(compose, "coverUrl"),
                text(compose, "imageUrl"),
                text(context.path("keyframe"), "coverUrl"),
                text(context.path("keyframe"), "imageUrl")
        );
        if (coverUrl == null) {
            JsonNode images = context.path("keyframe").path("images");
            if (images.isArray() && !images.isEmpty()) {
                coverUrl = firstNonBlank(text(images.get(0), "imageUrl"), text(images.get(0), "url"));
            }
        }
        if (coverUrl != null && !coverUrl.isBlank()) {
            result.put("coverUrl", coverUrl);
        }
        String subtitleUrl = text(compose, "subtitleUrl");
        if (subtitleUrl != null && !subtitleUrl.isBlank()) {
            result.put("subtitleUrl", subtitleUrl);
        }
        JsonNode segments = compose.path("segments");
        if (segments.isArray() && !segments.isEmpty()) {
            result.set("segments", segments);
        }
        JsonNode sceneCount = compose.path("sceneCount");
        if (sceneCount.isNumber()) {
            result.set("sceneCount", sceneCount);
        }
        if (markdown != null && !markdown.isBlank()) {
            result.put("markdown", markdown);
        }
        return writeJson(result);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void pauseForUserFeedback(WorkflowRun run,
                                      WorkflowRunStep step,
                                      WorkflowNodeDef node,
                                      String stageLabel,
                                      String fieldKey) {
        String label = stageLabel == null || stageLabel.isBlank() ? fieldKey : stageLabel;
        String message = "等待您的" + label + "（可留空直接继续）";
        run.setCurrentNodeId(node.id());
        run.setErrorMessage(null);
        run.setFinishedAt(null);
        persistRunState(run, RUN_RUNNING, RUN_AWAITING_USER);
        taskMapper.markAwaitingUser(
                run.getRootTaskId(),
                progressForNode(node.type()),
                limit(message, 240),
                List.of(TaskStatus.PROCESSING.name(), TaskStatus.QUEUED.name(), TaskStatus.AWAITING_USER.name())
        );
        delegatedToolCallLifecycleService.progressForWorkflow(
                run.getRootTaskId(), run.getId(), RUN_AWAITING_USER
        );
        LOGGER.info("workflow paused for user input runId={} nodeId={} fieldKey={}", run.getId(), node.id(), fieldKey);
    }

    private void pauseForConfirmation(WorkflowRun run,
                                      WorkflowRunStep step,
                                      WorkflowNodeDef node,
                                      ObjectNode context) {
        ObjectNode preview = objectMapper.createObjectNode();
        preview.set("preview", collectConfirmPreview(context, node));
        confirmationTokenService.issue(run, step, node.parameters());
        if (workflowRunStepMapper.markAwaitingUser(
                step.getId(), step.getRevision() == null ? 0L : step.getRevision(), writeJson(preview)
        ) != 1) {
            throw new IllegalStateException("Workflow confirmation step compare-and-set failed: " + step.getId());
        }
        if (workflowRunMapper.markAwaitingUser(
                run.getId(), run.getRevision() == null ? 0L : run.getRevision(), node.id(), step.getId()
        ) != 1) {
            throw new IllegalStateException("Workflow run could not pause for confirmation: " + run.getId());
        }
        if (taskMapper.markAwaitingUser(
                run.getRootTaskId(), progressForNode(node.type()), "等待您的确认",
                List.of(TaskStatus.PROCESSING.name(), TaskStatus.QUEUED.name())
        ) != 1) {
            throw new IllegalStateException("Workflow root task could not pause for confirmation: " + run.getRootTaskId());
        }
        delegatedToolCallLifecycleService.progressForWorkflow(
                run.getRootTaskId(), run.getId(), RUN_AWAITING_USER
        );
        LOGGER.info("workflow paused for confirmation runId={} nodeId={} stepId={}",
                run.getId(), node.id(), step.getId());
    }

    private boolean hasUserStageResponse(WorkflowRun run, String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) {
            return false;
        }
        JsonNode input = readInput(run);
        if (input == null) {
            return false;
        }
        if (input.has(fieldKey)) {
            return true;
        }
        return input.path("userRevisions").has(fieldKey);
    }

    private String resolveUserFeedback(WorkflowRun run, String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) {
            return null;
        }
        JsonNode input = readInput(run);
        if (input == null) {
            return null;
        }
        if (input.has(fieldKey)) {
            return input.get(fieldKey).asText("");
        }
        JsonNode revisions = input.path("userRevisions");
        if (revisions.has(fieldKey)) {
            return revisions.get(fieldKey).asText("");
        }
        return null;
    }

    public JsonNode buildWorkflowPreview(Long rootTaskId) {
        WorkflowRun run = workflowRunMapper.findByRootTaskId(rootTaskId).orElse(null);
        if (run == null) {
            return null;
        }
        ObjectNode context = readContext(run);
        ObjectNode preview = objectMapper.createObjectNode();
        String fieldKey = resolveAwaitingFieldKey(run);
        String stageLabel = null;
        if (run.getCurrentNodeId() != null && !run.getCurrentNodeId().isBlank()) {
            try {
                WorkflowDsl dsl = loadDsl(run);
                WorkflowNodeDef node = dsl.requireNode(run.getCurrentNodeId());
                stageLabel = text(node.parameters(), "stageLabel");
                if (fieldKey == null || fieldKey.isBlank()) {
                    fieldKey = text(node.parameters(), "fieldKey");
                }
            } catch (Exception exception) {
                LOGGER.warn("build workflow preview node lookup failed runId={}", run.getId(), exception);
            }
        }
        if (stageLabel != null && !stageLabel.isBlank()) {
            preview.put("stageLabel", stageLabel);
        }
        if (fieldKey != null && !fieldKey.isBlank()) {
            preview.put("fieldKey", fieldKey);
        }
        if (run.getCurrentNodeId() != null && !run.getCurrentNodeId().isBlank()) {
            preview.put("currentNodeId", run.getCurrentNodeId());
        }

        JsonNode script = summarizeScriptPreview(context.path("script-planner"));
        if (script != null && !script.isMissingNode() && !script.isNull()) {
            preview.set("script", script);
        }
        String imageUrl = text(context.path("keyframe"), "imageUrl");
        if (imageUrl != null && !imageUrl.isBlank()) {
            preview.put("imageUrl", imageUrl);
        }
        JsonNode keyframeImages = summarizeSceneMediaList(context.path("keyframe").path("images"), "imageUrl");
        if (keyframeImages != null) {
            preview.set("images", keyframeImages);
        }
        JsonNode referenceAssets = summarizeReferenceAssets(context.path("keyframe").path("referenceAssets"));
        if (referenceAssets != null) {
            preview.set("referenceAssets", referenceAssets);
        }
        String audioUrl = text(context.path("tts"), "audioUrl");
        if (audioUrl == null || audioUrl.isBlank()) {
            audioUrl = text(context.path("tts"), "audioDataUrl");
        }
        if (audioUrl != null && !audioUrl.isBlank()) {
            preview.put("audioUrl", audioUrl);
        }
        JsonNode sceneAudios = summarizeSceneMediaList(context.path("tts").path("audios"), "audioUrl");
        if (sceneAudios != null) {
            preview.set("audios", sceneAudios);
        }
        String videoUrl = text(context.path("clip-video"), "videoUrl");
        if (videoUrl != null && !videoUrl.isBlank()) {
            preview.put("videoUrl", videoUrl);
        }
        JsonNode sceneClips = summarizeSceneMediaList(context.path("clip-video").path("clips"), "videoUrl");
        if (sceneClips != null) {
            preview.set("clips", sceneClips);
        }
        JsonNode compose = context.path("compose");
        if (compose.isMissingNode()) {
            compose = context.path("subtitle");
        }
        String finalVideoUrl = text(compose, "finalVideoUrl");
        if (finalVideoUrl == null || finalVideoUrl.isBlank()) {
            finalVideoUrl = text(compose, "videoUrl");
        }
        if (finalVideoUrl != null && !finalVideoUrl.isBlank()) {
            preview.put("finalVideoUrl", finalVideoUrl);
        }
        applyStagePreviewFilter(preview, fieldKey);
        return preview.isEmpty() ? null : preview;
    }

    /**
     * Keep only the artifact relevant to the current user-input stage so the trial UI
     * shows script at script stage, keyframe at scene stage, audio at BGM stage, etc.
     */
    private void applyStagePreviewFilter(ObjectNode preview, String fieldKey) {
        if (fieldKey == null || fieldKey.isBlank()) {
            return;
        }
        switch (fieldKey) {
            case "scriptFeedback", "storyboardFeedback" -> {
                preview.remove("imageUrl");
                preview.remove("images");
                preview.remove("referenceAssets");
                preview.remove("audioUrl");
                preview.remove("audios");
                preview.remove("videoUrl");
                preview.remove("finalVideoUrl");
                preview.remove("clips");
            }
            case "sceneFeedback" -> {
                // 场景图阶段保留 script.scenes（用于逐镜对照），仅过滤音视频
                preview.remove("audioUrl");
                preview.remove("audios");
                preview.remove("videoUrl");
                preview.remove("finalVideoUrl");
                preview.remove("clips");
            }
            case "bgmFeedback" -> {
                preview.remove("script");
                preview.remove("imageUrl");
                preview.remove("images");
                preview.remove("referenceAssets");
                preview.remove("videoUrl");
                preview.remove("finalVideoUrl");
                preview.remove("clips");
            }
            default -> {
                // no-op
            }
        }
    }

    /** Shallow copy of per-scene media list (keyframe images / tts audios) for preview responses. */
    private JsonNode summarizeSceneMediaList(JsonNode list, String urlField) {
        if (list == null || !list.isArray() || list.isEmpty()) {
            return null;
        }
        var result = objectMapper.createArrayNode();
        for (JsonNode item : list) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String url = text(item, urlField);
            if (url == null || url.isBlank()) {
                continue;
            }
            ObjectNode entry = objectMapper.createObjectNode();
            JsonNode sceneIndex = item.get("sceneIndex");
            if (sceneIndex != null && sceneIndex.isNumber()) {
                entry.put("sceneIndex", sceneIndex.asInt());
            }
            entry.put(urlField, url);
            copyTextField(entry, item, "speechText");
            copyArrayField(entry, item, "referenceAssetIds");
            copyArrayField(entry, item, "referenceImages");
            result.add(entry);
        }
        return result.isEmpty() ? null : result;
    }

    /** Shallow script preview for API responses — avoids deep LLM JSON blowing Jackson nesting limits. */
    private JsonNode summarizeReferenceAssets(JsonNode list) {
        if (list == null || !list.isArray() || list.isEmpty()) {
            return null;
        }
        var result = objectMapper.createArrayNode();
        for (JsonNode item : list) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String imageUrl = text(item, "imageUrl");
            if (imageUrl == null || imageUrl.isBlank()) {
                continue;
            }
            ObjectNode entry = objectMapper.createObjectNode();
            copyTextField(entry, item, "assetType");
            copyTextField(entry, item, "assetId");
            copyTextField(entry, item, "name");
            entry.put("imageUrl", imageUrl);
            result.add(entry);
        }
        return result.isEmpty() ? null : result;
    }

    private JsonNode summarizeScriptPreview(JsonNode script) {
        if (script == null || script.isMissingNode() || script.isNull()) {
            return null;
        }
        if (script.isTextual()) {
            ObjectNode text = objectMapper.createObjectNode();
            text.put("contentText", script.asText());
            return text;
        }
        JsonNode source = script;
        String embedded = text(script, "contentText");
        if (embedded != null && embedded.trim().startsWith("{")) {
            try {
                source = objectMapper.readTree(embedded);
            } catch (Exception exception) {
                LOGGER.debug("script contentText is not JSON, using node as-is");
            }
        }
        ObjectNode summary = objectMapper.createObjectNode();
        copyTextField(summary, source, "contentText");
        copyTextField(summary, source, "sceneTitle");
        copyTextField(summary, source, "sceneDescription");
        copyTextField(summary, source, "dialogue");
        copyTextField(summary, source, "narration");
        copyTextField(summary, source, "subtitleZh");
        copyTextField(summary, source, "subtitleEn");
        copyTextField(summary, source, "presenterGender");
        copyTextField(summary, source, "script");
        copyTextField(summary, source, "markdown");
        copyTextField(summary, source, "title");
        copyTextField(summary, source, "synopsis");
        copyTextField(summary, source, "screenplay");
        copyTextField(summary, source, "genre");
        copyArrayField(summary, source, "characters");
        copyArrayField(summary, source, "props");
        copyArrayField(summary, source, "locations");
        JsonNode sceneCount = source.get("sceneCount");
        if (sceneCount != null && sceneCount.isNumber()) {
            summary.put("sceneCount", sceneCount.asInt());
        }
        JsonNode scenes = source.get("scenes");
        if (scenes != null && scenes.isArray() && !scenes.isEmpty()) {
            var sceneList = objectMapper.createArrayNode();
            for (JsonNode scene : scenes) {
                if (scene == null || !scene.isObject()) {
                    continue;
                }
                ObjectNode sceneSummary = objectMapper.createObjectNode();
                JsonNode index = scene.get("index");
                if (index != null && index.isNumber()) {
                    sceneSummary.put("index", index.asInt());
                }
                copyTextField(sceneSummary, scene, "sceneTitle");
                copyTextField(sceneSummary, scene, "sceneDescription");
                copyTextField(sceneSummary, scene, "dialogue");
                copyTextField(sceneSummary, scene, "narration");
                copyTextField(sceneSummary, scene, "subtitleZh");
                copyTextField(sceneSummary, scene, "subtitleEn");
                copyTextField(sceneSummary, scene, "presenterGender");
                copyTextField(sceneSummary, scene, "characterScene");
                copyTextField(sceneSummary, scene, "cameraLanguage");
                copyTextField(sceneSummary, scene, "plot");
                copyTextField(sceneSummary, scene, "voiceDirection");
                copyTextField(sceneSummary, scene, "textToVideoPrompt");
                copyTextField(sceneSummary, scene, "imageToVideoPrompt");
                copyTextField(sceneSummary, scene, "multiImageVideoPrompt");
                copyTextField(sceneSummary, scene, "keyframeTransitionPrompt");
                copyArrayField(sceneSummary, scene, "characterRefs");
                copyArrayField(sceneSummary, scene, "propRefs");
                copyArrayField(sceneSummary, scene, "locationRefs");
                JsonNode durationSeconds = scene.get("durationSeconds");
                if (durationSeconds != null && durationSeconds.isNumber()) {
                    sceneSummary.put("durationSeconds", durationSeconds.asInt());
                }
                if (!sceneSummary.isEmpty()) {
                    sceneList.add(sceneSummary);
                }
            }
            if (!sceneList.isEmpty()) {
                summary.set("scenes", sceneList);
            }
        }
        if (!summary.isEmpty()) {
            return summary;
        }
        return script.isObject() ? script : null;
    }

    private void copyTextField(ObjectNode target, JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isTextual()) {
            target.put(field, value.asText());
        }
    }

    private void copyArrayField(ObjectNode target, JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isArray()) {
            target.set(field, value);
        }
    }

    private String resolveAwaitingFieldKey(WorkflowRun run) {
        if (run.getCurrentNodeId() == null || run.getCurrentNodeId().isBlank()) {
            return null;
        }
        try {
            WorkflowDsl dsl = loadDsl(run);
            WorkflowNodeDef node = dsl.requireNode(run.getCurrentNodeId());
            return text(node.parameters(), "fieldKey");
        } catch (Exception exception) {
            LOGGER.warn("resolve awaiting field key failed runId={}", run.getId(), exception);
            return null;
        }
    }

    private void updateRootProgress(WorkflowRun run, String nodeId, int progress, String message) {
        run.setCurrentNodeId(nodeId);
        if (RUN_AWAITING_USER.equals(run.getStatus())) {
            return;
        }
        if (!RUN_RUNNING.equals(run.getStatus())) {
            throw new IllegalStateException("Workflow run progress is immutable in status: " + run.getStatus());
        }
        taskMapper.markProcessing(
                run.getRootTaskId(),
                progress,
                limit(message, 240),
                List.of(TaskStatus.PROCESSING.name(), TaskStatus.QUEUED.name(), TaskStatus.AWAITING_USER.name())
        );
        persistRunState(run, RUN_RUNNING, RUN_RUNNING);
    }

    private int progressForNode(WorkflowNodeDefType type) {
        return switch (type) {
            case FIELD_INPUT, USER_INPUT -> 10;
            case USER_CONFIRM -> 18;
            case CONDITION, SCENE_LOOP -> 20;
            case LLM_TEXT -> 22;
            case IMAGE_MODEL -> 38;
            case TTS_MODEL -> 52;
            case VIDEO_MODEL -> 72;
            case SUBTITLE -> 88;
            case VIDEO_OUTPUT -> 96;
            default -> 15;
        };
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize workflow JSON", exception);
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
