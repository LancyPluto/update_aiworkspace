package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.agent.service.AgentDelegatedToolCallLifecycleService;
import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.agent.dto.ModelExecutionSnapshot;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.service.ModelExecutionSnapshotService;
import com.aiminilab.aitoolmarket.common.enums.CreditSourceType;
import com.aiminilab.aitoolmarket.common.enums.TaskStatus;
import com.aiminilab.aitoolmarket.credit.dto.PricingPolicySnapshot;
import com.aiminilab.aitoolmarket.credit.dto.PricingQuote;
import com.aiminilab.aitoolmarket.credit.dto.PricingUsage;
import com.aiminilab.aitoolmarket.credit.dto.ModelPricingSnapshot;
import com.aiminilab.aitoolmarket.credit.service.CreditService;
import com.aiminilab.aitoolmarket.credit.service.PricingService;
import com.aiminilab.aitoolmarket.task.dto.WorkerSuccessRequest;
import com.aiminilab.aitoolmarket.task.dto.WorkerFailedRequest;
import com.aiminilab.aitoolmarket.task.entity.AiTask;
import com.aiminilab.aitoolmarket.task.mapper.TaskMapper;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowVersionMapper;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRun;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowRunStep;
import com.aiminilab.aitoolmarket.workflow.entity.WorkflowStepCharge;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunStepMapper;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepChargeMapper;
import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowChargeStatus;
import com.aiminilab.aitoolmarket.workflow.model.WorkflowReservationResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.math.BigDecimal;

@Service
public class WorkflowBillingService {

    private final WorkflowStepChargeMapper chargeMapper;
    private final WorkflowRunMapper runMapper;
    private final TaskMapper taskMapper;
    private final CreditService creditService;
    private final BillingService usageService;
    private final ModelExecutionSnapshotService snapshotService;
    private final ObjectMapper objectMapper;
    private final ToolWorkflowVersionMapper versionMapper;
    private final WorkflowRunStepMapper stepMapper;
    private final PricingService pricingService;
    private final WorkflowMetrics metrics;
    private final AgentDelegatedToolCallLifecycleService delegatedToolCallLifecycleService;

    public WorkflowBillingService(WorkflowStepChargeMapper chargeMapper,
                                  WorkflowRunMapper runMapper,
                                  TaskMapper taskMapper,
                                  CreditService creditService,
                                  BillingService usageService,
                                  ModelExecutionSnapshotService snapshotService,
                                  ObjectMapper objectMapper,
                                  ToolWorkflowVersionMapper versionMapper,
                                   WorkflowRunStepMapper stepMapper,
                                   PricingService pricingService,
                                   WorkflowMetrics metrics,
                                   AgentDelegatedToolCallLifecycleService delegatedToolCallLifecycleService) {
        this.chargeMapper = chargeMapper;
        this.runMapper = runMapper;
        this.taskMapper = taskMapper;
        this.creditService = creditService;
        this.usageService = usageService;
        this.snapshotService = snapshotService;
        this.objectMapper = objectMapper;
        this.versionMapper = versionMapper;
        this.stepMapper = stepMapper;
        this.pricingService = pricingService;
        this.metrics = metrics;
        this.delegatedToolCallLifecycleService = delegatedToolCallLifecycleService;
    }

