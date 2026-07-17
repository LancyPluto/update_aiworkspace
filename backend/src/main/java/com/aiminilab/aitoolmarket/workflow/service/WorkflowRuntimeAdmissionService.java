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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class WorkflowRuntimeAdmissionService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

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
        if (userId == null || toolId == null) {
            throw blocked("admission_identity_invalid");
        }
        ToolWorkflow workflow = workflowMapper.selectCanonicalPublishedByToolId(toolId);
        if (workflow == null || workflow.getPublishedVersionId() == null) {
            throw blocked("canonical_workflow_not_published");
        }

        requireAllowed(gate.evaluateBaseNewRun(
                userId,
                Boolean.TRUE.equals(workflow.getExecutionEnabled())
        ));

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
        if (dsl.nodes().stream().anyMatch(node -> node.type() == WorkflowNodeDefType.USER_CONFIRM)
                && !gate.isConfirmationEnabled()) {
            throw blocked("confirmation_disabled");
        }

        long estimatedRunCredits = estimatedRunCredits(version, dsl);
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
                Boolean.TRUE.equals(workflow.getExecutionEnabled()),
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

    private long estimatedRunCredits(ToolWorkflowVersion version, WorkflowDsl dsl) {
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
            for (String nodeId : workerNodeIds) {
                JsonNode maxCreditCost = nodePolicies.path(nodeId).get("maxCreditCost");
                if (maxCreditCost == null
                        || !maxCreditCost.isIntegralNumber()
                        || !maxCreditCost.canConvertToLong()
                        || maxCreditCost.longValue() <= 0) {
                    throw new IllegalArgumentException("invalid maxCreditCost");
                }
                total = Math.addExact(total, maxCreditCost.longValue());
            }
            return total;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw blocked("billing_policy_invalid");
        }
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
