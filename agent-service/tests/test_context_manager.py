from app.core.schemas import ChatMessage
from app.runtime.context_manager import (
    ContextManager,
    estimate_messages_tokens,
    middle_truncate,
    trim_tool_output,
)


def test_middle_truncate_keeps_short_text():
    assert middle_truncate("hello", 100) == "hello"


def test_middle_truncate_collapses_long_text():
    text = "A" * 1000 + "B" * 1000
    out = middle_truncate(text, 200, head_ratio=0.5)
    assert "已截断" in out
    assert out.startswith("A" * 100)
    assert out.endswith("B" * 100)
    assert len(out) < len(text)


def test_middle_truncate_zero_limit_is_noop():
    assert middle_truncate("anything", 0) == "anything"


def test_trim_tool_output_preserves_dropped_urls():
    url = "https://cdn.example.com/generated/video-final.mp4"
    content = ("noise " * 500) + url + (" trailing " * 500)
    out = trim_tool_output(content, 120)
    assert "已截断" in out
    assert url in out  # URL survived even though it was in the elided middle


def test_trim_tool_output_no_double_append_when_url_kept():
    url = "https://cdn.example.com/a.png"
    content = url + " " + ("x" * 50)
    out = trim_tool_output(content, 500)
    # short enough to not truncate at all
    assert out == content
    assert out.count(url) == 1


def test_window_keeps_last_n_turns():
    history = [
        ChatMessage(role="user", content="turn1"),
        ChatMessage(role="assistant", content="a1"),
        ChatMessage(role="user", content="turn2"),
        ChatMessage(role="assistant", content="a2"),
        ChatMessage(role="user", content="turn3"),
        ChatMessage(role="assistant", content="a3"),
    ]
    cm = ContextManager(max_recent_turns=2, max_history_messages=20)
    windowed = cm.window(history)
    contents = [m.content for m in windowed]
    assert contents == ["turn2", "a2", "turn3", "a3"]


def test_window_keeps_tool_result_paired_with_assistant_tool_call():
    history = [
        ChatMessage(role="user", content="turn1"),
        ChatMessage(role="assistant", content="", toolCalls=[{"id": "c1", "name": "tool"}]),
        ChatMessage(role="tool", content="result1", toolCallId="c1"),
        ChatMessage(role="user", content="turn2"),
        ChatMessage(role="assistant", content="a2"),
    ]
    cm = ContextManager(max_recent_turns=1, max_history_messages=20)
    windowed = cm.window(history)
    contents = [m.content for m in windowed]
    assert contents == ["", "result1", "turn2", "a2"]


def test_window_respects_hard_message_cap():
    history = [ChatMessage(role="user", content=f"m{i}") for i in range(50)]
    cm = ContextManager(max_recent_turns=100, max_history_messages=10)
    windowed = cm.window(history)
    assert len(windowed) == 10
    assert windowed[-1].content == "m49"


def test_compact_uses_role_specific_limits():
    cm = ContextManager(msg_char_limit=50, tool_output_char_limit=10)
    messages = [
        ChatMessage(role="assistant", content="x" * 200),
        ChatMessage(role="tool", content="y" * 200, toolCallId="c1", name="search"),
    ]
    compacted = cm.compact(messages)
    assert "已截断" in compacted[0].content
    assert "已截断" in compacted[1].content
    # tool limit is stricter than message limit
    assert len(compacted[1].content) < len(compacted[0].content)
    # tool metadata is preserved through model_copy
    assert compacted[1].toolCallId == "c1"
    assert compacted[1].name == "search"


def test_compact_keeps_short_messages_identical():
    cm = ContextManager(msg_char_limit=2000)
    original = ChatMessage(role="assistant", content="short answer")
    compacted = cm.compact([original])
    assert compacted[0] is original


def test_build_history_windows_then_truncates():
    history = [
        ChatMessage(role="user", content="old"),
        ChatMessage(role="user", content="recent"),
        ChatMessage(role="assistant", content="z" * 500),
    ]
    cm = ContextManager(max_recent_turns=1, max_history_messages=20, msg_char_limit=40)
    built = cm.build_history(history)
    assert [m.content for m in built[:1]] == ["recent"]
    assert "已截断" in built[-1].content


def test_estimate_messages_tokens():
    messages = [ChatMessage(role="user", content="a" * 40)]
    assert estimate_messages_tokens(messages) == 10


def test_prune_trims_only_tool_messages_over_budget():
    long_tool = "y" * 2000
    history = [
        ChatMessage(role="user", content="u" * 2000),
        ChatMessage(role="assistant", content="a" * 2000),
        ChatMessage(role="tool", content=long_tool, toolCallId="c1"),
    ]
    cm = ContextManager(prune_token_budget=500, pruning_tool_char_limit=200)
    pruned = cm.prune(history)
    assert pruned[0].content == history[0].content
    assert pruned[1].content == history[1].content
    assert "已截断" in pruned[2].content
    assert len(pruned[2].content) < len(long_tool)


def test_prune_noop_when_under_budget():
    history = [ChatMessage(role="tool", content="short", toolCallId="c1")]
    cm = ContextManager(prune_token_budget=5000)
    assert cm.prune(history) == history


def test_build_history_metrics_reports_pruning():
    history = [
        ChatMessage(role="user", content="u" * 2000),
        ChatMessage(role="tool", content="t" * 2000, toolCallId="c1"),
    ]
    cm = ContextManager(prune_token_budget=500, pruning_tool_char_limit=200, tool_output_char_limit=100)
    metrics = cm.build_history_metrics(history)
    assert metrics["pruningApplied"] is True
    assert metrics["estimatedTokensAfterPrune"] < metrics["estimatedTokensAfterWindow"]
    assert metrics["estimatedTokensAfter"] <= metrics["estimatedTokensAfterPrune"]


def test_from_settings_reads_config():
    class FakeSettings:
        agent_context_max_recent_turns = 3
        agent_max_history_messages = 12
        agent_msg_char_limit = 1500
        agent_tool_output_char_limit = 600
        agent_pruning_tool_char_limit = 350
        agent_context_prune_token_budget = 2500

    cm = ContextManager.from_settings(FakeSettings())
    assert cm.max_recent_turns == 3
    assert cm.max_history_messages == 12
    assert cm.msg_char_limit == 1500
    assert cm.tool_output_char_limit == 600
    assert cm.pruning_tool_char_limit == 350
    assert cm.prune_token_budget == 2500
