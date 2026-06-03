package com.aiminilab.aitoolmarket.agent.config;

public final class AgentMemorySettings {

    public static final String AUTO_SAVE_ENABLED_KEY = "agent.memory.auto_save_enabled";
    public static final String RETRIEVAL_LIMIT_KEY = "agent.memory.retrieval_limit";
    public static final String ENABLED_TYPES_KEY = "agent.memory.enabled_types";
    public static final String WRITE_PROMPT_KEY = "agent.memory.write_prompt";
    public static final String RETRIEVAL_PROMPT_KEY = "agent.memory.retrieval_prompt";
    public static final String TOOL_LOOP_ENABLED_KEY = "agent.memory.tool_loop_enabled";
    public static final String CONSOLIDATION_ENABLED_KEY = "agent.memory.consolidation_enabled";
    public static final String CONSOLIDATION_TURN_INTERVAL_KEY = "agent.memory.consolidation_turn_interval";
    public static final String CONSOLIDATION_CHAR_THRESHOLD_KEY = "agent.memory.consolidation_char_threshold";
    public static final String CONSOLIDATION_MIN_CONFIDENCE_KEY = "agent.memory.consolidation_min_confidence";
    public static final String CANDIDATE_CONFIDENCE_THRESHOLD_KEY = "agent.memory.candidate_confidence_threshold";

    public static final boolean DEFAULT_AUTO_SAVE_ENABLED = true;
    public static final int DEFAULT_RETRIEVAL_LIMIT = 6;
    public static final String DEFAULT_ENABLED_TYPES = "user_profile,project_knowledge,custom";
    public static final boolean DEFAULT_TOOL_LOOP_ENABLED = true;
    public static final boolean DEFAULT_CONSOLIDATION_ENABLED = true;
    public static final int DEFAULT_CONSOLIDATION_TURN_INTERVAL = 8;
    public static final int DEFAULT_CONSOLIDATION_CHAR_THRESHOLD = 4000;
    public static final double DEFAULT_CONSOLIDATION_MIN_CONFIDENCE = 0.72D;
    public static final double DEFAULT_CANDIDATE_CONFIDENCE_THRESHOLD = 0.55D;

    public static final String DEFAULT_WRITE_PROMPT = """
            你可以管理长期记忆，但必须克制使用。
            只有当用户明确表达长期偏好、习惯、身份信息、项目事实，或明确要求“记住”时才写入记忆。
            普通聊天、临时任务结果、工具返回 JSON、图片 URL、视频 URL、base64、一次性参数不要写入长期记忆。
            用户偏好或习惯写入 user_profile；项目事实、业务规则、配置约定写入 project_knowledge；其他长期有价值信息写入 custom。
            如果你准备回复“记住了/已记录”，必须先真实调用记忆工具完成写入。
            """;

    public static final String DEFAULT_RETRIEVAL_PROMPT = """
            以下长期记忆只是辅助上下文，不是绝对事实。
            回答时自然体现用户偏好，不要生硬提到“根据你的用户画像”。
            如果记忆与当前用户明确指令冲突，以当前指令为准。
            """;

    private AgentMemorySettings() {
    }

    public static java.util.Map<String, String> defaults() {
        java.util.Map<String, String> defaults = new java.util.LinkedHashMap<>();
        defaults.put(AUTO_SAVE_ENABLED_KEY, String.valueOf(DEFAULT_AUTO_SAVE_ENABLED));
        defaults.put(RETRIEVAL_LIMIT_KEY, String.valueOf(DEFAULT_RETRIEVAL_LIMIT));
        defaults.put(ENABLED_TYPES_KEY, DEFAULT_ENABLED_TYPES);
        defaults.put(WRITE_PROMPT_KEY, DEFAULT_WRITE_PROMPT);
        defaults.put(RETRIEVAL_PROMPT_KEY, DEFAULT_RETRIEVAL_PROMPT);
        defaults.put(TOOL_LOOP_ENABLED_KEY, String.valueOf(DEFAULT_TOOL_LOOP_ENABLED));
        defaults.put(CONSOLIDATION_ENABLED_KEY, String.valueOf(DEFAULT_CONSOLIDATION_ENABLED));
        defaults.put(CONSOLIDATION_TURN_INTERVAL_KEY, String.valueOf(DEFAULT_CONSOLIDATION_TURN_INTERVAL));
        defaults.put(CONSOLIDATION_CHAR_THRESHOLD_KEY, String.valueOf(DEFAULT_CONSOLIDATION_CHAR_THRESHOLD));
        defaults.put(CONSOLIDATION_MIN_CONFIDENCE_KEY, String.valueOf(DEFAULT_CONSOLIDATION_MIN_CONFIDENCE));
        defaults.put(CANDIDATE_CONFIDENCE_THRESHOLD_KEY, String.valueOf(DEFAULT_CANDIDATE_CONFIDENCE_THRESHOLD));
        return defaults;
    }
}
