#!/usr/bin/env python3
"""Patch ai-tool-market config bundle: consolidate Kling model configs and tools."""

from __future__ import annotations

import json
from copy import deepcopy
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BUNDLE_PATH = ROOT / "ai-tool-market-config-2026-06-15.json"

KLING_MODEL_CONFIG_CODES = {
    "kling-v3-omni",
    "kling_image_to_video",
    "kling-v1-image-to-video",
    "kling-v2-master-image-to-video",
    "kling-image-generation-v1-model",
    "kling-image-generation-v3-model",
    "kling-v1-5-image-to-video",
    "kling-v1-6-image-to-video",
    "kling-v2-1-image-to-video",
    "kling-v2-1-master-image-to-video",
    "kling-v2-5-turbo-image-to-video",
    "kling-v2-6-motion-control",
    "kling-v2-6-image-to-video",
    "kling-v3-motion-control",
    "kling-v3-image-to-video",
    "kling-v3-text-to-video",
    "kling-video-o1-omni",
    "8",
}

KLING_TOOL_CODES = {
    "v2_1",
    "kling_image_to_video",
    "kling-v3-omni",
    "kling-video-o1-omni",
    "kling-v3-text-to-video",
    "kling-v3-image-to-video",
    "kling-v3-multi-image-reference",
    "kling-multi-image-to-video",
    "kling-omni-video",
    "kling-text-to-video",
    "kling-image-to-video",
    "kling-motion-control",
    "kling-image-generation",
    "kling-omni-image",
    "kling-v3-motion-control",
    "kling-v2-6-image-to-video",
    "kling-v2-6-motion-control",
    "kling-v2-5-turbo-image-to-video",
    "kling-v2-1-master-image-to-video",
    "kling-v2-1-image-to-video",
    "kling-v2-master-image-to-video",
    "kling-v1-6-image-to-video",
    "kling-v1-5-image-to-video",
    "kling-v1-image-to-video",
    "kling-image-generation-v3",
    "kling-image-generation-v2-1",
    "kling-image-generation-v1",
    "tool",
}

KLING_VIDEO_PRICING_RULES = [
    {
        "paramKey": "mode",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "pro",
        "factor": 1.5,
        "extraCredits": 0,
        "priority": 50,
        "enabled": True,
        "remark": "pro 相对 std 倍率",
    },
    {
        "paramKey": "sound",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "on",
        "factor": 1.2,
        "extraCredits": 0,
        "priority": 51,
        "enabled": True,
        "remark": "开启声音",
    },
    {
        "paramKey": "model",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "kling-v2-5-turbo",
        "factor": 0.8,
        "extraCredits": 0,
        "priority": 60,
        "enabled": True,
        "remark": "Turbo 版本折价示例",
    },
]

KLING_IMAGE_PRICING_RULES = [
    {
        "paramKey": "count",
        "ruleType": "MULTIPLIER",
        "matchOp": "VALUE",
        "factor": 1,
        "extraCredits": 0,
        "priority": 40,
        "enabled": True,
        "remark": "按生成数量 count 倍率",
    },
]


KLING_OMNI_IMAGE_PRICING_RULES = [
    {
        "paramKey": "count",
        "ruleType": "MULTIPLIER",
        "matchOp": "VALUE",
        "factor": 1,
        "extraCredits": 0,
        "priority": 40,
        "enabled": True,
        "remark": "按生成数量 count 倍率",
    },
    {
        "paramKey": "resolution",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "2k",
        "factor": 1.5,
        "extraCredits": 0,
        "priority": 50,
        "enabled": True,
        "remark": "2K 相对 1K 倍率",
    },
]


def _field(
    key: str,
    name: str,
    ftype: str,
    *,
    required: bool = False,
    sort_order: int,
    placeholder: str | None = None,
    options: Any = None,
    options_json: str | None = None,
    default_value: str | None = None,
    core: bool = False,
) -> dict[str, Any]:
    if options is None and options_json:
        try:
            options = json.loads(options_json)
        except json.JSONDecodeError:
            options = None
    row: dict[str, Any] = {
        "fieldKey": key,
        "fieldName": name,
        "fieldType": ftype,
        "placeholder": placeholder,
        "options": options,
        "optionsJson": options_json,
        "required": required,
        "executionRequired": required,
        "userRequired": required,
        "defaultValue": default_value,
        "agentFillStrategy": "ask_user" if required else "default",
        "riskLevel": "LOW",
        "sortOrder": sort_order,
    }
    if core and options_json:
        parsed = json.loads(options_json) if isinstance(options_json, str) else options_json
        if isinstance(parsed, dict):
            parsed["core"] = True
            row["options"] = parsed
            row["optionsJson"] = json.dumps(parsed, ensure_ascii=False)
    return row


