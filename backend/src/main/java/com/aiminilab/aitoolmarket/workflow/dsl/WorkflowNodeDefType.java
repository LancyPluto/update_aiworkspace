package com.aiminilab.aitoolmarket.workflow.dsl;

import java.util.Locale;
import java.util.Optional;

public enum WorkflowNodeDefType {
    START,
    FIELD_INPUT,
    LLM_TEXT,
    MODEL_CALL,
    IMAGE_MODEL,
    TTS_MODEL,
    VIDEO_MODEL,
    SUBTITLE,
    TOOL_CALL,
    VIDEO_OUTPUT,
    USER_INPUT,
    USER_CONFIRM,
    CONDITION;

    public static Optional<WorkflowNodeDefType> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "start" -> Optional.of(START);
            case "field_input", "input" -> Optional.of(FIELD_INPUT);
            case "llm_text", "text_model", "llm_model" -> Optional.of(LLM_TEXT);
            case "model_call" -> Optional.of(MODEL_CALL);
            case "backend_tool" -> Optional.of(TOOL_CALL);
            case "image_model", "image_generation" -> Optional.of(IMAGE_MODEL);
            case "tts_model", "voice_model" -> Optional.of(TTS_MODEL);
            case "video_model", "video_generation" -> Optional.of(VIDEO_MODEL);
            case "subtitle", "compose" -> Optional.of(SUBTITLE);
            case "tool_call" -> Optional.of(TOOL_CALL);
            case "video_output", "output", "final_output" -> Optional.of(VIDEO_OUTPUT);
            case "user_input", "user_input_node" -> Optional.of(USER_INPUT);
            case "user_confirm", "user_confirm_node" -> Optional.of(USER_CONFIRM);
            case "condition", "condition_node" -> Optional.of(CONDITION);
            default -> Optional.empty();
        };
    }

    public boolean isInline() {
        return this == START
                || this == FIELD_INPUT
                || this == USER_INPUT
                || this == USER_CONFIRM
                || this == CONDITION
                || this == VIDEO_OUTPUT;
    }

    public boolean isWorkerStep() {
        return !isInline();
    }

    public WorkflowNodeDefType normalized() {
        if (this == MODEL_CALL) {
            return LLM_TEXT;
        }
        if (this == TOOL_CALL) {
            return SUBTITLE;
        }
        return this;
    }
}
