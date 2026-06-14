package com.aiminilab.aitoolmarket.credit;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.credit.mapper.PricingMarginMapper;
import com.aiminilab.aitoolmarket.credit.mapper.PricingRuleMapper;
import com.aiminilab.aitoolmarket.credit.service.PricingService;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.credit.service.impl.PricingServiceImpl;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Regression around the legacy estimate API. With no margin/rule rows configured the unified
 * pricing engine must reproduce the historical numbers (markup 1.20, no floor, no rules).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskCreditEstimateServiceTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private ModelCapabilityService modelCapabilityService;
    @Mock
    private PricingMarginMapper pricingMarginMapper;
    @Mock
    private PricingRuleMapper pricingRuleMapper;

    private TaskCreditEstimateService taskCreditEstimateService;

    @BeforeEach
    void setUp() {
        PricingService pricingService = new PricingServiceImpl(pricingMarginMapper, pricingRuleMapper);
        taskCreditEstimateService = new TaskCreditEstimateService(modelCapabilityService, pricingService);
    }

    @Test
    void perCallBilling_usesUnitPrice() {
        AiTool tool = new AiTool();
        tool.setEstimatedCreditCost(5);

        AgentModelConfig modelConfig = new AgentModelConfig();
        modelConfig.setBillingUnit("PER_CALL");
        modelConfig.setUnitPrice(new BigDecimal("0.03"));

        assertThat(taskCreditEstimateService.estimateTaskCredits(tool, modelConfig)).isEqualTo(3);
        assertThat(taskCreditEstimateService.estimateUserFacingTaskCredits(tool, modelConfig)).isEqualTo(4);
    }

    @Test
    void perCallBilling_fallsBackToToolEstimateWhenPriceMissing() {
        AiTool tool = new AiTool();
        tool.setEstimatedCreditCost(8);

        AgentModelConfig modelConfig = new AgentModelConfig();
        modelConfig.setBillingUnit("PER_CALL");
        modelConfig.setUnitPrice(null);

        assertThat(taskCreditEstimateService.estimateTaskCredits(tool, modelConfig)).isEqualTo(8);
    }

    @Test
    void perSecondBilling_usesDurationParam() throws Exception {
        AiTool tool = new AiTool();
        tool.setEstimatedCreditCost(5);

        AgentModelConfig modelConfig = new AgentModelConfig();
        modelConfig.setBillingUnit("PER_SECOND");
        modelConfig.setUnitPrice(new BigDecimal("0.02"));

        assertThat(taskCreditEstimateService.estimateUserFacingTaskCredits(
                tool,
                modelConfig,
                OBJECT_MAPPER.readTree("{\"duration\":5}")
        )).isEqualTo(12);
    }

    @Test
    void perSecondBilling_defaultsToFiveSeconds() {
        AiTool tool = new AiTool();
        tool.setEstimatedCreditCost(5);

        AgentModelConfig modelConfig = new AgentModelConfig();
        modelConfig.setBillingUnit("PER_SECOND");
        modelConfig.setUnitPrice(new BigDecimal("0.02"));

        assertThat(taskCreditEstimateService.estimateUserFacingTaskCredits(tool, modelConfig)).isEqualTo(12);
    }

    @Test
    void tokenBilling_usesToolEstimate() {
        AiTool tool = new AiTool();
        tool.setEstimatedCreditCost(12);

        AgentModelConfig modelConfig = new AgentModelConfig();
        modelConfig.setBillingUnit("TOKEN_PER_M");

        assertThat(taskCreditEstimateService.estimateTaskCredits(tool, modelConfig)).isEqualTo(12);
    }

    @Test
    void imageTokenBilling_usesTokenPriceUpperEstimateInsteadOfFixedToolEstimate() {
        AiTool tool = new AiTool();
        tool.setEstimatedCreditCost(300);

        AgentModelConfig modelConfig = new AgentModelConfig();
        modelConfig.setBillingUnit("IMAGE_TOKEN");
        modelConfig.setInputTokenPricePer1m(new BigDecimal("8"));
        modelConfig.setOutputTokenPricePer1m(new BigDecimal("30"));

        assertThat(taskCreditEstimateService.estimateTaskCredits(tool, modelConfig)).isEqualTo(31);
        assertThat(taskCreditEstimateService.estimateUserFacingTaskCredits(tool, modelConfig)).isEqualTo(38);
    }

    @Test
    void estimateForTool_resolvesModelConfig() {
        AiTool tool = new AiTool();
        tool.setEstimatedCreditCost(1);
        tool.setModelConfigId(10L);

        AgentModelConfig modelConfig = new AgentModelConfig();
        modelConfig.setBillingUnit("PER_CALL");
        modelConfig.setUnitPrice(new BigDecimal("0.10"));

        when(modelCapabilityService.resolveModelConfigForTool(tool)).thenReturn(modelConfig);

        assertThat(taskCreditEstimateService.estimateForTool(tool)).isEqualTo(10);
        assertThat(taskCreditEstimateService.estimateUserFacingTaskCredits(tool)).isEqualTo(12);
    }
}
