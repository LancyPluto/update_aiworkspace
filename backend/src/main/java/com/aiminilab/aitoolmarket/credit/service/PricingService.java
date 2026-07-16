package com.aiminilab.aitoolmarket.credit.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.credit.dto.PricingQuote;
import com.aiminilab.aitoolmarket.credit.dto.PricingUsage;
import com.aiminilab.aitoolmarket.credit.dto.PricingPolicySnapshot;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Single source of truth for "parameters/usage -&gt; credits" pricing. Both the pre-execution
 * estimate and the post-execution settlement call {@link #computeQuote} so the two can never drift.
 */
public interface PricingService {

    /**
     * Compute a quote.
     *
     * @param tool            owning tool (nullable) — used for category-scoped rules/margin and fallback
     * @param modelConfig     resolved model config (nullable) — when null only the fallback applies
     * @param params          task params (nullable) — drives parameter rules and PER_SECOND duration
     * @param usage           real usage (nullable) — when null the call is treated as an estimate
     * @param fallbackCredits static credits used when the amount cannot be model-derived
     */
    PricingQuote computeQuote(AiTool tool, AgentModelConfig modelConfig, JsonNode params,
                              PricingUsage usage, int fallbackCredits);

    PricingPolicySnapshot snapshot(AiTool tool, AgentModelConfig modelConfig);

    PricingQuote computeQuote(PricingPolicySnapshot snapshot, AgentModelConfig modelConfig,
                              JsonNode params, PricingUsage usage, int fallbackCredits);

    /**
     * Token-only quote for the Agent run path (no tool/params), markup resolved by model scope.
     */
    PricingQuote computeTokenQuote(AgentModelConfig modelConfig, Integer promptTokens, Integer completionTokens);
}
