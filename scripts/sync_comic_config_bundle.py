#!/usr/bin/env python3
"""Patch ai-tool-market-config bundle for comic drama workflow tool."""

from __future__ import annotations

import json
from pathlib import Path

from apply_comic_opinion_workflow import EDGES, MODEL_IDS, NODES

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "ai-tool-market-config-2026-06-09.json"

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
        "optionsJson": json.dumps(["都市逆袭", "甜宠恋爱", "悬疑反转", "科幻脑洞"], ensure_ascii=False),
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
        "optionsJson": json.dumps(["电影感写实", "国漫厚涂", "日漫赛璐璐", "Q 版轻喜剧"], ensure_ascii=False),
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
        "placeholder": "选择画幅",
        "options": ["16:9", "9:16", "1:1"],
        "optionsJson": json.dumps(["16:9", "9:16", "1:1"], ensure_ascii=False),
        "required": False,
        "executionRequired": False,
        "userRequired": False,
        "defaultValue": None,
        "agentFillStrategy": "default",
        "riskLevel": "LOW",
        "sortOrder": 5,
    },
    {
        "fieldKey": "episodeLength",
        "fieldName": "单集时长",
        "fieldType": "select",
        "placeholder": "选择目标时长",
        "options": ["30s", "60s", "90s"],
        "optionsJson": json.dumps(["30s", "60s", "90s"], ensure_ascii=False),
        "required": False,
        "executionRequired": False,
        "userRequired": False,
        "defaultValue": None,
        "agentFillStrategy": "default",
        "riskLevel": "LOW",
        "sortOrder": 6,
    },
]


def main() -> None:
    data = json.loads(CONFIG.read_text(encoding="utf-8-sig"))
    for tool in data.get("tools", []):
        if tool.get("toolCode") != "ai_comic_drama_agent":
            continue
        tool["status"] = "ONLINE"
        tool["executionHandler"] = "WORKFLOW"
        tool["toolType"] = "VIDEO_GENERATION"
        tool["fields"] = COMIC_FIELDS
        tool["workflow"] = {
            "workflowName": "comic_opinion_v1",
            "status": "PUBLISHED",
            "version": 7,
            "modelConfigIds": MODEL_IDS,
            "nodes": NODES,
            "edges": EDGES,
        }
        break
    CONFIG.write_text(json.dumps(data, ensure_ascii=False, indent=4), encoding="utf-8")
    print(f"updated {CONFIG}")


if __name__ == "__main__":
    main()
