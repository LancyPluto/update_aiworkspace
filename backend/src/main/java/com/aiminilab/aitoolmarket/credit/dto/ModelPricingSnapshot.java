package com.aiminilab.aitoolmarket.credit.dto;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;

import java.math.BigDecimal;

public record ModelPricingSnapshot(
        Long id,
        String provider,
        String modelName,
        BigDecimal inputTokenPricePer1k,
        BigDecimal outputTokenPricePer1k,
        BigDecimal inputTokenPricePer1m,
        BigDecimal outputTokenPricePer1m,
        String billingUnit,
        BigDecimal unitPrice
) {

    public static ModelPricingSnapshot from(AgentModelConfig config) {
        if (config == null) {
            return null;
        }
        return new ModelPricingSnapshot(
                config.getId(),
                config.getProvider(),
                config.getModelName(),
                config.getInputTokenPricePer1k(),
                config.getOutputTokenPricePer1k(),
                config.getInputTokenPricePer1m(),
                config.getOutputTokenPricePer1m(),
                config.getBillingUnit(),
                config.getUnitPrice()
        );
    }

    public AgentModelConfig toModelConfig() {
        AgentModelConfig config = new AgentModelConfig();
        config.setId(id);
        config.setProvider(provider);
        config.setModelName(modelName);
        config.setInputTokenPricePer1k(inputTokenPricePer1k);
        config.setOutputTokenPricePer1k(outputTokenPricePer1k);
        config.setInputTokenPricePer1m(inputTokenPricePer1m);
        config.setOutputTokenPricePer1m(outputTokenPricePer1m);
        config.setBillingUnit(billingUnit);
        config.setUnitPrice(unitPrice);
        return config;
    }
}
