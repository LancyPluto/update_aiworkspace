import json

import pytest

from tests.conftest import legacy_llm_router_settings

pytestmark = pytest.mark.usefixtures("legacy_llm_router_settings")

from app.core.event_types import ROUTER_FALLBACK, ROUTER_SELECTED
from app.core.intent_router import Intent, IntentResult
from app.core.schemas import AgentRouterSettings, ChatMessage, RecentToolCallContext, RunContext, ToolDescriptor
from app.runtime.agent_router_service import AgentRouterService


class FakeBackend:
    def __init__(self) -> None:
        self.events = []

    async def append_event(self, run_id, event):
        self.events.append((run_id, event))


class FakeModel:
    def __init__(self, payload) -> None:
        self.payload = payload
        self.calls = 0
        self.messages = []

    async def chat(self, messages):
        self.calls += 1
        self.messages.append(messages)
        if isinstance(self.payload, Exception):
            raise self.payload
        return json.dumps(self.payload)


def _context(message="生成一张 cos 远景拍摄图片", router_settings=None):
    return RunContext(
        runId=7,
        sessionId=1,
        userId=1,
        message=message,
        routerSettings=router_settings,
        availableTools=[
            ToolDescriptor(
                toolCode="kling_image_v21",
                toolName="可灵生图 V2.1",
                description="图片生成，写真，海报，文生图",
                autoCallable=True,
            ),
            ToolDescriptor(
                toolCode="deepseek_text",
                toolName="文本生成",
                description="文案，标题，文章",
                autoCallable=True,
            ),
        ],
    )


@pytest.mark.asyncio
async def test_router_accepts_valid_tool_selection():
    backend = FakeBackend()
    model = FakeModel({
        "intent": "tool_use",
        "selectedToolCode": "kling_image_v21",
        "candidateToolCodes": ["kling_image_v21"],
        "confidence": 0.92,
        "reason": "user asks for image generation",
        "arguments": {"prompt": "cos remote photo"},
        "missingFields": [],
    })
    service = AgentRouterService(backend, model)

    result = await service.classify(
        _context(),
        IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="rule_fallback"),
    )

    assert result is not None
    assert result.intent == Intent.TOOL_USE
    assert result.selectedToolCode == "kling_image_v21"
    assert any(event.eventType == ROUTER_SELECTED for _, event in backend.events)


@pytest.mark.asyncio
async def test_router_accepts_image_editing_alias_as_tool_use():
    backend = FakeBackend()
    model = FakeModel({
        "intent": "image_editing",
        "selectedToolCode": "kling_image_v21",
        "candidateToolCodes": ["kling_image_v21"],
        "confidence": 0.95,
        "reason": "face swap poster",
        "arguments": {"prompt": "movie poster"},
        "missingFields": [],
    })
    service = AgentRouterService(backend, model)

    result = await service.classify(
        _context("换脸生成电影海报"),
        IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="rule_fallback"),
    )

    assert result is not None
    assert result.intent == Intent.TOOL_USE
    assert result.selectedToolCode == "kling_image_v21"
    assert any(event.eventType == ROUTER_SELECTED for _, event in backend.events)


@pytest.mark.asyncio
async def test_router_accepts_image_tool_alias_as_tool_use():
    backend = FakeBackend()
    model = FakeModel({
        "intent": "image_tool",
        "selectedToolCode": "kling_image_v21",
        "candidateToolCodes": ["kling_image_v21", "deepseek_text"],
        "confidence": 0.95,
        "reason": "image poster editing request",
        "arguments": {"prompt": "悬疑智斗电影海报"},
        "missingFields": [],
    })
    service = AgentRouterService(backend, model)

    result = await service.classify(
        _context("把该电影海报改为悬疑智斗电影"),
        IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="rule_fallback"),
    )

    assert result is not None
    assert result.intent == Intent.TOOL_USE
    assert result.selectedToolCode == "kling_image_v21"
    assert result.arguments["prompt"] == "悬疑智斗电影海报"
    assert any(event.eventType == ROUTER_SELECTED for _, event in backend.events)