    @Transactional
    public WorkflowReservationResult reserve(WorkflowRun run,
                                             WorkflowRunStep step,
                                             String attemptKey,
                                             int reservedCredits) {
        boolean resumingFromFunds = "AWAITING_FUNDS".equals(run.getStatus());
        if (resumingFromFunds
                && run.getCurrentStepId() != null
                && !run.getCurrentStepId().equals(step.getId())) {
            throw new IllegalStateException("Workflow run is awaiting funds for another step");
        }
        if (!"RUNNING".equals(run.getStatus()) && !resumingFromFunds) {
            throw new IllegalStateException("Workflow run cannot reserve credits in status: " + run.getStatus());
        }
        if (reservedCredits <= 0) {
            if (resumingFromFunds) {
                resumeAfterReservation(run, step);
            }
            return WorkflowReservationResult.NOT_REQUIRED;
        }
        WorkflowStepCharge existing = chargeMapper.selectByIdempotencyKey(attemptKey);
        if (existing != null) {
            validateReservation(existing, run, step, reservedCredits);
            if (resumingFromFunds && WorkflowChargeStatus.RESERVED.name().equals(existing.getStatus())) {
                resumeAfterReservation(run, step);
            }
            return WorkflowReservationResult.valueOf(existing.getStatus());
        }
        if (!creditService.tryFreeze(
                run.getUserId(),
                CreditSourceType.WORKFLOW_STEP,
                step.getId(),
                reservedCredits,
                attemptKey + ":reserve"
        )) {
            if (!resumingFromFunds) {
                pauseForFunds(run, step);
            }
            return WorkflowReservationResult.INSUFFICIENT;
        }

        WorkflowStepCharge charge = new WorkflowStepCharge();
        charge.setRunId(run.getId());
        charge.setStepId(step.getId());
        charge.setUserId(run.getUserId());
        charge.setStatus(WorkflowChargeStatus.RESERVED.name());
        charge.setReservedCredits(reservedCredits);
        charge.setChargedCredits(0);
        charge.setIdempotencyKey(attemptKey);
        boolean inserted = false;
        try {
            if (chargeMapper.insert(charge) != 1) {
                throw new IllegalStateException("Workflow charge reservation could not be inserted");
            }
            inserted = true;
        } catch (DuplicateKeyException exception) {
            existing = chargeMapper.selectByIdempotencyKey(attemptKey);
            if (existing == null) {
                throw exception;
            }
            validateReservation(existing, run, step, reservedCredits);
        }
        if (inserted) {
            metrics.recordBillingState(WorkflowMetrics.BillingState.RESERVED);
        }
        if (resumingFromFunds) {
            resumeAfterReservation(run, step);
        }
        return WorkflowReservationResult.RESERVED;
    }

    @Transactional
    public void bindAttempt(String attemptKey, Long attemptId) {
        WorkflowStepCharge charge = chargeMapper.selectByIdempotencyKey(attemptKey);
        if (charge == null) {
            return;
        }
        if (charge.getAttemptId() != null) {
            if (!charge.getAttemptId().equals(attemptId)) {
                throw new IllegalStateException("Workflow charge is already bound to another attempt");
            }
            return;
        }
        if (chargeMapper.bindAttempt(charge.getId(), attemptId) != 1) {
            throw new IllegalStateException("Workflow charge could not be bound to its attempt");
        }
    }

    @Transactional
    public void capture(Long attemptId, Long childTaskId, WorkerSuccessRequest request) {
        WorkflowStepCharge charge = chargeMapper.selectByAttemptIdForUpdate(attemptId);
        if (charge == null) {
            return;
        }
        if (WorkflowChargeStatus.CAPTURED.name().equals(charge.getStatus())) {
            attachActualProviderAccounting(charge, childTaskId, request);
            return;
        }
        if (!WorkflowChargeStatus.RESERVED.name().equals(charge.getStatus())) {
            throw new IllegalStateException("Workflow charge is not reserved for capture");
        }
        taskMapper.findById(childTaskId)
                .orElseThrow(() -> new IllegalStateException("Workflow child task does not exist"));
        Settlement settlement = settleFromSnapshot(charge, request);
        AgentModelConfig modelConfig = settlement.modelConfig();
        ProviderAccounting providerAccounting = providerAccounting(request);
        int actualCredits = Math.min(settlement.chargeCredits(), charge.getReservedCredits());
        creditService.captureReserved(
                charge.getUserId(),
                CreditSourceType.WORKFLOW_STEP,
                charge.getStepId(),
                charge.getReservedCredits(),
                actualCredits,
                charge.getIdempotencyKey() + ":capture"
        );
        Long usageId = usageService.recordUsageOnce(
                charge.getIdempotencyKey() + ":usage",
                "WORKFLOW_STEP",
                charge.getStepId(),
                charge.getUserId(),
                modelConfig,
                request.promptTokens(),
                request.completionTokens(),
                request.billableUnits(),
                actualCredits,
                providerAccounting.amount(),
                providerAccounting.currency(),
                settlement.markupRatio(),
                "SUCCESS",
                null,
                "PROVIDER_CALLBACK",
                null,
                providerAccounting.requestId(),
                providerAccounting.charged()
        );
        if (chargeMapper.markCaptured(
                charge.getId(), actualCredits, providerAccounting.amount(),
                providerAccounting.currency(), usageId
        ) != 1) {
            throw new IllegalStateException("Workflow charge capture compare-and-set failed");
        }
        metrics.recordBillingState(WorkflowMetrics.BillingState.CAPTURED);
    }

