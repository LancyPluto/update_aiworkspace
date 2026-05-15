package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AgentMessageResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentSessionResponse;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentSessionRequest;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;

public interface AgentSessionService {
    AgentSessionResponse create(Long userId, CreateAgentSessionRequest request);

    PageResponse<AgentSessionResponse> list(Long userId, Integer pageNo, Integer pageSize);

    AgentSessionResponse detail(Long userId, Long sessionId);

    PageResponse<AgentMessageResponse> messages(Long userId, Long sessionId, Integer pageNo, Integer pageSize);

    void delete(Long userId, Long sessionId);
}
