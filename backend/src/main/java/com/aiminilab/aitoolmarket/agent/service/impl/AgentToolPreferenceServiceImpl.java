package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.dto.AgentToolPreferenceResponse;
import com.aiminilab.aitoolmarket.agent.dto.UpdateAgentToolPreferenceRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentToolPreference;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolPreferenceMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentToolPreferenceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AgentToolPreferenceServiceImpl implements AgentToolPreferenceService {

    private final AgentToolPreferenceMapper agentToolPreferenceMapper;

    public AgentToolPreferenceServiceImpl(AgentToolPreferenceMapper agentToolPreferenceMapper) {
        this.agentToolPreferenceMapper = agentToolPreferenceMapper;
    }

    @Override
    public List<AgentToolPreferenceResponse> list(Long userId) {
        return agentToolPreferenceMapper.findByUserId(userId)
                .stream()
                .map(AgentToolPreferenceResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public AgentToolPreferenceResponse update(Long userId, String toolCode, UpdateAgentToolPreferenceRequest request) {
        LocalDateTime now = LocalDateTime.now();
        AgentToolPreference existing = agentToolPreferenceMapper.findByUserIdAndToolCode(userId, toolCode);
        if (existing == null) {
            AgentToolPreference preference = new AgentToolPreference();
            preference.setUserId(userId);
            preference.setToolCode(toolCode);
            preference.setAutoCallEnabled(request.autoCallEnabled());
            preference.setCreatedAt(now);
            preference.setUpdatedAt(now);
            agentToolPreferenceMapper.insertPreference(preference);
            return AgentToolPreferenceResponse.from(preference);
        }
        agentToolPreferenceMapper.updatePreference(userId, toolCode, request.autoCallEnabled(), now);
        AgentToolPreference updated = agentToolPreferenceMapper.findByUserIdAndToolCode(userId, toolCode);
        return AgentToolPreferenceResponse.from(updated);
    }
}