    @Transactional
    public void release(Long attemptId) {
        release(attemptId, null, null);
    }

    @Transactional
    public void release(Long attemptId, Long childTaskId, WorkerFailedRequest request) {
        release(attemptId, childTaskId, request, false);
    }

    @Transactional
    public void releaseLateFailure(Long attemptId, Long childTaskId, WorkerFailedRequest request) {
        release(attemptId, childTaskId, request, true);
    }

    private void release(Long attemptId,
                         Long childTaskId,
                         WorkerFailedRequest request,
                         boolean lateCallback) {
        WorkflowStepCharge charge = chargeMapper.selectByAttemptId(attemptId);
        if (charge == null || WorkflowChargeStatus.CAPTURED.name().equals(charge.getStatus())) {
            return;
        }
        boolean reserved = WorkflowChargeStatus.RESERVED.name().equals(charge.getStatus());
        boolean released = WorkflowChargeStatus.RELEASED.name().equals(charge.getStatus());
        if (!reserved && !released) {
            throw new IllegalStateException("Workflow charge is not reserved for release");
        }
        boolean hasProviderCost = request != null
                && !Boolean.FALSE.equals(request.providerCharged())
                && (Boolean.TRUE.equals(request.providerCharged())
                || (request.providerCostAmount() != null && request.providerCostAmount().signum() > 0));
        if (released && (!hasProviderCost || charge.getBillingUsageId() != null)) {
            return;
        }
        if (reserved) {
            creditService.releaseReserved(
                    charge.getUserId(),
                    CreditSourceType.WORKFLOW_STEP,
                    charge.getStepId(),
                    charge.getReservedCredits(),
                    charge.getIdempotencyKey() + ":release"
            );
        }
        Long usageId = null;
        java.math.BigDecimal providerCost = null;
        String providerCostCurrency = null;
        if (hasProviderCost) {
            AiTask child = taskMapper.findById(childTaskId)
                    .orElseThrow(() -> new IllegalStateException("Workflow child task does not exist"));
            ModelExecutionSnapshot snapshot = snapshotService.parse(child.getModelSnapshotJson());
            AgentModelConfig modelConfig = snapshot == null ? null : snapshot.toModelConfig();
            FailureProviderAccounting providerAccounting = providerAccounting(request);
            providerCost = providerAccounting.amount();
            providerCostCurrency = providerAccounting.currency();
            usageId = usageService.recordUsageOnce(
                    charge.getIdempotencyKey() + ":usage",
                    "WORKFLOW_STEP",
                    charge.getStepId(),
                    charge.getUserId(),
                    modelConfig,
                    request.promptTokens(),
                    request.completionTokens(),
                    request.billableUnits(),
                    0,
                    providerCost,
                    providerCostCurrency,
                    null,
                    "FAILED",
                    request.errorCode(),
                    request.failureStage(),
                    request.providerErrorCode(),
                    providerAccounting.requestId(),
                    request.providerCharged()
            );
        }
        int updated = reserved
                ? chargeMapper.markReleased(charge.getId(), providerCost, providerCostCurrency, usageId)
                : chargeMapper.attachProviderCostToReleased(
                        charge.getId(), providerCost, providerCostCurrency, usageId
                );
        if (updated != 1) {
            throw new IllegalStateException("Workflow charge release compare-and-set failed");
        }
        if (reserved) {
            metrics.recordBillingState(WorkflowMetrics.BillingState.RELEASED);
        }
        if (lateCallback && hasProviderCost && usageId != null) {
            metrics.recordLateCallback(WorkflowMetrics.LateCallbackResult.RECORDED_PROVIDER_COST);
        }
    }