@pytest.mark.asyncio
async def test_router_falls_back_on_unknown_tool():
    backend = FakeBackend()
    model = FakeModel({
        "intent": "tool_use",
        "selectedToolCode": "missing_tool",
        "candidateToolCodes": ["missing_tool"],
        "confidence": 0.95,
        "reason": "bad tool",
    })
    service = AgentRouterService(backend, model)

    result = await service.classify(
        _context(),
        IntentResult(intent=Intent.TOOL_USE, confidence=0.8, selectedToolCode="kling_image_v21", reason="rule_tool"),
    )

    assert result is None
    assert any(event.eventType == ROUTER_FALLBACK for _, event in backend.events)


@pytest.mark.asyncio
async def test_router_falls_back_on_low_confidence():
    backend = FakeBackend()
    model = FakeModel({
        "intent": "tool_use",
        "selectedToolCode": "kling_image_v21",
        "candidateToolCodes": ["kling_image_v21"],
        "confidence": 0.2,
        "reason": "unsure",
    })
    service = AgentRouterService(backend, model)

    result = await service.classify(
        _context(router_settings=AgentRouterSettings(minConfidence=0.7)),
        IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="rule_fallback"),
    )

    assert result is None
    assert any(event.eventType == ROUTER_FALLBACK for _, event in backend.events)


@pytest.mark.asyncio
async def test_router_disabled_does_not_call_model():
    backend = FakeBackend()
    model = FakeModel({})
    service = AgentRouterService(backend, model)

    result = await service.classify(
        _context(router_settings=AgentRouterSettings(enabled=False)),
        IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="rule_fallback"),
    )

    assert result is None
    assert model.calls == 0
    assert any(event.eventType == ROUTER_FALLBACK for _, event in backend.events)


@pytest.mark.asyncio
async def test_router_prompt_uses_compact_candidates_without_input_schema():
    backend = FakeBackend()
    model = FakeModel({
        "intent": "tool_use",
        "selectedToolCode": "kling_image_v21",
        "candidateToolCodes": ["kling_image_v21"],
        "confidence": 0.92,
        "reason": "image",
        "arguments": {},
        "missingFields": [],
    })
    service = AgentRouterService(backend, model)
    context = _context()
    context.availableTools[0] = ToolDescriptor(
        toolCode="kling_image_v21",
        toolName="可灵生图 V2.1",
        description="图片生成",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "properties": {"prompt": {"type": "string", "enum": ["a", "b"]}},
        },
        fields=[],
    )

    await service.classify(context, IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="rule_fallback"))

    prompt = model.messages[0][0].content
    assert "inputSchema" not in prompt
    started = next(event for _, event in backend.events if event.eventType == "router.started")
    assert started.eventJson.get("promptBytes", 0) > 0


@pytest.mark.asyncio
async def test_router_prompt_includes_schema_history_and_recent_tool_calls():
    backend = FakeBackend()
    model = FakeModel({
        "intent": "tool_use",
        "selectedToolCode": "kling_image_v21",
        "candidateToolCodes": ["kling_image_v21"],
        "confidence": 0.94,
        "reason": "followup image",
        "arguments": {"prompt": "new prompt"},
        "followupPatch": {"prompt": "new prompt"},
        "requiresConfirmation": False,
        "missingFields": [],
    })
    service = AgentRouterService(backend, model)
    context = _context(message="给狛枝凪斗也来一张同款")
    context.history = [ChatMessage(role="assistant", content="上一张图已生成")]
    context.recentToolCalls = [
        RecentToolCallContext(
            id=91,
            toolCode="kling_image_v21",
            taskId=9001,
            argumentsJson={"prompt": "old prompt", "aspectRatio": "16:9"},
            resourceType="IMAGE",
            mediaUrls=["/generated/images/9001/image-1.png"],
        )
    ]
    context.availableTools[0].inputSchema = {
        "type": "object",
        "required": ["prompt"],
        "properties": {"prompt": {"type": "string", "title": "提示词"}},
    }

    result = await service.classify(
        context,
        IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="rule_fallback"),
    )

    prompt = model.messages[0][0].content
    assert result is not None
    assert result.followupPatch == {"prompt": "new prompt"}
    assert result.requiresConfirmation is False
    assert "recentToolCalls" in prompt
    assert "old prompt" in prompt
    assert "fieldKey" in prompt or '"fields"' in prompt
    assert "outputModality" in prompt
    assert "inputSchema" not in prompt