def _select_field(key: str, name: str, options: list[dict], default: str, sort_order: int, *, ui_tier: str = "all") -> dict:
    payload = {"uiTier": ui_tier, "uiGroup": "meta", "defaultValue": default, "options": options}
    return _field(key, name, "select", sort_order=sort_order, options=payload, options_json=json.dumps(payload, ensure_ascii=False), default_value=default)


def _radio_field(
    key: str,
    name: str,
    options: list[dict],
    sort_order: int,
    *,
    required: bool = True,
    default: str | None = None,
    visible_when: dict[str, list[str]] | None = None,
) -> dict:
    payload: dict[str, Any] = {"options": options}
    if default:
        payload["defaultValue"] = default
    if visible_when:
        payload["visibleWhen"] = visible_when
    return _field(key, name, "radio", required=required, sort_order=sort_order, options=payload, options_json=json.dumps(payload, ensure_ascii=False), default_value=default)


_SOUND_VISIBLE_WHEN = {"model": ["kling-v2-6", "kling-v2-5-turbo", "kling-v3"]}
_DURATION_V3_OPTIONS = [{"label": f"{value}秒", "value": str(value)} for value in range(3, 16)]
_DURATION_LEGACY_OPTIONS = [{"label": "5秒", "value": "5"}, {"label": "10秒", "value": "10"}]
_DURATION_OMNI_O1_OPTIONS = [{"label": "5秒", "value": "5"}, {"label": "10秒", "value": "10"}]
_RESOLUTION_OPTIONS = [
    {"label": "720P", "value": "720p"},
    {"label": "1080P", "value": "1080p"},
]


def _model_options_text2video() -> list[dict]:
    return [
        {"label": "V3（推荐）", "value": "kling-v3"},
        {"label": "V2.5 Turbo", "value": "kling-v2-5-turbo"},
        {"label": "V2.1 Master", "value": "kling-v2-1-master"},
        {"label": "V2 Master", "value": "kling-v2-master"},
        {"label": "V1.6", "value": "kling-v1-6"},
        {"label": "V1", "value": "kling-v1"},
    ]


def _model_options_image2video() -> list[dict]:
    return [
        {"label": "V3（推荐）", "value": "kling-v3"},
        {"label": "V2.6", "value": "kling-v2-6"},
        {"label": "V2.5 Turbo", "value": "kling-v2-5-turbo"},
        {"label": "V2.1 Master", "value": "kling-v2-1-master"},
        {"label": "V2.1", "value": "kling-v2-1"},
        {"label": "V2 Master", "value": "kling-v2-master"},
        {"label": "V1.6", "value": "kling-v1-6"},
        {"label": "V1.5", "value": "kling-v1-5"},
        {"label": "V1", "value": "kling-v1"},
    ]


def _model_options_motion() -> list[dict]:
    return [
        {"label": "V3（推荐）", "value": "kling-v3"},
        {"label": "V2.6", "value": "kling-v2-6"},
    ]


def _model_options_omni() -> list[dict]:
    return [
        {"label": "V3 Omni（推荐）", "value": "kling-v3-omni"},
        {"label": "Video O1", "value": "kling-video-o1"},
    ]


def _model_options_image_gen() -> list[dict]:
    return [
        {"label": "V3（推荐）", "value": "kling-v3"},
        {"label": "V2.1", "value": "kling-v2-1"},
        {"label": "V2 New", "value": "kling-v2-new"},
        {"label": "V2", "value": "kling-v2"},
        {"label": "V1.5", "value": "kling-v1-5"},
        {"label": "V1", "value": "kling-v1"},
    ]


def _model_options_multi_image() -> list[dict]:
    return [
        {"label": "V1.6（官方）", "value": "kling-v1-6"},
    ]


def _model_options_omni_image() -> list[dict]:
    return [
        {"label": "Image O1（推荐）", "value": "kling-image-o1"},
        {"label": "V3 Omni", "value": "kling-v3-omni"},
    ]


_KLING_IMAGE_LIST_OPTIONS = '{"minCount":1,"maxCount":7,"accept":"image/*","libraryEnabled":true,"libraryKind":"image","uiTier":"advanced"}'
_KLING_VIDEO_LIST_OPTIONS = '{"maxCount":4,"accept":"video/*","libraryEnabled":true,"libraryKind":"video","uiTier":"advanced"}'
_KLING_OMNI_VIDEO_LIST_OPTIONS = '{"maxCount":4,"accept":"video/*","libraryEnabled":true,"libraryKind":"video","uiTier":"advanced"}'
_KLING_ELEMENT_LIST_OPTIONS = '{"maxCount":7,"libraryEnabled":true,"uiTier":"advanced"}'
_KLING_OMNI_DURATION_OPTIONS = '{"slider":{"min":3,"max":15,"step":1},"defaultValue":5,"unit":"秒"}'


