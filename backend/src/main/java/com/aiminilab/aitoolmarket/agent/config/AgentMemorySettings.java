package com.aiminilab.aitoolmarket.agent.config;

public final class AgentMemorySettings {

    public static final String AUTO_SAVE_ENABLED_KEY = "agent.memory.auto_save_enabled";
    public static final String RETRIEVAL_LIMIT_KEY = "agent.memory.retrieval_limit";
    public static final String ENABLED_TYPES_KEY = "agent.memory.enabled_types";
    public static final String WRITE_PROMPT_KEY = "agent.memory.write_prompt";
    public static final String RETRIEVAL_PROMPT_KEY = "agent.memory.retrieval_prompt";

    public static final boolean DEFAULT_AUTO_SAVE_ENABLED = true;
    public static final int DEFAULT_RETRIEVAL_LIMIT = 6;
    public static final String DEFAULT_ENABLED_TYPES = "user_profile,project_knowledge,custom";

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
}
