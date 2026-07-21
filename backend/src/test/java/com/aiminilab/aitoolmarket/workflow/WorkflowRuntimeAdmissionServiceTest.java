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
import com.aiminilab.aitoolmarket.workflow.metrics.WorkflowMetrics;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowDslService;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRuntimeAdmission;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowRuntimeAdmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private WorkflowMetrics metrics;
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
        metrics = mock(WorkflowMetrics.class);
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
                metrics,
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
        version.setBillingPolicyJson(fallbackBillingPolicy(Map.of(
                "writer", 20,
                "renderer", 30
        )));
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl(worker("writer"), worker("renderer")));

        WorkflowRuntimeAdmission admitted = service.admitNewRun(11L, 7L);

        assertThat(admitted.workflow()).isSameAs(workflow);
        assertThat(admitted.version()).isSameAs(version);
        assertThat(admitted.paidRun()).isTrue();
        assertThat(admitted.estimatedRunCredits()).isEqualTo(50L);
        assertThat(admitted.providerCostReservedCny()).isEqualByComparingTo("0.000000");

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(chargeMapper).sumCommittedCreditsForUserBetween(eq(11L), start.capture(), end.capture());
        assertThat(start.getValue()).isEqualTo(LocalDateTime.of(2026, 7, 15, 0, 0));
        assertThat(end.getValue()).isEqualTo(LocalDateTime.of(2026, 7, 16, 0, 0));
    }

    @Test
    void operationScopeChargesTheSumOfAllRequestedWorkerNodes() {
        enableHealthyRuntime(10, 100);
        version.setBillingPolicyJson(fallbackBillingPolicy(Map.of(
                "script", 50,
                "tts", 2,
                "keyframe", 3,
                "video", 4
        )));
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl(
                        worker("script", "comic.script"),
                        worker("tts", "comic.shot_tts"),
                        worker("keyframe", "comic.shot_keyframe"),
                        worker("video", "comic.shot_video")
                ));

        WorkflowRuntimeAdmission admitted = service.admitNewRun(
                11L,
                7L,
                List.of("comic.shot_tts", "comic.shot_keyframe", "comic.shot_video")
        );

        assertThat(admitted.estimatedRunCredits()).isEqualTo(9L);
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
    void legacySnapshotWithoutPricingSourceFailsBeforeBudgetChecks() throws Exception {
        enableHealthyRuntime(100, 100);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode policy = (ObjectNode) mapper.readTree(fallbackBillingPolicy(Map.of("writer", 10)));
        ((ObjectNode) policy.path("nodePolicies").path("writer")).remove("pricingSource");
        version.setBillingPolicyJson(policy.toString());
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl(worker("writer")));

        assertBlocked(() -> service.admitNewRun(11L, 7L), "billing_policy_invalid");
        verify(chargeMapper, never()).sumCommittedCreditsForUserBetween(any(), any(), any());
    }

    @Test
    void validModelPricingSnapshotCanBeAdmitted() {
        enableHealthyRuntime(100, 100);
        version.setBillingPolicyJson(modelBillingPolicy("writer", 10));
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl(worker("writer")));

        assertThat(service.admitNewRun(11L, 7L).estimatedRunCredits()).isEqualTo(10L);
    }

    @Test
    void localComposeIsTheOnlyZeroCostWorkerAdmission() {
        enableHealthyRuntime(100, 100);
        version.setBillingPolicyJson(localZeroCostBillingPolicy("compose", "comic.compose"));
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl(worker("compose", "comic.compose")));

        WorkflowRuntimeAdmission admitted = service.admitNewRun(11L, 7L);

        assertThat(admitted.paidRun()).isFalse();
        assertThat(admitted.estimatedRunCredits()).isZero();
        verify(chargeMapper, never()).sumCommittedCreditsForUserBetween(any(), any(), any());
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
    void canonicalWorkflowExecutionFlagDoesNotBlockAdmission() {
        enableHealthyRuntime(100, 100);
        workflow.setExecutionEnabled(false);
        version.setBillingPolicyJson("{\"mode\":\"WORKFLOW_STEP\",\"nodePolicies\":{}}");
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl());

        assertThat(service.admitNewRun(11L, 7L).workflow()).isSameAs(workflow);
    }

    @Test
    void paidRunRecordsUnsupportedProviderCurrencyWithoutBlocking() {
        enableHealthyRuntime(100, 100);
        version.setBillingPolicyJson(fallbackBillingPolicy(Map.of("writer", 10)));
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl(worker("writer")));
        when(chargeMapper.countUnsupportedProviderCostCurrenciesBetween(any(), any())).thenReturn(1);

        assertThat(service.admitNewRun(11L, 7L).paidRun()).isTrue();
        verify(metrics).recordProviderCostAnomaly(WorkflowMetrics.ProviderCostAnomaly.CURRENCY_UNSUPPORTED);
    }

    @Test
    void paidRunRecordsUnknownProviderCostWithoutBlocking() {
        enableHealthyRuntime(100, 100);
        version.setBillingPolicyJson(fallbackBillingPolicy(Map.of("writer", 10)));
        when(dslService.parse(version.getNodesJson(), version.getEdgesJson(), version.getConfigJson()))
                .thenReturn(dsl(worker("writer")));
        when(chargeMapper.countUnknownProviderCostsBetween(any(), any())).thenReturn(1);

        assertThat(service.admitNewRun(11L, 7L).paidRun()).isTrue();
        verify(metrics).recordProviderCostAnomaly(WorkflowMetrics.ProviderCostAnomaly.ACTUAL_COST_UNKNOWN);
    }

    private void enableHealthyRuntime(int maxRunCredits, int maxDailyCredits) {
        properties.setEnabled(true);
        properties.setExecutionEnabled(true);
        properties.setRealBillingEnabled(true);
        properties.setAllowedUserIds(List.of(11L));
        properties.setMaxRunCostCredits(maxRunCredits);
        properties.setMaxUserDailyCostCredits(maxDailyCredits);
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

    private WorkflowNodeDef worker(String id, String handlerKey) {
        return new WorkflowNodeDef(
                id,
                WorkflowNodeDefType.LLM_TEXT,
                id,
                new ObjectMapper().createObjectNode().put("handlerKey", handlerKey)
        );
    }

    private String fallbackBillingPolicy(Map<String, Integer> nodeCosts) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode().put("mode", "WORKFLOW_STEP");
        ObjectNode nodePolicies = root.putObject("nodePolicies");
        nodeCosts.forEach((nodeId, credits) -> {
            ObjectNode nodePolicy = baseNodePolicy(mapper, credits, credits, "TOOL_FALLBACK");
            nodePolicy.putNull("modelPricingSnapshot");
            nodePolicies.set(nodeId, nodePolicy);
        });
        return root.toString();
    }

    private String modelBillingPolicy(String nodeId, int credits) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode().put("mode", "WORKFLOW_STEP");
        ObjectNode nodePolicy = baseNodePolicy(mapper, credits, 0, "MODEL_PRICING");
        nodePolicy.put("estimatedProviderCostCny", new BigDecimal("0.100000"));
        nodePolicy.put("maxProviderCostCny", new BigDecimal("0.100000"));
        ObjectNode model = nodePolicy.putObject("modelPricingSnapshot");
        model.put("id", 101L);
        model.put("provider", "test");
        model.put("modelName", "test-model");
        model.put("billingUnit", "PER_CALL");
        model.put("unitPrice", new BigDecimal("0.100000"));
        root.putObject("nodePolicies").set(nodeId, nodePolicy);
        return root.toString();
    }

    private String localZeroCostBillingPolicy(String nodeId, String handlerKey) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode().put("mode", "WORKFLOW_STEP");
        ObjectNode nodePolicy = baseNodePolicy(mapper, 0, 0, "LOCAL_ZERO_COST");
        ((ObjectNode) nodePolicy.path("staticParams")).put("handlerKey", handlerKey);
        nodePolicy.putNull("modelPricingSnapshot");
        root.putObject("nodePolicies").set(nodeId, nodePolicy);
        return root.toString();
    }

    private ObjectNode baseNodePolicy(ObjectMapper mapper,
                                      int maxCreditCost,
                                      int fallbackCredits,
                                      String pricingSource) {
        ObjectNode nodePolicy = mapper.createObjectNode();
        nodePolicy.put("maxCreditCost", maxCreditCost);
        nodePolicy.put("estimatedProviderCostCny", 0);
        nodePolicy.put("maxProviderCostCny", 0);
        nodePolicy.put("fallbackChargeCredits", fallbackCredits);
        nodePolicy.put("pricingSource", pricingSource);
        nodePolicy.putObject("staticParams");
        ObjectNode pricingPolicy = nodePolicy.putObject("pricingPolicy");
        pricingPolicy.put("markupRatio", 1.5);
        pricingPolicy.put("minCredits", 0);
        pricingPolicy.put("imageEstimateInputTokens", 8000);
        pricingPolicy.put("imageEstimateOutputTokens", 8000);
        pricingPolicy.put("tokenEstimateInputTokens", 1000);
        pricingPolicy.put("tokenEstimateOutputTokens", 1000);
        pricingPolicy.putArray("rules");
        return nodePolicy;
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
