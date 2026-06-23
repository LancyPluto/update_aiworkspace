"""Progressive tool disclosure: compact catalog + relevance shortlist + lazy expand.

Phase 1.5 of the context-cost refactor. The dominant per-call token cost is the
full function-calling definition for every marketplace tool (~25 tools, each with
a verbose ``inputSchema``) attached on every model turn. This module shrinks that
surface without reducing capability:

* ``format_tool_catalog`` - a one-line-per-tool catalog (replaces the duplicated
  full tool-list system prompt). The model always *sees* every tool.
* ``select_relevant_tools`` - rank the tools for the current request (reusing the
  existing keyword/modality scorer) and keep only the Top-K.
* ``trim_tool_description`` / ``trim_tool_parameters`` - compact each definition
  (drop label maps, ``x-*`` metadata, defaults, long descriptions) while keeping
  field names and enum *values* so the model can still set quality/ratio/etc.
* ``build_disclosed_definitions`` - build full (trimmed) definitions only for the
  shortlist plus any tools the model explicitly expanded.
* ``expand_tool_definition`` - the ``expand_tool`` meta-tool the model calls to
  pull a tool's full schema on demand (the progressive-disclosure escape hatch).

The pure functions avoid importing the model client / ``httpx`` so they can be
unit-tested in isolation (``build_disclosed_definitions`` imports the shared
builder lazily).
"""

from __future__ import annotations

from typing import Any, TYPE_CHECKING

from app.core.preferred_tool_bias import preferred_tool_code
from app.core.schemas import RunContext, ToolDescriptor
from app.routing.semantic_tool_recall import recall_tool_codes
from app.tools.registry import (
    ToolRegistry,
    infer_output_modality,
    requested_output_modality,
)

if TYPE_CHECKING:  # pragma: no cover - typing only.
    pass

EXPAND_TOOL = "expand_tool"

_CORE_KEYS = {"prompt", "userRequest", "userrequest", "user_request"}
_MEDIA_MODALITIES = {"image", "video", "audio"}

# Local copy of agent_graph.prompts._MEDIA_FIELD_HINTS so this module stays
# importable without pulling the agent_graph package (and langgraph).
_MEDIA_FIELD_HINTS = {
    "image": ("image", "img", "picture", "photo", "cover", "reference_image", "init_image", "first_frame", "last_frame"),
    "video": ("video", "clip", "movie", "footage"),
    "audio": ("audio", "voice", "sound", "music", "speech"),
}


def _field_media_modality(key: str, spec: dict[str, Any]) -> str | None:
    name = (key or "").lower()
    declared = str(spec.get("x-modality") or spec.get("format") or "").lower()
    for modality, hints in _MEDIA_FIELD_HINTS.items():
        if modality in declared:
            return modality
        if any(hint in name for hint in hints):
            return modality
    return None


# ----------------------------------------------------------------------------- #
# Compact catalog (replaces the verbose tool-list system prompt)
# ----------------------------------------------------------------------------- #
def format_tool_catalog(tools: list[ToolDescriptor], *, desc_limit: int = 120) -> str:
    if not tools:
        return ""
    lines: list[str] = []
    for tool in tools:
        name = tool.toolName or tool.toolCode
        modality = infer_output_modality(tool) or "-"
        desc = (tool.description or "").strip().replace("\n", " ")[:desc_limit]
        suffix = f" | {desc}" if desc else ""
        lines.append(f"- {tool.toolCode} | {name} | {modality} | {tool.estimatedCreditCost}cr{suffix}")
    return (
        "可用平台 AI 工具目录（这些工具使用各自后台绑定的模型，不要把当前 Agent 模型当作工具执行模型）。"
        "仅最相关的工具已展开完整参数；如需调用目录中其他工具，先调用 expand_tool(toolCode) 取回其参数：\n"
        + "\n".join(lines)
    )


# ----------------------------------------------------------------------------- #
# Relevance shortlist
# ----------------------------------------------------------------------------- #
def select_relevant_tools(
    context: RunContext,
    tools: list[ToolDescriptor],
    k: int,
    *,
    artifacts: list[dict[str, Any]] | None = None,
) -> list[str]:
    """Return up to ``k`` toolCodes most relevant to the current request.

    Combines the user-selected preferred tool, the existing keyword/modality
    scorer, the requested output modality, and (for chaining) media tools when
    prior artifacts exist. Falls back to the first tools so the model is never
    left with an empty toolset.
    """
    if k <= 0 or not tools:
        return []
    available = {tool.toolCode: tool for tool in tools}
    ordered: list[str] = []

    def add(code: str | None) -> None:
        if code and code in available and code not in ordered:
            ordered.append(code)

    add(getattr(context, "preferredToolCode", None))
    for code in recall_tool_codes(context, limit=max(k * 2, 12)):
        add(code)
    requested = requested_output_modality(context.message or "")
    if requested:
        for tool in tools:
            if infer_output_modality(tool) == requested:
                add(tool.toolCode)
    if artifacts:
        for tool in tools:
            if infer_output_modality(tool) in _MEDIA_MODALITIES:
                add(tool.toolCode)
    if not ordered:
        ordered = [tool.toolCode for tool in tools]
    return ordered[:k]


