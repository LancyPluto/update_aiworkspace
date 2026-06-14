package com.aiminilab.aitoolmarket.credit.service.impl;

import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.credit.dto.PricingBreakdownItem;
import com.aiminilab.aitoolmarket.credit.dto.PricingQuote;
import com.aiminilab.aitoolmarket.credit.dto.PricingUsage;
import com.aiminilab.aitoolmarket.credit.entity.PricingMargin;
import com.aiminilab.aitoolmarket.credit.entity.PricingRule;
import com.aiminilab.aitoolmarket.credit.mapper.PricingMarginMapper;
import com.aiminilab.aitoolmarket.credit.mapper.PricingRuleMapper;
import com.aiminilab.aitoolmarket.credit.service.PricingService;
import com.aiminilab.aitoolmarket.tool.entity.AiTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class PricingServiceImpl implements PricingService {

    private static final String BILLING_UNIT_PER_CALL = "PER_CALL";
    private static final String BILLING_UNIT_PER_SECOND = "PER_SECOND";
    private static final String BILLING_UNIT_IMAGE_TOKEN = "IMAGE_TOKEN";

    /** 1 credit = 0.01 CNY (single definition of the credit face value). */
    static final BigDecimal CREDIT_PRICE_CNY = new BigDecimal("0.01");
    /** Default markup applied when no margin config row matches. Equivalent to the legacy 1.2. */
    static final BigDecimal DEFAULT_MARKUP = new BigDecimal("1.20");
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);
    private static final int IMAGE_INPUT_TOKEN_UPPER_ESTIMATE = 8_000;
    private static final int IMAGE_OUTPUT_TOKEN_UPPER_ESTIMATE = 8_000;
    private static final int DEFAULT_VIDEO_DURATION_SECONDS = 5;

    private final PricingMarginMapper pricingMarginMapper;
    private final PricingRuleMapper pricingRuleMapper;

    public PricingServiceImpl(PricingMarginMapper pricingMarginMapper, PricingRuleMapper pricingRuleMapper) {
        this.pricingMarginMapper = pricingMarginMapper;
        this.pricingRuleMapper = pricingRuleMapper;
    }

    @Override
    public PricingQuote computeQuote(AiTool tool, AgentModelConfig modelConfig, JsonNode params,
                                     PricingUsage usage, int fallbackCredits) {
        int fallback = Math.max(0, fallbackCredits);
        ResolvedMargin margin = resolveMargin(tool, modelConfig);
        if (modelConfig == null) {
            return fallbackQuote(fallback, margin);
        }
        VendorCost vendor = computeVendorCost(modelConfig, params, usage);
        if (!vendor.derived) {
            return fallbackQuote(fallback, margin);
        }

        List<PricingBreakdownItem> breakdown = new ArrayList<>();
        BigDecimal adjustedCost = vendor.cost;
        breakdown.add(PricingBreakdownItem.of("基础成本",
                vendor.detail + "（厂商成本 " + adjustedCost.stripTrailingZeros().toPlainString() + " 元）",
                costToCredits(adjustedCost)));

        RuleOutcome rules = applyRules(tool, modelConfig, params, adjustedCost, breakdown);
        adjustedCost = rules.cost;

        int baseCredits = costToCredits(adjustedCost) + rules.extraCredits;
        int charge = applyMarkup(baseCredits, margin.ratio);
        if (charge < margin.minCredits) {
            breakdown.add(PricingBreakdownItem.of("保底价", "低于保底价，按最低收费", margin.minCredits - charge));
            charge = margin.minCredits;
        }
        breakdown.add(PricingBreakdownItem.of("平台加价",
                "加价 " + margin.ratio.stripTrailingZeros().toPlainString() + " 倍", charge - baseCredits));

        return new PricingQuote(adjustedCost, baseCredits, margin.ratio, charge, true, breakdown);
    }

    @Override
    public PricingQuote computeTokenQuote(AgentModelConfig modelConfig, Integer promptTokens, Integer completionTokens) {
        return computeQuote(null, modelConfig, null, new PricingUsage(promptTokens, completionTokens, null), 0);
    }

    private PricingQuote fallbackQuote(int fallbackCredits, ResolvedMargin margin) {
        int charge = Math.max(fallbackCredits, margin.minCredits);
        List<PricingBreakdownItem> breakdown = List.of(
                PricingBreakdownItem.of("预设算力", "未匹配模型计价，按工具预设值", charge));
        return new PricingQuote(BigDecimal.ZERO, fallbackCredits, margin.ratio, charge, false, breakdown);
    }

    private VendorCost computeVendorCost(AgentModelConfig modelConfig, JsonNode params, PricingUsage usage) {
        String unit = modelConfig.getBillingUnit();
        if ((BILLING_UNIT_PER_CALL.equals(unit) || BILLING_UNIT_PER_SECOND.equals(unit))
                && modelConfig.getUnitPrice() != null) {
            int units = resolveUnits(unit, params, usage);
            BigDecimal cost = price(modelConfig.getUnitPrice()).multiply(BigDecimal.valueOf(units));
            String detail = BILLING_UNIT_PER_SECOND.equals(unit) ? (units + " 秒") : (units + " 次");
            return cost.compareTo(BigDecimal.ZERO) > 0 ? VendorCost.derived(cost, detail) : VendorCost.fallback();
        }
        if (BILLING_UNIT_IMAGE_TOKEN.equals(unit)) {
            if (usage == null) {
                BigDecimal cost = tokenCost(modelConfig, IMAGE_INPUT_TOKEN_UPPER_ESTIMATE, IMAGE_OUTPUT_TOKEN_UPPER_ESTIMATE);
                return cost.compareTo(BigDecimal.ZERO) > 0 ? VendorCost.derived(cost, "图片 token 预估") : VendorCost.fallback();
            }
            if (!usage.hasTokens()) {
                return VendorCost.fallback();
            }
            BigDecimal cost = tokenCost(modelConfig, usage.prompt(), usage.completion());
            return cost.compareTo(BigDecimal.ZERO) > 0
                    ? VendorCost.derived(cost, "图片 token " + (usage.prompt() + usage.completion()))
                    : VendorCost.fallback();
        }
        // TOKEN_PER_M and any other token-based unit.
        if (usage != null && usage.hasTokens()) {
            BigDecimal cost = tokenCost(modelConfig, usage.prompt(), usage.completion());
            return cost.compareTo(BigDecimal.ZERO) > 0
                    ? VendorCost.derived(cost, "token " + (usage.prompt() + usage.completion()))
                    : VendorCost.fallback();
        }
        // Estimate with no usage for a token-priced model: fall back to the static tool estimate.
        return VendorCost.fallback();
    }

    private int resolveUnits(String unit, JsonNode params, PricingUsage usage) {
        if (usage != null) {
            return usage.hasUnits() ? usage.units() : 1;
        }
        if (BILLING_UNIT_PER_SECOND.equals(unit)) {
            return durationSeconds(params);
        }
        return 1;
    }

    private int durationSeconds(JsonNode params) {
        if (params != null && params.has("duration")) {
            JsonNode node = params.get("duration");
            if (node != null && node.isNumber()) {
                return Math.max(1, (int) Math.ceil(node.asDouble()));
            }
            if (node != null && node.isTextual()) {
                String digits = node.asText("").trim().replaceAll("[^0-9.]", "");
                if (!digits.isBlank()) {
                    try {
                        return Math.max(1, (int) Math.ceil(new BigDecimal(digits).doubleValue()));
                    } catch (NumberFormatException ignored) {
                        // fall through to default
                    }
                }
            }
        }
        return DEFAULT_VIDEO_DURATION_SECONDS;
    }

    private BigDecimal tokenCost(AgentModelConfig modelConfig, int promptTokens, int completionTokens) {
        BigDecimal inputPrice = price(modelConfig.getInputTokenPricePer1m());
        BigDecimal outputPrice = price(modelConfig.getOutputTokenPricePer1m());
        return inputPrice.multiply(BigDecimal.valueOf(promptTokens))
                .add(outputPrice.multiply(BigDecimal.valueOf(completionTokens)))
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
    }

    private RuleOutcome applyRules(AiTool tool, AgentModelConfig modelConfig, JsonNode params,
                                   BigDecimal cost, List<PricingBreakdownItem> breakdown) {
        Long modelId = modelConfig == null ? null : modelConfig.getId();
        Long toolId = tool == null ? null : tool.getId();
        Long categoryId = tool == null ? null : tool.getCategoryId();
        if (modelId == null && toolId == null && categoryId == null) {
            return new RuleOutcome(cost, 0);
        }
        List<PricingRule> rules = pricingRuleMapper.findActiveForScopes(modelId, toolId, categoryId);
        if (rules == null || rules.isEmpty()) {
            return new RuleOutcome(cost, 0);
        }
        BigDecimal adjusted = cost;
        int extraCredits = 0;
        for (PricingRule rule : rules) {
            if (!ruleMatches(rule, params)) {
                continue;
            }
            String type = rule.getRuleType() == null ? "MULTIPLIER" : rule.getRuleType().toUpperCase();
            if ("ADDITIVE".equals(type)) {
                int add = rule.getExtraCredits() == null ? 0 : Math.max(0, rule.getExtraCredits());
                if (add > 0) {
                    extraCredits += add;
                    breakdown.add(PricingBreakdownItem.of("参数加价(" + rule.getParamKey() + ")",
                            ruleDesc(rule), add));
                }
            } else {
                String op = rule.getMatchOp() == null ? "EQ" : rule.getMatchOp().toUpperCase();
                BigDecimal factor = resolveMultiplierFactor(rule, params, op);
                if (factor.compareTo(BigDecimal.ZERO) > 0 && factor.compareTo(BigDecimal.ONE) != 0) {
                    BigDecimal before = adjusted;
                    adjusted = adjusted.multiply(factor);
                    breakdown.add(PricingBreakdownItem.of("参数倍率(" + rule.getParamKey() + ")",
                            ruleDesc(rule),
                            costToCredits(adjusted) - costToCredits(before)));
                }
            }
        }
        return new RuleOutcome(adjusted, extraCredits);
    }

    private boolean ruleMatches(PricingRule rule, JsonNode params) {
        String op = rule.getMatchOp() == null ? "EQ" : rule.getMatchOp().toUpperCase();
        if ("ANY".equals(op)) {
            return params != null && params.hasNonNull(rule.getParamKey());
        }
        if (params == null || rule.getParamKey() == null) {
            return false;
        }
        JsonNode node = params.get(rule.getParamKey());
        if (node == null || node.isNull()) {
            return false;
        }
        String expected = rule.getMatchValue();
        if ("EQ".equals(op)) {
            return expected != null && node.asText("").trim().equalsIgnoreCase(expected.trim());
        }
        Double actual = asNumber(node);
        if ("VALUE".equals(op)) {
            return actual != null && actual > 0;
        }
        Double threshold = parseDouble(expected);
        if (actual == null || threshold == null) {
            return false;
        }
        return switch (op) {
            case "GT" -> actual > threshold;
            case "GTE" -> actual >= threshold;
            case "LT" -> actual < threshold;
            case "LTE" -> actual <= threshold;
            default -> false;
        };
    }

    private BigDecimal resolveMultiplierFactor(PricingRule rule, JsonNode params, String op) {
        if ("VALUE".equals(op)) {
            if (params == null || rule.getParamKey() == null) {
                return BigDecimal.ONE;
            }
            Double value = asNumber(params.get(rule.getParamKey()));
            if (value == null || value <= 0) {
                return BigDecimal.ONE;
            }
            BigDecimal factor = BigDecimal.valueOf(value);
            BigDecimal ruleFactor = rule.getFactor();
            if (ruleFactor != null && ruleFactor.compareTo(BigDecimal.ZERO) > 0) {
                factor = factor.multiply(ruleFactor);
            }
            return factor;
        }
        return rule.getFactor() == null ? BigDecimal.ONE : rule.getFactor();
    }

    private ResolvedMargin resolveMargin(AiTool tool, AgentModelConfig modelConfig) {
        if (modelConfig != null && modelConfig.getId() != null) {
            ResolvedMargin model = toMargin(pricingMarginMapper.findEnabledByScope("MODEL", modelConfig.getId()));
            if (model != null) {
                return model;
            }
        }
        if (tool != null && tool.getCategoryId() != null) {
            ResolvedMargin category = toMargin(pricingMarginMapper.findEnabledByScope("CATEGORY", tool.getCategoryId()));
            if (category != null) {
                return category;
            }
        }
        ResolvedMargin global = toMargin(pricingMarginMapper.findEnabledByScope("GLOBAL", 0L));
        return global != null ? global : new ResolvedMargin(DEFAULT_MARKUP, 0);
    }

    private ResolvedMargin toMargin(PricingMargin margin) {
        if (margin == null) {
            return null;
        }
        BigDecimal ratio = margin.getMarkupRatio() == null || margin.getMarkupRatio().compareTo(BigDecimal.ZERO) <= 0
                ? DEFAULT_MARKUP
                : margin.getMarkupRatio();
        int min = margin.getMinCredits() == null ? 0 : Math.max(0, margin.getMinCredits());
        return new ResolvedMargin(ratio, min);
    }

    private int applyMarkup(int baseCredits, BigDecimal ratio) {
        if (baseCredits <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(baseCredits).multiply(ratio).setScale(0, RoundingMode.CEILING).intValue();
    }

    private int costToCredits(BigDecimal cost) {
        if (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return cost.divide(CREDIT_PRICE_CNY, 0, RoundingMode.CEILING).intValue();
    }

    private BigDecimal price(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private Double asNumber(JsonNode node) {
        if (node.isNumber()) {
            return node.asDouble();
        }
        return parseDouble(node.asText(null));
    }

    private Double parseDouble(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.trim().replaceAll("[^0-9.\\-]", "");
        if (digits.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(digits);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String ruleDesc(PricingRule rule) {
        String op = rule.getMatchOp() == null ? "EQ" : rule.getMatchOp();
        if ("ANY".equalsIgnoreCase(op)) {
            return rule.getParamKey() + " 存在";
        }
        if ("VALUE".equalsIgnoreCase(op)) {
            return rule.getParamKey() + " 按参数数值倍率";
        }
        return rule.getParamKey() + " " + op + " " + rule.getMatchValue();
    }

    private record ResolvedMargin(BigDecimal ratio, int minCredits) {
    }

    private record RuleOutcome(BigDecimal cost, int extraCredits) {
    }

    private static final class VendorCost {
        private final boolean derived;
        private final BigDecimal cost;
        private final String detail;

        private VendorCost(boolean derived, BigDecimal cost, String detail) {
            this.derived = derived;
            this.cost = cost;
            this.detail = detail;
        }

        static VendorCost derived(BigDecimal cost, String detail) {
            return new VendorCost(true, cost, detail);
        }

        static VendorCost fallback() {
            return new VendorCost(false, BigDecimal.ZERO, "");
        }
    }
}
