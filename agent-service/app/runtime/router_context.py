from __future__ import annotations

from app.core.schemas import AgentRouterSettings, ChatMessage, RunContext

DEFAULT_ROUTER_HISTORY_TURNS = 4
DEFAULT_ROUTER_RECENT_TOOL_CALLS = 5


def router_history_turns(context: RunContext) -> int:
    settings = context.routerSettings
    if settings is None or settings.historyTurns is None:
        return DEFAULT_ROUTER_HISTORY_TURNS
    try:
        return max(0, min(20, int(settings.historyTurns)))
    except (TypeError, ValueError):
        return DEFAULT_ROUTER_HISTORY_TURNS


def router_recent_tool_call_limit(context: RunContext) -> int:
    settings = context.routerSettings
    if settings is None or settings.recentToolCallLimit is None:
        return DEFAULT_ROUTER_RECENT_TOOL_CALLS
    try:
        return max(0, min(10, int(settings.recentToolCallLimit)))
    except (TypeError, ValueError):
        return DEFAULT_ROUTER_RECENT_TOOL_CALLS


def slice_history_by_turns(history: list[ChatMessage], max_turns: int) -> list[ChatMessage]:
    """Keep messages for the last N user turns (each turn may include assistant/system replies)."""
    if max_turns <= 0 or not history:
        return []
    user_turns = 0
    start_index = len(history)
    for index in range(len(history) - 1, -1, -1):
        role = (history[index].role or "").strip().lower()
        if role == "user":
            user_turns += 1
            if user_turns >= max_turns:
                start_index = index
                break
    return history[start_index:]
