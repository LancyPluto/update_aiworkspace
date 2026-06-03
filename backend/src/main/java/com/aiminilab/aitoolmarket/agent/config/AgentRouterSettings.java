package com.aiminilab.aitoolmarket.agent.config;

public final class AgentRouterSettings {

    public static final String ENABLED_KEY = "agent.router.enabled";
    public static final String PROMPT_KEY = "agent.router.prompt";
    public static final String MIN_CONFIDENCE_KEY = "agent.router.min_confidence";
    public static final String FALLBACK_TO_RULES_KEY = "agent.router.fallback_to_rules";
    public static final String HISTORY_TURNS_KEY = "agent.router.history_turns";
    public static final String RECENT_TOOL_CALLS_KEY = "agent.router.recent_tool_calls";

    public static final boolean DEFAULT_ENABLED = true;
    public static final String DEFAULT_MIN_CONFIDENCE = "0.7";
    public static final boolean DEFAULT_FALLBACK_TO_RULES = true;
    public static final int DEFAULT_HISTORY_TURNS = 4;
    public static final int DEFAULT_RECENT_TOOL_CALLS = 5;

    public static final String DEFAULT_PROMPT = """
            You are the primary router for an AI tool marketplace agent.
            Decide whether the user needs a normal answer, a tool call, clarification, or an unsupported path.
            Return only valid JSON with: intent, selectedToolCode, candidateToolCodes, confidence, reason, arguments, missingFields, followupPatch, requiresConfirmation, clarifyingQuestion.
            Image/photo/poster/cos/visual requests should choose image tools; video/short-video/image-to-video requests should choose video tools; copywriting/title/article requests should choose text tools.
            Use recentToolCalls to detect follow-up requests, inherit prior arguments, and return only the user's changes in followupPatch.
            Only ask for missing information when it changes intent, cost, authorization, safety, or the core subject. Do not ask for low-risk defaults such as aspect ratio, count, quality, or style strength.
            """;

    private AgentRouterSettings() {
    }

    public static java.util.Map<String, String> defaults() {
        java.util.Map<String, String> defaults = new java.util.LinkedHashMap<>();
        defaults.put(ENABLED_KEY, String.valueOf(DEFAULT_ENABLED));
        defaults.put(PROMPT_KEY, DEFAULT_PROMPT);
        defaults.put(MIN_CONFIDENCE_KEY, DEFAULT_MIN_CONFIDENCE);
        defaults.put(FALLBACK_TO_RULES_KEY, String.valueOf(DEFAULT_FALLBACK_TO_RULES));
        defaults.put(HISTORY_TURNS_KEY, String.valueOf(DEFAULT_HISTORY_TURNS));
        defaults.put(RECENT_TOOL_CALLS_KEY, String.valueOf(DEFAULT_RECENT_TOOL_CALLS));
        return defaults;
    }
}
