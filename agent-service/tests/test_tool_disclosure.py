from app.core.schemas import RunContext, ToolDescriptor
from app.runtime.tool_disclosure import (
    EXPAND_TOOL,
    expand_tool_definition,
    format_tool_catalog,
    select_relevant_tools,
    trim_tool_description,
    trim_tool_parameters,
)


def _image_tool() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="ofox_gpt_image2",
        toolName="GPT-image2",
        description="文生图，支持海报与写真",
        estimatedCreditCost=360,
        inputSchema={"type": "object", "required": ["prompt"], "properties": {"prompt": {"type": "string"}}},
    )


def _video_tool() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="kling_image_to_video",
        toolName="Kling 图生视频",
        description="图生视频/文生视频",
        estimatedCreditCost=900,
        inputSchema={"type": "object", "properties": {"prompt": {"type": "string"}}},
    )


def _text_tool() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="小红书文案",
        description="生成种草笔记文案",
        estimatedCreditCost=20,
        inputSchema={"type": "object", "properties": {"prompt": {"type": "string"}}},
    )


def _context(message: str, tools, **kwargs) -> RunContext:
    return RunContext(runId=1, sessionId=2, userId=3, message=message, availableTools=tools, **kwargs)


# --------------------------------------------------------------------------- #
# Catalog
# --------------------------------------------------------------------------- #
def test_format_tool_catalog_one_line_per_tool():
    catalog = format_tool_catalog([_image_tool(), _video_tool()])
    lines = [line for line in catalog.splitlines() if line.startswith("- ")]
    assert len(lines) == 2
    assert "ofox_gpt_image2" in catalog
    assert "image" in catalog and "video" in catalog
    assert "expand_tool" in catalog


def test_format_tool_catalog_empty():
    assert format_tool_catalog([]) == ""


# --------------------------------------------------------------------------- #
# Shortlist
# --------------------------------------------------------------------------- #
def test_select_relevant_tools_prefers_requested_modality():
    tools = [_text_tool(), _image_tool(), _video_tool()]
    ctx = _context("帮我生成一张产品海报图片", tools)
    codes = select_relevant_tools(ctx, tools, k=2)
    assert "ofox_gpt_image2" in codes
    assert len(codes) <= 2


def test_select_relevant_tools_preferred_first():
    tools = [_text_tool(), _image_tool(), _video_tool()]
    ctx = _context("生成一段视频", tools, preferredToolCode="xiaohongshu_copywriting")
    codes = select_relevant_tools(ctx, tools, k=3)
    assert codes[0] == "xiaohongshu_copywriting"


def test_select_relevant_tools_falls_back_to_first_k():
    tools = [_text_tool(), _image_tool(), _video_tool()]
    ctx = _context("随便聊聊", tools)
    codes = select_relevant_tools(ctx, tools, k=2)
    assert len(codes) == 2  # never leaves the model with an empty toolset


def test_select_relevant_tools_respects_zero_k():
    assert select_relevant_tools(_context("x", [_image_tool()]), [_image_tool()], k=0) == []


# --------------------------------------------------------------------------- #
# Description trimming
# --------------------------------------------------------------------------- #
def test_trim_tool_description_bounded_and_no_hints():
    tool = ToolDescriptor(
        toolCode="t1",
        toolName="T1",
        description="x" * 500,
        hints={"verbose": "y" * 500},
    )
    out = trim_tool_description(tool, limit=120)
    assert len(out) <= 120
    assert "y" * 50 not in out  # hints dump excluded


# --------------------------------------------------------------------------- #
# Parameter trimming
# --------------------------------------------------------------------------- #
def _rich_schema() -> dict:
    return {
        "type": "object",
        "required": ["prompt"],
        "properties": {
            "prompt": {"type": "string", "title": "画面描述", "x-agent-fill-strategy": "default"},
            "aspectRatio": {
                "type": "string",
                "title": "画面比例",
                "enum": ["auto", "1024x1536", "1024x1024"],
                "x-agent-fill-strategy": "default",
            },
            "count": {"type": "number", "title": "生成数量", "x-agent-fill-strategy": "default"},
            "image": {
                "type": "array",
                "items": {"type": "string"},
                "x-modality": "image",
                "x-agent-fill-strategy": "default",
            },
            "quality": {
                "type": "string",
                "enum": ["low", "medium", "high"],
                "default": "high",
                "x-agent-fill-strategy": "default",
            },
            "decorative": {"type": "string", "title": "内部字段", "x-agent-fill-strategy": "default"},
        },
    }


def test_trim_tool_parameters_prunes_decorative_keeps_useful():
    out = trim_tool_parameters(_rich_schema(), prune_fields=True)
    props = out["properties"]
    # core / enum / media survive
    assert "prompt" in props
    assert "aspectRatio" in props and props["aspectRatio"]["enum"] == ["auto", "1024x1536", "1024x1024"]
    assert "quality" not in props
    assert "image" in props
    # decorative default-filled, no-enum, non-media fields are dropped
    assert "count" not in props
    assert "decorative" not in props
    # x-* metadata and defaults are stripped (short titles may be kept as a
    # helpful description, which is cheap and aids comprehension)
    serialized = str(out)
    assert "x-agent-fill-strategy" not in serialized
    assert "x-modality" not in serialized
    assert "'default'" not in serialized
    assert out["required"] == ["prompt"]


