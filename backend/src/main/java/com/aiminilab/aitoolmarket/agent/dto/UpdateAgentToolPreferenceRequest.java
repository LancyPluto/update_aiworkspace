package com.aiminilab.aitoolmarket.agent.dto;

public record UpdateAgentToolPreferenceRequest(
        Boolean autoCallEnabled,
        Boolean disabled
) {
}
