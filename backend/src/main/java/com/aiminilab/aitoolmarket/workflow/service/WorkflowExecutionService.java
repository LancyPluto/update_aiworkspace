package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.mapper.AgentModelConfigMapper;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.task.service.TaskOutboxService;
import com.aiminilab.aitoolmarket.task.service.TaskStateMachine;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflow;
import com.aiminilab.aitoolmarket.tool.mapper.ToolMapper;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDsl;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowEdgeDef;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowNodeDef;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowNodeDefType;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class WorkflowExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowExecutionService.class);
    private static final String RUN_RUNNING = "RUNNING";
    private static final String RUN_AWAITING_USER = "AWAITING_USER";
    private static final String RUN_SUCCESS = "SUCCESS";
    private static final String RUN_FAILED = "FAILED";
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
    private final AgentModelConfigMapper agentModelConfigMapper;
    private final ModelExecutionSnapshotService modelExecutionSnapshotService;
    private final TaskOutboxService taskOutboxService;
    private final CreditService creditService;
    private final TaskCreditEstimateService taskCreditEstimateService;
    private final BillingService billingService;
    private final ObjectMapper objectMapper;
    private final WorkflowRootTaskFinalizer workflowRootTaskFinalizer;

    public WorkflowExecutionService(WorkflowDslService workflowDslService,
                                    WorkflowRunMapper workflowRunMapper,
                                    WorkflowRunStepMapper workflowRunStepMapper,
                                    TaskMapper taskMapper,
                                    ToolMapper toolMapper,
                                    AgentModelConfigMapper agentModelConfigMapper,
                                    ModelExecutionSnapshotService modelExecutionSnapshotService,
                                    TaskOutboxService taskOutboxService,
                                    CreditService creditService,
                                    TaskCreditEstimateService taskCreditEstimateService,
                                    BillingService billingService,
                                    ObjectMapper objectMapper,
                                    @Lazy WorkflowRootTaskFinalizer workflowRootTaskFinalizer) {
        this.workflowDslService = workflowDslService;
        this.workflowRunMapper = workflowRunMapper;
        this.workflowRunStepMapper = workflowRunStepMapper;
        this.taskMapper = taskMapper;
        this.toolMapper = toolMapper;
        this.agentModelConfigMapper = agentModelConfigMapper;
        this.modelExecutionSnapshotService = modelExecutionSnapshotService;
        this.taskOutboxService = taskOutboxService;
        this.creditService = creditService;
        this.taskCreditEstimateService = taskCreditEstimateService;
        this.billingService = billingService;
        this.objectMapper = objectMapper;
        this.workflowRootTaskFinalizer = workflowRootTaskFinalizer;
    }

    @Transactional
    public void submitUserFeedback(Long rootTaskId, Long userId, Map<String, String> feedbackFields) {
        WorkflowRun run = workflowRunMapper.findByRootTaskId(rootTaskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "工作流运行不存在"));
        if (!RUN_AWAITING_USER.equals(run.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "当前任务不在等待用户输入状态");
        }
        AiTask rootTask = taskMapper.findById(rootTaskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND, "任务不存在"));
        if (!rootTask.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该任务");
        }
        JsonNode rawInput = readInput(run);
        ObjectNode params = rawInput instanceof ObjectNode objectNode
                ? objectNode.deepCopy()
                : objectMapper.createObjectNode();
        ObjectNode revisions = params.has("userRevisions") && params.get("userRevisions").isObject()
                ? (ObjectNode) params.get("userRevisions")
                : objectMapper.createObjectNode();
        if (feedbackFields == null || feedbackFields.isEmpty()) {
            String fieldKey = resolveAwaitingFieldKey(run);
            if (fieldKey != null && !fieldKey.isBlank()) {
                feedbackFields = Map.of(fieldKey, "");
            }
        }
        feedbackFields.forEach((key, value) -> {
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
        run.setStatus(RUN_RUNNING);
        workflowRunMapper.updateRunStateWithInput(
                run.getId(),
                run.getStatus(),
                paramsJson,
                run.getContextJson(),
                run.getCurrentNodeId(),
                null,
                null
        );
        taskMapper.markProcessing(
                rootTaskId,
                rootTask.getProgress() == null ? 12 : rootTask.getProgress(),
                "已收到您的意见，继续生成",
                List.of(TaskStatus.AWAITING_USER.name(), TaskStatus.PROCESSING.name())
        );
        advanceRun(run.getId());
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
        WorkflowDsl dsl = workflowDslService.parse(workflow);

        WorkflowRun run = new WorkflowRun();
        run.setUserId(rootTask.getUserId());
        run.setToolId(tool.getId());
        run.setWorkflowId(workflow.getId());
        run.setWorkflowVersion(workflow.getVersion());
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
    public void onStepTaskSuccess(Long stepTaskId, WorkerSuccessRequest request) {
        WorkflowRunStep step = workflowRunStepMapper.findByTaskId(stepTaskId)
                .orElse(null);
        if (step == null) {
            return;
        }
        JsonNode output = parseWorkflowOutput(request);
        step.setStatus(STEP_SUCCESS);
        step.setOutputJson(writeJson(output));
        step.setFinishedAt(LocalDateTime.now());
        workflowRunStepMapper.updateStepState(
                step.getId(),
                step.getStatus(),
                step.getTaskId(),
                step.getAttempt(),
                step.getInputJson(),
                step.getOutputJson(),
                step.getErrorMessage(),
                step.getStartedAt(),
                step.getFinishedAt()
        );
        WorkflowRun run = requireRun(step.getRunId());
        ObjectNode context = readContext(run);
        context.set(step.getNodeId(), output);
        saveContext(run, context, step.getNodeId());
        updateRootProgress(run, step.getNodeId(), 90, "节点完成：" + step.getNodeId());
        chargeWorkflowStepCredits(run, stepTaskId, step.getNodeDefType(), output);
        advanceRun(run.getId());
    }

    @Transactional
    public void onStepTaskFailed(Long stepTaskId, WorkerFailedRequest request) {
        WorkflowRunStep step = workflowRunStepMapper.findByTaskId(stepTaskId).orElse(null);
        if (step == null) {
            return;
        }
        String errorMessage = request.errorMessage() == null ? "Workflow step failed" : request.errorMessage();
        step.setStatus(STEP_FAILED);
        step.setErrorMessage(limit(errorMessage, 1900));
        step.setFinishedAt(LocalDateTime.now());
        workflowRunStepMapper.updateStepState(
                step.getId(),
                step.getStatus(),
                step.getTaskId(),
                step.getAttempt(),
                step.getInputJson(),
                step.getOutputJson(),
                step.getErrorMessage(),
                step.getStartedAt(),
                step.getFinishedAt()
        );
        failRun(step.getRunId(), errorMessage);
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

        for (String nodeId : dsl.executionOrder()) {
            WorkflowRunStep step = steps.get(nodeId);
            if (step == null || !STEP_PENDING.equals(step.getStatus())) {
                continue;
            }
            if (!dependenciesSucceeded(dsl, steps, nodeId)) {
                continue;
            }
            WorkflowNodeDef node = dsl.requireNode(nodeId);
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
            case USER_CONFIRM -> output.set("preview", collectConfirmPreview(context, node));
            case CONDITION -> output.set("branch", evaluateConditionBranch(run, context, node));
            case SCENE_LOOP -> output.setAll(buildSceneLoopOutput(run, node));
            case VIDEO_OUTPUT -> output.set("artifacts", collectArtifacts(context));
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Unsupported inline node: " + node.type());
        }
        markStepSuccess(step, output);
        context.set(node.id(), output);
        saveContext(run, context, node.id());
        if (node.type() == WorkflowNodeDefType.VIDEO_OUTPUT) {
            finalizeRootTask(run, context);
            markRunSuccess(run);
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
        Long modelConfigId = resolveModelConfigId(node);
        AgentModelConfig modelConfig = modelConfigId == null
                ? null
                : Optional.ofNullable(agentModelConfigMapper.findActiveById(modelConfigId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "模型配置不存在: " + modelConfigId));
        ModelExecutionSnapshot snapshot = modelConfig == null ? null : modelExecutionSnapshotService.create(modelConfig);

        ObjectNode stepParams = objectMapper.createObjectNode();
        stepParams.put("workflowRunId", run.getId());
        stepParams.put("workflowStepId", step.getId());
        stepParams.put("parentTaskId", run.getRootTaskId());
        stepParams.put("nodeId", node.id());
        stepParams.put("nodeDefType", node.type().name());
        stepParams.set("workflowInputs", inputs);
        stepParams.put("workflowStep", true);

        AiTask child = new AiTask();
        child.setTaskNo(generateTaskNo());
        child.setUserId(run.getUserId());
        child.setToolId(run.getToolId());
        child.setParamsJson(writeJson(stepParams));
        if (snapshot != null) {
            child.setModelSnapshotJson(modelExecutionSnapshotService.serialize(snapshot));
        }
        child.setEstimatedCreditCost(0);
        child.setIdempotencyKey("wf-" + run.getId() + "-" + node.id() + "-" + (step.getAttempt() + 1));
        Long childTaskId = taskMapper.insertTask(child);

        step.setStatus(STEP_QUEUED);
        step.setAttempt(step.getAttempt() == null ? 1 : step.getAttempt() + 1);
        step.setTaskId(childTaskId);
        step.setInputJson(writeJson(inputs));
        step.setStartedAt(LocalDateTime.now());
        workflowRunStepMapper.updateStepState(
                step.getId(),
                step.getStatus(),
                step.getTaskId(),
                step.getAttempt(),
                step.getInputJson(),
                step.getOutputJson(),
                step.getErrorMessage(),
                step.getStartedAt(),
                step.getFinishedAt()
        );
        saveContext(run, context, node.id());
        updateRootProgress(run, node.id(), progressForNode(node.type()), "排队执行：" + node.title());
        taskOutboxService.enqueueTaskCreated(childTaskId);
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
        String markdown = text(compose, "markdown");
        if (markdown == null || markdown.isBlank()) {
            markdown = buildFallbackMarkdown(context, finalVideoUrl);
        }
        workflowRootTaskFinalizer.finalizeSuccess(run.getRootTaskId(), markdown);
    }

    private void completeRun(WorkflowRun run) {
        if (RUN_SUCCESS.equals(run.getStatus())) {
            return;
        }
        ObjectNode context = readContext(run);
        finalizeRootTask(run, context);
        markRunSuccess(run);
    }

    private void failRun(Long runId, String errorMessage) {
        WorkflowRun run = requireRun(runId);
        run.setStatus(RUN_FAILED);
        run.setErrorMessage(limit(errorMessage, 1900));
        run.setFinishedAt(LocalDateTime.now());
        workflowRunMapper.updateRunState(
                run.getId(),
                run.getStatus(),
                run.getContextJson(),
                run.getCurrentNodeId(),
                run.getErrorMessage(),
                run.getFinishedAt()
        );
        AiTask rootTask = taskMapper.findById(run.getRootTaskId()).orElse(null);
        if (rootTask != null) {
            creditService.release(rootTask.getUserId(), CreditSourceType.TASK, rootTask.getId(), rootTask.getEstimatedCreditCost());
            taskMapper.markFailed(
                    rootTask.getId(),
                    TaskStatus.FAILED.name(),
                    ErrorCode.MODEL_CALL_FAILED.name(),
                    limit("工作流失败：" + (errorMessage == null ? "" : errorMessage), 240),
                    limit(errorMessage, 4000),
                    List.of(TaskStatus.QUEUED.name(), TaskStatus.PROCESSING.name())
            );
        }
    }

    private void markRunSuccess(WorkflowRun run) {
        run.setStatus(RUN_SUCCESS);
        run.setFinishedAt(LocalDateTime.now());
        workflowRunMapper.updateRunState(
                run.getId(),
                run.getStatus(),
                run.getContextJson(),
                run.getCurrentNodeId(),
                null,
                run.getFinishedAt()
        );
    }

    private ObjectNode buildNodeInputs(WorkflowDsl dsl, WorkflowNodeDef node, ObjectNode context, JsonNode formInput) {
        ObjectNode inputs = objectMapper.createObjectNode();
        inputs.set("form", formInput);
        for (WorkflowEdgeDef edge : dsl.edges()) {
            if (!node.id().equals(edge.target())) {
                continue;
            }
            JsonNode upstream = context.get(edge.source());
            if (upstream != null && !upstream.isMissingNode()) {
                inputs.set(edge.source(), upstream);
            }
        }
        if (node.parameters() != null && !node.parameters().isMissingNode()) {
            inputs.set("parameters", node.parameters());
        }
        return inputs;
    }

    private Long resolveModelConfigId(WorkflowNodeDef node) {
        if (node.parameters() != null && node.parameters().hasNonNull("modelConfigId")) {
            return node.parameters().get("modelConfigId").asLong();
        }
        return null;
    }

    private boolean dependenciesSucceeded(WorkflowDsl dsl, Map<String, WorkflowRunStep> steps, String nodeId) {
        List<WorkflowEdgeDef> incoming = dsl.edges().stream().filter(edge -> nodeId.equals(edge.target())).toList();
        if (incoming.isEmpty()) {
            return true;
        }
        for (WorkflowEdgeDef edge : incoming) {
            WorkflowRunStep upstream = steps.get(edge.source());
            if (upstream == null || !STEP_SUCCESS.equals(upstream.getStatus())) {
                return false;
            }
        }
        return true;
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
        ToolWorkflow workflow = workflowDslService.findPublishedWorkflow(run.getToolId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "工作流未发布"));
        return workflowDslService.parse(workflow);
    }

    private JsonNode readInput(WorkflowRun run) {
        try {
            return objectMapper.readTree(run.getInputJson());
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
        run.setContextJson(writeJson(context));
        run.setCurrentNodeId(currentNodeId);
        if (RUN_AWAITING_USER.equals(run.getStatus())) {
            workflowRunMapper.updateRunStateWithInput(
                    run.getId(),
                    run.getStatus(),
                    run.getInputJson(),
                    run.getContextJson(),
                    run.getCurrentNodeId(),
                    run.getErrorMessage(),
                    run.getFinishedAt()
            );
            return;
        }
        workflowRunMapper.updateRunState(
                run.getId(),
                run.getStatus(),
                run.getContextJson(),
                run.getCurrentNodeId(),
                run.getErrorMessage(),
                run.getFinishedAt()
        );
    }

    private void markStepSuccess(WorkflowRunStep step, JsonNode output) {
        step.setStatus(STEP_SUCCESS);
        step.setOutputJson(writeJson(output));
        step.setFinishedAt(LocalDateTime.now());
        workflowRunStepMapper.updateStepState(
                step.getId(),
                step.getStatus(),
                step.getTaskId(),
                step.getAttempt(),
                step.getInputJson(),
                step.getOutputJson(),
                step.getErrorMessage(),
                step.getStartedAt(),
                step.getFinishedAt()
        );
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

    private void pauseForUserFeedback(WorkflowRun run,
                                      WorkflowRunStep step,
                                      WorkflowNodeDef node,
                                      String stageLabel,
                                      String fieldKey) {
        String label = stageLabel == null || stageLabel.isBlank() ? fieldKey : stageLabel;
        String message = "等待您的" + label + "（可留空直接继续）";
        run.setStatus(RUN_AWAITING_USER);
        run.setCurrentNodeId(node.id());
        workflowRunMapper.updateRunState(
                run.getId(),
                run.getStatus(),
                run.getContextJson(),
                run.getCurrentNodeId(),
                null,
                null
        );
        taskMapper.markAwaitingUser(
                run.getRootTaskId(),
                progressForNode(node.type()),
                limit(message, 240),
                List.of(TaskStatus.PROCESSING.name(), TaskStatus.QUEUED.name(), TaskStatus.AWAITING_USER.name())
        );
        LOGGER.info("workflow paused for user input runId={} nodeId={} fieldKey={}", run.getId(), node.id(), fieldKey);
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
                WorkflowDsl dsl = workflowDslService.findPublishedWorkflow(run.getToolId())
                        .map(workflowDslService::parse)
                        .orElse(null);
                if (dsl != null) {
                    WorkflowNodeDef node = dsl.requireNode(run.getCurrentNodeId());
                    stageLabel = text(node.parameters(), "stageLabel");
                    if (fieldKey == null || fieldKey.isBlank()) {
                        fieldKey = text(node.parameters(), "fieldKey");
                    }
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
                preview.remove("audioUrl");
                preview.remove("audios");
                preview.remove("videoUrl");
                preview.remove("finalVideoUrl");
            }
            case "sceneFeedback" -> {
                // 场景图阶段保留 script.scenes（用于逐镜对照），仅过滤音视频
                preview.remove("audioUrl");
                preview.remove("audios");
                preview.remove("videoUrl");
                preview.remove("finalVideoUrl");
            }
            case "bgmFeedback" -> {
                preview.remove("script");
                preview.remove("imageUrl");
                preview.remove("images");
                preview.remove("videoUrl");
                preview.remove("finalVideoUrl");
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
            result.add(entry);
        }
        return result.isEmpty() ? null : result;
    }

    /** Shallow script preview for API responses — avoids deep LLM JSON blowing Jackson nesting limits. */
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

    private String resolveAwaitingFieldKey(WorkflowRun run) {
        if (run.getCurrentNodeId() == null || run.getCurrentNodeId().isBlank()) {
            return null;
        }
        try {
            WorkflowDsl dsl = workflowDslService.findPublishedWorkflow(run.getToolId())
                    .map(workflowDslService::parse)
                    .orElse(null);
            if (dsl == null) {
                return null;
            }
            WorkflowNodeDef node = dsl.requireNode(run.getCurrentNodeId());
            return text(node.parameters(), "fieldKey");
        } catch (Exception exception) {
            LOGGER.warn("resolve awaiting field key failed runId={}", run.getId(), exception);
            return null;
        }
    }

    /**
     * 工作流不做静态预估（前端展示"算力不详"），每个节点成功后按本次实际使用的模型
     * 成本 ×1.2（见 {@link TaskCreditEstimateService#estimateUserFacingTaskCredits}）响应式扣减。
     * 多分镜节点（关键帧/配音/图生视频）一次产出 N 个分镜，对应 N 次模型调用，按 N 倍计费。
     */
    private void chargeWorkflowStepCredits(WorkflowRun run, Long stepTaskId, String nodeDefType, JsonNode output) {
        AiTask stepTask = taskMapper.findById(stepTaskId).orElse(null);
        if (stepTask == null) {
            return;
        }
        AiTool tool = toolMapper.findById(run.getToolId()).orElse(null);
        if (tool == null) {
            return;
        }
        ModelExecutionSnapshot snapshot = modelExecutionSnapshotService.parse(stepTask.getModelSnapshotJson());
        AgentModelConfig modelConfig = snapshot == null ? null : snapshot.toModelConfig();
        if (modelConfig == null) {
            return;
        }
        JsonNode stepParams = parseStepParams(stepTask.getParamsJson());
        int unitCredits = taskCreditEstimateService.estimateUserFacingTaskCredits(tool, modelConfig, stepParams);
        if (unitCredits <= 0) {
            return;
        }
        int units = resolveStepBillingUnits(nodeDefType, output);
        int stepCredits = unitCredits * units;
        int chargedCredits = creditService.settleCompleted(
                run.getUserId(),
                CreditSourceType.TASK,
                run.getRootTaskId(),
                stepCredits
        );
        if (chargedCredits < stepCredits) {
            throw new BusinessException(ErrorCode.CREDIT_NOT_ENOUGH, "算力不足，无法完成当前工作流步骤");
        }
        billingService.recordUsage(
                "TASK",
                run.getRootTaskId(),
                run.getUserId(),
                modelConfig,
                null,
                null,
                units,
                chargedCredits
        );
    }

    /** 每镜一次模型调用的节点按实际分镜数计费；LLM/合成节点单次计费。 */
    private int resolveStepBillingUnits(String nodeDefType, JsonNode output) {
        if (nodeDefType == null || output == null) {
            return 1;
        }
        boolean perSceneNode = "IMAGE_MODEL".equals(nodeDefType)
                || "TTS_MODEL".equals(nodeDefType)
                || "VIDEO_MODEL".equals(nodeDefType);
        if (!perSceneNode) {
            return 1;
        }
        JsonNode sceneCount = output.get("sceneCount");
        if (sceneCount != null && sceneCount.isNumber()) {
            return Math.max(1, sceneCount.asInt());
        }
        return 1;
    }

    private JsonNode parseStepParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(paramsJson);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private void updateRootProgress(WorkflowRun run, String nodeId, int progress, String message) {
        if (!RUN_AWAITING_USER.equals(run.getStatus())) {
            taskMapper.markProcessing(
                    run.getRootTaskId(),
                    progress,
                    limit(message, 240),
                    List.of(TaskStatus.PROCESSING.name(), TaskStatus.QUEUED.name(), TaskStatus.AWAITING_USER.name())
            );
        }
        run.setCurrentNodeId(nodeId);
        if (RUN_AWAITING_USER.equals(run.getStatus())) {
            return;
        }
        workflowRunMapper.updateRunState(
                run.getId(),
                run.getStatus(),
                run.getContextJson(),
                run.getCurrentNodeId(),
                run.getErrorMessage(),
                run.getFinishedAt()
        );
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

    private String generateTaskNo() {
        return "WF" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
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
