package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunDetailResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunListItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunStatsResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;

public interface AdminAgentRunService {
    PageResponse<AdminAgentRunListItemResponse> list(String status, Long userId, Integer pageNo, Integer pageSize);

    AdminAgentRunStatsResponse stats();

    AdminAgentRunDetailResponse detail(Long runId);

    AgentRunResponse cancel(Long runId);
}
