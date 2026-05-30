package com.aiminilab.aitoolmarket.agent.config;

public final class AgentRouterSettings {

    public static final String ENABLED_KEY = "agent.router.enabled";
    public static final String PROMPT_KEY = "agent.router.prompt";
    public static final String MIN_CONFIDENCE_KEY = "agent.router.min_confidence";
    public static final String FALLBACK_TO_RULES_KEY = "agent.router.fallback_to_rules";

    public static final boolean DEFAULT_ENABLED = true;
    public static final String DEFAULT_MIN_CONFIDENCE = "0.7";
    public static final boolean DEFAULT_FALLBACK_TO_RULES = true;

    public static final String DEFAULT_PROMPT = """
            You are the primary router for an AI tool marketplace agent.
            Decide whether the user needs a normal answer, a tool call, clarification, or an unsupported path.
            Return only valid JSON with: intent, selectedToolCode, candidateToolCodes, confidence, reason, arguments, missingFields, clarifyingQuestion.
            Image/photo/poster/cos/visual requests should choose image tools; video/short-video/image-to-video requests should choose video tools; copywriting/title/article requests should choose text tools.
            Only ask for missing information when it changes intent, cost, authorization, safety, or the core subject. Do not ask for low-risk defaults such as aspect ratio, count, quality, or style strength.
            """;

    private AgentRouterSettings() {
    }
}
