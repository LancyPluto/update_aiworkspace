package com.aiminilab.aitoolmarket.credit;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.credit.service.TaskCreditEstimateService;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskCreditEstimateServiceTest {

    @Mock
    private ModelCapabilityService modelCapabilityService;

    @InjectMocks
    private TaskCreditEstimateService taskCreditEstimateService;

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
