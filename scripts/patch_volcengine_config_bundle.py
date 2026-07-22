#!/usr/bin/env python3
"""Patch ai-tool-market config bundle: consolidate Volcengine/Doubao video & image gateways."""

from __future__ import annotations

import json
import re
import sys
from copy import deepcopy
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]

VOLCENGINE_GATEWAY_CODES = {
    "volcengine-gateway-video",
    "volcengine-gateway-image",
}

VOLCENGINE_LEGACY_MODEL_CONFIG_CODES = {
    "volcengine-seedance",
    "seedance_video_generation",
    "volcengine-seedream",
}

VOLCENGINE_LEGACY_TOOL_CODES = {
    "seedance2_0_2",
    "doubao-seedream-image-generation",
}

LEGACY_MODEL_CONFIG_RE = re.compile(
    r"^volcengine-(seedance|seedream)(-|$)",
    re.IGNORECASE,
)
LEGACY_TOOL_CODE_RE = re.compile(
    r"^volcengine-(seedance|seedream)-",
    re.IGNORECASE,
)

ARK_BASE = "https://ark.cn-beijing.volces.com"
CONSOLE_URL = "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey"
BALANCE_URL = "https://console.volcengine.com/finance/overview"
DOCS_VIDEO = "https://www.volcengine.com/docs/82379/1520757"
DOCS_IMAGE = "https://www.volcengine.com/docs/82379/1541523"

VIDEO_MODEL_OPTIONS = [
    {"label": "Seedance 1.5 Pro（推荐）", "value": "doubao-seedance-1-5-pro-251215"},
    {"label": "Seedance 2.0", "value": "doubao-seedance-2-0-260128"},
    {"label": "Seedance 2.0 Fast", "value": "doubao-seedance-2-0-fast-260128"},
    {"label": "Seedance 2.0 Mini", "value": "doubao-seedance-2-0-mini-260615"},
    {"label": "Seedance 1.0 Pro", "value": "doubao-seedance-1-0-pro-250528"},
    {"label": "Seedance 1.0 Pro Fast", "value": "doubao-seedance-1-0-pro-fast-251015"},
]

IMAGE_MODEL_OPTIONS = [
    {"label": "Seedream 4.5（推荐）", "value": "doubao-seedream-4-5-251128"},
    {"label": "Seedream 5.0 Lite", "value": "doubao-seedream-5-0-260128"},
    {"label": "Seedream 4.0", "value": "doubao-seedream-4.0"},
]

VIDEO_PRICING_RULES = [
    {
        "paramKey": "model",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "doubao-seedance-2-0-260128",
        "factor": 5.75,
        "extraCredits": 0,
        "priority": 45,
        "enabled": True,
        "remark": "Seedance 2.0 720p 无视频输入单价约 46 元/百万 token，相对 1.5 Pro 无声基线",
    },
    {
        "paramKey": "model",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "doubao-seedance-2-0-fast-260128",
        "factor": 4.625,
        "extraCredits": 0,
        "priority": 45,
        "enabled": True,
        "remark": "Seedance 2.0 Fast 720p 无视频输入单价约 37 元/百万 token",
    },
    {
        "paramKey": "model",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "doubao-seedance-2-0-mini-260615",
        "factor": 2.875,
        "extraCredits": 0,
        "priority": 45,
        "enabled": True,
        "remark": "Seedance 2.0 Mini 720p 无视频输入单价约 23 元/百万 token",
    },
    {
        "paramKey": "model",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "doubao-seedance-1-0-pro-250528",
        "factor": 1.875,
        "extraCredits": 0,
        "priority": 45,
        "enabled": True,
        "remark": "Seedance 1.0 Pro 单价约 15 元/百万 token",
    },
    {
        "paramKey": "model",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "doubao-seedance-1-0-pro-fast-251015",
        "factor": 0.525,
        "extraCredits": 0,
        "priority": 45,
        "enabled": True,
        "remark": "Seedance 1.0 Pro Fast 单价约 4.2 元/百万 token",
    },
    {
        "paramKey": "resolution",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "480p",
        "factor": 0.465,
        "extraCredits": 0,
        "priority": 50,
        "enabled": True,
        "remark": "480p 相对 720p，按官方 5 秒示例 2.31/4.97 折算",
    },
    {
        "paramKey": "resolution",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "1080p",
        "factor": 2.493,
        "extraCredits": 0,
        "priority": 50,
        "enabled": True,
        "remark": "1080p 相对 720p，按官方 5 秒示例 12.39/4.97 折算",
    },
    {
        "paramKey": "resolution",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "4k",
        "factor": 5.086,
        "extraCredits": 0,
        "priority": 50,
        "enabled": True,
        "remark": "4K 相对 720p，按 token 公式和 4K 单价折算",
    },
    {
        "paramKey": "generateAudio",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "true",
        "factor": 2,
        "extraCredits": 0,
        "priority": 55,
        "enabled": True,
        "remark": "Seedance 1.5 Pro 有声/无声官方单价约 16/8 元/百万 token",
    },
]

