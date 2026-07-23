package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunContextSnapshotResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunDetailResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunListItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.AdminAgentRunStatsResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunEventResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentRunResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolCallResponse;
import com.aiminilab.aitoolmarket.agent.entity.AgentRun;
import com.aiminilab.aitoolmarket.agent.entity.AgentRunEvent;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunEventMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
import com.aiminilab.aitoolmarket.agent.service.AdminAgentRunService;
import com.aiminilab.aitoolmarket.agent.service.AgentRunService;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import com.aiminilab.aitoolmarket.agent.mapper.AgentContextSnapshotMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

@Service
public class AdminAgentRunServiceImpl implements AdminAgentRunService {

    private static final int ADMIN_EVENT_LIMIT = 500;

    private final AgentRunMapper agentRunMapper;
    private final AgentRunEventMapper agentRunEventMapper;
    private final AgentToolCallMapper agentToolCallMapper;
    private final AgentRunService agentRunService;
    private final AgentContextSnapshotMapper contextSnapshotMapper;
    private final ObjectMapper objectMapper;

    public AdminAgentRunServiceImpl(AgentRunMapper agentRunMapper,
                                    AgentRunEventMapper agentRunEventMapper,
                                    AgentToolCallMapper agentToolCallMapper,
                                    AgentRunService agentRunService,
                                    AgentContextSnapshotMapper contextSnapshotMapper,
                                    ObjectMapper objectMapper) {
        this.agentRunMapper = agentRunMapper;
        this.agentRunEventMapper = agentRunEventMapper;
        this.agentToolCallMapper = agentToolCallMapper;
        this.agentRunService = agentRunService;
        this.contextSnapshotMapper = contextSnapshotMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResponse<AdminAgentRunListItemResponse> list(String status, Long userId, Long taskId, Integer pageNo, Integer pageSize) {
        int limit = PageResponse.normalizePageSize(pageSize);
        int offset = PageResponse.offset(pageNo, pageSize);
        long total = agentRunMapper.countForAdmin(status, userId, taskId);
        return PageResponse.of(agentRunMapper.findForAdmin(status, userId, taskId, limit, offset), total, pageNo, pageSize);
    }

    @Override
    public AdminAgentRunStatsResponse stats() {
        return agentRunMapper.statsForAdmin();
    }

    @Override
    public AdminAgentRunDetailResponse detail(Long runId) {
        AgentRun run = findRun(runId);
        long totalEventCount = agentRunEventMapper.countByRunId(runId);
        List<AgentRunEvent> events = loadRecentEvents(runId, totalEventCount);
        var context = run.getContextSnapshotId() == null ? null : contextSnapshotMapper.selectById(run.getContextSnapshotId());
        return new AdminAgentRunDetailResponse(
                AgentRunResponse.fromAdmin(run),
                events.stream().map(AgentRunEventResponse::from).toList(),
                agentToolCallMapper.findByRunId(runId).stream()
                        .map(AgentToolCallResponse::fromAdmin)
                        .toList(),
                AdminAgentRunContextSnapshotResponse.from(context, objectMapper),
                totalEventCount > events.size(),
                totalEventCount
        );
    }

    @Override
    public List<AgentRunEventResponse> listEvents(Long runId, Long afterEventId, Integer pageSize) {
        findRun(runId);
        int limit = normalizeEventPageSize(pageSize);
        return agentRunEventMapper.findEventsForAdminPaged(runId, afterEventId, limit).stream()
                .map(AgentRunEventResponse::from)
                .toList();
    }

    @Override
    public AgentRunResponse cancel(Long runId) {
        AgentRun run = findRun(runId);
        return agentRunService.cancel(run.getUserId(), runId);
    }

    private List<AgentRunEvent> loadRecentEvents(Long runId, long totalEventCount) {
        if (totalEventCount <= ADMIN_EVENT_LIMIT) {
            return agentRunEventMapper.findEventsForAdmin(runId, ADMIN_EVENT_LIMIT);
        }
        return agentRunEventMapper.findRecentEventsForAdmin(runId, ADMIN_EVENT_LIMIT);
    }

    private int normalizeEventPageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 200;
        }
        return Math.min(pageSize, 500);
    }

    private AgentRun findRun(Long runId) {
        return agentRunMapper.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent run not found"));
    }
}
