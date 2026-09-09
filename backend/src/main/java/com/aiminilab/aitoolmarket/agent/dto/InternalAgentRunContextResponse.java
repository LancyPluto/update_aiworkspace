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
        String conversationSummary,
        List<InternalAgentFileContextResponse> agentFiles,
        List<InternalAgentFileChunkContextResponse> agentFileChunks,
        List<AgentToolDescriptorResponse> availableTools,
        List<AgentSkillDescriptorResponse> availableSkills,
        List<AgentToolPreferenceResponse> toolPreferences,
        Integer creditBudget,
        AgentContextWindowResponse contextWindow,
        InternalAgentModelConfigResponse modelConfig,
        String agentSystemPrompt,
        AgentMemorySettingsResponse memorySettings,
        AgentRouterSettingsResponse routerSettings,
        AgentRuntimeSettingsResponse runtimeSettings,
        List<InternalRecentToolCallContextResponse> recentToolCalls,
        InternalPendingToolContextResponse pendingToolContext,
        String preferredToolCode,
        List<InternalReferenceMentionResponse> referenceMentions,
        List<Object> globalFileIds,
        List<java.util.Map<String, Object>> contentParts,
        String positionalPrompt
) {
}
