import json

import pytest

from app.core.event_types import ROUTER_FALLBACK, ROUTER_SELECTED
from app.core.intent_router import Intent, IntentResult
from app.core.schemas import AgentRouterSettings, RunContext, ToolDescriptor
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

    async def chat(self, messages):
        self.calls += 1
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
