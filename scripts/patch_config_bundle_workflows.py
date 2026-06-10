#!/usr/bin/env python3
"""Patch ai-tool-market-config bundle: comic + digital human workflows and simplified fields."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATH = ROOT / "ai-tool-market-config-2026-06-09.json"

COMIC_FIELDS = [
    {
        "fieldKey": "storyTheme",
        "fieldName": "漫剧主题",
        "fieldType": "text",
        "placeholder": "例如：穿越后我靠 AI 开店逆袭",
        "options": None,
        "optionsJson": None,
        "required": True,
        "executionRequired": False,
        "userRequired": False,
        "defaultValue": None,
        "agentFillStrategy": "default",
        "riskLevel": "LOW",
        "sortOrder": 1,
    },
    {
        "fieldKey": "genre",
        "fieldName": "题材类型",
        "fieldType": "select",
        "placeholder": "选择漫剧题材",
        "options": ["都市逆袭", "甜宠恋爱", "悬疑反转", "科幻脑洞"],
        "optionsJson": '["都市逆袭", "甜宠恋爱", "悬疑反转", "科幻脑洞"]',
        "required": False,
        "executionRequired": False,
        "userRequired": False,
        "defaultValue": None,
        "agentFillStrategy": "default",
        "riskLevel": "LOW",
        "sortOrder": 2,
    },
    {
        "fieldKey": "plotOutline",
        "fieldName": "剧情梗概（可选）",
        "fieldType": "textarea",
        "placeholder": "留空则由大模型自动生成剧本与分镜",
        "options": None,
        "optionsJson": None,
        "required": False,
        "executionRequired": False,
        "userRequired": False,
        "defaultValue": None,
        "agentFillStrategy": "default",
        "riskLevel": "LOW",
        "sortOrder": 3,
    },
    {
        "fieldKey": "visualStyle",
        "fieldName": "画风风格",
        "fieldType": "select",
        "placeholder": "选择画面风格",
        "options": ["电影感写实", "国漫厚涂", "日漫赛璐璐", "Q 版轻喜剧"],
        "optionsJson": '["电影感写实", "国漫厚涂", "日漫赛璐璐", "Q 版轻喜剧"]',
        "required": False,
        "executionRequired": False,
        "userRequired": False,
        "defaultValue": None,
        "agentFillStrategy": "default",
        "riskLevel": "LOW",
        "sortOrder": 4,
    },
    {
        "fieldKey": "aspectRatio",
        "fieldName": "画面比例",
        "fieldType": "select",
        "placeholder": "选择发布画幅",
        "options": ["9:16 竖屏", "16:9 横屏", "1:1 方形"],
        "optionsJson": '["9:16 竖屏", "16:9 横屏", "1:1 方形"]',
        "required": False,
        "executionRequired": False,
        "userRequired": False,
        "defaultValue": None,
        "agentFillStrategy": "default",
        "riskLevel": "LOW",
        "sortOrder": 5,
    },
    {
        "fieldKey": "resolution",
        "fieldName": "视频清晰度",
        "fieldType": "select",
        "placeholder": "480p 更快更省算力",
        "options": ["480p", "720p"],
        "optionsJson": '["480p", "720p"]',
        "required": False,
        "executionRequired": False,
        "userRequired": False,
        "defaultValue": None,
        "agentFillStrategy": "default",
        "riskLevel": "LOW",
        "sortOrder": 6,
    },
    {
        "fieldKey": "referenceMaterial",
        "fieldName": "参考素材（可选）",
        "fieldType": "textarea",
        "placeholder": "参考作品、角色设定、禁用元素等",
        "options": None,
        "optionsJson": None,
        "required": False,
        "executionRequired": False,
        "userRequired": False,
        "defaultValue": None,
        "agentFillStrategy": "default",
        "riskLevel": "LOW",
        "sortOrder": 7,
    },
    {
        "fieldKey": "scriptRevision",
        "fieldName": "剧本修订意见",
        "fieldType": "textarea",
        "placeholder": "在工作台确认剧本后填写修改意见，留空则继续生成",
        "options": None,
        "optionsJson": None,
        "required": False,
        "executionRequired": False,
        "userRequired": False,
        "defaultValue": None,
        "agentFillStrategy": "default",
        "riskLevel": "LOW",
        "sortOrder": 8,
    },
    {
        "fieldKey": "visualRevision",
        "fieldName": "画面修订意见",
        "fieldType": "textarea",
        "placeholder": "对关键帧/分镜画面提出修改要求",
        "options": None,
        "optionsJson": None,
        "required": False,
        "executionRequired": False,
        "userRequired": False,
        "defaultValue": None,
        "agentFillStrategy": "default",
        "riskLevel": "LOW",
        "sortOrder": 9,
    },
]


def comic_workflow() -> dict:
    nodes = [
        {"id": "start", "type": "workflowNode", "position": {"x": 40, "y": 220}, "data": {"title": "Start", "nodeDefType": "start", "kind": "start", "color": "#10b981"}},
        {"id": "field-input", "type": "workflowNode", "position": {"x": 300, "y": 220}, "data": {"title": "初始表单", "nodeDefType": "field_input", "kind": "input", "color": "#64748b"}},
        {"id": "script-planner", "type": "workflowNode", "position": {"x": 560, "y": 220}, "data": {"title": "剧本与分镜", "nodeDefType": "llm_text", "kind": "model", "color": "#3b82f6", "parameters": {"role": "comic_script_planner"}}},
        {"id": "confirm-script", "type": "workflowNode", "position": {"x": 820, "y": 120}, "data": {"title": "确认剧本", "nodeDefType": "user_confirm", "kind": "confirm", "color": "#14b8a6", "parameters": {"sourceNodeId": "script-planner"}}},
        {"id": "user-input-script", "type": "workflowNode", "position": {"x": 820, "y": 340}, "data": {"title": "剧本修订输入", "nodeDefType": "user_input", "kind": "input", "color": "#0ea5e9", "parameters": {"fieldKey": "scriptRevision"}}},
        {"id": "condition-script", "type": "workflowNode", "position": {"x": 1080, "y": 220}, "data": {"title": "是否改剧本", "nodeDefType": "condition", "kind": "condition", "color": "#a855f7", "parameters": {"revisionField": "scriptRevision"}}},
        {"id": "keyframe", "type": "workflowNode", "position": {"x": 1340, "y": 220}, "data": {"title": "电影感关键帧", "nodeDefType": "image_model", "kind": "model", "color": "#ec4899"}},
        {"id": "confirm-keyframe", "type": "workflowNode", "position": {"x": 1600, "y": 120}, "data": {"title": "确认关键帧", "nodeDefType": "user_confirm", "kind": "confirm", "color": "#14b8a6", "parameters": {"sourceNodeId": "keyframe"}}},
        {"id": "user-input-visual", "type": "workflowNode", "position": {"x": 1600, "y": 360}, "data": {"title": "画面修订输入", "nodeDefType": "user_input", "kind": "input", "color": "#0ea5e9", "parameters": {"fieldKey": "visualRevision"}}},
        {"id": "tts", "type": "workflowNode", "position": {"x": 1860, "y": 80}, "data": {"title": "角色配音", "nodeDefType": "tts_model", "kind": "model", "color": "#8b5cf6"}},
        {"id": "clip-video", "type": "workflowNode", "position": {"x": 1860, "y": 360}, "data": {"title": "图生视频", "nodeDefType": "video_model", "kind": "model", "color": "#f97316"}},
        {"id": "compose", "type": "workflowNode", "position": {"x": 2120, "y": 220}, "data": {"title": "字幕合成", "nodeDefType": "subtitle", "kind": "tool", "color": "#f97316"}},
        {"id": "output", "type": "workflowNode", "position": {"x": 2380, "y": 220}, "data": {"title": "成片输出", "nodeDefType": "video_output", "kind": "output", "color": "#ef4444"}},
    ]
    edges = [
        {"id": "e1", "source": "start", "target": "field-input", "type": "smoothstep"},
        {"id": "e2", "source": "field-input", "target": "script-planner", "type": "smoothstep"},
        {"id": "e3", "source": "script-planner", "target": "confirm-script", "type": "smoothstep"},
        {"id": "e4", "source": "script-planner", "target": "user-input-script", "type": "smoothstep"},
        {"id": "e5", "source": "confirm-script", "target": "condition-script", "type": "smoothstep"},
        {"id": "e6", "source": "user-input-script", "target": "condition-script", "type": "smoothstep"},
        {"id": "e7", "source": "condition-script", "target": "keyframe", "type": "smoothstep"},
        {"id": "e8", "source": "script-planner", "target": "keyframe", "type": "smoothstep"},
        {"id": "e9", "source": "keyframe", "target": "confirm-keyframe", "type": "smoothstep"},
        {"id": "e10", "source": "keyframe", "target": "user-input-visual", "type": "smoothstep"},
        {"id": "e11", "source": "script-planner", "target": "tts", "type": "smoothstep"},
        {"id": "e12", "source": "keyframe", "target": "clip-video", "type": "smoothstep"},
        {"id": "e13", "source": "tts", "target": "compose", "type": "smoothstep"},
        {"id": "e14", "source": "clip-video", "target": "compose", "type": "smoothstep"},
        {"id": "e15", "source": "compose", "target": "output", "type": "smoothstep"},
    ]
    return {
        "workflowName": "default",
        "nodesJson": json.dumps(nodes, ensure_ascii=False),
        "edgesJson": json.dumps(edges, ensure_ascii=False),
        "groupsJson": None,
        "configJson": json.dumps({"workflowType": "AI_COMIC_DRAMA", "integrationMode": "STANDARD_TASK"}, ensure_ascii=False),
        "status": "PUBLISHED",
    }


def digital_human_workflow() -> dict:
    nodes = [
        {"id": "start", "type": "workflowNode", "position": {"x": 40, "y": 210}, "data": {"title": "Start", "nodeDefType": "start", "kind": "start", "color": "#10b981"}},
        {"id": "field-input", "type": "workflowNode", "position": {"x": 340, "y": 210}, "data": {"title": "用户表单", "nodeDefType": "field_input", "kind": "input", "color": "#64748b"}},
        {"id": "tts", "type": "workflowNode", "position": {"x": 650, "y": 60}, "data": {"title": "TTS 配音", "nodeDefType": "tts_model", "kind": "model", "color": "#8b5cf6"}},
        {"id": "image", "type": "workflowNode", "position": {"x": 650, "y": 340}, "data": {"title": "形象与背景生图", "nodeDefType": "image_model", "kind": "model", "color": "#ec4899"}},
        {"id": "video", "type": "workflowNode", "position": {"x": 980, "y": 210}, "data": {"title": "图生视频", "nodeDefType": "video_model", "kind": "model", "color": "#f97316"}},
        {"id": "subtitle", "type": "workflowNode", "position": {"x": 1310, "y": 210}, "data": {"title": "字幕合成", "nodeDefType": "subtitle", "kind": "tool", "color": "#f97316"}},
        {"id": "output", "type": "workflowNode", "position": {"x": 1620, "y": 210}, "data": {"title": "成片输出", "nodeDefType": "video_output", "kind": "output", "color": "#ef4444"}},
    ]
    edges = [
        {"id": "e-start-field", "source": "start", "target": "field-input", "type": "smoothstep"},
        {"id": "e-field-tts", "source": "field-input", "target": "tts", "type": "smoothstep"},
        {"id": "e-field-image", "source": "field-input", "target": "image", "type": "smoothstep"},
        {"id": "e-tts-video", "source": "tts", "target": "video", "type": "smoothstep"},
        {"id": "e-image-video", "source": "image", "target": "video", "type": "smoothstep"},
        {"id": "e-video-subtitle", "source": "video", "target": "subtitle", "type": "smoothstep"},
        {"id": "e-subtitle-output", "source": "subtitle", "target": "output", "type": "smoothstep"},
    ]
    return {
        "workflowName": "default",
        "nodesJson": json.dumps(nodes, ensure_ascii=False),
        "edgesJson": json.dumps(edges, ensure_ascii=False),
        "groupsJson": None,
        "configJson": json.dumps({
            "requiredModelConfigCodes": [
                "siliconflow_voice_tts",
                "siliconflow_image_turbo",
                "seedance_video_generation",
                "siliconflow_asr_teleai",
            ],
            "audioVideoSyncStrategy": "audio_driven_reference_image_to_video_then_ffmpeg_mux",
        }, ensure_ascii=False),
        "status": "PUBLISHED",
    }


def main() -> None:
    bundle = json.loads(CONFIG_PATH.read_text(encoding="utf-8-sig"))
    for tool in bundle.get("tools", []):
        code = tool.get("toolCode")
        if code == "ai_comic_drama_agent":
            tool["status"] = "ONLINE"
            tool["estimatedCreditCost"] = 0
            tool["description"] = "面向短剧、漫剧生产：按工作流生成剧本、关键帧、配音、图生视频与字幕成片，支持分步确认与修订。"
            tool["fields"] = COMIC_FIELDS
            tool["workflow"] = comic_workflow()
            if tool.get("prompts"):
                for prompt in tool["prompts"]:
                    for version in prompt.get("versions", []):
                        version["userPromptTemplate"] = (
                            "请基于以下需求生成 AI 漫剧单镜剧本 JSON（sceneTitle, sceneDescription, dialogue, subtitleZh, subtitleEn）。\n\n"
                            "漫剧主题：{{storyTheme}}\n题材：{{genre}}\n剧情梗概：{{plotOutline}}\n画风：{{visualStyle}}\n"
                            "画幅：{{aspectRatio}}\n清晰度：{{resolution}}\n参考：{{referenceMaterial}}\n"
                            "剧本修订：{{scriptRevision}}\n画面修订：{{visualRevision}}"
                        )
        elif code == "digital_human_agent":
            tool["workflow"] = digital_human_workflow()
            tool["status"] = "ONLINE"
            tool["estimatedCreditCost"] = 0
            tool["modelConfigCode"] = "seedance_video_generation"
    CONFIG_PATH.write_text(json.dumps(bundle, ensure_ascii=False, indent=4), encoding="utf-8")
    print(f"Patched {CONFIG_PATH}")


if __name__ == "__main__":
    main()
