package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AgentToolDescriptorResponse;

import java.util.List;

public interface AgentToolDescriptorService {
    List<AgentToolDescriptorResponse> listAvailableToolsForUser(Long userId);

    AgentToolDescriptorResponse getToolForAgent(Long userId, String toolCode);
}
