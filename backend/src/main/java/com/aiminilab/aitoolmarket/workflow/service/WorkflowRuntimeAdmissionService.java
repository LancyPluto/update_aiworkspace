package com.aiminilab.aitoolmarket.workflow.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.mapper.CreditMapper;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflow;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowVersionMapper;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDsl;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowNodeDef;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowNodeDefType;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepChargeMapper;
import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class WorkflowRuntimeAdmissionService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<String> LOCAL_ZERO_COST_HANDLER_ALLOWLIST = Set.of("comic.compose");

    private final ToolWorkflowMapper workflowMapper;
    private final ToolWorkflowVersionMapper versionMapper;
    private final WorkflowStepChargeMapper chargeMapper;
    private final CreditMapper creditMapper;
    private final WorkflowDslService dslService;
    private final WorkflowRuntimeGate gate;
    private final ObjectMapper objectMapper;
    private final WorkflowMetrics metrics;
    private final Clock clock;

    @Autowired
    public WorkflowRuntimeAdmissionService(ToolWorkflowMapper workflowMapper,
                                           ToolWorkflowVersionMapper versionMapper,
                                           WorkflowStepChargeMapper chargeMapper,
                                           CreditMapper creditMapper,
                                           WorkflowDslService dslService,
                                           WorkflowRuntimeGate gate,
                                           ObjectMapper objectMapper,
                                           WorkflowMetrics metrics) {
        this(workflowMapper, versionMapper, chargeMapper, creditMapper, dslService, gate, objectMapper,
                metrics,
                Clock.system(SHANGHAI));
    }

    public WorkflowRuntimeAdmissionService(ToolWorkflowMapper workflowMapper,
                                           ToolWorkflowVersionMapper versionMapper,
                                           WorkflowStepChargeMapper chargeMapper,
                                           CreditMapper creditMapper,
                                           WorkflowDslService dslService,
                                           WorkflowRuntimeGate gate,
                                           ObjectMapper objectMapper,
                                           WorkflowMetrics metrics,
                                           Clock clock) {
        this.workflowMapper = workflowMapper;
        this.versionMapper = versionMapper;
        this.chargeMapper = chargeMapper;
        this.creditMapper = creditMapper;
        this.dslService = dslService;
        this.gate = gate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public WorkflowRuntimeAdmission admitNewRun(Long userId, Long toolId) {
        return admitNewRun(userId, toolId, Set.of());
    }

    @Transactional
    public WorkflowRuntimeAdmission admitNewRun(Long userId, Long toolId, String operationHandlerKey) {
        return admitNewRun(
                userId,
                toolId,
                operationHandlerKey == null ? Set.of() : Set.of(operationHandlerKey)
        );
    }

    @Transactional
    public WorkflowRuntimeAdmission admitNewRun(Long userId,
                                                Long toolId,
                                                Collection<String> operationHandlerKeys) {
        if (userId == null || toolId == null) {
            throw blocked("admission_identity_invalid");
        }
        ToolWorkflow workflow = workflowMapper.selectCanonicalPublishedByToolId(toolId);
        if (workflow == null || workflow.getPublishedVersionId() == null) {
            throw blocked("canonical_workflow_not_published");
        }

        requireAllowed(gate.evaluateBaseNewRun(userId));

        ToolWorkflowVersion version = versionMapper.selectById(workflow.getPublishedVersionId());
        if (version == null || !workflow.getId().equals(version.getWorkflowId())) {
            throw blocked("published_version_invalid");
        }

        WorkflowDsl dsl;
        try {
            dsl = dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson());
        } catch (RuntimeException exception) {
            throw blocked("published_version_invalid");
        }
        if (dsl == null || dsl.nodes() == null) {
            throw blocked("published_version_invalid");
        }
        Set<String> operationNodeIds = operationNodeIds(dsl, operationHandlerKeys);
        if (operationNodeIds.isEmpty()
                && dsl.nodes().stream().anyMatch(node -> node.type() == WorkflowNodeDefType.USER_CONFIRM)
                && !gate.isConfirmationEnabled()) {
            throw blocked("confirmation_disabled");
        }

        long estimatedRunCredits = estimatedRunCredits(version, dsl, operationNodeIds);
        boolean paidRun = estimatedRunCredits > 0;
        long committedToday = paidRun ? committedToday(userId) : 0L;
        if (paidRun && unknownProviderCostsToday() > 0) {
            if (metrics != null) {
                metrics.recordProviderCostAnomaly(WorkflowMetrics.ProviderCostAnomaly.ACTUAL_COST_UNKNOWN);
            }
        }
        if (paidRun && unsupportedProviderCostCurrenciesToday() > 0) {
            if (metrics != null) {
                metrics.recordProviderCostAnomaly(WorkflowMetrics.ProviderCostAnomaly.CURRENCY_UNSUPPORTED);
            }
        }
        WorkflowRuntimeGate.Decision decision = gate.evaluateNewRun(
                userId,
                paidRun,
                estimatedRunCredits,
                committedToday
        );
        requireAllowed(decision);
        return new WorkflowRuntimeAdmission(
                workflow,
                version,
                dsl,
                paidRun,
                estimatedRunCredits,
                BigDecimal.ZERO.setScale(6)
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockNewRunAdmission(Long userId) {
        if (userId == null) {
            throw blocked("admission_identity_invalid");
        }
        lockCreditAccount(userId);
    }

    public void rejectLegacyWorkflowEntry() {
        throw blocked("legacy_workflow_entry_disabled");
    }

    private long estimatedRunCredits(ToolWorkflowVersion version,
                                     WorkflowDsl dsl,
                                     Set<String> operationNodeIds) {
        try {
            JsonNode root = objectMapper.readTree(version.getBillingPolicyJson());
            JsonNode nodePolicies = root == null ? null : root.get("nodePolicies");
            if (root == null || !root.isObject() || nodePolicies == null || !nodePolicies.isObject()) {
                throw new IllegalArgumentException("nodePolicies missing");
            }

            Set<String> workerNodeIds = new HashSet<>();
            dsl.nodes().stream()
                    .filter(node -> node.type().isWorkerStep())
                    .forEach(node -> workerNodeIds.add(node.id()));
            if (!workerNodeIds.equals(fieldNames(nodePolicies))) {
                throw new IllegalArgumentException("nodePolicies do not match worker nodes");
            }

            long total = 0L;
            Set<String> chargedNodeIds = operationNodeIds.isEmpty() ? workerNodeIds : operationNodeIds;
            for (String nodeId : chargedNodeIds) {
                JsonNode nodePolicy = nodePolicies.path(nodeId);
                long maxCreditCost = requiredNonNegativeInteger(nodePolicy, "maxCreditCost");
                validatePricingPolicy(dsl.requireNode(nodeId), nodePolicy, maxCreditCost);
                total = Math.addExact(total, maxCreditCost);
            }
            return total;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw blocked("billing_policy_invalid");
        }
    }

    private void validatePricingPolicy(WorkflowNodeDef node,
                                       JsonNode nodePolicy,
                                       long maxCreditCost) {
        if (!nodePolicy.isObject()
                || !nodePolicy.path("staticParams").isObject()
                || !nodePolicy.has("modelPricingSnapshot")
                || !nodePolicy.path("pricingPolicy").isObject()
                || !positiveDecimal(nodePolicy.path("pricingPolicy").get("markupRatio"))) {
            throw new IllegalArgumentException("incomplete immutable pricing policy");
        }

        JsonNode pricingSourceNode = nodePolicy.get("pricingSource");
        if (pricingSourceNode == null
                || !pricingSourceNode.isTextual()
                || pricingSourceNode.textValue().isBlank()) {
            throw new IllegalArgumentException("pricingSource missing");
        }
        String pricingSource = pricingSourceNode.textValue().trim();
        long fallbackCredits = requiredNonNegativeInteger(nodePolicy, "fallbackChargeCredits");
        BigDecimal estimatedProviderCost = requiredNonNegativeDecimal(
                nodePolicy,
                "estimatedProviderCostCny"
        );
        BigDecimal legacyProviderCost = requiredNonNegativeDecimal(
                nodePolicy,
                "maxProviderCostCny"
        );
        JsonNode modelPricingSnapshot = nodePolicy.get("modelPricingSnapshot");

        switch (pricingSource) {
            case "MODEL_PRICING" -> {
                if (!validModelPricingSnapshot(modelPricingSnapshot)
                        || fallbackCredits != 0
                        || maxCreditCost <= 0
                        || estimatedProviderCost.signum() <= 0
                        || legacyProviderCost.signum() <= 0) {
                    throw new IllegalArgumentException("invalid model pricing policy");
                }
            }
            case "TOOL_FALLBACK" -> {
                if (modelPricingSnapshot == null
                        || !modelPricingSnapshot.isNull()
                        || fallbackCredits <= 0
                        || maxCreditCost <= 0) {
                    throw new IllegalArgumentException("invalid tool fallback pricing policy");
                }
            }
            case "LOCAL_ZERO_COST" -> {
                String handlerKey = handlerKey(node.parameters());
                String snapshotHandlerKey = handlerKey(nodePolicy.path("staticParams"));
                if (modelPricingSnapshot == null
                        || !modelPricingSnapshot.isNull()
                        || fallbackCredits != 0
                        || maxCreditCost != 0
                        || estimatedProviderCost.signum() != 0
                        || legacyProviderCost.signum() != 0
                        || !LOCAL_ZERO_COST_HANDLER_ALLOWLIST.contains(handlerKey)
                        || !handlerKey.equals(snapshotHandlerKey)) {
                    throw new IllegalArgumentException("invalid local zero-cost pricing policy");
                }
            }
            default -> throw new IllegalArgumentException("invalid pricingSource");
        }
    }

    private long requiredNonNegativeInteger(JsonNode nodePolicy, String field) {
        JsonNode value = nodePolicy.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.longValue() < 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value.longValue();
    }

    private BigDecimal requiredNonNegativeDecimal(JsonNode nodePolicy, String field) {
        JsonNode value = nodePolicy.get(field);
        if (value == null || !value.isNumber() || value.decimalValue().signum() < 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value.decimalValue();
    }

    private boolean validModelPricingSnapshot(JsonNode snapshot) {
        if (snapshot == null
                || !snapshot.isObject()
                || !positiveInteger(snapshot.get("id"))
                || !nonBlankText(snapshot.get("provider"))
                || !nonBlankText(snapshot.get("modelName"))
                || !nonBlankText(snapshot.get("billingUnit"))) {
            return false;
        }
        return switch (snapshot.path("billingUnit").asText().trim().toUpperCase(Locale.ROOT)) {
            case "PER_CALL", "PER_SECOND" -> positiveDecimal(snapshot.get("unitPrice"));
            case "TOKEN_PER_M", "IMAGE_TOKEN" -> positiveDecimal(snapshot.get("inputTokenPricePer1m"))
                    || positiveDecimal(snapshot.get("outputTokenPricePer1m"))
                    || positiveDecimal(snapshot.get("inputTokenPricePer1k"))
                    || positiveDecimal(snapshot.get("outputTokenPricePer1k"));
            default -> false;
        };
    }

    private boolean positiveInteger(JsonNode value) {
        return value != null
                && value.isIntegralNumber()
                && value.canConvertToLong()
                && value.longValue() > 0;
    }

    private boolean positiveDecimal(JsonNode value) {
        return value != null && value.isNumber() && value.decimalValue().signum() > 0;
    }

    private boolean nonBlankText(JsonNode value) {
        return value != null && value.isTextual() && !value.textValue().isBlank();
    }

    private Set<String> operationNodeIds(WorkflowDsl dsl, Collection<String> operationHandlerKeys) {
        if (operationHandlerKeys == null || operationHandlerKeys.isEmpty()) {
            return Set.of();
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String value : operationHandlerKeys) {
            if (value != null && !value.isBlank()) {
                requested.add(value.trim());
            }
        }
        if (requested.isEmpty()) {
            return Set.of();
        }
        Set<String> nodeIds = new LinkedHashSet<>();
        for (String operationHandlerKey : requested) {
            var matches = dsl.nodes().stream()
                    .filter(node -> operationHandlerKey.equals(handlerKey(node.parameters())))
                    .toList();
            if (matches.size() != 1 || !matches.get(0).type().isWorkerStep()) {
                throw new BusinessException(
                        ErrorCode.PARAM_ERROR,
                        "operationHandlerKey 必须唯一匹配一个 worker 节点: " + operationHandlerKey
                );
            }
            nodeIds.add(matches.get(0).id());
        }
        return Set.copyOf(nodeIds);
    }

    private String handlerKey(JsonNode parameters) {
        if (parameters == null || parameters.isMissingNode()) {
            return null;
        }
        JsonNode value = parameters.get("handlerKey");
        if (value == null || value.isNull() || value.asText().isBlank()) {
            value = parameters.get("operation");
        }
        return value == null || value.isNull() ? null : value.asText().trim();
    }

    private Set<String> fieldNames(JsonNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private long committedToday(Long userId) {
        lockCreditAccount(userId);
        LocalDateTime dayStart = dayStart();
        return chargeMapper.sumCommittedCreditsForUserBetween(
                userId,
                dayStart,
                dayStart.plusDays(1)
        );
    }

    private int unsupportedProviderCostCurrenciesToday() {
        LocalDateTime dayStart = dayStart();
        return chargeMapper.countUnsupportedProviderCostCurrenciesBetween(
                dayStart,
                dayStart.plusDays(1)
        );
    }

    private int unknownProviderCostsToday() {
        LocalDateTime dayStart = dayStart();
        return chargeMapper.countUnknownProviderCostsBetween(
                dayStart,
                dayStart.plusDays(1)
        );
    }

    private LocalDateTime dayStart() {
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        return today.atStartOfDay();
    }

    private void lockCreditAccount(Long userId) {
        creditMapper.getOrCreateAccount(userId);
        if (creditMapper.selectByUserIdForUpdate(userId) == null) {
            throw blocked("credit_account_unavailable");
        }
    }

    private void requireAllowed(WorkflowRuntimeGate.Decision decision) {
        if (!decision.allowed()) {
            throw blocked(decision.reason());
        }
    }

    private BusinessException blocked(String reason) {
        return new BusinessException(
                ErrorCode.WORKFLOW_RUNTIME_BLOCKED,
                "Workflow runtime is not accepting new runs",
                Map.of("reason", reason)
        );
    }
}
