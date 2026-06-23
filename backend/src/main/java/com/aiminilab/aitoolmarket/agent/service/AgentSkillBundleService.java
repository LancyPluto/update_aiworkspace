package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.dto.AgentSkillBundleResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentSkillDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolDescriptorResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentSkillBundleRequest;

import java.util.List;

public interface AgentSkillBundleService {
    List<AgentSkillBundleResponse> listAdmin();

    AgentSkillBundleResponse getAdmin(String skillCode);

    AgentSkillBundleResponse saveDraft(String skillCode, UpdateAgentSkillBundleRequest request);

    AgentSkillBundleResponse publish(String skillCode);

    AgentSkillBundleResponse getPublished(String skillCode);

    List<AgentSkillDescriptorResponse> listAvailableSkillDescriptors(List<AgentToolDescriptorResponse> availableTools);
}