    @Transactional
    public void releaseLateSuccess(Long attemptId, Long childTaskId, WorkerSuccessRequest request) {
        WorkflowStepCharge charge = chargeMapper.selectByAttemptIdForUpdate(attemptId);
        if (charge == null) {
            return;
        }
        if (WorkflowChargeStatus.CAPTURED.name().equals(charge.getStatus())) {
            attachActualProviderAccounting(charge, childTaskId, request);
            return;
        }
        boolean reserved = WorkflowChargeStatus.RESERVED.name().equals(charge.getStatus());
        boolean released = WorkflowChargeStatus.RELEASED.name().equals(charge.getStatus());
        if (!reserved && !released) {
            return;
        }
        if (released && charge.getBillingUsageId() != null) {
            return;
        }
        if (reserved) {
            creditService.releaseReserved(
                    charge.getUserId(),
                    CreditSourceType.WORKFLOW_STEP,
                    charge.getStepId(),
                    charge.getReservedCredits(),
                    charge.getIdempotencyKey() + ":release"
            );
        }
        taskMapper.findById(childTaskId)
                .orElseThrow(() -> new IllegalStateException("Workflow child task does not exist"));
        Settlement settlement = settleFromSnapshot(charge, request);
        ProviderAccounting providerAccounting = providerAccounting(request);
        Long usageId = usageService.recordUsageOnce(
                charge.getIdempotencyKey() + ":usage",
                "WORKFLOW_STEP",
                charge.getStepId(),
                charge.getUserId(),
                settlement.modelConfig(),
                request.promptTokens(),
                request.completionTokens(),
                request.billableUnits(),
                0,
                providerAccounting.amount(),
                providerAccounting.currency(),
                settlement.markupRatio(),
                "CANCELLED_LATE_SUCCESS",
                null,
                "PROVIDER_CALLBACK",
                null,
                providerAccounting.requestId(),
                providerAccounting.charged()
        );
        int updated = reserved
                ? chargeMapper.markReleased(charge.getId(), providerAccounting.amount(),
                        providerAccounting.currency(), usageId)
                : chargeMapper.attachProviderCostToReleased(charge.getId(), providerAccounting.amount(),
                        providerAccounting.currency(), usageId);
        if (updated != 1) {
            throw new IllegalStateException("Late workflow success cost could not be recorded");
        }
        if (reserved) {
            metrics.recordBillingState(WorkflowMetrics.BillingState.RELEASED);
        }
        if (usageId != null) {
            metrics.recordLateCallback(WorkflowMetrics.LateCallbackResult.RECORDED_PROVIDER_COST);
        }
    }

    private void attachActualProviderAccounting(WorkflowStepCharge charge,
                                                Long childTaskId,
                                                WorkerSuccessRequest request) {
        if (request.providerCostAmount() == null) {
            return;
        }
        if (charge.getBillingUsageId() == null) {
            throw new IllegalStateException("Captured workflow charge has no billing usage to attach provider cost");
        }
        taskMapper.findById(childTaskId)
                .orElseThrow(() -> new IllegalStateException("Workflow child task does not exist"));
        ProviderAccounting accounting = providerAccounting(request);
        Settlement settlement = settleFromSnapshot(charge, request);
        boolean attached = usageService.attachActualProviderAccounting(
                charge.getBillingUsageId(),
                settlement.modelConfig(),
                accounting.amount(),
                accounting.currency(),
                accounting.requestId()
        );
        if (charge.getProviderCost() == null) {
            if (chargeMapper.attachActualProviderAccountingToCaptured(
                    charge.getId(),
                    charge.getBillingUsageId(),
                    accounting.amount(),
                    accounting.currency()
            ) != 1) {
                throw new IllegalStateException("Captured workflow provider accounting compare-and-set failed");
            }
        } else if (charge.getProviderCost().compareTo(accounting.amount()) != 0
                || !java.util.Objects.equals(charge.getProviderCostCurrency(), accounting.currency())) {
            throw new IllegalStateException("Actual provider accounting conflicts with captured workflow charge");
        }
        if (attached) {
            metrics.recordLateCallback(WorkflowMetrics.LateCallbackResult.RECORDED_PROVIDER_COST);
        }
    }

