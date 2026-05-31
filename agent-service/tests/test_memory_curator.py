from app.core.schemas import RunContext
from app.runtime.memory_curator import MemoryCuratorService


def test_memory_curator_auto_adds_explicit_preference():
    curator = MemoryCuratorService()
    context = RunContext(runId=1, sessionId=2, userId=3, message="记住我喜欢二次元荒诞梗图风格")

    decision = curator.decide(context, "记住了")

    assert decision.action == "add"
    assert decision.memory_type == "preference"
    assert decision.confidence >= 0.8


def test_memory_curator_creates_candidate_for_workspace_fact():
    curator = MemoryCuratorService()
    context = RunContext(runId=1, sessionId=2, userId=3, message="这套网站默认用 DeepSeek V4 Flash 做 Agent 模型")

    decision = curator.decide(context, "好的")

    assert decision.action == "candidate"
    assert decision.memory_type == "workspace_fact"


def test_memory_curator_rejects_media_payloads():
    curator = MemoryCuratorService()
    context = RunContext(runId=1, sessionId=2, userId=3, message="生成一张图")

    decision = curator.decide(context, '{"url": "/generated/images/1.png", "backup": "/generated/images/2.png"}')

    assert decision.action == "none"
    assert decision.reason == "ephemeral_or_large_payload"


def test_memory_curator_saves_profile_summary_for_reflective_memory_request():
    curator = MemoryCuratorService()
    context = RunContext(runId=1, sessionId=2, userId=3, message="你觉得我是什么样的人？写入你的记忆里")

    decision = curator.decide(context, "已记录。你是一个喜欢二次元、网感梗图和 AI 创作的人。")

    assert decision.action == "add"
    assert decision.memory_type == "user_profile"
    assert "二次元" in decision.content
    assert "写入你的记忆" not in decision.content
