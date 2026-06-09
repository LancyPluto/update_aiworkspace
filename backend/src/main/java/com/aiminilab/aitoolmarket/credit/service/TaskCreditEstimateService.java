package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.agent.service.ModelCapabilityService;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Estimates task credits using the same rules as task creation ({@code TaskServiceImpl}).
 */
@Service
public class TaskCreditEstimateService {

    private static final String BILLING_UNIT_PER_CALL = "PER_CALL";
    private static final String BILLING_UNIT_IMAGE_TOKEN = "IMAGE_TOKEN";
    private static final String BILLING_UNIT_PER_SECOND = "PER_SECOND";
    private static final BigDecimal CREDIT_PRICE_CNY = new BigDecimal("0.01");
    private static final int IMAGE_INPUT_TOKEN_UPPER_ESTIMATE = 8_000;
    private static final int IMAGE_OUTPUT_TOKEN_UPPER_ESTIMATE = 8_000;
    private static final int DEFAULT_VIDEO_DURATION_SECONDS = 5;

    private final ModelCapabilityService modelCapabilityService;

    public TaskCreditEstimateService(ModelCapabilityService modelCapabilityService) {
        this.modelCapabilityService = modelCapabilityService;
    }

    public int estimateForTool(AiTool tool) {
        if (tool == null) {
            return 0;
        }
        AgentModelConfig modelConfig = tool.getModelConfigId() == null
                ? null
                : modelCapabilityService.resolveModelConfigForTool(tool);
        return estimate(tool, modelConfig).credits();
    }

    public int estimateUserFacingTaskCredits(AiTool tool) {
        if (tool == null) {
            return 0;
        }
        AgentModelConfig modelConfig = tool.getModelConfigId() == null
                ? null
                : modelCapabilityService.resolveModelConfigForTool(tool);
        return userFacingCredits(estimate(tool, modelConfig));
    }

    public int estimateUserFacingTaskCredits(AiTool tool, AgentModelConfig modelConfig) {
        return userFacingCredits(estimate(tool, modelConfig, null));
    }

    public int estimateUserFacingTaskCredits(AiTool tool, AgentModelConfig modelConfig, JsonNode params) {
        return userFacingCredits(estimate(tool, modelConfig, params));
    }

    public int estimateTaskCredits(AiTool tool, AgentModelConfig modelConfig) {
        return estimate(tool, modelConfig, null).credits();
    }

    private EstimateResult estimate(AiTool tool, AgentModelConfig modelConfig) {
        return estimate(tool, modelConfig, null);
    }

    private EstimateResult estimate(AiTool tool, AgentModelConfig modelConfig, JsonNode params) {
        int toolEstimate = tool.getEstimatedCreditCost() == null ? 0 : Math.max(0, tool.getEstimatedCreditCost());
        if (modelConfig == null) {
            return new EstimateResult(toolEstimate, false);
        }
        String billingUnit = modelConfig.getBillingUnit();
        if (BILLING_UNIT_PER_CALL.equals(billingUnit) && modelConfig.getUnitPrice() != null) {
            int calculated = modelConfig.getUnitPrice().divide(CREDIT_PRICE_CNY, 0, RoundingMode.CEILING).intValue();
            return calculated > 0 ? new EstimateResult(calculated, true) : new EstimateResult(toolEstimate, false);
        }
        if (BILLING_UNIT_PER_SECOND.equals(billingUnit) && modelConfig.getUnitPrice() != null) {
            BigDecimal duration = resolveDurationSeconds(params);
            BigDecimal estimatedCost = modelConfig.getUnitPrice().multiply(duration);
            int calculated = estimatedCost.divide(CREDIT_PRICE_CNY, 0, RoundingMode.CEILING).intValue();
            return calculated > 0 ? new EstimateResult(calculated, true) : new EstimateResult(toolEstimate, false);
        }
        if (BILLING_UNIT_IMAGE_TOKEN.equals(billingUnit)) {
            BigDecimal inputPrice = price(modelConfig.getInputTokenPricePer1m());
            BigDecimal outputPrice = price(modelConfig.getOutputTokenPricePer1m());
            BigDecimal estimatedCost = inputPrice.multiply(BigDecimal.valueOf(IMAGE_INPUT_TOKEN_UPPER_ESTIMATE))
                    .add(outputPrice.multiply(BigDecimal.valueOf(IMAGE_OUTPUT_TOKEN_UPPER_ESTIMATE)))
                    .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
            int calculated = estimatedCost.divide(CREDIT_PRICE_CNY, 0, RoundingMode.CEILING).intValue();
            return calculated > 0 ? new EstimateResult(calculated, true) : new EstimateResult(toolEstimate, false);
        }
        return new EstimateResult(toolEstimate, false);
    }

    private BigDecimal resolveDurationSeconds(JsonNode params) {
        if (params != null && params.has("duration")) {
            JsonNode node = params.get("duration");
            if (node != null && node.isNumber()) {
                return BigDecimal.valueOf(Math.max(1, node.asDouble()));
            }
            if (node != null && node.isTextual()) {
                String raw = node.asText("").trim();
                String digits = raw.replaceAll("[^0-9.]", "");
                if (!digits.isBlank()) {
                    try {
                        return new BigDecimal(digits).max(BigDecimal.ONE);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return BigDecimal.valueOf(DEFAULT_VIDEO_DURATION_SECONDS);
    }

    private BigDecimal price(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private int userFacingCredits(int baseCredits) {
        if (baseCredits <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(baseCredits)
                .multiply(new BigDecimal("1.2"))
                .setScale(0, RoundingMode.CEILING)
                .intValue();
    }

    private int userFacingCredits(EstimateResult estimate) {
        if (!estimate.modelDerived()) {
            return estimate.credits();
        }
        return userFacingCredits(estimate.credits());
    }

    private record EstimateResult(int credits, boolean modelDerived) {
    }
}