@pytest.mark.asyncio
async def test_router_respects_history_turns_in_prompt():
    backend = FakeBackend()
    model = FakeModel({
        "intent": "general_chat",
        "selectedToolCode": None,
        "candidateToolCodes": [],
        "confidence": 0.9,
        "reason": "chat",
    })
    service = AgentRouterService(backend, model)
    context = _context(router_settings=AgentRouterSettings(historyTurns=1))
    context.history = [
        ChatMessage(role="user", content="old-user-1"),
        ChatMessage(role="assistant", content="old-assistant-1"),
        ChatMessage(role="user", content="recent-user"),
        ChatMessage(role="assistant", content="recent-assistant"),
    ]

    await service.classify(
        context,
        IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="default_general_chat"),
    )

    prompt = model.messages[0][0].content
    assert "old-user-1" not in prompt
    assert "recent-user" in prompt
    assert "recent-assistant" in prompt


@pytest.mark.asyncio
async def test_router_rejects_output_modality_mismatch():
    backend = FakeBackend()
    model = FakeModel({
        "intent": "tool_use",
        "selectedToolCode": "kling_video",
        "candidateToolCodes": ["kling_video"],
        "confidence": 0.95,
        "reason": "wrong modality",
    })
    service = AgentRouterService(backend, model)
    context = RunContext(
        runId=8,
        sessionId=1,
        userId=1,
        message="生成一张图片",
        availableTools=[
            ToolDescriptor(
                toolCode="kling_video",
                toolName="可灵视频",
                description="视频生成，文生视频",
                autoCallable=True,
            ),
        ],
    )

    result = await service.classify(
        context,
        IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="rule_fallback"),
    )

    assert result is None
    assert any(event.eventType == ROUTER_FALLBACK for _, event in backend.events)


@pytest.mark.asyncio
async def test_router_accepts_tool_call_intent_alias():
    backend = FakeBackend()
    model = FakeModel({
        "intent": "tool_call",
        "selectedToolCode": "kling_image_v21",
        "candidateToolCodes": ["kling_image_v21"],
        "confidence": 0.95,
        "reason": "image generation request",
        "arguments": {"prompt": "poster"},
    })
    service = AgentRouterService(backend, model)

    result = await service.classify(
        _context(),
        IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="default_general_chat"),
    )

    assert result is not None
    assert result.intent == Intent.TOOL_USE
    assert result.selectedToolCode == "kling_image_v21"
    assert any(event.eventType == ROUTER_SELECTED for _, event in backend.events)


@pytest.mark.asyncio
async def test_router_accepts_image_generation_intent_alias():
    backend = FakeBackend()
    model = FakeModel({
        "intent": "image_generation",
        "selectedToolCode": "kling_image_v21",
        "candidateToolCodes": ["kling_image_v21"],
        "confidence": 0.98,
        "reason": "image edit request",
        "arguments": {"prompt": "换装", "image": "/generated/images/301/image-1.png"},
    })
    service = AgentRouterService(backend, model)

    result = await service.classify(
        _context(),
        IntentResult(intent=Intent.GENERAL_CHAT, confidence=0.6, reason="default_general_chat"),
    )

    assert result is not None
    assert result.intent == Intent.TOOL_USE
    assert result.selectedToolCode == "kling_image_v21"
    assert any(event.eventType == ROUTER_SELECTED for _, event in backend.events)
