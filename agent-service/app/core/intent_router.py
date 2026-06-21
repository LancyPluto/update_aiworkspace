"""Backward-compatible shim — use app.routing instead."""

from app.routing.helpers import (
    looks_like_session_recap_question,
    looks_like_tool_request,
)
from app.routing.state_guard import StateGuard
from app.routing.types import Intent, IntentResult


class IntentRouter(StateGuard):
    """Deprecated alias for StateGuard. NLP keyword routing has been removed."""

    _looks_like_session_recap_question = staticmethod(looks_like_session_recap_question)
    _looks_like_tool_request = staticmethod(looks_like_tool_request)

    def _is_short_chat(self, message_lower: str) -> bool:
        from app.routing.helpers import is_short_chat_message

        return is_short_chat_message(message_lower)


__all__ = ["Intent", "IntentResult", "IntentRouter"]
