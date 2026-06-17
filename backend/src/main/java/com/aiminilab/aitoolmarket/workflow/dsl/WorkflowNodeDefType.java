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
    CONDITION,
    /** 分镜循环：按时长字段自动算出分镜数，供下游模型节点逐镜生成多段产物。 */
    SCENE_LOOP;

    public static Optional<WorkflowNodeDefType> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "start" -> Optional.of(START);
            case "field_input", "input" -> Optional.of(FIELD_INPUT);
            // 文本生成类（剧本/分镜/系列策划，见 AI漫剧交付计划书 §5）。
            case "llm_text", "text_model", "llm_model",
                 "script_planner", "storyboard_generator" -> Optional.of(LLM_TEXT);
            case "model_call" -> Optional.of(MODEL_CALL);
            case "backend_tool" -> Optional.of(TOOL_CALL);
            // 图像生成类（角色定妆/场景设定/关键帧）。
            case "image_model", "image_generation",
                 "character_design", "scene_design", "keyframe_generator" -> Optional.of(IMAGE_MODEL);
            // 音频类（角色配音 / BGM 音效）。
            case "tts_model", "voice_model", "voice_tts", "music_sfx" -> Optional.of(TTS_MODEL);
            // 视频生成类（图生视频 / 片段接力）。
            case "video_model", "video_generation", "image_to_video" -> Optional.of(VIDEO_MODEL);
            case "subtitle", "compose", "video_composer" -> Optional.of(SUBTITLE);
            case "tool_call" -> Optional.of(TOOL_CALL);
            case "video_output", "output", "final_output" -> Optional.of(VIDEO_OUTPUT);
            case "user_input", "user_input_node" -> Optional.of(USER_INPUT);
            // 人工审核 / 质检确认 → 内联确认节点（不触发付费生成）。
            case "user_confirm", "user_confirm_node", "human_review" -> Optional.of(USER_CONFIRM);
            // 帧/片段质检 → 内联条件分支（不触发付费生成）。
            case "condition", "condition_node", "frame_qc", "clip_qc" -> Optional.of(CONDITION);
            case "scene_loop", "loop", "scene_split" -> Optional.of(SCENE_LOOP);
            default -> Optional.empty();
        };
    }

    public boolean isInline() {
        return this == START
                || this == FIELD_INPUT
                || this == USER_INPUT
                || this == USER_CONFIRM
                || this == CONDITION
                || this == SCENE_LOOP
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
