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
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowRunMapper;
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
    private final WorkflowRunMapper runMapper;
    private final CreditMapper creditMapper;
    private final WorkflowDslService dslService;
    private final WorkflowRuntimeGate gate;
    private final ObjectMapper objectMapper;
    private final WorkflowCostAlertNotifier costAlertNotifier;
    private final Clock clock;

    @Autowired
    public WorkflowRuntimeAdmissionService(ToolWorkflowMapper workflowMapper,
                                           ToolWorkflowVersionMapper versionMapper,
                                           WorkflowStepChargeMapper chargeMapper,
                                           WorkflowRunMapper runMapper,
                                           CreditMapper creditMapper,
                                           WorkflowDslService dslService,
                                           WorkflowRuntimeGate gate,
                                           ObjectMapper objectMapper,
                                           WorkflowCostAlertNotifier costAlertNotifier) {
        this(workflowMapper, versionMapper, chargeMapper, runMapper, creditMapper, dslService, gate, objectMapper,
                costAlertNotifier,
                Clock.system(SHANGHAI));
    }

    public WorkflowRuntimeAdmissionService(ToolWorkflowMapper workflowMapper,
                                           ToolWorkflowVersionMapper versionMapper,
                                           WorkflowStepChargeMapper chargeMapper,
                                           CreditMapper creditMapper,
                                           WorkflowDslService dslService,
                                           WorkflowRuntimeGate gate,
                                           ObjectMapper objectMapper,
                                           Clock clock) {
        this(workflowMapper, versionMapper, chargeMapper, null, creditMapper, dslService, gate, objectMapper,
                null, clock);
    }

    private WorkflowRuntimeAdmissionService(ToolWorkflowMapper workflowMapper,
                                            ToolWorkflowVersionMapper versionMapper,
                                            WorkflowStepChargeMapper chargeMapper,
                                            WorkflowRunMapper runMapper,
                                            CreditMapper creditMapper,
                                            WorkflowDslService dslService,
                                            WorkflowRuntimeGate gate,
                                            ObjectMapper objectMapper,
                                            WorkflowCostAlertNotifier costAlertNotifier,
                                            Clock clock) {
        this.workflowMapper = workflowMapper;
        this.versionMapper = versionMapper;
        this.chargeMapper = chargeMapper;
        this.runMapper = runMapper;
        this.creditMapper = creditMapper;
        this.dslService = dslService;
        this.gate = gate;
        this.objectMapper = objectMapper;
        this.costAlertNotifier = costAlertNotifier;
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
        BigDecimal providerCostReservedCny = paidRun
                ? providerCostReservation(version, dsl)
                : BigDecimal.ZERO;
        BigDecimal activeProviderReservations = paidRun
                ? lockAndReadActiveProviderReservations()
                : BigDecimal.ZERO;
        BigDecimal providerCostToday = paidRun ? providerCostToday() : BigDecimal.ZERO;
        if (paidRun && unknownProviderCostsToday() > 0) {
            if (costAlertNotifier != null) {
                costAlertNotifier.notifyLimitReachedAsync(
                        "provider_actual_cost_unknown",
                        providerCostToday
                );
            }
            throw blocked("provider_actual_cost_unknown");
        }
        if (paidRun && unsupportedProviderCostCurrenciesToday() > 0) {
            if (costAlertNotifier != null) {
                costAlertNotifier.notifyLimitReachedAsync(
                        "provider_daily_cost_currency_unsupported",
                        providerCostToday
                );
            }
            throw blocked("provider_daily_cost_currency_unsupported");
        }
        WorkflowRuntimeGate.Decision decision = gate.evaluateNewRun(
                userId,
                Boolean.TRUE.equals(workflow.getExecutionEnabled()),
                paidRun,
                estimatedRunCredits,
                committedToday,
                providerCostToday
        );
        if (!decision.allowed()
                && "provider_daily_cost_limit_exceeded".equals(decision.reason())
                && costAlertNotifier != null) {
            costAlertNotifier.notifyLimitReachedAsync(decision.reason(), providerCostToday);
        }
        requireAllowed(decision);
        if (paidRun) {
            WorkflowRuntimeGate.Decision providerReservationDecision = gate.evaluateProviderCostReservation(
                    providerCostToday,
                    activeProviderReservations,
                    providerCostReservedCny
            );
            if (!providerReservationDecision.allowed() && costAlertNotifier != null) {
                costAlertNotifier.notifyLimitReachedAsync(
                        providerReservationDecision.reason(),
                        providerCostToday.add(activeProviderReservations)
                );
            }
            requireAllowed(providerReservationDecision);
        }
        return new WorkflowRuntimeAdmission(
                workflow,
                version,
                dsl,
                paidRun,
                estimatedRunCredits,
                providerCostReservedCny
        );
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockNewRunAdmission(Long userId) {
        if (userId == null) {
            throw blocked("admission_identity_invalid");
        }
        // New runs must wait behind existing run owners before taking the user's credit lock.
        lockAndReadActiveProviderReservations();
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

    private BigDecimal providerCostToday() {
        LocalDateTime dayStart = dayStart();
        return sumNonNegative(
                chargeMapper.selectProviderCostsBetweenForUpdate(dayStart, dayStart.plusDays(1)),
                "provider_cost_ledger_invalid"
        );
    }

    private BigDecimal providerCostReservation(ToolWorkflowVersion version, WorkflowDsl dsl) {
        try {
            JsonNode root = objectMapper.readTree(version.getBillingPolicyJson());
            JsonNode nodePolicies = root == null ? null : root.get("nodePolicies");
            if (nodePolicies == null || !nodePolicies.isObject()) {
                throw blocked("provider_run_cost_unknown");
            }
            BigDecimal total = BigDecimal.ZERO.setScale(6);
            for (var node : dsl.nodes()) {
                if (!node.type().isWorkerStep()) {
                    continue;
                }
                JsonNode value = nodePolicies.path(node.id()).get("maxProviderCostCny");
                if (value == null || !value.isNumber()) {
                    throw blocked("provider_run_cost_unknown");
                }
                BigDecimal amount = value.decimalValue();
                if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 6) {
                    throw blocked("provider_run_cost_unknown");
                }
                amount = amount.setScale(6);
                if (amount.precision() - amount.scale() > 12) {
                    throw blocked("provider_run_cost_unknown");
                }
                total = total.add(amount);
                if (total.precision() - total.scale() > 12) {
                    throw blocked("provider_run_cost_unknown");
                }
            }
            return total.signum() > 0 ? total : BigDecimal.ZERO.setScale(6);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw blocked("provider_run_cost_unknown");
        }
    }

    private BigDecimal lockAndReadActiveProviderReservations() {
        if (runMapper == null) {
            return BigDecimal.ZERO;
        }
        LocalDate budgetDate = dayStart().toLocalDate();
        // The upsert requests an exclusive lock directly and avoids duplicate-key S-to-X upgrade deadlocks.
        runMapper.acquireProviderCostBudgetDayLock(budgetDate);
        java.util.List<Long> missingReservations =
                runMapper.selectActiveRunsWithMissingProviderCostReservationForUpdate();
        if (missingReservations == null || !missingReservations.isEmpty()) {
            throw blocked("provider_active_reservation_unknown");
        }
        return sumNonNegative(
                runMapper.selectActiveProviderCostReservationsForUpdate(),
                "provider_cost_budget_invalid"
        );
    }

    private BigDecimal sumNonNegative(java.util.List<BigDecimal> values, String invalidReason) {
        if (values == null) {
            throw blocked(invalidReason);
        }
        BigDecimal total = BigDecimal.ZERO.setScale(6);
        for (BigDecimal value : values) {
            if (value == null || value.signum() < 0) {
                throw blocked(invalidReason);
            }
            total = total.add(value);
        }
        return total;
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