    private void pauseForFunds(WorkflowRun run, WorkflowRunStep step) {
        if (runMapper.markAwaitingFunds(
                run.getId(), revision(run), step.getId(), "Insufficient credits for workflow step"
        ) != 1) {
            throw new IllegalStateException("Workflow run could not pause for insufficient funds");
        }
        if (taskMapper.markAwaitingFunds(
                run.getRootTaskId(),
                "算力不足，请充值后继续",
                List.of(TaskStatus.PROCESSING.name())
        ) != 1) {
            throw new IllegalStateException("Workflow root task could not pause for insufficient funds");
        }
        delegatedToolCallLifecycleService.progressForWorkflow(
                run.getRootTaskId(), run.getId(), "AWAITING_FUNDS"
        );
    }

    private void resumeAfterReservation(WorkflowRun run, WorkflowRunStep step) {
        long expectedRevision = revision(run);
        if (runMapper.resumeAwaitingFunds(run.getId(), expectedRevision, step.getId()) != 1) {
            throw new IllegalStateException("Workflow run could not resume after reserving credits");
        }
        AiTask rootTask = taskMapper.findById(run.getRootTaskId())
                .orElseThrow(() -> new IllegalStateException("Workflow root task does not exist"));
        if (taskMapper.markProcessing(
                run.getRootTaskId(),
                rootTask.getProgress() == null ? 1 : rootTask.getProgress(),
                "算力已冻结，继续执行",
                List.of(TaskStatus.AWAITING_FUNDS.name())
        ) != 1) {
            throw new IllegalStateException("Workflow root task could not resume after reserving credits");
        }
        run.setStatus("RUNNING");
        run.setBillingStatus("CLEAR");
        run.setRevision(expectedRevision + 1);
        delegatedToolCallLifecycleService.progressForWorkflow(
                run.getRootTaskId(), run.getId(), "RUNNING"
        );
    }

    private void validateReservation(WorkflowStepCharge charge,
                                     WorkflowRun run,
                                     WorkflowRunStep step,
                                     int reservedCredits) {
        boolean matches = run.getId().equals(charge.getRunId())
                && step.getId().equals(charge.getStepId())
                && run.getUserId().equals(charge.getUserId())
                && Integer.valueOf(reservedCredits).equals(charge.getReservedCredits());
        if (!matches) {
            throw new IllegalStateException("Workflow reservation key conflicts with an existing charge");
        }
    }

    private long revision(WorkflowRun run) {
        return run.getRevision() == null ? 0L : run.getRevision();
    }

    private JsonNode readJson(String json) {
        try {
            return json == null || json.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException("Workflow billing params are not valid JSON", exception);
        }
    }

    private Settlement settleFromSnapshot(WorkflowStepCharge charge,
                                          WorkerSuccessRequest request) {
        WorkflowRun run = runMapper.selectById(charge.getRunId());
        if (run == null || run.getWorkflowVersionId() == null) {
            throw new IllegalStateException("Workflow run has no fixed billing version");
        }
        ToolWorkflowVersion version = versionMapper.selectById(run.getWorkflowVersionId());
        if (version == null) {
            throw new IllegalStateException("Workflow billing version does not exist");
        }
        WorkflowRunStep step = stepMapper.selectById(charge.getStepId());
        if (step == null || !run.getId().equals(step.getRunId()) || step.getNodeId() == null) {
            throw new IllegalStateException("Workflow charge has no valid billing step");
        }
        JsonNode nodePolicy = readJson(version.getBillingPolicyJson())
                .path("nodePolicies")
                .path(step.getNodeId());
        if (!nodePolicy.isObject()
                || !nodePolicy.path("staticParams").isObject()
                || !nodePolicy.has("modelPricingSnapshot")
                || !nodePolicy.path("pricingPolicy").isObject()) {
            throw new IllegalStateException("Workflow node has no immutable pricing snapshot: " + step.getNodeId());
        }
        PricingPolicySnapshot pricingPolicy = readPricingPolicy(nodePolicy.path("pricingPolicy"));
        AgentModelConfig modelConfig = readModelPricing(nodePolicy.get("modelPricingSnapshot"));
        int fallback = Math.max(0, nodePolicy.path("fallbackChargeCredits").asInt(0));
        PricingQuote quote = pricingService.computeQuote(
                pricingPolicy,
                modelConfig,
                billingParams(nodePolicy, step),
                new PricingUsage(request.promptTokens(), request.completionTokens(), request.billableUnits()),
                fallback
        );
        return new Settlement(quote.vendorCost(), quote.chargeCredits(), quote.markupRatio(), modelConfig);
    }