def _text2video_fields() -> list[dict]:
    order = 1
    fields = [
        _field("prompt", "画面描述", "textarea", required=True, sort_order=order, placeholder="描述主体、场景、镜头语言和细节", options_json='{"core": true}', core=True),
    ]
    order += 1
    fields.append(_radio_field("aspectRatio", "画面比例", [{"label": "16:9", "value": "16:9"}, {"label": "9:16", "value": "9:16"}, {"label": "1:1", "value": "1:1"}], order))
    order += 1
    fields.append(_radio_field("duration", "时长", _DURATION_V3_OPTIONS, order, default="5"))
    order += 1
    fields.append(_radio_field("mode", "质量档位", [{"label": "标准（std）", "value": "std"}, {"label": "高质量（pro）", "value": "pro"}], order, default="std"))
    order += 1
    fields.append(_radio_field("resolution", "分辨率", _RESOLUTION_OPTIONS, order, required=False))
    order += 1
    fields.append(_radio_field("sound", "音频", [{"label": "关闭", "value": "off"}, {"label": "开启", "value": "on"}], order, required=False, default="off", visible_when=_SOUND_VISIBLE_WHEN))
    order += 1
    fields.append(_radio_field("multiShot", "多镜头", [{"label": "关闭", "value": "false"}, {"label": "开启", "value": "true"}], order, required=True, default="false"))
    order += 1
    fields.append(_field("shotType", "分镜类型", "select", sort_order=order, options={"options": [{"label": "自定义", "value": "customize"}], "visibleWhen": {"multiShot": ["true"]}}, options_json='{"options": [{"label": "自定义", "value": "customize"}], "visibleWhen": {"multiShot": ["true"]}}'))
    order += 1
    fields.append(_field("multiPrompt", "分镜提示词", "textarea", sort_order=order, placeholder="多镜头时的分镜描述", options_json='{"visibleWhen": {"multiShot": ["true"]}}'))
    order += 1
    fields.append(_field("cfgScale", "CFG 强度", "number", sort_order=order, placeholder="可选，默认 0.5"))
    order += 1
    fields.append(_field("negativePrompt", "反向提示词", "textarea", sort_order=order, placeholder="不希望出现的元素"))
    return fields


def _image2video_fields() -> list[dict]:
    order = 1
    fields = [
        _field("imageUrl", "首帧图片", "image", required=True, sort_order=order, placeholder="上传后的 URL 或可访问的图片链接"),
        _field("imageTail", "尾帧图片", "image", sort_order=order + 1, placeholder="可选，需 pro 模式"),
    ]
    order += 2
    fields.append(_field("prompt", "画面描述", "textarea", sort_order=order, placeholder="描述主体、场景、镜头语言和细节", options_json='{"core": true}', core=True))
    order += 1
    fields.append(_radio_field("aspectRatio", "画面比例", [{"label": "16:9", "value": "16:9"}, {"label": "9:16", "value": "9:16"}, {"label": "1:1", "value": "1:1"}], order))
    order += 1
    fields.append(_radio_field("duration", "时长", _DURATION_V3_OPTIONS, order, default="5"))
    order += 1
    fields.append(_radio_field("mode", "质量档位", [{"label": "标准（std）", "value": "std"}, {"label": "高质量（pro）", "value": "pro"}], order, default="std"))
    order += 1
    fields.append(_radio_field("resolution", "分辨率", _RESOLUTION_OPTIONS, order, required=False))
    order += 1
    fields.append(_radio_field("sound", "音频", [{"label": "关闭", "value": "off"}, {"label": "开启", "value": "on"}], order, required=False, default="off", visible_when=_SOUND_VISIBLE_WHEN))
    order += 1
    fields.append(_field("negativePrompt", "反向提示词", "textarea", sort_order=order, placeholder="不希望出现的元素"))
    return fields


