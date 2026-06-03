from app.core.schemas import AgentRouterSettings, ChatMessage, RunContext
from app.runtime.router_context import slice_history_by_turns


def test_slice_history_by_turns_keeps_last_n_user_turns():
    history = [
        ChatMessage(role="user", content="u1"),
        ChatMessage(role="assistant", content="a1"),
        ChatMessage(role="user", content="u2"),
        ChatMessage(role="assistant", content="a2"),
        ChatMessage(role="user", content="u3"),
    ]
    sliced = slice_history_by_turns(history, 2)
    assert [message.content for message in sliced] == ["u2", "a2", "u3"]


def test_slice_history_by_turns_zero_returns_empty():
    history = [ChatMessage(role="user", content="u1")]
    assert slice_history_by_turns(history, 0) == []


def test_router_settings_history_turns_from_context():
    from app.runtime.router_context import router_history_turns

    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="hello",
        routerSettings=AgentRouterSettings(historyTurns=6),
    )
    assert router_history_turns(context) == 6
