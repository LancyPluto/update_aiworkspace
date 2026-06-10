#!/usr/bin/env python3
"""Apply 12-node comic opinion workflow to MySQL with valid UTF-8 JSON."""

from __future__ import annotations

import json
import subprocess

# Model config IDs from dev DB (script / image / tts / video)
MODEL_IDS = {
    "script-planner": 29,
    "keyframe": 4,
    "tts": 2,
    "clip-video": 5,
}

NODES = [
    {
        "id": "start",
        "type": "workflowNode",
        "position": {"x": 40, "y": 280},
        "data": {
            "title": "Start",
            "nodeDefType": "start",
            "kind": "start",
            "color": "#10b981",
            "outputSlots": [{"name": "context", "type": "json", "label": "会话上下文"}],
        },
    },
    {
        "id": "field-input",
        "type": "workflowNode",
        "position": {"x": 400, "y": 280},
        "data": {
            "title": "初始表单",
            "nodeDefType": "field_input",
            "kind": "input",
            "color": "#64748b",
            "inputSlots": [{"name": "context", "type": "json", "label": "会话上下文"}],
            "outputSlots": [{"name": "params", "type": "json", "label": "用户填写参数"}],
        },
    },
    {
        "id": "script-planner",
        "type": "workflowNode",
        "position": {"x": 780, "y": 280},
        "data": {
            "title": "剧本与分镜",
            "nodeDefType": "llm_text",
            "kind": "model",
            "color": "#3b82f6",
            "inputSlots": [{"name": "form", "type": "json", "label": "初始表单"}],
            "outputSlots": [{"name": "script", "type": "json", "label": "剧本分镜"}],
            "parameters": {
                "modelConfigId": MODEL_IDS["script-planner"],
                "role": "comic_script_planner",
                "progressStep": "生成剧本与分镜",
            },
        },
    },
    {
        "id": "user-input-script",
        "type": "workflowNode",
        "position": {"x": 1140, "y": 60},
        "data": {
            "title": "脚本意见",
            "nodeDefType": "user_input",
            "kind": "input",
            "color": "#0ea5e9",
            "inputSlots": [{"name": "upstream", "type": "any", "label": "上一步结果"}],
            "outputSlots": [{"name": "scriptFeedback", "type": "text", "label": "脚本意见"}],
            "parameters": {"fieldKey": "scriptFeedback", "stageLabel": "脚本意见"},
        },
    },
    {
        "id": "user-input-storyboard",
        "type": "workflowNode",
        "position": {"x": 1140, "y": 280},
        "data": {
            "title": "分镜意见",
            "nodeDefType": "user_input",
            "kind": "input",
            "color": "#0ea5e9",
            "inputSlots": [{"name": "upstream", "type": "any", "label": "上一步结果"}],
            "outputSlots": [{"name": "storyboardFeedback", "type": "text", "label": "分镜意见"}],
            "parameters": {"fieldKey": "storyboardFeedback", "stageLabel": "分镜意见"},
        },
    },
    {
        "id": "keyframe",
        "type": "workflowNode",
        "position": {"x": 1500, "y": 280},
        "data": {
            "title": "电影感关键帧",
            "nodeDefType": "image_model",
            "kind": "model",
            "color": "#ec4899",
            "inputSlots": [
                {"name": "script", "type": "json", "label": "剧本分镜"},
                {"name": "form", "type": "json", "label": "表单参数"},
            ],
            "outputSlots": [{"name": "keyframe", "type": "image", "label": "关键帧"}],
            "parameters": {
                "modelConfigId": MODEL_IDS["keyframe"],
                "progressStep": "生成电影感关键帧",
            },
        },
    },
    {
        "id": "user-input-scene",
        "type": "workflowNode",
        "position": {"x": 1860, "y": 60},
        "data": {
            "title": "场景图意见",
            "nodeDefType": "user_input",
            "kind": "input",
            "color": "#0ea5e9",
            "inputSlots": [{"name": "upstream", "type": "any", "label": "上一步结果"}],
            "outputSlots": [{"name": "sceneFeedback", "type": "text", "label": "场景图意见"}],
            "parameters": {"fieldKey": "sceneFeedback", "stageLabel": "场景图意见"},
        },
    },
    {
        "id": "tts",
        "type": "workflowNode",
        "position": {"x": 1860, "y": 420},
        "data": {
            "title": "角色配音",
            "nodeDefType": "tts_model",
            "kind": "model",
            "color": "#8b5cf6",
            "inputSlots": [{"name": "script", "type": "json", "label": "剧本分镜"}],
            "outputSlots": [{"name": "audio", "type": "audio", "label": "配音音频"}],
            "parameters": {
                "modelConfigId": MODEL_IDS["tts"],
                "progressStep": "生成角色配音",
            },
        },
    },
    {
        "id": "user-input-bgm",
        "type": "workflowNode",
        "position": {"x": 2220, "y": 60},
        "data": {
            "title": "BGM意见",
            "nodeDefType": "user_input",
            "kind": "input",
            "color": "#0ea5e9",
            "inputSlots": [{"name": "upstream", "type": "any", "label": "上一步结果"}],
            "outputSlots": [{"name": "bgmFeedback", "type": "text", "label": "BGM意见"}],
            "parameters": {"fieldKey": "bgmFeedback", "stageLabel": "BGM意见"},
        },
    },
    {
        "id": "clip-video",
        "type": "workflowNode",
        "position": {"x": 2220, "y": 420},
        "data": {
            "title": "图生视频",
            "nodeDefType": "video_model",
            "kind": "model",
            "color": "#f97316",
            "inputSlots": [
                {"name": "keyframe", "type": "image", "label": "关键帧"},
                {"name": "script", "type": "json", "label": "剧本分镜"},
            ],
            "outputSlots": [{"name": "clip", "type": "video", "label": "视频片段"}],
            "parameters": {
                "modelConfigId": MODEL_IDS["clip-video"],
                "progressStep": "图生视频",
            },
        },
    },
    {
        "id": "compose",
        "type": "workflowNode",
        "position": {"x": 2580, "y": 280},
        "data": {
            "title": "字幕合成",
            "nodeDefType": "subtitle",
            "kind": "tool",
            "color": "#f97316",
            "inputSlots": [
                {"name": "clip", "type": "video", "label": "视频片段"},
                {"name": "audio", "type": "audio", "label": "配音音频"},
                {"name": "script", "type": "json", "label": "剧本分镜"},
            ],
            "outputSlots": [{"name": "finalVideo", "type": "video", "label": "成片"}],
            "parameters": {"progressStep": "字幕与音视频合成"},
        },
    },
    {
        "id": "output",
        "type": "workflowNode",
        "position": {"x": 2940, "y": 280},
        "data": {
            "title": "成片输出",
            "nodeDefType": "video_output",
            "kind": "output",
            "color": "#ef4444",
            "inputSlots": [{"name": "finalVideo", "type": "video", "label": "成片"}],
            "parameters": {"displayMode": "video"},
        },
    },
]

