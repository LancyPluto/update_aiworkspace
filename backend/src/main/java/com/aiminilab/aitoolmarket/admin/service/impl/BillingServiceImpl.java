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
                            Integer promptTokens, Integer completionTokens, Integer chargedCredits) {
        int prompt = nonNegative(promptTokens);
        int completion = nonNegative(completionTokens);
        if (prompt == 0 && completion == 0) {
            return;
        }
        BigDecimal inputPrice = price(modelConfig == null ? null : modelConfig.getInputTokenPricePer1k());
        BigDecimal outputPrice = price(modelConfig == null ? null : modelConfig.getOutputTokenPricePer1k());
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
        log.setInputTokenPricePer1k(inputPrice);
        log.setOutputTokenPricePer1k(outputPrice);
        log.setCostAmount(cost(prompt, inputPrice).add(cost(completion, outputPrice)));
        log.setChargedCredits(nonNegative(chargedCredits));
        log.setCreatedAt(LocalDateTime.now());
        billingUsageLogMapper.insert(log);
    }

    private BigDecimal cost(int tokens, BigDecimal pricePer1k) {
        return pricePer1k.multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
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
