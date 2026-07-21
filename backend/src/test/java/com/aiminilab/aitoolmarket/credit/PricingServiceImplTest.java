package com.aiminilab.aitoolmarket.credit;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.credit.dto.PricingQuote;
import com.aiminilab.aitoolmarket.credit.dto.PricingUsage;
import com.aiminilab.aitoolmarket.credit.entity.PricingMargin;
import com.aiminilab.aitoolmarket.credit.entity.PricingRule;
import com.aiminilab.aitoolmarket.credit.mapper.PricingMarginMapper;
import com.aiminilab.aitoolmarket.credit.mapper.PricingRuleMapper;
import com.aiminilab.aitoolmarket.credit.service.impl.PricingServiceImpl;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PricingServiceImplTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private PricingMarginMapper pricingMarginMapper;
    @Mock
    private PricingRuleMapper pricingRuleMapper;

    private PricingServiceImpl pricingService;

    @BeforeEach
    void setUp() {
        pricingService = new PricingServiceImpl(pricingMarginMapper, pricingRuleMapper);
    }

    private AgentModelConfig perSecond(String unitPrice) {
        AgentModelConfig config = new AgentModelConfig();
        config.setId(1L);
        config.setBillingUnit("PER_SECOND");
        config.setUnitPrice(new BigDecimal(unitPrice));
        return config;
    }

    @Test
    void estimateAndSettlement_areConsistentForSameUsage() throws Exception {
        AiTool tool = new AiTool();
        tool.setEstimatedCreditCost(0);
        AgentModelConfig config = perSecond("0.02");
        JsonNode params = OBJECT_MAPPER.readTree("{\"duration\":5}");

        PricingQuote estimate = pricingService.computeQuote(tool, config, params, null, 0);
        PricingQuote settlement = pricingService.computeQuote(tool, config, params,
                new PricingUsage(0, 0, 5), 0);

        assertThat(estimate.chargeCredits()).isEqualTo(15);
        assertThat(settlement.chargeCredits()).isEqualTo(estimate.chargeCredits());
    }

    @Test
    void happyHorseOfficialPricing_720pAnd1080p() throws Exception {
        AiTool tool = new AiTool();
        tool.setId(10L);
        AgentModelConfig config = perSecond("0.9");
        when(pricingRuleMapper.findActiveForScopes(eq(1L), eq(10L), any())).thenReturn(List.of(
                happyHorse1080pRule()));

        JsonNode params720 = OBJECT_MAPPER.readTree("{\"duration\":3,\"resolution\":\"720P\"}");
        PricingQuote q720 = pricingService.computeQuote(tool, config, params720, null, 0);
        assertThat(q720.chargeCredits()).isEqualTo(405);

        JsonNode params1080 = OBJECT_MAPPER.readTree("{\"duration\":3,\"resolution\":\"1080P\"}");
        PricingQuote q1080 = pricingService.computeQuote(tool, config, params1080, null, 0);
        assertThat(q1080.chargeCredits()).isEqualTo(722);
    }

    private PricingRule happyHorse1080pRule() {
        PricingRule rule = new PricingRule();
        rule.setRuleType("MULTIPLIER");
        rule.setParamKey("resolution");
        rule.setMatchOp("EQ");
        rule.setMatchValue("1080P");
        rule.setFactor(new BigDecimal("1.7778"));
        return rule;
    }

    @Test
    void countValueRule_multipliesByParamValue() throws Exception {
        AiTool tool = new AiTool();
        tool.setId(20L);
        AgentModelConfig config = perSecond("0.02");

        PricingRule rule = new PricingRule();
        rule.setRuleType("MULTIPLIER");
        rule.setParamKey("count");
        rule.setMatchOp("VALUE");
        rule.setFactor(BigDecimal.ONE);
        rule.setEnabled(true);
        when(pricingRuleMapper.findActiveForScopes(anyLong(), any(), any())).thenReturn(List.of(rule));

        JsonNode params = OBJECT_MAPPER.readTree("{\"duration\":5,\"count\":3}");
        // base 0.10 -> x3 count -> 0.30 -> 30 credits -> x1.50 = 45
        PricingQuote quote = pricingService.computeQuote(tool, config, params, null, 0);
        assertThat(quote.baseCredits()).isEqualTo(30);
        assertThat(quote.chargeCredits()).isEqualTo(45);
    }

    @Test
    void gptImage2OfficialPricing_qualityMultipliers() throws Exception {
        AiTool tool = new AiTool();
        tool.setId(20L);
        AgentModelConfig config = new AgentModelConfig();
        config.setId(9L);
        config.setBillingUnit("IMAGE_TOKEN");
        config.setInputTokenPricePer1m(new BigDecimal("8"));
        config.setOutputTokenPricePer1m(new BigDecimal("30"));

        when(pricingRuleMapper.findActiveForScopes(eq(9L), eq(20L), any())).thenReturn(List.of(
                gptImage2CountRule(),
                gptImage2MediumRule(),
                gptImage2HighRule()));

        JsonNode low = OBJECT_MAPPER.readTree("{\"quality\":\"low\",\"count\":\"1\"}");
        PricingQuote qLow = pricingService.computeQuote(tool, config, low, null, 38);
        assertThat(qLow.chargeCredits()).isEqualTo(47);

        JsonNode medium = OBJECT_MAPPER.readTree("{\"quality\":\"medium\",\"count\":\"1\"}");
        PricingQuote qMedium = pricingService.computeQuote(tool, config, medium, null, 38);
        assertThat(qMedium.chargeCredits()).isEqualTo(404);

        JsonNode high = OBJECT_MAPPER.readTree("{\"quality\":\"high\",\"count\":\"1\"}");
        PricingQuote qHigh = pricingService.computeQuote(tool, config, high, null, 38);
        assertThat(qHigh.chargeCredits()).isEqualTo(1605);

        JsonNode lowCount3 = OBJECT_MAPPER.readTree("{\"quality\":\"low\",\"count\":\"3\"}");
        PricingQuote qLowCount3 = pricingService.computeQuote(tool, config, lowCount3, null, 38);
        assertThat(qLowCount3.chargeCredits()).isEqualTo(138);
    }

    @Test
    void imageTokenEstimate_usesConfiguredModelTokenEstimate() {
        AiTool tool = new AiTool();
        tool.setId(20L);
        AgentModelConfig config = new AgentModelConfig();
        config.setId(9L);
        config.setBillingUnit("IMAGE_TOKEN");
        config.setInputTokenPricePer1m(new BigDecimal("8"));
        config.setOutputTokenPricePer1m(new BigDecimal("30"));

        PricingMargin margin = new PricingMargin();
        margin.setScopeType("MODEL");
        margin.setScopeRef(9L);
        margin.setMarkupRatio(new BigDecimal("1.50"));
        margin.setMinCredits(0);
        margin.setImageEstimateInputTokens(1000);
        margin.setImageEstimateOutputTokens(1000);
        margin.setEnabled(true);
        when(pricingMarginMapper.findEnabledByScope(eq("MODEL"), eq(9L))).thenReturn(margin);

        PricingQuote quote = pricingService.computeQuote(tool, config, OBJECT_MAPPER.createObjectNode(), null, 38);

        assertThat(quote.baseCredits()).isEqualTo(4);
        assertThat(quote.chargeCredits()).isEqualTo(6);
        assertThat(quote.breakdown().get(0).detail()).contains("1000 input + 1000 output");
    }

    @Test
    void tokenPerMillionEstimate_requiresExplicitConfiguredUsage() {
        AiTool tool = new AiTool();
        tool.setId(21L);
        AgentModelConfig config = new AgentModelConfig();
        config.setId(10L);
        config.setBillingUnit("TOKEN_PER_M");
        config.setInputTokenPricePer1m(new BigDecimal("10"));
        config.setOutputTokenPricePer1m(new BigDecimal("30"));

        PricingQuote missingEstimate = pricingService.computeQuote(
                tool, config, OBJECT_MAPPER.createObjectNode(), null, 0
        );
        assertThat(missingEstimate.modelDerived()).isFalse();
        assertThat(missingEstimate.chargeCredits()).isZero();

        PricingMargin margin = new PricingMargin();
        margin.setScopeType("MODEL");
        margin.setScopeRef(10L);
        margin.setMarkupRatio(new BigDecimal("1.50"));
        margin.setMinCredits(0);
        margin.setTokenEstimateInputTokens(1000);
        margin.setTokenEstimateOutputTokens(2000);
        margin.setEnabled(true);
        when(pricingMarginMapper.findEnabledByScope(eq("MODEL"), eq(10L))).thenReturn(margin);

        PricingQuote configured = pricingService.computeQuote(
                tool, config, OBJECT_MAPPER.createObjectNode(), null, 0
        );
        assertThat(configured.modelDerived()).isTrue();
        assertThat(configured.vendorCost()).isEqualByComparingTo("0.07000000");
        assertThat(configured.chargeCredits()).isEqualTo(11);
        assertThat(configured.breakdown().get(0).detail()).contains("1000 input + 2000 output");
    }

    private PricingRule gptImage2CountRule() {
        PricingRule rule = new PricingRule();
        rule.setRuleType("MULTIPLIER");
        rule.setParamKey("count");
        rule.setMatchOp("VALUE");
        rule.setFactor(BigDecimal.ONE);
        rule.setEnabled(true);
        return rule;
    }

    private PricingRule gptImage2MediumRule() {
        PricingRule rule = new PricingRule();
        rule.setRuleType("MULTIPLIER");
        rule.setParamKey("quality");
        rule.setMatchOp("EQ");
        rule.setMatchValue("medium");
        rule.setFactor(new BigDecimal("8.8333"));
        rule.setEnabled(true);
        return rule;
    }

    private PricingRule gptImage2HighRule() {
        PricingRule rule = new PricingRule();
        rule.setRuleType("MULTIPLIER");
        rule.setParamKey("quality");
        rule.setMatchOp("EQ");
        rule.setMatchValue("high");
        rule.setFactor(new BigDecimal("35.1667"));
        rule.setEnabled(true);
        return rule;
    }

    @Test
    void multiplierRule_scalesVendorCost() {
        AiTool tool = new AiTool();
        AgentModelConfig config = perSecond("0.02");

        PricingRule rule = new PricingRule();
        rule.setRuleType("MULTIPLIER");
        rule.setParamKey("quality");
        rule.setMatchOp("EQ");
        rule.setMatchValue("1080p");
        rule.setFactor(new BigDecimal("2"));
        rule.setEnabled(true);
        when(pricingRuleMapper.findActiveForScopes(anyLong(), any(), any())).thenReturn(List.of(rule));

        JsonNode params = OBJECT_MAPPER.createObjectNode().put("duration", 5).put("quality", "1080p");
        // base cost 0.10 -> x2 -> 0.20 -> 20 credits -> x1.50 markup = 30
        PricingQuote quote = pricingService.computeQuote(tool, config, params, null, 0);
        assertThat(quote.baseCredits()).isEqualTo(20);
        assertThat(quote.chargeCredits()).isEqualTo(30);
    }

    @Test
    void additiveRule_addsFlatCredits() {
        AiTool tool = new AiTool();
        AgentModelConfig config = perSecond("0.02");

        PricingRule rule = new PricingRule();
        rule.setRuleType("ADDITIVE");
        rule.setParamKey("watermarkFree");
        rule.setMatchOp("ANY");
        rule.setExtraCredits(5);
        rule.setEnabled(true);
        when(pricingRuleMapper.findActiveForScopes(anyLong(), any(), any())).thenReturn(List.of(rule));

        JsonNode params = OBJECT_MAPPER.createObjectNode().put("duration", 5).put("watermarkFree", true);
        // base 10 credits + 5 additive = 15 -> x1.50 = 23
        PricingQuote quote = pricingService.computeQuote(tool, config, params, null, 0);
        assertThat(quote.baseCredits()).isEqualTo(15);
        assertThat(quote.chargeCredits()).isEqualTo(23);
    }

    @Test
    void marginOverride_appliesCustomMarkupAndFloor() {
        AiTool tool = new AiTool();
        AgentModelConfig config = perSecond("0.02");

        PricingMargin margin = new PricingMargin();
        margin.setScopeType("MODEL");
        margin.setScopeRef(1L);
        margin.setMarkupRatio(new BigDecimal("1.50"));
        margin.setMinCredits(50);
        margin.setEnabled(true);
        when(pricingMarginMapper.findEnabledByScope(eq("MODEL"), eq(1L))).thenReturn(margin);

        JsonNode params = OBJECT_MAPPER.createObjectNode().put("duration", 5);
        // base 10 -> x1.5 = 15 -> below floor 50 -> 50
        PricingQuote quote = pricingService.computeQuote(tool, config, params, null, 0);
        assertThat(quote.markupRatio()).isEqualByComparingTo("1.50");
        assertThat(quote.chargeCredits()).isEqualTo(50);
    }

    @Test
    void tokenQuote_appliesMarkup() {
        AgentModelConfig config = new AgentModelConfig();
        config.setId(2L);
        config.setBillingUnit("TOKEN_PER_M");
        config.setInputTokenPricePer1m(new BigDecimal("10"));
        config.setOutputTokenPricePer1m(new BigDecimal("30"));

        // input 1,000,000 * 10 / 1e6 = 10 CNY; output 0 -> 10 CNY -> 1000 credits -> x1.5 = 1500
        PricingQuote quote = pricingService.computeTokenQuote(config, 1_000_000, 0);
        assertThat(quote.baseCredits()).isEqualTo(1000);
        assertThat(quote.chargeCredits()).isEqualTo(1500);
    }

    @Test
    void fallback_whenNoModelConfig_returnsStaticCreditsWithoutMarkup() {
        AiTool tool = new AiTool();
        PricingQuote quote = pricingService.computeQuote(tool, null, null, null, 42);
        assertThat(quote.modelDerived()).isFalse();
        assertThat(quote.chargeCredits()).isEqualTo(42);
    }
}