def _omni_video_fields() -> list[dict]:
    order = 1
    fields = [
        _field("prompt", "画面描述", "textarea", required=True, sort_order=order, placeholder="描述主体、场景、镜头语言和细节", options_json='{"core": true}', core=True),
    ]
    order += 1
    fields.append(_radio_field("aspectRatio", "画面比例", [{"label": "16:9", "value": "16:9"}, {"label": "9:16", "value": "9:16"}, {"label": "1:1", "value": "1:1"}], order))
    order += 1
    fields.append(_field("duration", "时长", "slider", sort_order=order, required=True, options_json=_KLING_OMNI_DURATION_OPTIONS, default_value="5"))
    order += 1
    fields.append(_radio_field("mode", "质量档位", [{"label": "标准（std）", "value": "std"}, {"label": "高质量（pro）", "value": "pro"}], order, default="std"))
    order += 1
    fields.append(_radio_field("sound", "音频", [{"label": "关闭", "value": "off"}, {"label": "开启", "value": "on"}], order, required=False, default="off"))
    order += 1
    fields.append(_field("imageList", "参考图片列表", "multi_image", sort_order=order, placeholder="选择参考图片", options_json=_KLING_IMAGE_LIST_OPTIONS))
    order += 1
    fields.append(_field("videoList", "参考视频列表", "omni_video_list", sort_order=order, placeholder="上传或选择参考视频", options_json=_KLING_OMNI_VIDEO_LIST_OPTIONS))
    order += 1
    fields.append(_field("elementList", "主体参考列表", "subject_element_list", sort_order=order, placeholder="添加主体参考", options_json=_KLING_ELEMENT_LIST_OPTIONS))
    order += 1
    fields.append(_radio_field("multiShot", "多镜头", [{"label": "关闭", "value": "false"}, {"label": "开启", "value": "true"}], order, required=True, default="false"))
    order += 1
    fields.append(_field("shotType", "分镜类型", "select", sort_order=order, options={"options": [{"label": "自定义", "value": "customize"}], "visibleWhen": {"multiShot": ["true"]}}, options_json='{"options": [{"label": "自定义", "value": "customize"}], "visibleWhen": {"multiShot": ["true"]}}'))
    order += 1
    fields.append(_field("multiPrompt", "分镜提示词", "textarea", sort_order=order, placeholder="多镜头时的分镜描述", options_json='{"visibleWhen": {"multiShot": ["true"]}}'))
    order += 1
    fields.append(_field("negativePrompt", "反向提示词", "textarea", sort_order=order, placeholder="不希望出现的元素"))
    return fields


def _motion_fields() -> list[dict]:
    return [
        _field("imageUrl", "人物图片", "image_upload", required=True, sort_order=1, placeholder="上传角色参考图，人物比例尽量与动作视频一致", options_json='{"uiGroup":"core","uiGroupLabel":"核心输入","uiTier":"all","uiRole":"character_image","uiOrder":1,"layoutHint":"paired_media","accept":"image/jpeg,image/png,.jpg,.jpeg,.png","maxSizeMb":10,"helpText":"上传角色参考图，人物比例尽量与动作视频一致"}'),
        _field("videoUrl", "动作视频", "video_upload", required=True, sort_order=2, placeholder="上传公网可访问的 MP4/MOV 动作视频；3 秒起，不超过 100MB", options_json='{"uiGroup":"core","uiGroupLabel":"核心输入","uiTier":"all","uiRole":"motion_video","uiOrder":2,"layoutHint":"paired_media","accept":".mp4,.mov,video/mp4,video/quicktime","maxSizeMb":100,"requiresPublicUrl":true,"minDuration":3,"durationByOrientation":{"image":10,"video":30},"helpText":"MP4/MOV，公网可访问，3 秒起，不超过 100MB"}'),
        _field("elementList", "主体参考", "subject_element_list", sort_order=3, placeholder="可选：选择 1 个已就绪主体", options_json='{"uiGroup":"subject","uiGroupLabel":"主体参考","uiTier":"all","uiRole":"subject_element","uiOrder":3,"layoutHint":"full_width","maxCount":1,"maxItems":1,"libraryEnabled":true,"allowedModes":["library_ref","element_id"],"forceCharacterOrientation":"video","helpText":"可选：使用主体库保持角色一致性；引用主体时角色朝向会锁定为跟随视频"}'),
        _field("prompt", "补充描述", "textarea", sort_order=4, placeholder="可选：补充角色服装、场景或镜头效果", options_json='{"uiGroup":"settings","uiGroupLabel":"常用设置","uiTier":"all","uiRole":"motion_prompt","uiOrder":4,"layoutHint":"full_width","maxLength":2500,"helpText":"可通过描述补充服装、场景、镜头或想保留的细节"}'),
        _field("characterOrientation", "角色朝向", "select", required=True, sort_order=5, options={"options": [{"label": "跟随视频", "value": "video"}, {"label": "跟随图片", "value": "image"}]}, options_json='{"uiGroup":"settings","uiGroupLabel":"常用设置","uiTier":"all","uiRole":"character_orientation","uiOrder":5,"options":[{"label":"跟随视频","value":"video"},{"label":"跟随图片","value":"image"}],"helpText":"跟随图片最长 10 秒；跟随视频最长 30 秒。引用主体时只能跟随视频"}', default_value="video"),
        _field("mode", "质量档位", "radio", required=True, sort_order=6, options_json='{"uiGroup":"settings","uiGroupLabel":"常用设置","uiTier":"all","uiRole":"quality_mode","uiOrder":6,"options":[{"label":"标准（std）","value":"std"},{"label":"高质量（pro）","value":"pro"}],"defaultValue":"std"}', default_value="std"),
        _field("keepOriginalSound", "保留原声", "radio", sort_order=7, options_json='{"uiGroup":"settings","uiGroupLabel":"常用设置","uiTier":"all","uiRole":"keep_original_sound","uiOrder":7,"options":[{"label":"是","value":"yes"},{"label":"否","value":"no"}],"defaultValue":"yes"}', default_value="yes"),
        _field("staticMask", "静态遮罩", "image_upload", sort_order=8, placeholder="高级实验项：当前动作控制接口暂不提交遮罩参数", options_json='{"uiGroup":"advanced","uiGroupLabel":"高级参数","uiTier":"advanced","uiRole":"static_mask","uiOrder":8,"layoutHint":"paired_media","submitPolicy":"ui_only","accept":"image/jpeg,image/png,.jpg,.jpeg,.png","helpText":"当前动作控制接口暂不提交遮罩参数，确认官方字段后可改为 submit"}'),
        _field("dynamicMasks", "动态遮罩", "textarea", sort_order=9, placeholder="高级实验项：当前动作控制接口暂不提交遮罩参数", options_json='{"uiGroup":"advanced","uiGroupLabel":"高级参数","uiTier":"advanced","uiRole":"dynamic_mask","uiOrder":9,"layoutHint":"paired_media","submitPolicy":"ui_only","helpText":"当前动作控制接口暂不提交遮罩参数，确认官方字段后可改为 submit"}'),
    ]


