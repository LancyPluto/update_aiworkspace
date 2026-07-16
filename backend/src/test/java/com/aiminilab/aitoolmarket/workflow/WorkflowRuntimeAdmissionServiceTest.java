package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.credit.entity.CreditAccount;
import com.aiminilab.aitoolmarket.credit.mapper.CreditMapper;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflow;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowMapper;
import com.aiminilab.aitoolmarket.tool.mapper.ToolWorkflowVersionMapper;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeGate;
import com.aiminilab.aitoolmarket.workflow.config.WorkflowRuntimeProperties;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDsl;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowNodeDef;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowNodeDefType;
import com.aiminilab.aitoolmarket.workflow.mapper.WorkflowStepChargeMapper;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowDslService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRuntimeAdmission;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRuntimeAdmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRuntimeAdmissionServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private ToolWorkflowMapper workflowMapper;
    private ToolWorkflowVersionMapper versionMapper;
    private WorkflowStepChargeMapper chargeMapper;
    private CreditMapper creditMapper;
    private WorkflowDslService dslService;
    private WorkflowRuntimeProperties properties;
    private WorkflowRuntimeGate gate;
    private WorkflowRuntimeAdmissionService service;
    private ToolWorkflow workflow;
    private ToolWorkflowVersion version;

    @BeforeEach
    void setUp() {
        workflowMapper = mock(ToolWorkflowMapper.class);
        versionMapper = mock(ToolWorkflowVersionMapper.class);
        chargeMapper = mock(WorkflowStepChargeMapper.class);
        creditMapper = mock(CreditMapper.class);
        dslService = mock(WorkflowDslService.class);
        properties = new WorkflowRuntimeProperties();
        gate = new WorkflowRuntimeGate(properties);
        service = new WorkflowRuntimeAdmissionService(
                workflowMapper,
                versionMapper,
                chargeMapper,
                creditMapper,
                dslService,
                gate,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-15T04:30:00Z"), SHANGHAI)
        );

        workflow = new ToolWorkflow();
        workflow.setId(31L);
        workflow.setToolId(7L);
        workflow.setPublishedVersionId(41L);
        workflow.setExecutionEnabled(true);
        version = new ToolWorkflowVersion();
        version.setId(41L);
        version.setWorkflowId(31L);
        version.setVersion(3);

        when(workflowMapper.selectCanonicalPublishedByToolId(7L)).thenReturn(workflow);
        when(versionMapper.selectById(41L)).thenReturn(version);
        when(creditMapper.selectByUserIdForUpdate(11L)).thenReturn(new CreditAccount());
        when(chargeMapper.sumCommittedCreditsForUserBetween(eq(11L), any(), any())).thenReturn(40L);
        when(chargeMapper.selectProviderCostsBetweenForUpdate(any(), any()))
                .thenReturn(List.of(new BigDecimal("12.50")));
        when(chargeMapper.countUnsupportedProviderCostCurrenciesBetween(any(), any())).thenReturn(0);
        when(chargeMapper.countUnknownProviderCostsBetween(any(), any())).thenReturn(0);
    }

    @Test
    void defaultDisabledRuntimeRejectsBeforeAnyRunCanBeCreated() {
        version.setBillingPolicyJson("{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{}}");
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl());

        assertBlocked(() -> service.admitNewRun(11L, 7L), "runtime_disabled");

        verify(chargeMapper, never()).sumCommittedCreditsForUserBetween(any(), any(), any());
    }

    @Test
    void paidBudgetComesOnlyFromPinnedVersionAndCommittedDatabaseLedger() {
        enableHealthyRuntime(100, 100);
        version.setBillingPolicyJson("""
                {"mode":"WORKFLOW_STEP","nodePolicies":{
                  "writer":{"maxCreditCost":20,"maxProviderCostCny":0.20},
                  "renderer":{"maxCreditCost":30,"maxProviderCostCny":0.30}
                }}
                """);
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl(worker("writer"), worker("renderer")));

        WorkflowRuntimeAdmission admitted = service.admitNewRun(11L, 7L);

        assertThat(admitted.workflow()).isSameAs(workflow);
        assertThat(admitted.version()).isSameAs(version);
        assertThat(admitted.paidRun()).isTrue();
        assertThat(admitted.estimatedRunCredits()).isEqualTo(50L);
        assertThat(admitted.providerCostReservedCny()).isEqualByComparingTo("0.500000");

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(chargeMapper).sumCommittedCreditsForUserBetween(eq(11L), start.capture(), end.capture());
        verify(chargeMapper).selectProviderCostsBetweenForUpdate(start.getValue(), end.getValue());
        assertThat(start.getValue()).isEqualTo(LocalDateTime.of(2026, 7, 15, 0, 0));
        assertThat(end.getValue()).isEqualTo(LocalDateTime.of(2026, 7, 16, 0, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"mode\":\"WORKFLOW_STEP\"}",
            "{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{\"writer\":{}}}",
            "{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{\"writer\":{\"maxCreditCost\":1.5}}}",
            "{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{\"writer\":{\"maxCreditCost\":-1}}}",
            "{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{\"writer\":{\"maxCreditCost\":9223372036854775807},\"renderer\":{\"maxCreditCost\":1}}}"
    })
    void missingInvalidOrOverflowingSnapshotCostFailsClosed(String billingPolicy) {
        enableHealthyRuntime(Integer.MAX_VALUE, Integer.MAX_VALUE);
        version.setBillingPolicyJson(billingPolicy);
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl(worker("writer"), worker("renderer")));

        assertBlocked(() -> service.admitNewRun(11L, 7L), "billing_policy_invalid");
    }

    @Test
    void paidRunWithoutPublishedProviderCostCapFailsClosed() {
        enableHealthyRuntime(100, 100);
        version.setBillingPolicyJson(
                "{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{\"writer\":{\"maxCreditCost\":10}}}"
        );
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl(worker("writer")));

        assertBlocked(() -> service.admitNewRun(11L, 7L), "provider_run_cost_unknown");
    }

    @Test
    void confirmationNodeCannotStartWhileConfirmationSwitchIsOff() {
        enableHealthyRuntime(100, 100);
        properties.setConfirmationEnabled(false);
        version.setBillingPolicyJson("{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{}}");
        WorkflowNodeDef confirmation = new WorkflowNodeDef(
                "approval",
                WorkflowNodeDefType.USER_CONFIRM,
                "Approval",
                new ObjectMapper().createObjectNode()
        );
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl(confirmation));

        assertBlocked(() -> service.admitNewRun(11L, 7L), "confirmation_disabled");

        verify(chargeMapper, never()).sumCommittedCreditsForUserBetween(any(), any(), any());
    }

    @Test
    void canonicalWorkflowExecutionSwitchIsPartOfAdmission() {
        enableHealthyRuntime(100, 100);
        workflow.setExecutionEnabled(false);
        version.setBillingPolicyJson("{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{}}");
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl());

        assertBlocked(() -> service.admitNewRun(11L, 7L), "tool_execution_disabled");
    }

    @Test
    void paidRunStopsWhenTodaysProviderCostCannotBeConvertedToCny() {
        enableHealthyRuntime(100, 100);
        version.setBillingPolicyJson(
                "{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{\"writer\":{\"maxCreditCost\":10,\"maxProviderCostCny\":0.10}}}"
        );
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl(worker("writer")));
        when(chargeMapper.countUnsupportedProviderCostCurrenciesBetween(any(), any())).thenReturn(1);

        assertBlocked(
                () -> service.admitNewRun(11L, 7L),
                "provider_daily_cost_currency_unsupported"
        );
    }

    @Test
    void paidRunStopsWhenTodaysSuccessfulWorkflowHasUnknownActualProviderCost() {
        enableHealthyRuntime(100, 100);
        version.setBillingPolicyJson(
                "{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{\"writer\":{\"maxCreditCost\":10,\"maxProviderCostCny\":0.10}}}"
        );
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl(worker("writer")));
        when(chargeMapper.countUnknownProviderCostsBetween(any(), any())).thenReturn(1);

        assertBlocked(
                () -> service.admitNewRun(11L, 7L),
                "provider_actual_cost_unknown"
        );
    }

    private void enableHealthyRuntime(int maxRunCredits, int maxDailyCredits) {
        properties.setEnabled(true);
        properties.setExecutionEnabled(true);
        properties.setRealBillingEnabled(true);
        properties.setAllowedUserIds(List.of(11L));
        properties.setMaxRunCostCredits(maxRunCredits);
        properties.setMaxUserDailyCostCredits(maxDailyCredits);
        properties.setMaxProviderDailyCostCny(new BigDecimal("100.00"));
        gate.markReconciliationHealthyAfterFullScan(gate.reconciliationFailureGeneration());
    }

    private WorkflowNodeDef worker(String id) {
        return new WorkflowNodeDef(
                id,
                WorkflowNodeDefType.LLM_TEXT,
                id,
                new ObjectMapper().createObjectNode()
        );
    }

    private WorkflowDsl dsl(WorkflowNodeDef... nodes) {
        List<WorkflowNodeDef> nodeList = List.of(nodes);
        return new WorkflowDsl(nodeList, List.of(), new ObjectMapper().createObjectNode(),
                nodeList.stream().map(WorkflowNodeDef::id).toList());
    }

    private void assertBlocked(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                               String reason) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.WORKFLOW_RUNTIME_BLOCKED);
                    assertThat(exception.getData()).isInstanceOf(Map.class);
                    assertThat(((Map<?, ?>) exception.getData()).get("reason")).isEqualTo(reason);
                });
    }
}
