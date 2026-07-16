package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;
import java.util.Map;

public record AgentToolDescriptorResponse(
        String toolCode,
        String name,
        String description,
        Integer creditCost,
        Object inputSchema,
        boolean autoCallable,
        List<AgentToolFieldDescriptorResponse> fields,
        Map<String, Object> agentHints,
        String executionMode,
        String billingMode,
        Integer minimumRequiredCredits,
        String runRouteTemplate,
        String riskLevel,
        String confirmationPolicy
) {

    public AgentToolDescriptorResponse(
            String toolCode,
            String name,
            String description,
            Integer creditCost,
            Object inputSchema,
            boolean autoCallable,
            List<AgentToolFieldDescriptorResponse> fields,
            Map<String, Object> agentHints
    ) {
        this(
                toolCode,
                name,
                description,
                creditCost,
                inputSchema,
                autoCallable,
                fields,
                agentHints,
                "DIRECT",
                "FIXED",
                0,
                null,
                "low",
                "auto"
        );
    }
}