def _multi_image_fields() -> list[dict]:
    return [
        _field("prompt", "画面描述", "textarea", required=True, sort_order=1, placeholder="描述镜头与主体", options_json='{"core": true}', core=True),
        _field("imageList", "参考图片列表", "multi_image", required=True, sort_order=2, placeholder="选择参考图片", options_json=_KLING_IMAGE_LIST_OPTIONS),
        _radio_field("aspectRatio", "画面比例", [{"label": "16:9", "value": "16:9"}, {"label": "9:16", "value": "9:16"}, {"label": "1:1", "value": "1:1"}], 3, required=False),
        _radio_field("duration", "时长", _DURATION_LEGACY_OPTIONS, 4, default="5"),
        _radio_field("mode", "质量档位", [{"label": "标准（std）", "value": "std"}, {"label": "高质量（pro）", "value": "pro"}], 5, default="std"),
        _field("negativePrompt", "反向提示词", "textarea", sort_order=6, placeholder="不希望出现的元素"),
    ]


def _image_gen_fields() -> list[dict]:
    return [
        _field("prompt", "画面描述", "textarea", required=True, sort_order=1, placeholder="描述主体、场景、风格、构图与细节", options_json='{"core": true}', core=True),
        _radio_field("aspectRatio", "画面比例", [{"label": "1:1", "value": "1:1"}, {"label": "16:9", "value": "16:9"}, {"label": "9:16", "value": "9:16"}], 2, default="1:1"),
        _radio_field("resolution", "清晰度", [{"label": "1K", "value": "1k"}, {"label": "2K", "value": "2k"}], 3, required=False, default="1k"),
        _field("count", "生成数量", "select", sort_order=4, options={"options": [{"label": "1", "value": "1"}, {"label": "2", "value": "2"}, {"label": "3", "value": "3"}, {"label": "4", "value": "4"}], "defaultValue": "1"}, options_json='{"options": [{"label": "1", "value": "1"}, {"label": "2", "value": "2"}, {"label": "3", "value": "3"}, {"label": "4", "value": "4"}], "defaultValue": "1"}', default_value="1"),
        _field("negativePrompt", "反向提示词", "textarea", sort_order=5, placeholder="不希望出现的元素"),
        _field("imageUrl", "参考图", "image", sort_order=6, placeholder="可选参考图 URL"),
        _field("seed", "随机种子", "number", sort_order=7, placeholder="可选"),
    ]


def _omni_image_fields() -> list[dict]:
    return [
        _field("prompt", "画面描述", "textarea", required=True, sort_order=1, placeholder="描述主体、场景、风格与细节", options_json='{"core": true}', core=True),
        _field("imageList", "参考图片列表", "multi_image", sort_order=2, placeholder="可选参考图", options_json='{"minCount":0,"maxCount":7,"accept":"image/*","libraryEnabled":true,"libraryKind":"image","uiTier":"advanced"}'),
        _radio_field("aspectRatio", "画面比例", [
            {"label": "16:9", "value": "16:9"},
            {"label": "9:16", "value": "9:16"},
            {"label": "1:1", "value": "1:1"},
            {"label": "4:3", "value": "4:3"},
            {"label": "3:4", "value": "3:4"},
            {"label": "3:2", "value": "3:2"},
            {"label": "2:3", "value": "2:3"},
            {"label": "21:9", "value": "21:9"},
            {"label": "自动", "value": "auto"},
        ], 3, required=False, default="16:9"),
        _radio_field("resolution", "清晰度", [{"label": "1K", "value": "1k"}, {"label": "2K", "value": "2k"}], 4, required=False, default="1k"),
        _field("count", "生成数量", "select", sort_order=5, options={"options": [{"label": str(n), "value": str(n)} for n in range(1, 10)], "defaultValue": "1"}, options_json=json.dumps({"options": [{"label": str(n), "value": str(n)} for n in range(1, 10)], "defaultValue": "1"}, ensure_ascii=False), default_value="1"),
        _radio_field("resultType", "输出类型", [{"label": "单图", "value": "single"}, {"label": "组图", "value": "series"}], 6, required=False, default="single"),
    ]


