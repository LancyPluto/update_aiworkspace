package com.aiminilab.aitoolmarket.admin.service;

import com.aiminilab.aitoolmarket.admin.dto.BillingOverviewResponse;
import com.aiminilab.aitoolmarket.admin.dto.BillingUsageLogResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;

import java.time.LocalDate;

public interface BillingService {
    BillingOverviewResponse overview(Long userId, Long modelConfigId, String provider, String modelName,
                                      String sourceType, Long sourceId, LocalDate startDate, LocalDate endDate);

    PageResponse<BillingUsageLogResponse> logs(Integer pageNo, Integer pageSize, Long userId, Long modelConfigId,
                                               String provider, String modelName, String sourceType,
                                               Long sourceId, LocalDate startDate, LocalDate endDate);

    void recordUsage(String sourceType, Long sourceId, Long userId, AgentModelConfig modelConfig,
                     Integer promptTokens, Integer completionTokens, Integer billableUnits, Integer chargedCredits);
}