def test_trim_tool_parameters_no_prune_keeps_fields_but_compacts():
    out = trim_tool_parameters(_rich_schema(), prune_fields=False)
    props = out["properties"]
    assert "count" in props  # kept when prune disabled
    assert "x-agent-fill-strategy" not in str(out)  # still compacted


def test_trim_tool_parameters_options_to_enum():
    schema = {
        "type": "object",
        "properties": {
            "ratio": {
                "type": "string",
                "options": [{"label": "竖屏", "value": "9:16"}, {"label": "横屏", "value": "16:9"}],
            }
        },
    }
    out = trim_tool_parameters(schema, prune_fields=True)
    assert out["properties"]["ratio"]["enum"] == ["9:16", "16:9"]
    assert "label" not in str(out)


def test_trim_tool_parameters_keeps_custom_mode_even_with_default_strategy():
    schema = {
        "type": "object",
        "required": ["prompt", "customMode"],
        "properties": {
            "prompt": {"type": "string", "title": "音乐描述 / 歌词", "x-agent-fill-strategy": "derive"},
            "customMode": {
                "type": "boolean",
                "title": "创作模式",
                "description": "常规：仅描述想法；高级：自定义歌词、风格与标题",
                "enum": [False, True],
                "default": False,
                "x-agent-fill-strategy": "default",
            },
            "quality": {
                "type": "string",
                "enum": ["low", "high"],
                "default": "low",
                "x-agent-fill-strategy": "default",
            },
        },
    }

    out = trim_tool_parameters(schema, prune_fields=True)

    assert out["properties"]["customMode"]["type"] == "boolean"
    assert out["properties"]["customMode"]["enum"] == [False, True]
    assert "quality" not in out["properties"]


def test_trim_tool_parameters_empty_falls_back_to_generic():
    out = trim_tool_parameters({"type": "object", "properties": {}}, prune_fields=True)
    assert "userRequest" in out["properties"]


def test_trim_tool_parameters_all_pruned_falls_back_to_generic():
    schema = {
        "type": "object",
        "properties": {
            "a": {"type": "string", "x-agent-fill-strategy": "default"},
            "b": {"type": "string", "x-agent-fill-strategy": "derive"},
        },
    }
    out = trim_tool_parameters(schema, prune_fields=True)
    assert out["properties"] == {
        "userRequest": {
            "type": "string",
            "description": "The user's latest request, preserving important subject, style, and constraints.",
        }
    }


def test_trim_tool_parameters_preserves_v2_lite_image_semantic_fields():
    schema = {
        "type": "object",
        "required": ["operation"],
        "additionalProperties": False,
        "properties": {
            "operation": {"type": "string", "enum": ["generate", "edit", "variation", "composite"]},
            "generation_prompt": {
                "type": "string",
                "description": "Full standalone prompt for generate/composite.",
                "x-agent-fill-strategy": "derive",
            },
            "base_image_ref": {"type": "string", "x-agent-fill-strategy": "llm"},
            "base_prompt": {"type": "string", "x-agent-fill-strategy": "llm"},
            "modification_prompt": {"type": "string", "x-agent-fill-strategy": "llm"},
            "negative_prompt": {"type": "string", "x-agent-fill-strategy": "derive"},
            "references": {
                "type": "array",
                "description": "Structured current references.",
                "items": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "string"},
                        "role": {"type": "string"},
                        "source_ref": {"type": "string"},
                        "notes": {"type": "string"},
                    },
                },
            },
            "aspect_ratio": {"type": "string", "enum": ["auto", "1:1"]},
            "count": {"type": "integer", "default": 1, "x-agent-fill-strategy": "default"},
            "routing_notes": {"type": "string", "x-agent-fill-strategy": "derive"},
        },
    }

    out = trim_tool_parameters(schema, prune_fields=True)
    props = out["properties"]

    assert "generation_prompt" in props
    assert "base_prompt" in props
    assert "modification_prompt" in props
    assert "references" in props
    assert "routing_notes" in props
    assert props["references"]["items"]["required"] == ["id", "role", "source_ref"]
    assert "face_ref" in props["references"]["items"]["properties"]["role"]["enum"]
    assert props["references"]["items"]["properties"]["source_ref"]["description"]
    assert out["additionalProperties"] is False
    assert out["required"] == ["operation"]


# --------------------------------------------------------------------------- #
# Expand meta-tool
# --------------------------------------------------------------------------- #
def test_expand_tool_definition_shape():
    spec = expand_tool_definition()
    assert spec["function"]["name"] == EXPAND_TOOL
    assert spec["function"]["parameters"]["required"] == ["toolCode"]