IMAGE_PRICING_RULES = [
    {
        "paramKey": "count",
        "ruleType": "MULTIPLIER",
        "matchOp": "VALUE",
        "factor": 1,
        "extraCredits": 0,
        "priority": 40,
        "enabled": True,
        "remark": "按生成张数 count 倍率",
    },
    {
        "paramKey": "model",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "doubao-seedream-5-0-260128",
        "factor": 0.88,
        "extraCredits": 0,
        "priority": 50,
        "enabled": True,
        "remark": "Seedream 5.0 Lite 0.22 元/张，相对 4.5 的 0.25 元/张",
    },
    {
        "paramKey": "model",
        "ruleType": "MULTIPLIER",
        "matchOp": "EQ",
        "matchValue": "doubao-seedream-4.0",
        "factor": 0.8,
        "extraCredits": 0,
        "priority": 50,
        "enabled": True,
        "remark": "Seedream 4.0 0.20 元/张，相对 4.5 的 0.25 元/张",
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


def _select_field(key: str, name: str, options: list[dict], default: str, sort_order: int) -> dict:
    payload = {
        "uiTier": "all",
        "uiGroup": "meta",
        "uiGroupLabel": "基本信息",
        "defaultValue": default,
        "options": options,
    }
    return _field(
        key,
        name,
        "select",
        sort_order=sort_order,
        options=payload,
        options_json=json.dumps(payload, ensure_ascii=False),
        default_value=default,
    )


def _radio_field(
    key: str,
    name: str,
    options: list[dict],
    sort_order: int,
    *,
    default: str | None = None,
    ui_tier: str = "all",
) -> dict:
    payload: dict[str, Any] = {"uiTier": ui_tier, "options": options}
    if default:
        payload["defaultValue"] = default
    return _field(
        key,
        name,
        "radio",
        sort_order=sort_order,
        options=payload,
        options_json=json.dumps(payload, ensure_ascii=False),
        default_value=default,
    )


def _gateway_model(
    *,
    config_code: str,
    display_name: str,
    provider: str,
    model_name: str,
    execution_task: str,
    capabilities: list[str],
    billing_unit: str,
    unit_price: float,
    pricing_rules: list[dict],
    docs_url: str,
    extra_auth: dict,
    api_key: str = "",
    vendor_account_ref: str = "volcengine::默认账户",
) -> dict[str, Any]:
    return {
        "displayName": display_name,
        "configCode": config_code,
        "vendorAccountRef": vendor_account_ref,
        "channelCode": "volcengine",
        "channelLabel": "火山引擎 / 豆包",
        "channelIconAsset": "doubao",
        "provider": provider,
        "modelName": model_name,
        "baseUrl": ARK_BASE,
        "apiKey": api_key,
        "extraAuthJson": json.dumps(extra_auth, ensure_ascii=False),
        "executionTask": execution_task,
        "executionOptionsJson": None,
        "secretsRedacted": False,
        "minimaxGroupId": None,
        "consoleUrl": CONSOLE_URL,
        "balanceUrl": BALANCE_URL,
        "docsUrl": docs_url,
        "timeoutSeconds": 900 if provider == "seedance" else 600,
        "connectTimeoutSeconds": None,
        "readTimeoutSeconds": 600 if provider == "volcengine_images" else None,
        "inputTokenPricePer1k": 0,
        "outputTokenPricePer1k": 0,
        "inputTokenPricePer1m": 0,
        "outputTokenPricePer1m": 0,
        "billingUnit": billing_unit,
        "unitPrice": unit_price,
        "enabled": True,
        "agentEnabled": provider != "seedance",
        "isDefault": False,
        "capabilities": capabilities,
        "pricingRules": deepcopy(pricing_rules),
    }


def _extract_volcengine_api_key(bundle: dict[str, Any]) -> str:
    for account in bundle.get("vendorAccounts") or []:
        if str(account.get("vendorCode") or "").strip().lower() != "volcengine":
            continue
        api_key = str(account.get("apiKey") or "").strip()
        if api_key:
            return api_key
    for row in bundle.get("modelConfigs") or []:
        provider = str(row.get("provider") or "").strip().lower()
        if provider not in {"seedance", "volcengine_images", "openai_compatible"}:
            continue
        base_url = str(row.get("baseUrl") or "")
        if "volces.com" not in base_url and "volcengine" not in base_url:
            continue
        api_key = str(row.get("apiKey") or "").strip()
        if api_key:
            return api_key
    return ""


def _is_legacy_volcengine_model(row: dict[str, Any]) -> bool:
    config_code = str(row.get("configCode") or "").strip()
    if config_code in VOLCENGINE_GATEWAY_CODES:
        return False
    if config_code in VOLCENGINE_LEGACY_MODEL_CONFIG_CODES:
        return True
    if LEGACY_MODEL_CONFIG_RE.match(config_code):
        return True
    provider = str(row.get("provider") or "").strip().lower()
    return provider in {"seedance", "volcengine_images"}


def _is_legacy_volcengine_tool(row: dict[str, Any]) -> bool:
    tool_code = str(row.get("toolCode") or "").strip()
    if tool_code in {"volcengine-video", "volcengine-image"}:
        return False
    if tool_code in VOLCENGINE_LEGACY_TOOL_CODES:
        return True
    return bool(LEGACY_TOOL_CODE_RE.match(tool_code))


def build_volcengine_gateways(api_key: str = "") -> list[dict[str, Any]]:
    return [
        _gateway_model(
            config_code="volcengine-gateway-video",
            display_name="火山 · 视频生成",
            provider="seedance",
            model_name="doubao-seedance-1-5-pro-251215",
            execution_task="video_generation",
            capabilities=["VIDEO_GENERATION"],
            billing_unit="PER_SECOND",
            unit_price=0.1728,
            pricing_rules=VIDEO_PRICING_RULES,
            docs_url=DOCS_VIDEO,
            extra_auth={
                "createPath": "/contents/generations/tasks",
                "resultPath": "/contents/generations/tasks/{task_id}",
            },
            api_key=api_key,
        ),
        _gateway_model(
            config_code="volcengine-gateway-image",
            display_name="火山 · 图像生成",
            provider="volcengine_images",
            model_name="doubao-seedream-4-5-251128",
            execution_task="image_generation",
            capabilities=["IMAGE_GENERATION"],
            billing_unit="PER_CALL",
            unit_price=0.25,
            pricing_rules=IMAGE_PRICING_RULES,
            docs_url=DOCS_IMAGE,
            extra_auth={
                "imageInputMode": "jsonImageArray",
                "endpointPath": "/images/generations",
                "responseFormat": "url",
                "readTimeoutSeconds": 600,
                "connectionRetries": 2,
                "sslEofRetries": 2,
            },
            api_key=api_key,
        ),
    ]


def _video_fields() -> list[dict]:
    order = 1
    fields = [
        _field(
            "prompt",
            "画面描述",
            "textarea",
            required=True,
            sort_order=order,
            placeholder="描述主体、场景、镜头语言和细节",
            options_json='{"core": true, "uiTier": "all"}',
            core=True,
        ),
    ]
    order = 3
    fields.append(
        _field(
            "referenceImages",
            "参考图片",
            "multi_image",
            sort_order=order,
            placeholder="可选，首帧/末帧或 2.0 多图参考，最多 9 张",
            options_json='{"uiTier": "all", "minCount": 0, "maxCount": 9, "accept": "image/*", "libraryEnabled": true, "libraryKind": "image", "helpText": "1 张默认作为首帧；2 张可作为首尾帧；Seedance 2.0 系列支持 1-9 张 reference_image。"}',
        )
    )
    order += 1
    fields.append(
        _radio_field(
            "aspectRatio",
            "画面比例",
            [
                {"label": "智能", "value": "adaptive"},
                {"label": "16:9", "value": "16:9"},
                {"label": "9:16", "value": "9:16"},
                {"label": "1:1", "value": "1:1"},
                {"label": "4:3", "value": "4:3"},
                {"label": "3:4", "value": "3:4"},
                {"label": "21:9", "value": "21:9"},
            ],
            order,
            default="16:9",
        )
    )
    order += 1
    fields.append(
        _radio_field(
            "duration",
            "时长",
            [{"label": "5 秒", "value": "5"}, {"label": "10 秒", "value": "10"}, {"label": "12 秒", "value": "12"}],
            order,
            default="5",
        )
    )
    order += 1
    fields.append(
        _radio_field(
            "resolution",
            "分辨率",
            [
                {"label": "480p", "value": "480p"},
                {"label": "720p", "value": "720p"},
                {"label": "1080p", "value": "1080p"},
                {"label": "4K", "value": "4k"},
            ],
            order,
            default="720p",
        )
    )
    order += 1
    fields.append(
        _field(
            "sourceVideoUrl",
            "参考视频",
            "file",
            sort_order=order,
            placeholder="可选，Seedance 2.0 系列支持参考视频 URL",
            options_json='{"uiTier": "advanced", "accept": "video/*", "libraryKind": "video", "helpText": "最多 3 段参考视频，总时长建议不超过 15 秒。"}',
        )
    )
    order += 1
    fields.append(
        _field(
            "audioUrl",
            "参考音频",
            "file",
            sort_order=order,
            placeholder="可选，Seedance 2.0 系列支持参考音频 URL",
            options_json='{"uiTier": "advanced", "accept": "audio/*", "libraryKind": "audio", "helpText": "需搭配参考图或参考视频使用。"}',
        )
    )
    order += 1
    fields.append(
        _radio_field(
            "generateAudio",
            "生成音频",
            [{"label": "关闭", "value": "false"}, {"label": "开启", "value": "true"}],
            order,
            default="false",
            ui_tier="advanced",
        )
    )
    order += 1
    fields.append(
        _radio_field(
            "cameraFixed",
            "固定镜头",
            [{"label": "关闭", "value": "false"}, {"label": "开启", "value": "true"}],
            order,
            default="false",
            ui_tier="advanced",
        )
    )
    order += 1
    fields.append(
        _radio_field(
            "watermark",
            "水印",
            [{"label": "关闭", "value": "false"}, {"label": "开启", "value": "true"}],
            order,
            default="false",
            ui_tier="advanced",
        )
    )
    order += 1
    fields.append(
        _field(
            "seed",
            "随机种子",
            "number",
            sort_order=order,
            placeholder="可选，填写整数便于复现",
            options_json='{"uiTier": "advanced"}',
        )
    )
    order += 1
    fields.append(
        _field(
            "negativePrompt",
            "反向提示词",
            "textarea",
            sort_order=order,
            placeholder="不希望出现的元素",
            options_json='{"uiTier": "advanced"}',
        )
    )
    return fields


def _image_fields() -> list[dict]:
    order = 1
    fields = [
        _field(
            "prompt",
            "画面描述",
            "textarea",
            required=True,
            sort_order=order,
            placeholder="描述画面主体、风格与细节",
            options_json='{"core": true, "uiTier": "all"}',
            core=True,
        ),
    ]
    order = 3
    fields.append(
        _radio_field(
            "aspectRatio",
            "画面比例",
            [
                {"label": "1:1", "value": "1:1"},
                {"label": "16:9", "value": "16:9"},
                {"label": "9:16", "value": "9:16"},
                {"label": "4:3", "value": "4:3"},
                {"label": "3:4", "value": "3:4"},
                {"label": "3:2", "value": "3:2"},
                {"label": "2:3", "value": "2:3"},
                {"label": "21:9", "value": "21:9"},
            ],
            order,
            default="1:1",
        )
    )
    order += 1
    fields.append(
        _radio_field(
            "count",
            "生成张数",
            [{"label": "1 张", "value": "1"}, {"label": "2 张", "value": "2"}, {"label": "4 张", "value": "4"}],
            order,
            default="1",
        )
    )
    order += 1
    fields.append(
        _field(
            "referenceImages",
            "参考图片",
            "multi_image",
            sort_order=order,
            placeholder="可选，Seedream 5.0 Lite / 4.5 / 4.0 支持单图或多图输入，最多 14 张",
            options_json='{"uiTier": "all", "minCount": 0, "maxCount": 14, "accept": "image/*", "libraryEnabled": true, "libraryKind": "image", "helpText": "参考图 + 组图输出总数最多 15 张；支持 URL、上传图片或素材库。"}',
        )
    )
    order += 1
    fields.append(
        _radio_field(
            "size",
            "输出规格",
            [{"label": "2K", "value": "2K"}, {"label": "3K", "value": "3K"}, {"label": "4K", "value": "4K"}],
            order,
            default="2K",
        )
    )
    order += 1
    fields.append(
        _radio_field(
            "sequentialImageGeneration",
            "组图模式",
            [{"label": "关闭", "value": "disabled"}, {"label": "自动", "value": "auto"}],
            order,
            default="disabled",
            ui_tier="advanced",
        )
    )
    order += 1
    fields.append(
        _field(
            "maxImages",
            "组图上限",
            "number",
            sort_order=order,
            placeholder="1-15，仅组图模式自动时生效",
            options_json='{"uiTier": "advanced", "min": 1, "max": 15, "step": 1}',
            default_value="15",
        )
    )
    order += 1
    fields.append(
        _radio_field(
            "watermark",
            "水印",
            [{"label": "关闭", "value": "false"}, {"label": "开启", "value": "true"}],
            order,
            default="false",
            ui_tier="advanced",
        )
    )
    return fields


def _tool(
    *,
    tool_code: str,
    tool_name: str,
    model_config_code: str,
    fields: list[dict],
    model_field: dict,
    tool_type: str,
    estimated_credit_cost: int,
    status: str = "ONLINE",
    category_code: str,
) -> dict[str, Any]:
    all_fields = fields + [model_field]
    all_fields.sort(key=lambda row: int(row.get("sortOrder") or 0))
    for idx, row in enumerate(all_fields, start=1):
        row["sortOrder"] = idx
    input_modality = "IMAGE" if tool_type == "IMAGE_GENERATION" else "TEXT"
    return {
        "toolCode": tool_code,
        "toolName": tool_name,
        "categoryCode": category_code,
        "description": f"火山方舟 {tool_name}，同工具内通过 model 字段切换版本。",
        "coverUrl": None,
        "toolType": tool_type,
        "inputModality": input_modality,
        "outputModality": "VIDEO" if tool_type == "VIDEO_GENERATION" else "IMAGE",
        "configNote": (
            f"火山引擎 / 豆包 {tool_name}\n\n"
            '<!-- ai-tool-ui:{"primaryColor":"#4f46e5","welcomeMessage":"","mediaDisplayMode":"effect"} -->'
        ),
        "status": status,
        "estimatedCreditCost": estimated_credit_cost,
        "modelConfigCode": model_config_code,
        "executionHandler": tool_type,
        "agentEnabled": True,
        "fields": all_fields,
        "prompts": [],
        "workflow": None,
    }


def build_volcengine_tools() -> list[dict[str, Any]]:
    return [
        _tool(
            tool_code="volcengine-video",
            tool_name="豆包视频生成",
            model_config_code="volcengine-gateway-video",
            fields=_video_fields(),
            model_field=_select_field("model", "Seedance 模型", VIDEO_MODEL_OPTIONS, "doubao-seedance-1-5-pro-251215", 2),
            tool_type="VIDEO_GENERATION",
            estimated_credit_cost=3,
            category_code="text-to-video",
        ),
        _tool(
            tool_code="volcengine-image",
            tool_name="豆包图像生成",
            model_config_code="volcengine-gateway-image",
            fields=_image_fields(),
            model_field=_select_field("model", "Seedream 模型", IMAGE_MODEL_OPTIONS, "doubao-seedream-4-5-251128", 2),
            tool_type="IMAGE_GENERATION",
            estimated_credit_cost=2,
            category_code="text-to-image",
        ),
    ]


def _merge_gateway(existing: dict[str, Any] | None, fresh: dict[str, Any]) -> dict[str, Any]:
    merged = deepcopy(fresh)
    if not existing:
        return merged
    for key in ("apiKey", "vendorAccountRef", "timeoutSeconds"):
        value = existing.get(key)
        if value not in (None, "", []):
            merged[key] = value
    if not str(merged.get("apiKey") or "").strip():
        merged["apiKey"] = str(existing.get("apiKey") or "").strip()
    return merged


def _replace_legacy_codes_in_obj(value: Any) -> Any:
    if isinstance(value, str):
        replacements = {
            "seedance_video_generation": "volcengine-gateway-video",
            "seedance2_0_2": "volcengine-gateway-video",
            "volcengine-seedance": "volcengine-gateway-video",
            "doubao-seedream-image-generation": "volcengine-gateway-image",
            "volcengine-seedream": "volcengine-gateway-image",
        }
        updated = value
        for old, new in replacements.items():
            updated = updated.replace(old, new)
        return updated
    if isinstance(value, list):
        return [_replace_legacy_codes_in_obj(item) for item in value]
    if isinstance(value, dict):
        return {key: _replace_legacy_codes_in_obj(item) for key, item in value.items()}
    return value


def _patch_tool_references(tool: dict[str, Any]) -> dict[str, Any]:
    patched = deepcopy(tool)
    model_config_code = str(patched.get("modelConfigCode") or "").strip()
    if _is_legacy_volcengine_model({"configCode": model_config_code, "provider": ""}) or LEGACY_MODEL_CONFIG_RE.match(
        model_config_code
    ):
        if "seedream" in model_config_code.lower() or patched.get("toolType") == "IMAGE_GENERATION":
            patched["modelConfigCode"] = "volcengine-gateway-image"
        else:
            patched["modelConfigCode"] = "volcengine-gateway-video"
    if patched.get("workflow"):
        patched["workflow"] = _replace_legacy_codes_in_obj(patched["workflow"])
    return patched


def patch_bundle(bundle: dict[str, Any]) -> dict[str, Any]:
    patched = deepcopy(bundle)
    model_configs = list(patched.get("modelConfigs") or [])
    tools = list(patched.get("tools") or [])

    existing_gateways = {
        str(row.get("configCode") or "").strip(): row
        for row in model_configs
        if str(row.get("configCode") or "").strip() in VOLCENGINE_GATEWAY_CODES
    }

    api_key = _extract_volcengine_api_key(patched)
    fresh_gateways = {
        row["configCode"]: _merge_gateway(existing_gateways.get(row["configCode"]), row)
        for row in build_volcengine_gateways(api_key=api_key)
    }

    kept_models = [row for row in model_configs if not _is_legacy_volcengine_model(row)]
    kept_models = [row for row in kept_models if str(row.get("configCode") or "").strip() not in VOLCENGINE_GATEWAY_CODES]
    kept_models.extend(fresh_gateways.values())

    kept_tools = []
    for row in tools:
        if _is_legacy_volcengine_tool(row):
            continue
        if str(row.get("toolCode") or "").strip() in {"volcengine-video", "volcengine-image"}:
            continue
        kept_tools.append(_patch_tool_references(row))
    kept_tools.extend(build_volcengine_tools())

    patched["modelConfigs"] = kept_models
    patched["tools"] = kept_tools
    settings = patched.setdefault("settings", {})
    if isinstance(settings, dict):
        settings["scope"] = "火山网关配置（视频/图像 2 模态 + 表单 model 版本选择）"
        settings["source"] = "volcengine gateway consolidation patch"
        settings["sourceVariant"] = "volcengine-gateway-consolidation"
    return patched


def main(argv: list[str] | None = None) -> int:
    args = argv if argv is not None else sys.argv[1:]
    bundle_path = Path(args[0]) if args else ROOT / "ai-tool-market-config-2026-06-15.json"
    if not bundle_path.is_file():
        print(f"Bundle not found: {bundle_path}", file=sys.stderr)
        return 1
    raw = json.loads(bundle_path.read_text(encoding="utf-8"))
    patched = patch_bundle(raw)
    out_path = bundle_path if len(args) < 2 else Path(args[1])
    out_path.write_text(json.dumps(patched, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    model_codes = [
        str(row.get("configCode") or "")
        for row in patched.get("modelConfigs") or []
        if str(row.get("provider") or "") in {"seedance", "volcengine_images"}
        or str(row.get("configCode") or "").startswith("volcengine-")
    ]
    tool_codes = [
        str(row.get("toolCode") or "")
        for row in patched.get("tools") or []
        if "volcengine" in str(row.get("toolCode") or "").lower()
        or "seedance" in str(row.get("toolCode") or "").lower()
        or "seedream" in str(row.get("toolCode") or "").lower()
    ]
    print(f"Patched {bundle_path} -> {out_path}")
    print("Volcengine modelConfigs:", ", ".join(model_codes))
    print("Volcengine tools:", ", ".join(tool_codes))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
