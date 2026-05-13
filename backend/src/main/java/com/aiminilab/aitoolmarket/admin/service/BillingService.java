package com.aiminilab.aitoolmarket.admin.service;

import com.aiminilab.aitoolmarket.admin.dto.BillingOverviewResponse;
import com.aiminilab.aitoolmarket.admin.dto.BillingUsageLogResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentModelConfig;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;

public interface BillingService {
    BillingOverviewResponse overview();

    PageResponse<BillingUsageLogResponse> logs(Integer pageNo, Integer pageSize);

    void recordUsage(String sourceType, Long sourceId, Long userId, AgentModelConfig modelConfig,
                     Integer promptTokens, Integer completionTokens, Integer chargedCredits);
}
