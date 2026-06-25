package com.aiminilab.aitoolmarket.agent.config;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentRuntimeSettings {

    public static final String MAX_MODEL_CALLS_KEY = "agent.runtime.max_model_calls";
    public static final String MAX_TOOL_CALLS_KEY = "agent.runtime.max_tool_calls";
    public static final String MAX_HISTORY_MESSAGES_KEY = "agent.runtime.max_history_messages";
    public static final String WORKING_MEMORY_TOKEN_BUDGET_KEY = "agent.runtime.working_memory_token_budget";
    public static final String MESSAGE_TOKEN_SOFT_LIMIT_KEY = "agent.runtime.message_token_soft_limit";
    public static final String TOOL_OUTPUT_TOKEN_SOFT_LIMIT_KEY = "agent.runtime.tool_output_token_soft_limit";
    public static final String SUMMARY_TOKEN_LIMIT_KEY = "agent.runtime.summary_token_limit";
    public static final String TOOL_EXECUTION_TIMEOUT_SECONDS_KEY = "agent.runtime.tool_execution_timeout_seconds";
    public static final String IMAGE_TOOL_EXECUTION_TIMEOUT_SECONDS_KEY = "agent.runtime.image_tool_execution_timeout_seconds";
    public static final String VIDEO_TOOL_EXECUTION_TIMEOUT_SECONDS_KEY = "agent.runtime.video_tool_execution_timeout_seconds";
    public static final String MUSIC_TOOL_EXECUTION_TIMEOUT_SECONDS_KEY = "agent.runtime.music_tool_execution_timeout_seconds";
    public static final String TOOL_POLL_INTERVAL_SECONDS_KEY = "agent.runtime.tool_poll_interval_seconds";
    public static final String TOOL_STREAM_RELAY_ENABLED_KEY = "agent.runtime.tool_stream_relay_enabled";
    public static final String PRODUCT_TOOL_LOOP_ENABLED_KEY = "agent.runtime.product_tool_loop_enabled";
    public static final String PRODUCT_TOOL_LOOP_MAX_CALLS_KEY = "agent.runtime.product_tool_loop_max_calls";
    public static final String PRODUCT_TOOL_LOOP_FALLBACK_TO_ROUTER_KEY = "agent.runtime.product_tool_loop_fallback_to_router";

    public static final int DEFAULT_MAX_MODEL_CALLS = 5;
    public static final int DEFAULT_MAX_TOOL_CALLS = 3;
    public static final int DEFAULT_MAX_HISTORY_MESSAGES = 20;
    public static final int DEFAULT_WORKING_MEMORY_TOKEN_BUDGET = 6000;
    public static final int DEFAULT_MESSAGE_TOKEN_SOFT_LIMIT = 1200;
    public static final int DEFAULT_TOOL_OUTPUT_TOKEN_SOFT_LIMIT = 500;
    public static final int DEFAULT_SUMMARY_TOKEN_LIMIT = 1200;
    public static final int DEFAULT_TOOL_EXECUTION_TIMEOUT_SECONDS = 120;
    public static final int DEFAULT_IMAGE_TOOL_EXECUTION_TIMEOUT_SECONDS = 600;
    public static final int DEFAULT_VIDEO_TOOL_EXECUTION_TIMEOUT_SECONDS = 900;
    public static final int DEFAULT_MUSIC_TOOL_EXECUTION_TIMEOUT_SECONDS = 900;
    public static final double DEFAULT_TOOL_POLL_INTERVAL_SECONDS = 1D;
    public static final boolean DEFAULT_TOOL_STREAM_RELAY_ENABLED = true;
    public static final boolean DEFAULT_PRODUCT_TOOL_LOOP_ENABLED = true;
    public static final int DEFAULT_PRODUCT_TOOL_LOOP_MAX_CALLS = 1;
    public static final boolean DEFAULT_PRODUCT_TOOL_LOOP_FALLBACK_TO_ROUTER = true;

    private AgentRuntimeSettings() {
    }

    public static Map<String, String> defaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put(MAX_MODEL_CALLS_KEY, String.valueOf(DEFAULT_MAX_MODEL_CALLS));
        defaults.put(MAX_TOOL_CALLS_KEY, String.valueOf(DEFAULT_MAX_TOOL_CALLS));
        defaults.put(MAX_HISTORY_MESSAGES_KEY, String.valueOf(DEFAULT_MAX_HISTORY_MESSAGES));
        defaults.put(WORKING_MEMORY_TOKEN_BUDGET_KEY, String.valueOf(DEFAULT_WORKING_MEMORY_TOKEN_BUDGET));
        defaults.put(MESSAGE_TOKEN_SOFT_LIMIT_KEY, String.valueOf(DEFAULT_MESSAGE_TOKEN_SOFT_LIMIT));
        defaults.put(TOOL_OUTPUT_TOKEN_SOFT_LIMIT_KEY, String.valueOf(DEFAULT_TOOL_OUTPUT_TOKEN_SOFT_LIMIT));
        defaults.put(SUMMARY_TOKEN_LIMIT_KEY, String.valueOf(DEFAULT_SUMMARY_TOKEN_LIMIT));
        defaults.put(TOOL_EXECUTION_TIMEOUT_SECONDS_KEY, String.valueOf(DEFAULT_TOOL_EXECUTION_TIMEOUT_SECONDS));
        defaults.put(IMAGE_TOOL_EXECUTION_TIMEOUT_SECONDS_KEY, String.valueOf(DEFAULT_IMAGE_TOOL_EXECUTION_TIMEOUT_SECONDS));
        defaults.put(VIDEO_TOOL_EXECUTION_TIMEOUT_SECONDS_KEY, String.valueOf(DEFAULT_VIDEO_TOOL_EXECUTION_TIMEOUT_SECONDS));
        defaults.put(MUSIC_TOOL_EXECUTION_TIMEOUT_SECONDS_KEY, String.valueOf(DEFAULT_MUSIC_TOOL_EXECUTION_TIMEOUT_SECONDS));
        defaults.put(TOOL_POLL_INTERVAL_SECONDS_KEY, String.valueOf(DEFAULT_TOOL_POLL_INTERVAL_SECONDS));
        defaults.put(TOOL_STREAM_RELAY_ENABLED_KEY, String.valueOf(DEFAULT_TOOL_STREAM_RELAY_ENABLED));
        defaults.put(PRODUCT_TOOL_LOOP_ENABLED_KEY, String.valueOf(DEFAULT_PRODUCT_TOOL_LOOP_ENABLED));
        defaults.put(PRODUCT_TOOL_LOOP_MAX_CALLS_KEY, String.valueOf(DEFAULT_PRODUCT_TOOL_LOOP_MAX_CALLS));
        defaults.put(PRODUCT_TOOL_LOOP_FALLBACK_TO_ROUTER_KEY, String.valueOf(DEFAULT_PRODUCT_TOOL_LOOP_FALLBACK_TO_ROUTER));
        return defaults;
    }
}
