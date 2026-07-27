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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.math.BigDecimal;

@Service
public class WorkflowBillingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowBillingService.class);
    private static final Set<String> LOCAL_ZERO_COST_HANDLER_ALLOWLIST = Set.of("comic.compose");

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
    public SettlementResult capture(Long attemptId, Long childTaskId, WorkerSuccessRequest request) {
        WorkflowStepCharge charge = chargeMapper.selectByAttemptIdForUpdate(attemptId);
        if (charge == null) {
            return SettlementResult.SETTLED;
        }
        request = canonicalSettlementRequest(charge, request);
        if (WorkflowChargeStatus.CAPTURED.name().equals(charge.getStatus())) {
            attachActualProviderAccounting(charge, childTaskId, request);
            return SettlementResult.SETTLED;
        }
        boolean reserved = WorkflowChargeStatus.RESERVED.name().equals(charge.getStatus());
        boolean awaitingFunds = WorkflowChargeStatus.AWAITING_FUNDS.name().equals(charge.getStatus());
        if (!reserved && !awaitingFunds) {
            throw new IllegalStateException("Workflow charge is not reserved for capture");
        }
        Settlement settlement = settleFromSnapshot(charge, request);
        if (Boolean.FALSE.equals(request.providerCalled())) {
            releaseSuccessfulNoProviderCall(charge);
            return SettlementResult.SETTLED;
        }
        taskMapper.findById(childTaskId)
                .orElseThrow(() -> new IllegalStateException("Workflow child task does not exist"));
        AgentModelConfig modelConfig = settlement.modelConfig();
        ProviderAccounting providerAccounting = providerAccounting(request);
        int intendedCredits = settlement.chargeCredits();
        int reservedCredits = charge.getReservedCredits();
        int capturedCredits = Math.min(intendedCredits, reservedCredits);
        int shortfall = Math.max(0, intendedCredits - capturedCredits);
        if (shortfall > 0 && !creditService.tryFreeze(
                charge.getUserId(),
                CreditSourceType.WORKFLOW_STEP,
                charge.getStepId(),
                shortfall,
                charge.getIdempotencyKey() + ":shortfall-reserve"
        )) {
            String payloadJson = settlementPayloadJson(request);
            if (reserved) {
                if (chargeMapper.markAwaitingFunds(charge.getId(), payloadJson) != 1) {
                    throw new IllegalStateException("Workflow settlement could not enter awaiting-funds state");
                }
            } else {
                validateSettlementPayload(charge, request);
            }
            LOGGER.warn(
                    "workflow step settlement deferred for insufficient credits runId={} stepId={} intendedCredits={} reservedCredits={} shortfall={}",
                    charge.getRunId(), charge.getStepId(), intendedCredits, reservedCredits, shortfall
            );
            return SettlementResult.AWAITING_FUNDS;
        }
        creditService.captureReserved(
                charge.getUserId(),
                CreditSourceType.WORKFLOW_STEP,
                charge.getStepId(),
                reservedCredits,
                capturedCredits,
                charge.getIdempotencyKey() + ":capture"
        );
        if (shortfall > 0) {
            creditService.captureReserved(
                    charge.getUserId(),
                    CreditSourceType.WORKFLOW_STEP,
                    charge.getStepId(),
                    shortfall,
                    shortfall,
                    charge.getIdempotencyKey() + ":shortfall"
            );
        }
        int chargedCredits = intendedCredits;
        Long usageId = usageService.recordUsageOnce(
                charge.getIdempotencyKey() + ":usage",
                "WORKFLOW_STEP",
                charge.getStepId(),
                charge.getUserId(),
                modelConfig,
                request.promptTokens(),
                request.completionTokens(),
                request.billableUnits(),
                chargedCredits,
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
                charge.getId(), chargedCredits, providerAccounting.amount(),
                providerAccounting.currency(), usageId
        ) != 1) {
            throw new IllegalStateException("Workflow charge capture compare-and-set failed");
        }
        metrics.recordBillingState(WorkflowMetrics.BillingState.CAPTURED);
        return SettlementResult.SETTLED;
    }

    @Transactional
    public void validateDeferredSettlement(Long attemptId, WorkerSuccessRequest request) {
        WorkflowStepCharge charge = chargeMapper.selectByAttemptIdForUpdate(attemptId);
        if (charge == null || !WorkflowChargeStatus.AWAITING_FUNDS.name().equals(charge.getStatus())) {
            throw new IllegalStateException("Workflow attempt has no deferred settlement");
        }
        validateSettlementPayload(charge, request);
    }

    @Transactional
    public WorkerSuccessRequest deferredSettlementRequest(Long attemptId, String outputJson) {
        WorkflowStepCharge charge = chargeMapper.selectByAttemptIdForUpdate(attemptId);
        if (charge == null || !WorkflowChargeStatus.AWAITING_FUNDS.name().equals(charge.getStatus())) {
            throw new IllegalStateException("Workflow attempt has no deferred settlement");
        }
        SettlementPayload payload = readSettlementPayload(charge);
        return payload.toWorkerSuccessRequest(outputJson);
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
        WorkflowStepCharge charge = chargeMapper.selectByAttemptIdForUpdate(attemptId);
        if (charge == null || WorkflowChargeStatus.CAPTURED.name().equals(charge.getStatus())) {
            return;
        }
        boolean reserved = WorkflowChargeStatus.RESERVED.name().equals(charge.getStatus());
        boolean released = WorkflowChargeStatus.RELEASED.name().equals(charge.getStatus());
        if (!reserved && !released) {
            throw new IllegalStateException("Workflow charge is not reserved for release");
        }
        if (released && request != null) {
            attachRecordedProviderRequestId(charge, request.providerRequestId());
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
        releaseSuccessfulAttempt(charge, childTaskId, request);
    }

    @Transactional
    public void recordReconciliationLateSuccess(Long attemptId,
                                                Long childTaskId,
                                                WorkerSuccessRequest request) {
        capture(attemptId, childTaskId, request);
    }

    @Transactional
    public boolean settleReconciliationSuccess(Long attemptId, Long childTaskId) {
        WorkflowStepCharge charge = chargeMapper.selectByAttemptIdForUpdate(attemptId);
        if (charge == null || WorkflowChargeStatus.CAPTURED.name().equals(charge.getStatus())) {
            return true;
        }
        if (!WorkflowChargeStatus.AWAITING_FUNDS.name().equals(charge.getStatus())) {
            return false;
        }
        WorkerSuccessRequest request = readSettlementPayload(charge).toWorkerSuccessRequest(null);
        return capture(attemptId, childTaskId, request) == SettlementResult.SETTLED;
    }

    @Transactional
    public void releaseDeferredSuccess(Long attemptId, Long childTaskId) {
        WorkflowStepCharge charge = chargeMapper.selectByAttemptIdForUpdate(attemptId);
        if (charge == null || !WorkflowChargeStatus.AWAITING_FUNDS.name().equals(charge.getStatus())) {
            return;
        }
        releaseSuccessfulAttempt(
                charge,
                childTaskId,
                readSettlementPayload(charge).toWorkerSuccessRequest(null)
        );
    }

    private void releaseSuccessfulAttempt(WorkflowStepCharge charge,
                                          Long childTaskId,
                                          WorkerSuccessRequest request) {
        if (charge == null) {
            return;
        }
        request = canonicalSettlementRequest(charge, request);
        if (WorkflowChargeStatus.CAPTURED.name().equals(charge.getStatus())) {
            attachActualProviderAccounting(charge, childTaskId, request);
            return;
        }
        boolean reserved = WorkflowChargeStatus.RESERVED.name().equals(charge.getStatus())
                || WorkflowChargeStatus.AWAITING_FUNDS.name().equals(charge.getStatus());
        boolean released = WorkflowChargeStatus.RELEASED.name().equals(charge.getStatus());
        if (!reserved && !released) {
            return;
        }
        if (released) {
            attachRecordedProviderRequestId(charge, request.providerRequestId());
        }
        if (Boolean.FALSE.equals(request.providerCalled())) {
            if (released) {
                return;
            }
            releaseSuccessfulNoProviderCall(charge);
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
        if (childTaskId != null) {
            taskMapper.findById(childTaskId)
                    .orElseThrow(() -> new IllegalStateException("Workflow child task does not exist"));
        }
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
        validateProviderCallDeclaration(request);
        if (charge.getBillingUsageId() == null) {
            throw new IllegalStateException("Captured workflow charge has no billing usage to attach provider cost");
        }
        attachRecordedProviderRequestId(charge, request.providerRequestId());
        if (request.providerCostAmount() == null) {
            return;
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

    private void attachRecordedProviderRequestId(WorkflowStepCharge charge, String providerRequestId) {
        if (charge.getBillingUsageId() != null) {
            usageService.attachProviderRequestId(charge.getBillingUsageId(), providerRequestId);
        }
    }

    private void pauseForFunds(WorkflowRun run, WorkflowRunStep step) {
        pauseForFunds(run, step, "Insufficient credits for workflow step");
    }

    @Transactional
    public void pauseForSettlementFunds(WorkflowRun run, WorkflowRunStep step) {
        pauseForFunds(run, step, "Provider succeeded; recharge to settle actual workflow usage");
    }

    private void pauseForFunds(WorkflowRun run, WorkflowRunStep step, String errorMessage) {
        if (runMapper.markAwaitingFunds(
                run.getId(), revision(run), step.getId(), errorMessage
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

    @Transactional
    public void resumeAfterSettlement(WorkflowRun run, WorkflowRunStep step) {
        resumeAfterReservation(run, step);
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

    public int perCharacterReservationCredits(ToolWorkflowVersion version,
                                              WorkflowRunStep step,
                                              int publishedEstimate) {
        JsonNode nodePolicy = readJson(version.getBillingPolicyJson())
                .path("nodePolicies")
                .path(step.getNodeId());
        if (!"MODEL_PRICING".equals(nodePolicy.path("pricingSource").asText())) {
            return publishedEstimate;
        }
        AgentModelConfig modelConfig = readModelPricing(nodePolicy.get("modelPricingSnapshot"));
        if (modelConfig == null || !"PER_CHARACTER".equalsIgnoreCase(modelConfig.getBillingUnit())) {
            return publishedEstimate;
        }

        String handlerKey = nodePolicy.path("staticParams").path("handlerKey").asText("").trim();
        JsonNode dynamicInput = readJson(step.getInputJson());
        String speechText = reservationSpeechText(dynamicInput, handlerKey);
        if (speechText.isBlank()) {
            return "comic.shot_tts".equals(handlerKey) ? 0 : publishedEstimate;
        }

        ObjectNode params = (ObjectNode) billingParams(nodePolicy, step);
        params.put("text", speechText);
        PricingQuote quote = pricingService.computeQuote(
                readPricingPolicy(nodePolicy.path("pricingPolicy")),
                modelConfig,
                params,
                null,
                requiredNonNegativeInteger(nodePolicy, "fallbackChargeCredits", step.getNodeId())
        );
        if (!quote.modelDerived() || quote.chargeCredits() <= 0) {
            throw new IllegalStateException(
                    "Workflow PER_CHARACTER reservation has no valid dynamic quote: " + step.getNodeId()
            );
        }
        return quote.chargeCredits();
    }

    private String reservationSpeechText(JsonNode input, String handlerKey) {
        JsonNode operationInput = input.path("operationInput");
        if ("comic.shot_tts".equals(handlerKey)) {
            JsonNode shot = findNamedValue(operationInput, "shot");
            return sceneSpeechText(shot != null && shot.isObject() ? shot : operationInput);
        }

        JsonNode scenes = findNamedValue(input, "scenes", "shots");
        String fallback = firstText(input.path("form"), "plotOutline", "text", "content");
        if (scenes != null && scenes.isArray()) {
            StringBuilder combined = new StringBuilder();
            for (JsonNode scene : scenes) {
                String text = sceneSpeechText(scene);
                if (text.isBlank()) {
                    text = fallback;
                }
                combined.append(text);
            }
            if (!combined.isEmpty()) {
                return combined.toString();
            }
        }
        String direct = firstText(operationInput, "text", "input", "prompt", "content", "plotOutline");
        return direct.isBlank() ? fallback : direct;
    }

    private String sceneSpeechText(JsonNode scene) {
        if (scene == null || !scene.isObject()) {
            return "";
        }
        JsonNode audio = scene.path("audio");
        for (String value : List.of(
                firstText(audio, "dialogue"),
                firstText(scene, "dialogue"),
                firstText(audio, "narration"),
                firstText(scene, "narration")
        )) {
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String firstText(JsonNode value, String... names) {
        if (value == null || !value.isObject()) {
            return "";
        }
        for (String name : names) {
            JsonNode candidate = value.get(name);
            if (candidate != null && candidate.isTextual() && !candidate.textValue().isBlank()) {
                return candidate.textValue().trim();
            }
        }
        return "";
    }

    private JsonNode findNamedValue(JsonNode value, String... names) {
        return findNamedValue(value, 0, names);
    }

    private JsonNode findNamedValue(JsonNode value, int depth, String... names) {
        if (value == null || value.isNull() || depth > 8) {
            return null;
        }
        if (value.isObject()) {
            for (String name : names) {
                JsonNode candidate = value.get(name);
                if (candidate != null && !candidate.isNull()) {
                    return candidate;
                }
            }
            var children = value.elements();
            while (children.hasNext()) {
                JsonNode found = findNamedValue(children.next(), depth + 1, names);
                if (found != null) {
                    return found;
                }
            }
        } else if (value.isArray()) {
            for (JsonNode child : value) {
                JsonNode found = findNamedValue(child, depth + 1, names);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
        String pricingSource = nodePolicy.path("pricingSource").asText("").trim().toUpperCase(Locale.ROOT);
        int fallback = requiredNonNegativeInteger(nodePolicy, "fallbackChargeCredits", step.getNodeId());
        if ("MODEL_PRICING".equals(pricingSource)) {
            validateModelPricingSnapshot(modelConfig, step.getNodeId());
            if (fallback != 0) {
                throw new IllegalStateException("MODEL_PRICING workflow node cannot have fallback credits: " + step.getNodeId());
            }
            if (request.providerCalled() == null) {
                throw new IllegalStateException(
                        "MODEL_PRICING workflow settlement must declare providerCalled: " + step.getNodeId()
                );
            }
            if (Boolean.FALSE.equals(request.providerCalled())) {
                return new Settlement(BigDecimal.ZERO, 0, pricingPolicy.markupRatio(), modelConfig);
            }
            requireReportedUsage(modelConfig, request, step.getNodeId());
        } else if ("TOOL_FALLBACK".equals(pricingSource)) {
            if (modelConfig != null || fallback <= 0) {
                throw new IllegalStateException(
                        "TOOL_FALLBACK workflow node must have no model and a positive fallback: " + step.getNodeId()
                );
            }
        } else if ("LOCAL_ZERO_COST".equals(pricingSource)) {
            validateLocalZeroCostPolicy(nodePolicy, modelConfig, fallback, step.getNodeId());
        } else {
            throw new IllegalStateException("Workflow node has no valid pricingSource: " + step.getNodeId());
        }
        PricingQuote quote = pricingService.computeQuote(
                pricingPolicy,
                modelConfig,
                billingParams(nodePolicy, step),
                new PricingUsage(request.promptTokens(), request.completionTokens(), request.billableUnits()),
                fallback
        );
        if ("MODEL_PRICING".equals(pricingSource)
                && (!quote.modelDerived()
                || quote.vendorCost() == null
                || quote.vendorCost().signum() <= 0
                || quote.chargeCredits() <= 0)) {
            throw new IllegalStateException(
                    "Workflow model settlement has no valid billable usage: " + step.getNodeId()
            );
        }
        if ("TOOL_FALLBACK".equals(pricingSource)
                && (quote.modelDerived() || quote.chargeCredits() <= 0)) {
            throw new IllegalStateException("Workflow fallback settlement is not valid: " + step.getNodeId());
        }
        if ("LOCAL_ZERO_COST".equals(pricingSource)
                && (quote.modelDerived() || quote.chargeCredits() != 0 || quote.vendorCost().signum() != 0)) {
            throw new IllegalStateException("Workflow local settlement must remain zero-cost: " + step.getNodeId());
        }
        return new Settlement(quote.vendorCost(), quote.chargeCredits(), quote.markupRatio(), modelConfig);
    }

    private int requiredNonNegativeInteger(JsonNode nodePolicy, String field, String nodeId) {
        JsonNode value = nodePolicy.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IllegalStateException("Workflow pricing snapshot has invalid " + field + ": " + nodeId);
        }
        return value.intValue();
    }

    private void validateModelPricingSnapshot(AgentModelConfig modelConfig, String nodeId) {
        if (modelConfig == null
                || modelConfig.getId() == null
                || modelConfig.getId() <= 0
                || modelConfig.getProvider() == null
                || modelConfig.getProvider().isBlank()
                || modelConfig.getModelName() == null
                || modelConfig.getModelName().isBlank()) {
            throw new IllegalStateException("MODEL_PRICING workflow node has no valid model snapshot: " + nodeId);
        }
        String unit = modelConfig.getBillingUnit() == null
                ? ""
                : modelConfig.getBillingUnit().trim().toUpperCase(Locale.ROOT);
        boolean priced = switch (unit) {
            case "PER_CALL", "PER_SECOND", "PER_CHARACTER" -> positive(modelConfig.getUnitPrice());
            case "TOKEN_PER_M", "IMAGE_TOKEN" -> positive(modelConfig.getInputTokenPricePer1m())
                    || positive(modelConfig.getOutputTokenPricePer1m())
                    || positive(modelConfig.getInputTokenPricePer1k())
                    || positive(modelConfig.getOutputTokenPricePer1k());
            default -> false;
        };
        if (!priced) {
            throw new IllegalStateException("MODEL_PRICING workflow node has no usable model price: " + nodeId);
        }
    }

    private void validateLocalZeroCostPolicy(JsonNode nodePolicy,
                                             AgentModelConfig modelConfig,
                                             int fallback,
                                             String nodeId) {
        String handlerKey = nodePolicy.path("staticParams").path("handlerKey").asText("").trim();
        BigDecimal estimatedCost = decimalField(nodePolicy, "estimatedProviderCostCny", nodeId);
        BigDecimal legacyEstimatedCost = decimalField(nodePolicy, "maxProviderCostCny", nodeId);
        int maxCreditCost = requiredNonNegativeInteger(nodePolicy, "maxCreditCost", nodeId);
        if (modelConfig != null
                || fallback != 0
                || maxCreditCost != 0
                || estimatedCost.signum() != 0
                || legacyEstimatedCost.signum() != 0
                || !LOCAL_ZERO_COST_HANDLER_ALLOWLIST.contains(handlerKey)) {
            throw new IllegalStateException("LOCAL_ZERO_COST workflow node violates its pricing invariant: " + nodeId);
        }
    }

    private BigDecimal decimalField(JsonNode nodePolicy, String field, String nodeId) {
        JsonNode value = nodePolicy.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalStateException("Workflow pricing snapshot has invalid " + field + ": " + nodeId);
        }
        return value.decimalValue();
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private void requireReportedUsage(AgentModelConfig modelConfig,
                                      WorkerSuccessRequest request,
                                      String nodeId) {
        if (modelConfig == null || !Boolean.TRUE.equals(request.providerCalled())) {
            return;
        }
        String unit = modelConfig.getBillingUnit() == null
                ? ""
                : modelConfig.getBillingUnit().trim().toUpperCase(java.util.Locale.ROOT);
        boolean valid = switch (unit) {
            case "TOKEN_PER_M", "IMAGE_TOKEN" ->
                    (request.promptTokens() != null || request.completionTokens() != null)
                            && (Math.max(0, request.promptTokens() == null ? 0 : request.promptTokens())
                            + Math.max(0, request.completionTokens() == null ? 0 : request.completionTokens()) > 0);
            case "PER_CALL", "PER_SECOND", "PER_CHARACTER" ->
                    request.billableUnits() != null && request.billableUnits() > 0;
            default -> false;
        };
        if (!valid) {
            throw new IllegalStateException(
                    "Workflow model settlement is missing actual usage for " + unit + ": " + nodeId
            );
        }
    }

    private void validateProviderCallDeclaration(WorkerSuccessRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Workflow settlement request is required");
        }
        requireNonNegative("promptTokens", request.promptTokens());
        requireNonNegative("completionTokens", request.completionTokens());
        requireNonNegative("billableUnits", request.billableUnits());
        if (request.providerCostAmount() != null && request.providerCostAmount().signum() < 0) {
            throw new IllegalArgumentException("Provider cost amount cannot be negative");
        }
        String requestId = normalizeProviderRequestId(request.providerRequestId());
        if (Boolean.FALSE.equals(request.providerCalled())
                && (positive(request.promptTokens())
                || positive(request.completionTokens())
                || positive(request.billableUnits())
                || positive(request.providerCostAmount())
                || requestId != null)) {
            throw new IllegalStateException(
                    "providerCalled=false conflicts with reported provider usage or accounting"
            );
        }
    }

    private void requireNonNegative(String field, Integer value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
    }

    private boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private String settlementPayloadJson(WorkerSuccessRequest request) {
        try {
            return objectMapper.writeValueAsString(settlementPayload(request));
        } catch (Exception exception) {
            throw new IllegalStateException("Workflow settlement payload could not be serialized", exception);
        }
    }

    private SettlementPayload settlementPayload(WorkerSuccessRequest request) {
        String currency = request.providerCostCurrency() == null
                ? null
                : request.providerCostCurrency().trim().toUpperCase(Locale.ROOT);
        return new SettlementPayload(
                request.promptTokens(),
                request.completionTokens(),
                request.billableUnits(),
                request.providerCostAmount(),
                currency,
                normalizeProviderRequestId(request.providerRequestId()),
                request.providerCalled()
        );
    }

    private SettlementPayload readSettlementPayload(WorkflowStepCharge charge) {
        String json = charge.getSettlementPayloadJson();
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Deferred workflow settlement has no persisted payload");
        }
        try {
            SettlementPayload payload = objectMapper.readValue(json, SettlementPayload.class);
            if (payload == null) {
                throw new IllegalStateException("Deferred workflow settlement payload is empty");
            }
            return payload;
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("Deferred workflow settlement payload is invalid", exception);
        }
    }

    private void validateSettlementPayload(WorkflowStepCharge charge, WorkerSuccessRequest request) {
        SettlementPayload persisted = readSettlementPayload(charge);
        SettlementPayload received = settlementPayload(request);
        if (!persisted.sameAccounting(received)) {
            throw new IllegalStateException("Deferred workflow settlement callback conflicts with persisted usage");
        }
    }

    private WorkerSuccessRequest canonicalSettlementRequest(WorkflowStepCharge charge,
                                                             WorkerSuccessRequest request) {
        validateProviderCallDeclaration(request);
        boolean awaitingFunds = WorkflowChargeStatus.AWAITING_FUNDS.name().equals(charge.getStatus());
        boolean hasPersistedPayload = charge.getSettlementPayloadJson() != null
                && !charge.getSettlementPayloadJson().isBlank();
        if (!awaitingFunds && !hasPersistedPayload) {
            return request;
        }
        SettlementPayload persisted = readSettlementPayload(charge);
        if (!persisted.sameAccounting(settlementPayload(request))) {
            throw new IllegalStateException("Deferred workflow settlement callback conflicts with persisted usage");
        }
        return persisted.toWorkerSuccessRequest(request.contentText());
    }

    private void releaseSuccessfulNoProviderCall(WorkflowStepCharge charge) {
        creditService.releaseReserved(
                charge.getUserId(),
                CreditSourceType.WORKFLOW_STEP,
                charge.getStepId(),
                charge.getReservedCredits(),
                charge.getIdempotencyKey() + ":release"
        );
        if (chargeMapper.markReleased(charge.getId(), null, null, null) != 1) {
            throw new IllegalStateException("Workflow no-provider success could not release its reservation");
        }
        metrics.recordBillingState(WorkflowMetrics.BillingState.RELEASED);
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

    public enum SettlementResult {
        SETTLED,
        AWAITING_FUNDS
    }

    private record SettlementPayload(Integer promptTokens,
                                     Integer completionTokens,
                                     Integer billableUnits,
                                     BigDecimal providerCostAmount,
                                     String providerCostCurrency,
                                     String providerRequestId,
                                     Boolean providerCalled) {

        private WorkerSuccessRequest toWorkerSuccessRequest(String outputJson) {
            return new WorkerSuccessRequest(
                    "JSON",
                    outputJson == null || outputJson.isBlank() ? "{}" : outputJson,
                    promptTokens,
                    completionTokens,
                    billableUnits,
                    providerCostAmount,
                    providerCostCurrency,
                    providerRequestId,
                    providerCalled,
                    null
            );
        }

        private boolean sameAccounting(SettlementPayload other) {
            return other != null
                    && Objects.equals(promptTokens, other.promptTokens)
                    && Objects.equals(completionTokens, other.completionTokens)
                    && Objects.equals(billableUnits, other.billableUnits)
                    && sameDecimal(providerCostAmount, other.providerCostAmount)
                    && Objects.equals(providerCostCurrency, other.providerCostCurrency)
                    && Objects.equals(providerRequestId, other.providerRequestId)
                    && Objects.equals(providerCalled, other.providerCalled);
        }

        private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
            return left == null ? right == null : right != null && left.compareTo(right) == 0;
        }
    }

    private record ProviderAccounting(BigDecimal amount, String currency, String requestId, boolean charged) {
    }

    private record FailureProviderAccounting(BigDecimal amount, String currency, String requestId) {
    }
}