def _tool(
    *,
    tool_code: str,
    tool_name: str,
    model_config_code: str,
    fields: list[dict],
    model_field: dict,
    status: str,
    tool_type: str,
    input_modality: str,
    cover_url: str | None = None,
    estimated_credit_cost: int = 360,
    category_code: str = "copywriting",
) -> dict[str, Any]:
    all_fields = deepcopy(fields)
    model_field = deepcopy(model_field)
    model_field["sortOrder"] = len(all_fields) + 1
    all_fields.append(model_field)
    return {
        "toolCode": tool_code,
        "toolName": tool_name,
        "categoryCode": category_code,
        "description": None,
        "coverUrl": cover_url,
        "toolType": tool_type,
        "inputModality": input_modality,
        "outputModality": "VIDEO" if tool_type == "VIDEO_GENERATION" else "IMAGE",
        "configNote": f"可灵 {tool_name}\n\n<!-- ai-tool-ui:{{\"primaryColor\":\"#3b82f6\",\"welcomeMessage\":\"\",\"mediaDisplayMode\":\"effect\"}} -->",
        "status": status,
        "estimatedCreditCost": estimated_credit_cost,
        "modelConfigCode": model_config_code,
        "executionHandler": tool_type,
        "agentEnabled": True,
        "fields": all_fields,
        "prompts": [],
        "workflow": None,
    }


def _gateway_model(
    *,
    config_code: str,
    display_name: str,
    model_name: str,
    api_task: str,
    create_path: str,
    result_path: str,
    docs_url: str,
    capabilities: list[str],
    billing_unit: str,
    unit_price: float,
    pricing_rules: list[dict],
    extra_auth: dict,
) -> dict[str, Any]:
    extra = {**extra_auth}
    return {
        "displayName": display_name,
        "configCode": config_code,
        "vendorAccountRef": "kling::默认账户",
        "channelCode": "kling",
        "channelLabel": "可灵 Kling",
        "channelIconAsset": "kling",
        "provider": "kling_video",
        "modelName": model_name,
        "baseUrl": "https://api-beijing.klingai.com",
        "apiKey": "",
        "extraAuthJson": json.dumps(extra, ensure_ascii=False),
        "executionTask": api_task,
        "executionOptionsJson": None,
        "secretsRedacted": False,
        "minimaxGroupId": None,
        "consoleUrl": "https://app.klingai.com/cn/dev/api-key",
        "balanceUrl": "https://app.klingai.com/cn/dev/resource-pack",
        "docsUrl": docs_url,
        "timeoutSeconds": 600,
        "connectTimeoutSeconds": None,
        "readTimeoutSeconds": None,
        "inputTokenPricePer1k": 0,
        "outputTokenPricePer1k": 0,
        "inputTokenPricePer1m": 0,
        "outputTokenPricePer1m": 0,
        "billingUnit": billing_unit,
        "unitPrice": unit_price,
        "enabled": True,
        "agentEnabled": False,
        "isDefault": False,
        "capabilities": capabilities,
        "pricingRules": deepcopy(pricing_rules),
    }


def _extract_kling_extra_auth(model_configs: list[dict]) -> dict[str, str]:
    for row in model_configs:
        code = str(row.get("configCode") or "")
        if row.get("provider") != "kling_video" and code not in KLING_MODEL_CONFIG_CODES:
            continue
        raw = row.get("extraAuthJson") or ""
        if not isinstance(raw, str) or not raw.strip():
            continue
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            continue
        access_key = str(parsed.get("accessKey") or parsed.get("access_key") or "").strip()
        secret_key = str(parsed.get("secretKey") or parsed.get("secret_key") or "").strip()
        if access_key and secret_key:
            return {"accessKey": access_key, "secretKey": secret_key}
    return {}


