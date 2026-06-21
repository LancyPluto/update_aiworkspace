"""Structured session routing thread — replaces regex follow-up detection."""

from __future__ import annotations

from dataclasses import dataclass

from app.core.schemas import RunContext
from app.tools.registry import ToolRegistry, infer_output_modality


@dataclass(frozen=True, slots=True)
class RoutingThreadState:
    active_tool_code: str | None = None
    task_modality: str | None = None
    missing_fields: tuple[str, ...] = ()
    collected_arguments: dict | None = None
    source: str | None = None

    @property
    def has_active_task(self) -> bool:
        return bool(self.active_tool_code)


def build_thread_state(context: RunContext) -> RoutingThreadState:
    pending = context.pendingToolContext
    if pending is None or pending.status != "ACTIVE" or not pending.selectedToolCode:
        return RoutingThreadState()
    registry = ToolRegistry(context)
    tool = registry.get(pending.selectedToolCode)
    modality = infer_output_modality(tool) if tool else None
    return RoutingThreadState(
        active_tool_code=pending.selectedToolCode,
        task_modality=modality,
        missing_fields=tuple(pending.missingArgumentsJson or ()),
        collected_arguments=dict(pending.collectedArgumentsJson or {}),
        source=getattr(pending, "source", None) or "pending_tool_context",
    )


def format_thread_state_block(state: RoutingThreadState) -> str:
    if not state.has_active_task:
        return ""
    lines = [
        "Active routing thread (session state — user is likely continuing this task):",
        f"- activeToolCode: {state.active_tool_code}",
    ]
    if state.task_modality:
        lines.append(f"- taskModality: {state.task_modality}")
    if state.missing_fields:
        lines.append(f"- awaitingFields: {', '.join(state.missing_fields)}")
    if state.collected_arguments:
        lines.append(f"- collectedArguments: {state.collected_arguments}")
    lines.append(
        "If the user gives a short deferral or partial answer, continue this active tool task. "
        "Do not switch to an older unrelated tool from recent history."
    )
    return "\n".join(lines)
