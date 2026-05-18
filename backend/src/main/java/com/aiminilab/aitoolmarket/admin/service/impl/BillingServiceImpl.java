package com.aiminilab.aitoolmarket.admin.service.impl;

import com.aiminilab.aitoolmarket.admin.dto.BillingOverviewResponse;
import com.aiminilab.aitoolmarket.admin.dto.BillingUsageLogResponse;
import com.aiminilab.aitoolmarket.admin.entity.BillingUsageLog;
import com.aiminilab.aitoolmarket.admin.mapper.BillingUsageLogMapper;
import com.aiminilab.aitoolmarket.admin.service.BillingService;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class BillingServiceImpl implements BillingService {
    private static final String BILLING_UNIT_PER_CALL = "PER_CALL";

    private final BillingUsageLogMapper billingUsageLogMapper;

    public BillingServiceImpl(BillingUsageLogMapper billingUsageLogMapper) {
        this.billingUsageLogMapper = billingUsageLogMapper;
    }

    @Override
    public BillingOverviewResponse overview() {
        LocalDate today = LocalDate.now();
        LocalDateTime startAt = today.atStartOfDay();
        LocalDateTime endAt = today.plusDays(1).atStartOfDay();
        return new BillingOverviewResponse(
                billingUsageLogMapper.sumPromptTokens(startAt, endAt),
                billingUsageLogMapper.sumCompletionTokens(startAt, endAt),
                billingUsageLogMapper.sumTotalTokens(startAt, endAt),
                zeroIfNull(billingUsageLogMapper.sumCostAmount(startAt, endAt)),
                billingUsageLogMapper.sumChargedCredits(startAt, endAt),
                billingUsageLogMapper.countUsage(startAt, endAt),
                billingUsageLogMapper.modelCosts(startAt, endAt)
        );
    }

    @Override
    public PageResponse<BillingUsageLogResponse> logs(Integer pageNo, Integer pageSize) {
        int normalizedPageSize = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        long total = billingUsageLogMapper.countLogs();
        return PageResponse.of(billingUsageLogMapper.findLogs(normalizedPageSize, offset), total, pageNo, pageSize);
    }

    @Override
    public void recordUsage(String sourceType, Long sourceId, Long userId, AgentModelConfig modelConfig,
                            Integer promptTokens, Integer completionTokens, Integer billableUnits, Integer chargedCredits) {
        int prompt = nonNegative(promptTokens);
        int completion = nonNegative(completionTokens);
        int units = nonNegative(billableUnits);
        int charged = nonNegative(chargedCredits);
        if (prompt == 0 && completion == 0 && units == 0 && charged == 0) {
            return;
        }
        BigDecimal inputPricePer1m = price(modelConfig == null ? null : modelConfig.getInputTokenPricePer1m());
        BigDecimal outputPricePer1m = price(modelConfig == null ? null : modelConfig.getOutputTokenPricePer1m());
        BigDecimal inputPricePer1k = price(modelConfig == null ? null : modelConfig.getInputTokenPricePer1k());
        BigDecimal outputPricePer1k = price(modelConfig == null ? null : modelConfig.getOutputTokenPricePer1k());
        BigDecimal unitPrice = price(modelConfig == null ? null : modelConfig.getUnitPrice());
        String billingUnit = modelConfig == null || modelConfig.getBillingUnit() == null || modelConfig.getBillingUnit().isBlank()
                ? "TOKEN_PER_M"
                : modelConfig.getBillingUnit();
        BillingUsageLog log = new BillingUsageLog();
        log.setSourceType(sourceType);
        log.setSourceId(sourceId);
        log.setUserId(userId);
        log.setModelConfigId(modelConfig == null ? null : modelConfig.getId());
        log.setProvider(modelConfig == null ? null : modelConfig.getProvider());
        log.setModelName(modelConfig == null ? null : modelConfig.getModelName());
        log.setPromptTokens(prompt);
        log.setCompletionTokens(completion);
        log.setTotalTokens(prompt + completion);
        log.setInputTokenPricePer1k(inputPricePer1k);
        log.setOutputTokenPricePer1k(outputPricePer1k);
        log.setInputTokenPricePer1m(inputPricePer1m);
        log.setOutputTokenPricePer1m(outputPricePer1m);
        log.setBillingUnit(billingUnit);
        log.setBillableUnits(units);
        log.setUnitPrice(unitPrice);
        log.setCostAmount(costPerMillion(prompt, inputPricePer1m)
                .add(costPerMillion(completion, outputPricePer1m))
                .add(perCallCost(billingUnit, units, unitPrice)));
        log.setChargedCredits(charged);
        log.setCreatedAt(LocalDateTime.now());
        billingUsageLogMapper.insert(log);
    }

    private BigDecimal costPerMillion(int tokens, BigDecimal pricePer1m) {
        return pricePer1m.multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal perCallCost(String billingUnit, int units, BigDecimal unitPrice) {
        if (!BILLING_UNIT_PER_CALL.equals(billingUnit) || units <= 0) {
            return BigDecimal.ZERO;
        }
        return unitPrice.multiply(BigDecimal.valueOf(units)).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal price(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
