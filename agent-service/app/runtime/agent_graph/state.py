from __future__ import annotations

import json
from typing import Annotated, Any, TypedDict

from app.core.schemas import ChatMessage

CHECKPOINT_VERSION = 2


def _extend(left: list | None, right: list | None) -> list:
    """Append-only reducer for graph-accumulated lists."""
    return list(left or []) + list(right or [])


def _replace(_left: Any, right: Any) -> Any:
    """Last-write-wins reducer for scalar state slots."""
    return right


class AgentState(TypedDict, total=False):
    """Mutable state threaded through the multi-step agent graph.

    Heavy, non-serializable runtime objects (RunContext, budget, tool specs)
    live on the engine instance; this state only holds graph-relevant data so a
    checkpointer can serialize it cleanly in Phase 2.
    """

    messages: Annotated[list[ChatMessage], _extend]
    pending_tool_calls: Annotated[list[dict[str, Any]], _replace]
    last_content: Annotated[str, _replace]
    plan: Annotated[list[dict[str, Any]], _replace]
    artifacts: Annotated[list[dict[str, Any]], _extend]
    iteration: Annotated[int, _replace]
    pending_confirmation: Annotated[dict[str, Any] | None, _replace]
    final_answer: Annotated[str, _replace]
    finished: Annotated[bool, _replace]
    schema_validation_retry_pending: Annotated[bool, _replace]
    skill_hydration_retry_pending: Annotated[bool, _replace]


def serialize_checkpoint(state: AgentState) -> str:
    """Serialize the resumable parts of agent state to a JSON blob.

    Stored by the backend across the human-in-the-loop confirmation boundary so
    a paused multi-step run can resume with its plan and prior artifacts intact.
    """
    payload = {
        "version": CHECKPOINT_VERSION,
        "kind": "agent_graph_business_checkpoint",
        "messages": [m.model_dump(mode="json", by_alias=True, exclude_none=True) for m in state.get("messages", [])],
        "plan": state.get("plan", []),
        "artifacts": state.get("artifacts", []),
        "iteration": int(state.get("iteration", 0)),
        "pending_confirmation": state.get("pending_confirmation"),
    }
    return json.dumps(payload, ensure_ascii=False)


def deserialize_checkpoint(blob: str) -> dict[str, Any]:
    data = json.loads(blob)
    if data.get("kind") not in (None, "agent_graph_business_checkpoint"):
        raise ValueError("unsupported agent graph checkpoint kind")
    if int(data.get("version", 1)) > CHECKPOINT_VERSION:
        raise ValueError("agent graph checkpoint is newer than this runtime")
    messages = [ChatMessage.model_validate(m) for m in data.get("messages", [])]
    return {
        "messages": messages,
        "plan": data.get("plan", []) or [],
        "artifacts": data.get("artifacts", []) or [],
        "iteration": int(data.get("iteration", 0)),
        "pending_confirmation": data.get("pending_confirmation"),
    }