# ----------------------------------------------------------------------------- #
# Schema / description trimming
# ----------------------------------------------------------------------------- #
def trim_tool_description(tool: ToolDescriptor, limit: int = 150) -> str:
    parts = [f"{tool.toolCode}: {tool.toolName or tool.toolCode}"]
    if tool.description:
        parts.append(tool.description.strip())
    modality = infer_output_modality(tool)
    if modality:
        parts.append(f"[{modality}]")
    return " ".join(parts).replace("\n", " ")[:limit]


def _property_user_required(spec: Any) -> bool:
    """Mirror of backend_tool._schema_property_user_required (kept httpx-free)."""
    if not isinstance(spec, dict):
        return True
    if spec.get("x-user-required") is False:
        return False
    strategy = str(spec.get("x-agent-fill-strategy") or "").strip().lower()
    if strategy in {"default", "derive", "none"}:
        return False
    if spec.get("default") not in (None, "") and strategy != "ask_user":
        return False
    return True


def _compact_property(
    spec: Any,
    desc_limit: int,
    *,
    media_desc_limit: int = 120,
    field_key: str = "",
) -> Any:
    if not isinstance(spec, dict):
        return spec
    out: dict[str, Any] = {}
    prop_type = spec.get("type")
    if prop_type:
        out["type"] = prop_type
    enum = spec.get("enum")
    if isinstance(enum, list) and enum:
        out["enum"] = enum
    elif isinstance(spec.get("options"), list):
        values = [
            option.get("value")
            for option in spec["options"]
            if isinstance(option, dict) and option.get("value") is not None
        ]
        if values:
            out["enum"] = values
    desc = spec.get("description") or spec.get("title")
    if isinstance(desc, str) and desc.strip():
        limit = media_desc_limit if _field_media_modality(field_key, spec) else desc_limit
        out["description"] = desc.strip().replace("\n", " ")[:limit]
    if prop_type == "array":
        items = spec.get("items")
        if isinstance(items, dict):
            out["items"] = {"type": items.get("type", "string")}
        else:
            out["items"] = {"type": "string"}
    return out


def _generic_parameters() -> dict[str, Any]:
    return {
        "type": "object",
        "required": ["userRequest"],
        "properties": {
            "userRequest": {
                "type": "string",
                "description": "The user's latest request, preserving important subject, style, and constraints.",
            }
        },
    }


def trim_tool_parameters(
    schema: Any,
    *,
    prune_fields: bool = True,
    desc_limit: int = 60,
    media_desc_limit: int = 120,
) -> dict[str, Any]:
    """Compact a tool's parameter schema without losing model-controllable fields.

    Keeps field names and enum *values*; drops label maps, ``x-*`` metadata,
    defaults, titles and long descriptions. When ``prune_fields`` is set, also
    drops purely-decorative fields the backend always fills (non-core, non-media,
    no enum, agent-fill default) - their values are restored by
    ``BackendToolBridge.build_arguments``.
    """
    if not isinstance(schema, dict):
        return _generic_parameters()
    properties = schema.get("properties")
    if not isinstance(properties, dict) or not properties:
        return _generic_parameters()
    if _is_v2_lite_image_schema(schema):
        return _trim_v2_lite_image_parameters(schema, desc_limit=desc_limit, media_desc_limit=media_desc_limit)

    kept: dict[str, Any] = {}
    for key, spec in properties.items():
        spec_dict = spec if isinstance(spec, dict) else {}
        is_core = key.lower() in _CORE_KEYS or bool(spec_dict.get("x-core"))
        is_media = _field_media_modality(key, spec_dict) is not None
        is_user_required = _property_user_required(spec_dict)
        has_enum = (isinstance(spec_dict.get("enum"), list) and bool(spec_dict.get("enum"))) or isinstance(
            spec_dict.get("options"), list
        )
        is_locked_default = (
            str(spec_dict.get("x-agent-fill-strategy") or "").strip().lower() == "default"
            and spec_dict.get("default") not in (None, "")
        )
        if prune_fields and is_locked_default and not is_core and not is_media:
            continue
        if prune_fields and not (is_core or is_media or is_user_required or has_enum):
            continue
        kept[key] = _compact_property(spec, desc_limit, media_desc_limit=media_desc_limit, field_key=key)

    if not kept:
        return _generic_parameters()
    out: dict[str, Any] = {"type": "object", "properties": kept}
    required = [name for name in (schema.get("required") or []) if name in kept]
    if required:
        out["required"] = required
    return out