def build_kling_gateways(extra_auth: dict[str, str]) -> list[dict]:
    video_rules = KLING_VIDEO_PRICING_RULES
    return [
        _gateway_model(
            config_code="kling-gateway-text-to-video",
            display_name="可灵 · 文生视频",
            model_name="kling-v3",
            api_task="text2video",
            create_path="/v1/videos/text2video",
            result_path="/v1/videos/text2video/{task_id}",
            docs_url="https://www.klingai.com/document-api/apiReference/model/textToVideo",
            capabilities=["VIDEO_GENERATION"],
            billing_unit="PER_SECOND",
            unit_price=0.6,
            pricing_rules=video_rules,
            extra_auth=extra_auth,
        ),
        _gateway_model(
            config_code="kling-gateway-image-to-video",
            display_name="可灵 · 图生视频",
            model_name="kling-v3",
            api_task="image2video",
            create_path="/v1/videos/image2video",
            result_path="/v1/videos/image2video/{task_id}",
            docs_url="https://www.klingai.com/document-api/apiReference/model/imageToVideo",
            capabilities=["VIDEO_GENERATION"],
            billing_unit="PER_SECOND",
            unit_price=0.6,
            pricing_rules=video_rules,
            extra_auth=extra_auth,
        ),
        _gateway_model(
            config_code="kling-gateway-motion-control",
            display_name="可灵 · 动作控制",
            model_name="kling-v3",
            api_task="motion_control",
            create_path="/v1/videos/motion-control",
            result_path="/v1/videos/motion-control/{task_id}",
            docs_url="https://www.klingai.com/document-api/apiReference/model/motionControl",
            capabilities=["VIDEO_GENERATION"],
            billing_unit="PER_SECOND",
            unit_price=0.6,
            pricing_rules=video_rules,
            extra_auth=extra_auth,
        ),
        _gateway_model(
            config_code="kling-gateway-multi-image-to-video",
            display_name="可灵 · 多图参考生视频",
            model_name="kling-v1-6",
            api_task="multi_image2video",
            create_path="/v1/videos/multi-image2video",
            result_path="/v1/videos/multi-image2video/{task_id}",
            docs_url="https://www.klingai.com/document-api/apiReference/model/multiImageToVideo",
            capabilities=["VIDEO_GENERATION"],
            billing_unit="PER_SECOND",
            unit_price=0.6,
            pricing_rules=video_rules,
            extra_auth=extra_auth,
        ),
        _gateway_model(
            config_code="kling-gateway-omni-video",
            display_name="可灵 · Omni 视频",
            model_name="kling-v3-omni",
            api_task="omni_video",
            create_path="/v1/videos/omni-video",
            result_path="/v1/videos/omni-video/{task_id}",
            docs_url="https://www.klingai.com/document-api/apiReference/model/OmniVideo",
            capabilities=["VIDEO_GENERATION", "IMAGE_GENERATION"],
            billing_unit="PER_SECOND",
            unit_price=0.6,
            pricing_rules=video_rules,
            extra_auth=extra_auth,
        ),
        _gateway_model(
            config_code="kling-gateway-image-generation",
            display_name="可灵 · 文生图",
            model_name="kling-v3",
            api_task="image_generation",
            create_path="/v1/images/generations",
            result_path="/v1/images/generations/{task_id}",
            docs_url="https://www.klingai.com/document-api/apiReference/model/imageGeneration",
            capabilities=["IMAGE_GENERATION"],
            billing_unit="PER_CALL",
            unit_price=0.019998,
            pricing_rules=KLING_IMAGE_PRICING_RULES,
            extra_auth=extra_auth,
        ),
        _gateway_model(
            config_code="kling-gateway-omni-image",
            display_name="可灵 · Omni 生图",
            model_name="kling-image-o1",
            api_task="omni_image",
            create_path="/v1/images/omni-image",
            result_path="/v1/images/omni-image/{task_id}",
            docs_url="https://www.klingai.com/document-api/apiReference/model/OmniImage",
            capabilities=["IMAGE_GENERATION"],
            billing_unit="PER_CALL",
            unit_price=0.019998,
            pricing_rules=KLING_OMNI_IMAGE_PRICING_RULES,
            extra_auth=extra_auth,
        ),
    ]


