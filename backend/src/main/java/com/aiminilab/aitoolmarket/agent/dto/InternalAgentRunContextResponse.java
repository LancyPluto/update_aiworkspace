package com.aiminilab.aitoolmarket.agent.dto;

import java.util.List;

public record InternalAgentRunContextResponse(
        Long runId,
        Long sessionId,
        Long workspaceId,
        Long userId,
        String status,
        String message,
        List<InternalAgentMessageResponse> history,
        List<InternalAgentFileContextResponse> agentFiles,
        List<InternalAgentFileChunkContextResponse> agentFileChunks,
        List<AgentToolDescriptorResponse> availableTools,
        List<AgentToolPreferenceResponse> toolPreferences,
        Integer creditBudget,
        AgentContextWindowResponse contextWindow,
        InternalAgentModelConfigResponse modelConfig,
        String agentSystemPrompt,
        String deepAgentsSystemPrompt,
        AgentMemorySettingsResponse memorySettings,
        InternalPendingToolContextResponse pendingToolContext
) {
}