def _is_v2_lite_image_schema(schema: dict[str, Any]) -> bool:
    properties = schema.get("properties")
    return isinstance(properties, dict) and {"operation", "generation_prompt", "base_image_ref", "references"}.issubset(properties.keys())


def _trim_v2_lite_image_parameters(
    schema: dict[str, Any],
    *,
    desc_limit: int,
    media_desc_limit: int,
) -> dict[str, Any]:
    properties = schema.get("properties") if isinstance(schema.get("properties"), dict) else {}
    ordered_keys = (
        "operation",
        "generation_prompt",
        "base_image_ref",
        "base_prompt",
        "modification_prompt",
        "negative_prompt",
        "references",
        "aspect_ratio",
        "count",
        "routing_notes",
    )
    kept: dict[str, Any] = {}
    for key in ordered_keys:
        if key not in properties:
            continue
        if key == "references":
            kept[key] = _compact_v2_references_property(properties[key], desc_limit=media_desc_limit)
        else:
            kept[key] = _compact_property(properties[key], desc_limit, media_desc_limit=media_desc_limit, field_key=key)
    required = [name for name in (schema.get("required") or []) if isinstance(name, str) and name in kept]
    return {
        "type": "object",
        "additionalProperties": False,
        "required": required or ["operation"],
        "properties": kept,
    }


def _compact_v2_references_property(spec: Any, *, desc_limit: int) -> dict[str, Any]:
    description = ""
    if isinstance(spec, dict):
        raw_description = spec.get("description") or spec.get("title") or ""
        if isinstance(raw_description, str):
            description = raw_description.strip().replace("\n", " ")[:desc_limit]
    role_enum = [
        "face_ref",
        "identity_ref",
        "style_ref",
        "pose_ref",
        "composition_ref",
        "controlnet_pose_ref",
        "background_ref",
        "object_ref",
        "supplemental_ref",
    ]
    out = {
        "type": "array",
        "description": description
        or "Structured reference image routing. Use one item per current reference image.",
        "items": {
            "type": "object",
            "additionalProperties": False,
            "required": ["id", "role", "source_ref"],
            "properties": {
                "id": {
                    "type": "string",
                    "description": "Stable id such as face_ref_1, pose_ref_1, style_ref_1.",
                },
                "role": {
                    "type": "string",
                    "enum": role_enum,
                    "description": "What this reference controls.",
                },
                "source_ref": {
                    "type": "string",
                    "description": "Use current aliases such as [当前参考图_1], not raw @ labels.",
                },
                "notes": {
                    "type": "string",
                    "description": "Natural-language routing notes: what to use and what not to override.",
                },
            },
        },
    }
    return out


# ----------------------------------------------------------------------------- #
# Disclosed definition builder + expand meta-tool
# ----------------------------------------------------------------------------- #
def _shortlist_k(context: RunContext) -> int:
    from app.config import settings

    requested = requested_output_modality(context.message or "")
    if requested in _MEDIA_MODALITIES:
        return max(settings.agent_tool_shortlist_k, settings.agent_tool_shortlist_k_media)
    return settings.agent_tool_shortlist_k


def build_disclosed_definitions(
    tools: list[ToolDescriptor],
    context: RunContext,
    *,
    k: int,
    expanded_codes: set[str] | None = None,
    artifacts: list[dict[str, Any]] | None = None,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Build trimmed function definitions for the shortlist plus expanded tools."""
    # Imported lazily so the pure helpers above stay importable without httpx.
    from app.runtime.product_tool_call_loop import build_tool_definitions

    expanded = set(expanded_codes or set())
    keep = set(select_relevant_tools(context, tools, _shortlist_k(context), artifacts=artifacts)) | expanded
    preferred = preferred_tool_code(context)
    if preferred:
        keep.add(preferred)
    subset = [tool for tool in tools if tool.toolCode in keep]
    if not subset:
        subset = tools
    return build_tool_definitions(subset, context)


def expand_tool_definition() -> dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": EXPAND_TOOL,
            "description": (
                "Load the full parameter schema for a platform tool by its toolCode when you intend to "
                "use a tool that is listed in the catalog but not yet expanded. After calling it, the "
                "tool becomes callable on the next step."
            ),
            "parameters": {
                "type": "object",
                "required": ["toolCode"],
                "properties": {
                    "toolCode": {
                        "type": "string",
                        "description": "The toolCode from the catalog to expand into a callable tool.",
                    }
                },
            },
        },
    }