    private ProviderAccounting providerAccounting(WorkerSuccessRequest request) {
        String requestId = normalizeProviderRequestId(request.providerRequestId());
        if (request.providerCostAmount() == null) {
            return new ProviderAccounting(null, "UNKNOWN", requestId, false);
        }
        if (request.providerCostAmount().signum() < 0) {
            throw new IllegalArgumentException("Provider cost amount cannot be negative");
        }
        String currency = request.providerCostCurrency() == null
                ? null
                : request.providerCostCurrency().trim().toUpperCase(java.util.Locale.ROOT);
        if (currency == null || !currency.matches("[A-Z0-9]{3,8}")) {
            throw new IllegalArgumentException("Explicit provider cost requires a 3-8 character currency code");
        }
        return new ProviderAccounting(request.providerCostAmount(), currency, requestId, true);
    }

    private FailureProviderAccounting providerAccounting(WorkerFailedRequest request) {
        String requestId = normalizeProviderRequestId(request.providerRequestId());
        if (request.providerCostAmount() != null && request.providerCostAmount().signum() < 0) {
            throw new IllegalArgumentException("Provider cost amount cannot be negative");
        }
        String currency = request.providerCostCurrency() == null
                ? null
                : request.providerCostCurrency().trim().toUpperCase(java.util.Locale.ROOT);
        if (request.providerCostAmount() != null
                && (currency == null || !currency.matches("[A-Z0-9]{3,8}"))) {
            throw new IllegalArgumentException("Explicit provider cost requires a 3-8 character currency code");
        }
        if (currency != null && !currency.matches("[A-Z0-9]{3,8}")) {
            throw new IllegalArgumentException("Provider cost currency must be a 3-8 character currency code");
        }
        return new FailureProviderAccounting(
                request.providerCostAmount(), currency == null ? "UNKNOWN" : currency, requestId
        );
    }

    private String normalizeProviderRequestId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("Provider request id cannot exceed 128 characters");
        }
        return normalized;
    }

    private JsonNode billingParams(JsonNode nodePolicy, WorkflowRunStep step) {
        ObjectNode params = objectMapper.createObjectNode();
        nodePolicy.path("staticParams").fields()
                .forEachRemaining(entry -> params.set(entry.getKey(), entry.getValue()));
        JsonNode dynamicParams = readJson(step.getInputJson());
        if (!dynamicParams.isObject()) {
            throw new IllegalStateException("Workflow step billing input is not a JSON object");
        }
        dynamicParams.fields()
                .forEachRemaining(entry -> params.set(entry.getKey(), entry.getValue()));
        return params;
    }

    private AgentModelConfig readModelPricing(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalStateException("Workflow model pricing snapshot is not valid JSON");
        }
        try {
            ModelPricingSnapshot snapshot = objectMapper.treeToValue(node, ModelPricingSnapshot.class);
            return snapshot == null ? null : snapshot.toModelConfig();
        } catch (Exception exception) {
            throw new IllegalStateException("Workflow model pricing snapshot is not valid JSON", exception);
        }
    }

    private PricingPolicySnapshot readPricingPolicy(JsonNode node) {
        try {
            PricingPolicySnapshot snapshot = objectMapper.treeToValue(node, PricingPolicySnapshot.class);
            if (snapshot == null || snapshot.markupRatio() == null || snapshot.markupRatio().signum() <= 0) {
                throw new IllegalStateException("Workflow pricing snapshot has no valid markup ratio");
            }
            return snapshot;
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("Workflow pricing snapshot is not valid JSON", exception);
        }
    }

    private record Settlement(BigDecimal vendorCost, int chargeCredits, BigDecimal markupRatio,
                              AgentModelConfig modelConfig) {
    }

    private record ProviderAccounting(BigDecimal amount, String currency, String requestId, boolean charged) {
    }

    private record FailureProviderAccounting(BigDecimal amount, String currency, String requestId) {
    }
}
