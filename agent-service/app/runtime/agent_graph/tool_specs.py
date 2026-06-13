from __future__ import annotations

import json
from typing import Any

from app.core.schemas import RunContext, ToolDescriptor
from app.runtime.product_tool_call_loop import _ToolAlias, build_tool_definitions


# Reserved internal tool names. Product tool aliases are namespaced with the
# ``agent_tool__`` prefix so they can never collide with these.
PLAN_TOOL = "update_plan"
FINISH_TOOL = "finish"
MEMORY_ADD_TOOL = "memory_add"
MEMORY_REPLACE_TOOL = "memory_replace"
MEMORY_REMOVE_TOOL = "memory_remove"

INTERNAL_TOOL_NAMES = {
    PLAN_TOOL,
    FINISH_TOOL,
    MEMORY_ADD_TOOL,
    MEMORY_REPLACE_TOOL,
    MEMORY_REMOVE_TOOL,
}


def product_tool_definitions(
    tools: list[ToolDescriptor],
    context: RunContext,
) -> tuple[list[dict[str, Any]], dict[str, _ToolAlias]]:
    """Build OpenAI function-tool definitions for visible product tools.

    Reuses the proven alias + schema builder from the product tool loop so the
    graph engine and the legacy product loop expose identical tool surfaces.
    """
    return build_tool_definitions(tools, context)


def planning_tool_definition() -> dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": PLAN_TOOL,
            "description": (
                "Record or update a short execution plan (todo list) for a multi-step task. "
                "Call this first when the task needs more than one tool, and again to mark "
                "steps done. Keep it concise (2-6 steps)."
            ),
            "parameters": {
                "type": "object",
                "required": ["steps"],
                "properties": {
                    "steps": {
                        "type": "array",
                        "description": "Ordered plan steps.",
                        "items": {
                            "type": "object",
                            "required": ["title", "status"],
                            "properties": {
                                "title": {"type": "string"},
                                "status": {
                                    "type": "string",
                                    "enum": ["pending", "in_progress", "done"],
                                },
                            },
                        },
                    }
                },
            },
        },
    }


def finish_tool_definition() -> dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": FINISH_TOOL,
            "description": (
                "Finish the task and return the final answer to the user. Call this when no "
                "more tools are needed."
            ),
            "parameters": {
                "type": "object",
                "required": ["answer"],
                "properties": {
                    "answer": {
                        "type": "string",
                        "description": "The final natural-language answer for the user.",
                    }
                },
            },
        },
    }


def memory_tool_definitions() -> list[dict[str, Any]]:
    return [
        {
            "type": "function",
            "function": {
                "name": MEMORY_ADD_TOOL,
                "description": "Save a durable long-term memory item for this workspace.",
                "parameters": {
                    "type": "object",
                    "required": ["memory_type", "title", "content"],
                    "properties": {
                        "memory_type": {"type": "string"},
                        "title": {"type": "string"},
                        "content": {"type": "string"},
                    },
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": MEMORY_REPLACE_TOOL,
                "description": "Replace an existing long-term memory of a given type.",
                "parameters": {
                    "type": "object",
                    "required": ["memory_type", "new_title", "new_content"],
                    "properties": {
                        "memory_type": {"type": "string"},
                        "new_title": {"type": "string"},
                        "new_content": {"type": "string"},
                    },
                },
            },
        },
        {
            "type": "function",
            "function": {
                "name": MEMORY_REMOVE_TOOL,
                "description": "Remove a long-term memory item by id.",
                "parameters": {
                    "type": "object",
                    "required": ["memory_id"],
                    "properties": {"memory_id": {"type": "integer"}},
                },
            },
        },
    ]


def tool_call_message_payload(call_id: str, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": call_id,
        "type": "function",
        "function": {
            "name": name,
            "arguments": json.dumps(arguments, ensure_ascii=False, separators=(",", ":")),
        },
    }


def redact_large(value: Any, limit: int = 1200) -> Any:
    text = json.dumps(value, ensure_ascii=False, default=str)
    if len(text) <= limit:
        return value
    return {"preview": text[:limit], "truncated": True}
