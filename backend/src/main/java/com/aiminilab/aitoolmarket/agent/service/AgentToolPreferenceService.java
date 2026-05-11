package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AgentToolPreferenceResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentToolPreferenceRequest;

import java.util.List;

public interface AgentToolPreferenceService {
    List<AgentToolPreferenceResponse> list(Long userId);

    AgentToolPreferenceResponse update(Long userId, String toolCode, UpdateAgentToolPreferenceRequest request);
}
