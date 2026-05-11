package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunDetailResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunListItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunStatsResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunEventResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolCallResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentRun;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunEventMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
import com.aiminilab.aitoolmarket.agent.service.AdminAgentRunService;
import com.aiminilab.aitoolmarket.agent.service.AgentRunService;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class AdminAgentRunServiceImpl implements AdminAgentRunService {

    private final AgentRunMapper agentRunMapper;
    private final AgentRunEventMapper agentRunEventMapper;
    private final AgentToolCallMapper agentToolCallMapper;
    private final AgentRunService agentRunService;

    public AdminAgentRunServiceImpl(AgentRunMapper agentRunMapper,
                                    AgentRunEventMapper agentRunEventMapper,
                                    AgentToolCallMapper agentToolCallMapper,
                                    AgentRunService agentRunService) {
        this.agentRunMapper = agentRunMapper;
        this.agentRunEventMapper = agentRunEventMapper;
        this.agentToolCallMapper = agentToolCallMapper;
        this.agentRunService = agentRunService;
    }

    @Override
    public PageResponse<AdminAgentRunListItemResponse> list(String status, Long userId, Integer pageNo, Integer pageSize) {
        int limit = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        long total = agentRunMapper.countForAdmin(status, userId);
        return PageResponse.of(agentRunMapper.findForAdmin(status, userId, limit, offset), total, pageNo, pageSize);
    }

    @Override
    public AdminAgentRunStatsResponse stats() {
        return agentRunMapper.statsForAdmin();
    }

    @Override
    public AdminAgentRunDetailResponse detail(Long runId) {
        AgentRun run = findRun(runId);
        return new AdminAgentRunDetailResponse(
                AgentRunResponse.from(run),
                agentRunEventMapper.findEventsForAdmin(runId, 200).stream()
                        .map(AgentRunEventResponse::from)
                        .toList(),
                agentToolCallMapper.findByRunId(runId).stream()
                        .map(AgentToolCallResponse::from)
                        .toList()
        );
    }

    @Override
    public AgentRunResponse cancel(Long runId) {
        AgentRun run = findRun(runId);
        return agentRunService.cancel(run.getUserId(), runId);
    }

    private AgentRun findRun(Long runId) {
        return agentRunMapper.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent run not found"));
    }
}
