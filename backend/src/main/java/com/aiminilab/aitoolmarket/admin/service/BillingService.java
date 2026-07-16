package com.aiminilab.aitoolmarket.admin.service;

import com.aiminilab.aitoolmarket.admin.dto.BillingOverviewResponse;
import com.aiminilab.aitoolmarket.admin.dto.BillingUsageLogResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface BillingService {
    BillingOverviewResponse overview(Long userId, Long modelConfigId, String provider, String modelName,
                                      String sourceType, Long sourceId, LocalDate startDate, LocalDate endDate);

    PageResponse<BillingUsageLogResponse> logs(Integer pageNo, Integer pageSize, Long userId, Long modelConfigId,
                                               String provider, String modelName, String sourceType,
                                               Long sourceId, LocalDate startDate, LocalDate endDate);

    /**
     * Records usage with full profitability fields. {@code chargedCredits} is the final user-facing
     * charge (incl. markup); {@code vendorCostAmount} (CNY) and {@code markupRatio} may be null when
     * the caller cannot derive them, in which case they are computed from token/unit pricing.
     */
    void recordUsage(String sourceType, Long sourceId, Long userId, AgentModelConfig modelConfig,
                     Integer promptTokens, Integer completionTokens, Integer billableUnits, Integer chargedCredits,
                     BigDecimal vendorCostAmount, BigDecimal markupRatio);

    void recordUsage(String sourceType, Long sourceId, Long userId, AgentModelConfig modelConfig,
                     Integer promptTokens, Integer completionTokens, Integer billableUnits, Integer chargedCredits,
                     BigDecimal vendorCostAmount, BigDecimal markupRatio,
                     String outcome, String errorCode, String failureStage,
                     String providerErrorCode, String providerRequestId, Boolean providerCharged);

    void recordUsage(String sourceType, Long sourceId, Long userId, AgentModelConfig modelConfig,
                     Integer promptTokens, Integer completionTokens, Integer billableUnits, Integer chargedCredits,
                     BigDecimal vendorCostAmount, String providerCostCurrency, BigDecimal markupRatio,
                     String outcome, String errorCode, String failureStage,
                     String providerErrorCode, String providerRequestId, Boolean providerCharged);

    Long recordUsageOnce(String idempotencyKey, String sourceType, Long sourceId, Long userId,
                         AgentModelConfig modelConfig, Integer promptTokens, Integer completionTokens,
                         Integer billableUnits, Integer chargedCredits, BigDecimal vendorCostAmount,
                         BigDecimal markupRatio);

    Long recordUsageOnce(String idempotencyKey, String sourceType, Long sourceId, Long userId,
                         AgentModelConfig modelConfig, Integer promptTokens, Integer completionTokens,
                         Integer billableUnits, Integer chargedCredits, BigDecimal vendorCostAmount,
                         BigDecimal markupRatio, String outcome, String errorCode, String failureStage,
                         String providerErrorCode, String providerRequestId, Boolean providerCharged);

    Long recordUsageOnce(String idempotencyKey, String sourceType, Long sourceId, Long userId,
                         AgentModelConfig modelConfig, Integer promptTokens, Integer completionTokens,
                         Integer billableUnits, Integer chargedCredits, BigDecimal vendorCostAmount,
                         String providerCostCurrency, BigDecimal markupRatio, String outcome,
                         String errorCode, String failureStage, String providerErrorCode,
                         String providerRequestId, Boolean providerCharged);

    boolean attachActualProviderAccounting(Long usageId, AgentModelConfig modelConfig,
                                           BigDecimal providerCostAmount, String providerCostCurrency,
                                           String providerRequestId);

    /** Backward-compatible overload (no explicit vendor cost / markup). */
    default void recordUsage(String sourceType, Long sourceId, Long userId, AgentModelConfig modelConfig,
                             Integer promptTokens, Integer completionTokens, Integer billableUnits, Integer chargedCredits) {
        recordUsage(sourceType, sourceId, userId, modelConfig, promptTokens, completionTokens, billableUnits,
                chargedCredits, null, null);
    }
}
