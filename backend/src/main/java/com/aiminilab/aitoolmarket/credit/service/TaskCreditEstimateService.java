package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Estimates task credits using the same rules as task creation ({@code TaskServiceImpl}).
 */
@Service
public class TaskCreditEstimateService {

    private final ModelCapabilityService modelCapabilityService;

    public TaskCreditEstimateService(ModelCapabilityService modelCapabilityService) {
        this.modelCapabilityService = modelCapabilityService;
    }

    public int estimateForTool(AiTool tool) {
        if (tool == null) {
            return 0;
        }
        return estimateTaskCredits(tool, modelCapabilityService.resolveModelConfigForTool(tool));
    }

    public int estimateTaskCredits(AiTool tool, AgentModelConfig modelConfig) {
        int toolEstimate = tool.getEstimatedCreditCost() == null ? 0 : Math.max(0, tool.getEstimatedCreditCost());
        if (modelConfig == null) {
            return toolEstimate;
        }
        String billingUnit = modelConfig.getBillingUnit();
        if ("PER_CALL".equals(billingUnit) && modelConfig.getUnitPrice() != null) {
            int calculated = modelConfig.getUnitPrice().divide(new BigDecimal("0.01"), 0, RoundingMode.CEILING).intValue();
            return calculated > 0 ? calculated : toolEstimate;
        }
        return toolEstimate;
    }
}