def build_kling_tools() -> list[dict]:
    return [
        _tool(
            tool_code="kling-text-to-video",
            tool_name="可灵文生视频",
            model_config_code="kling-gateway-text-to-video",
            fields=_text2video_fields(),
            model_field=_select_field("model", "可灵模型版本", _model_options_text2video(), "kling-v3", 99),
            status="OFFLINE",
            tool_type="VIDEO_GENERATION",
            input_modality="TEXT",
            estimated_credit_cost=360,
        ),
        _tool(
            tool_code="kling-image-to-video",
            tool_name="可灵图生视频",
            model_config_code="kling-gateway-image-to-video",
            fields=_image2video_fields(),
            model_field=_select_field("model", "可灵模型版本", _model_options_image2video(), "kling-v3", 99),
            status="ONLINE",
            tool_type="VIDEO_GENERATION",
            input_modality="IMAGE",
            cover_url="/generated/tool-covers/可灵-V3-图生视频-kling-v3-image-to-video-可灵-V3-Omni-20260608010046.mp4",
            estimated_credit_cost=360,
        ),
        _tool(
            tool_code="kling-motion-control",
            tool_name="可灵动作控制",
            model_config_code="kling-gateway-motion-control",
            fields=_motion_fields(),
            model_field=_select_field("model", "可灵模型版本", _model_options_motion(), "kling-v3", 99),
            status="ONLINE",
            tool_type="VIDEO_GENERATION",
            input_modality="IMAGE",
            estimated_credit_cost=360,
        ),
        _tool(
            tool_code="kling-multi-image-to-video",
            tool_name="可灵多图参考生视频",
            model_config_code="kling-gateway-multi-image-to-video",
            fields=_multi_image_fields(),
            model_field=_select_field("model", "可灵模型版本", _model_options_multi_image(), "kling-v1-6", 99),
            status="OFFLINE",
            tool_type="VIDEO_GENERATION",
            input_modality="IMAGE",
            estimated_credit_cost=360,
        ),
        _tool(
            tool_code="kling-omni-video",
            tool_name="可灵 Omni 视频",
            model_config_code="kling-gateway-omni-video",
            fields=_omni_video_fields(),
            model_field=_select_field("model", "可灵模型版本", _model_options_omni(), "kling-v3-omni", 99),
            status="ONLINE",
            tool_type="VIDEO_GENERATION",
            input_modality="TEXT",
            cover_url="/generated/tool-covers/可灵-V3-Omni-kling-v3-omni-可灵-V3-Omni-20260528094612.mp4",
            estimated_credit_cost=360,
        ),
        _tool(
            tool_code="kling-image-generation",
            tool_name="可灵生图",
            model_config_code="kling-gateway-image-generation",
            fields=_image_gen_fields(),
            model_field=_select_field("model", "可灵模型版本", _model_options_image_gen(), "kling-v3", 99),
            status="ONLINE",
            tool_type="IMAGE_GENERATION",
            input_modality="TEXT",
            category_code="ai-image",
            cover_url="/generated/tool-covers/可灵生图-V3-kling-image-generation-v3-可灵-V3-Omni-20260608010301.png",
            estimated_credit_cost=3,
        ),
        _tool(
            tool_code="kling-omni-image",
            tool_name="可灵 Omni 生图",
            model_config_code="kling-gateway-omni-image",
            fields=_omni_image_fields(),
            model_field=_select_field("model", "可灵模型版本", _model_options_omni_image(), "kling-image-o1", 99),
            status="ONLINE",
            tool_type="IMAGE_GENERATION",
            input_modality="TEXT",
            category_code="ai-image",
            estimated_credit_cost=3,
        ),
    ]


def patch_bundle(bundle: dict[str, Any]) -> dict[str, Any]:
    model_configs = bundle.get("modelConfigs") or []
    tools = bundle.get("tools") or []

    extra_auth = _extract_kling_extra_auth(model_configs)
    kept_models = [row for row in model_configs if row.get("configCode") not in KLING_MODEL_CONFIG_CODES and row.get("provider") != "kling_video"]
    kept_models.extend(build_kling_gateways(extra_auth))

    kept_tools = [row for row in tools if row.get("toolCode") not in KLING_TOOL_CODES]
    new_tools = build_kling_tools()
    # Insert kling tools before suno if possible
    insert_at = next((idx for idx, row in enumerate(kept_tools) if str(row.get("toolCode", "")).startswith("suno")), len(kept_tools))
    kept_tools[insert_at:insert_at] = new_tools

    bundle["modelConfigs"] = kept_models
    bundle["tools"] = kept_tools
    settings = bundle.setdefault("settings", {})
    settings["scope"] = "可灵网关配置（7 任务类型 + 表单 model 版本选择）"
    settings["source"] = "kling gateway consolidation patch"
    settings["sourceDoc"] = "可灵模型配置.md"
    return bundle


def main() -> None:
    bundle = json.loads(BUNDLE_PATH.read_text(encoding="utf-8"))
    patched = patch_bundle(bundle)
    BUNDLE_PATH.write_text(json.dumps(patched, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    model_count = sum(1 for row in patched["modelConfigs"] if str(row.get("configCode", "")).startswith("kling-gateway"))
    tool_count = sum(1 for row in patched["tools"] if str(row.get("toolCode", "")).startswith("kling-"))
    print(f"Patched {BUNDLE_PATH.name}: kling-gateway models={model_count}, kling tools={tool_count}")


if __name__ == "__main__":
    main()
