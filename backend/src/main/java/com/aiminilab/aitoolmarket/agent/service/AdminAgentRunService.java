package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunDetailResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunListItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunStatsResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunEventResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunResponse;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;

import java.util.List;

public interface AdminAgentRunService {
    PageResponse<AdminAgentRunListItemResponse> list(String status, Long userId, Long taskId, Integer pageNo, Integer pageSize);

    AdminAgentRunStatsResponse stats();

    AdminAgentRunDetailResponse detail(Long runId);

    List<AgentRunEventResponse> listEvents(Long runId, Long afterEventId, Integer pageSize);

    AgentRunResponse cancel(Long runId);
}
