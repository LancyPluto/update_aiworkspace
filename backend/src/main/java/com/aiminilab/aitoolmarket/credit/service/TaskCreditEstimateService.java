package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.credit.dto.PricingQuote;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper that resolves the model config for a tool and delegates all the actual math to the
 * unified {@link PricingService}. Kept for call-site compatibility (task creation, workflow steps).
 */
@Service
public class TaskCreditEstimateService {

    private final ModelCapabilityService modelCapabilityService;
    private final PricingService pricingService;

    public TaskCreditEstimateService(ModelCapabilityService modelCapabilityService, PricingService pricingService) {
        this.modelCapabilityService = modelCapabilityService;
        this.pricingService = pricingService;
    }

    /** Vendor-derived credits without markup (or the static fallback). */
    public int estimateForTool(AiTool tool) {
        if (tool == null) {
            return 0;
        }
        return quote(tool, resolveModelConfig(tool), null).baseCredits();
    }

    /** User-facing credits (markup + floor) for a tool, resolving its model config. */
    public int estimateUserFacingTaskCredits(AiTool tool) {
        if (tool == null) {
            return 0;
        }
        return quote(tool, resolveModelConfig(tool), null).chargeCredits();
    }

    public int estimateUserFacingTaskCredits(AiTool tool, AgentModelConfig modelConfig) {
        return quote(tool, modelConfig, null).chargeCredits();
    }

    public int estimateUserFacingTaskCredits(AiTool tool, AgentModelConfig modelConfig, JsonNode params) {
        return quote(tool, modelConfig, params).chargeCredits();
    }

    public int estimateTaskCredits(AiTool tool, AgentModelConfig modelConfig) {
        return quote(tool, modelConfig, null).baseCredits();
    }

    /** Full quote (incl. breakdown) used by the estimate endpoint. */
    public PricingQuote quoteUserFacing(AiTool tool, AgentModelConfig modelConfig, JsonNode params) {
        return quote(tool, modelConfig, params);
    }

    private PricingQuote quote(AiTool tool, AgentModelConfig modelConfig, JsonNode params) {
        int fallback = tool == null || tool.getEstimatedCreditCost() == null
                ? 0
                : Math.max(0, tool.getEstimatedCreditCost());
        return pricingService.computeQuote(tool, modelConfig, params, null, fallback);
    }

    private AgentModelConfig resolveModelConfig(AiTool tool) {
        return tool.getModelConfigId() == null ? null : modelCapabilityService.resolveModelConfigForTool(tool);
    }
}