EDGES = [
    {"id": "e-start-field", "source": "start", "target": "field-input", "sourceHandle": "out-context", "targetHandle": "in-context", "type": "smoothstep"},
    {"id": "e-field-script", "source": "field-input", "target": "script-planner", "sourceHandle": "out-params", "targetHandle": "in-form", "type": "smoothstep"},
    {"id": "e-script-feedback", "source": "script-planner", "target": "user-input-script", "sourceHandle": "out-script", "targetHandle": "in-upstream", "type": "smoothstep"},
    {"id": "e-script-storyboard", "source": "script-planner", "target": "user-input-storyboard", "sourceHandle": "out-script", "targetHandle": "in-upstream", "type": "smoothstep"},
    {"id": "e-storyboard-keyframe", "source": "user-input-storyboard", "target": "keyframe", "sourceHandle": "out-storyboardFeedback", "targetHandle": "in-script", "type": "smoothstep"},
    {"id": "e-field-keyframe", "source": "field-input", "target": "keyframe", "sourceHandle": "out-params", "targetHandle": "in-form", "type": "smoothstep"},
    {"id": "e-keyframe-scene", "source": "keyframe", "target": "user-input-scene", "sourceHandle": "out-keyframe", "targetHandle": "in-upstream", "type": "smoothstep"},
    {"id": "e-script-tts", "source": "script-planner", "target": "tts", "sourceHandle": "out-script", "targetHandle": "in-script", "type": "smoothstep"},
    {"id": "e-tts-bgm", "source": "tts", "target": "user-input-bgm", "sourceHandle": "out-audio", "targetHandle": "in-upstream", "type": "smoothstep"},
    {"id": "e-keyframe-clip", "source": "keyframe", "target": "clip-video", "sourceHandle": "out-keyframe", "targetHandle": "in-keyframe", "type": "smoothstep"},
    {"id": "e-script-clip", "source": "script-planner", "target": "clip-video", "sourceHandle": "out-script", "targetHandle": "in-script", "type": "smoothstep"},
    {"id": "e-clip-compose", "source": "clip-video", "target": "compose", "sourceHandle": "out-clip", "targetHandle": "in-clip", "type": "smoothstep"},
    {"id": "e-tts-compose", "source": "tts", "target": "compose", "sourceHandle": "out-audio", "targetHandle": "in-audio", "type": "smoothstep"},
    {"id": "e-script-compose", "source": "script-planner", "target": "compose", "sourceHandle": "out-script", "targetHandle": "in-script", "type": "smoothstep"},
    {"id": "e-compose-output", "source": "compose", "target": "output", "sourceHandle": "out-finalVideo", "targetHandle": "in-finalVideo", "type": "smoothstep"},
]


def run_sql(sql: str) -> None:
    subprocess.run(
        [
            "docker",
            "exec",
            "-i",
            "ai-supermarket-mysql",
            "mysql",
            "-uroot",
            "-proot123456",
            "ai_supermarket_v1",
        ],
        input=sql.encode("utf-8"),
        check=True,
    )


def _sql_literal(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def main() -> None:
    nodes_json = json.dumps(NODES, ensure_ascii=False)
    edges_json = json.dumps(EDGES, ensure_ascii=False)
    sql = f"""
UPDATE tool_workflows w
JOIN ai_tools t ON t.id = w.tool_id
SET w.nodes_json = {_sql_literal(nodes_json)},
    w.edges_json = {_sql_literal(edges_json)},
    w.status = 'PUBLISHED',
    w.version = w.version + 1,
    w.updated_at = CURRENT_TIMESTAMP
WHERE t.tool_code = 'ai_comic_drama_agent';
"""
    run_sql(sql)
    print(f"applied comic workflow: nodes={len(NODES)} edges={len(EDGES)}")


if __name__ == "__main__":
    main()
