package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AdminAgentToolAccessResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolPickerItemResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentToolAccessRequest;

import java.util.List;

public interface AgentToolDescriptorService {
    List<AgentToolDescriptorResponse> listAvailableToolsForUser(Long userId);

    List<AgentToolPickerItemResponse> listPickerToolsForUser(Long userId);

    AgentToolDescriptorResponse getToolForAgent(Long userId, String toolCode);

    List<AdminAgentToolAccessResponse> listAdminToolAccess();

    AdminAgentToolAccessResponse updateAdminToolAccess(String toolCode, UpdateAgentToolAccessRequest request);

    void markToolHealth(String toolCode, String healthStatus, String healthMessage);
}
