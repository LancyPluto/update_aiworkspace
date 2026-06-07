import pytest

from app.clients.model_client import ChatToolCall, ChatTurnResult
from app.core.event_types import MEMORY_SAVED, TOOL_CALL_EXECUTED, TOOL_CALL_LOOP_COMPLETED, TOOL_CALL_REJECTED, TOOL_CALL_REQUESTED
from app.core.intent_router import Intent
from app.core.schemas import ChatMessage, RunContext, ToolDescriptor
from app.runtime.product_tool_call_loop import ProductToolCallLoopExecutor
from app.runtime.tool_call_loop import (
    AgentToolCallLoopExecutor,
    contains_pseudo_tool_call,
    finalize_loop_answer,
    strip_pseudo_tool_calls,
)
from app.tools.memory_tool import MemoryTool


class FakeToolLoopModel:
    def __init__(self):
        self.calls = []

    async def chat_turn(self, messages, tools=None, tool_choice=None):
        self.calls.append((messages, tools, tool_choice))
        if len(self.calls) == 1:
            return ChatTurnResult(
                content="",
                tool_calls=[
                    ChatToolCall(
                        id="call_1",
                        name="memory_add",
                        arguments={
                            "memory_type": "user_profile",
                            "title": "User profile",
                            "content": "User enjoys playful, internet-native AI image ideas.",
                        },
                    )
                ],
                finish_reason="tool_calls",
            )
        return ChatTurnResult(content="已记录。你喜欢有网感、脑洞和二次元混搭的 AI 创作。")


class FakeToolLoopBackend:
    def __init__(self):
        self.events = []
        self.memories = []

    async def append_event(self, run_id, event):
        self.events.append((run_id, event))

    async def create_workspace_memory(self, **kwargs):
        self.memories.append(kwargs)
        return {"id": 88}


class FakeProductToolModel:
    def __init__(self, turn):
        self.turn = turn
        self.calls = []

    async def chat_turn(self, messages, tools=None, tool_choice=None):
        self.calls.append((messages, tools, tool_choice))
        return self.turn


@pytest.mark.asyncio
async def test_tool_call_loop_executes_memory_add_before_final_answer():
    backend = FakeToolLoopBackend()
    model = FakeToolLoopModel()
    memory_tool = MemoryTool(backend, workspace_id=1, user_id=2, run_id=9)
    loop = AgentToolCallLoopExecutor(
        backend=backend,
        model=model,
        run_id=9,
        memory_tool=memory_tool,
        reserve_model_call=lambda: None,
    )

    result = await loop.run([ChatMessage(role="user", content="你觉得我是什么样的人？写入你的记忆里")])

    assert result.answer.startswith("已记录")
    assert backend.memories[0]["memory_type"] == "user_profile"
    assert backend.memories[0]["content"] == "User enjoys playful, internet-native AI image ideas."
    event_types = [event.eventType for _, event in backend.events]
    assert TOOL_CALL_REQUESTED in event_types
    assert MEMORY_SAVED in event_types
    assert TOOL_CALL_EXECUTED in event_types
    assert TOOL_CALL_LOOP_COMPLETED in event_types
    assert model.calls[1][0][-1].role == "tool"


class RejectThenDsmlModel:
    def __init__(self):
        self.calls = []

    async def chat_turn(self, messages, tools=None, tool_choice=None):
        self.calls.append((messages, tools, tool_choice))
        if len(self.calls) == 1:
            return ChatTurnResult(
                content="",
                tool_calls=[
                    ChatToolCall(
                        id="call_mem",
                        name="memory_add",
                        arguments={
                            "memory_type": "user_profile",
                            "title": "会话摘要",
                            "content": "用户偏好写实高中校园场景与多语言翻唱。",
                        },
                    )
                ],
                finish_reason="tool_calls",
            )
        if tool_choice == "none":
            return ChatTurnResult(
                content=(
                    '<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name="ofox_gpt_image2">'
                    '<｜｜DSML｜｜parameter name="prompt" string="true">A poster</｜｜DSML｜｜parameter>'
                    "</｜｜DSML｜｜invoke></｜｜DSML｜｜tool_calls>"
                ),
                finish_reason="stop",
            )
        return ChatTurnResult(
            content="",
            tool_calls=[ChatToolCall(id="call_img", name="ofox_gpt_image2", arguments={"prompt": "poster"})],
            finish_reason="tool_calls",
        )


@pytest.mark.asyncio
async def test_tool_call_loop_early_stops_after_explicit_memory_request():
    backend = FakeToolLoopBackend()
    model = RejectThenDsmlModel()
    memory_tool = MemoryTool(backend, workspace_id=1, user_id=2, run_id=10)
    loop = AgentToolCallLoopExecutor(
        backend=backend,
        model=model,
        run_id=10,
        memory_tool=memory_tool,
        reserve_model_call=lambda: None,
        max_iterations=3,
    )

    result = await loop.run(
        [ChatMessage(role="user", content="总结我们的对话内容，写入你的记忆")],
        explicit_memory_request=True,
    )

    assert backend.memories
    assert len(model.calls) == 2
    assert model.calls[1][2] == "none"
    assert not contains_pseudo_tool_call(result.answer)
    assert result.answer == "已根据你的要求更新长期记忆。"
    rejected = [event for _, event in backend.events if event.eventType == TOOL_CALL_REJECTED]
    assert rejected == []


@pytest.mark.asyncio
async def test_tool_call_loop_rejects_product_tool_and_strips_dsml_answer():
    backend = FakeToolLoopBackend()
    model = RejectThenDsmlModel()
    memory_tool = MemoryTool(backend, workspace_id=1, user_id=2, run_id=11)
    loop = AgentToolCallLoopExecutor(
        backend=backend,
        model=model,
        run_id=11,
        memory_tool=memory_tool,
        reserve_model_call=lambda: None,
        max_iterations=3,
    )

    result = await loop.run(
        [ChatMessage(role="user", content="记住我喜欢写实风格")],
        explicit_memory_request=False,
    )

    assert backend.memories
    rejected = [event for _, event in backend.events if event.eventType == TOOL_CALL_REJECTED]
    assert rejected
    assert rejected[0].eventJson["reason"] == "tool_not_allowed"
    guidance = [message.content for message in model.calls[-1][0] if message.role == "system"]
    assert any("仅允许 memory 工具" in content for content in guidance)
    assert not contains_pseudo_tool_call(result.answer)


def test_strip_pseudo_tool_calls_removes_dsml_blocks():
    raw = (
        '思考完成\n<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke name="ofox_gpt_image2">'
        '<｜｜DSML｜｜parameter name="prompt" string="true">poster</｜｜DSML｜｜parameter>'
        "</｜｜DSML｜｜invoke></｜｜DSML｜｜tool_calls>"
    )
    cleaned = strip_pseudo_tool_calls(raw)
    assert "dsml" not in cleaned.lower()
    assert "invoke name=" not in cleaned.lower()
    assert finalize_loop_answer(raw, explicit_memory_request=True) == "已根据你的要求更新长期记忆。"


@pytest.mark.asyncio
async def test_product_tool_call_loop_selects_tool_by_alias():
    backend = FakeToolLoopBackend()
    model = FakeProductToolModel(
        ChatTurnResult(
            tool_calls=[
                ChatToolCall(
                    id="call_product_1",
                    name="agent_tool__kling_image_v21",
                    arguments={"userRequest": "生成一张 cos 远景拍摄图片"},
                )
            ],
            finish_reason="tool_calls",
        )
    )
    loop = ProductToolCallLoopExecutor(backend=backend, model=model)
    context = RunContext(
        runId=41,
        sessionId=1,
        userId=2,
        message="生成一张 cos 远景拍摄图片",
        availableTools=[
            ToolDescriptor(
                toolCode="kling-image-v21",
                toolName="可灵生图 V2.1",
                description="图片生成，写真，海报，文生图",
                autoCallable=True,
            )
        ],
    )

    result = await loop.run(context)

    assert result.intent is not None
    assert result.intent.intent == Intent.TOOL_USE
    assert result.intent.selectedToolCode == "kling-image-v21"
    assert result.intent.arguments == {"userRequest": "生成一张 cos 远景拍摄图片"}
    assert model.calls[0][2] == "auto"
    assert model.calls[0][1][0]["function"]["name"] == "agent_tool__kling_image_v21"


@pytest.mark.asyncio
async def test_product_tool_call_loop_accepts_raw_tool_code_and_records_alias():
    backend = FakeToolLoopBackend()
    model = FakeProductToolModel(
        ChatTurnResult(
            tool_calls=[
                ChatToolCall(
                    id="call_product_raw",
                    name="ofox_gpt_image2",
                    arguments={"userRequest": "生成 Suno 吉祥物"},
                )
            ],
            finish_reason="tool_calls",
        )
    )
    loop = ProductToolCallLoopExecutor(backend=backend, model=model)
    context = RunContext(
        runId=45,
        sessionId=1,
        userId=2,
        message="生成 Suno 吉祥物",
        availableTools=[
            ToolDescriptor(
                toolCode="ofox_gpt_image2",
                toolName="GPT-image2.0",
                description="图片生成，文生图",
                autoCallable=True,
            )
        ],
    )

    result = await loop.run(context)

    assert result.intent is not None
    assert result.intent.selectedToolCode == "ofox_gpt_image2"
    executed_events = [event for _, event in backend.events if event.eventType == TOOL_CALL_EXECUTED]
    assert executed_events[0].eventJson["usedRawToolCode"] is True
    assert executed_events[0].eventJson["expectedToolAlias"] == "agent_tool__ofox_gpt_image2"


@pytest.mark.asyncio
async def test_product_tool_call_loop_includes_workspace_memory_in_model_messages():
    backend = FakeToolLoopBackend()
    model = FakeProductToolModel(
        ChatTurnResult(
            tool_calls=[
                ChatToolCall(
                    id="call_product_1",
                    name="agent_tool__ofox_gpt_image2",
                    arguments={"userRequest": "生成一张图", "quality": "low"},
                )
            ],
            finish_reason="tool_calls",
        )
    )
    loop = ProductToolCallLoopExecutor(backend=backend, model=model)
    context = RunContext(
        runId=44,
        sessionId=1,
        userId=2,
        message="生成一张图",
        availableTools=[
            ToolDescriptor(
                toolCode="ofox_gpt_image2",
                toolName="GPT-image2.0",
                description="图片生成，文生图",
                autoCallable=True,
            )
        ],
    )

    result = await loop.run(
        context,
        workspace_memory_context="Frozen workspace memory snapshot\n- GPT生图默认选最低质量 low quality",
    )

    system_messages = [message.content for message in model.calls[0][0] if message.role == "system"]
    assert any("GPT生图默认选最低质量" in content for content in system_messages)
    assert result.intent is not None
    assert result.intent.arguments["quality"] == "low"


@pytest.mark.asyncio
async def test_product_tool_call_loop_rejects_unknown_tool_call():
    backend = FakeToolLoopBackend()
    model = FakeProductToolModel(
        ChatTurnResult(
            tool_calls=[ChatToolCall(id="call_bad", name="agent_tool__missing", arguments={})],
            finish_reason="tool_calls",
        )
    )
    loop = ProductToolCallLoopExecutor(backend=backend, model=model)
    context = RunContext(
        runId=42,
        sessionId=1,
        userId=2,
        message="生成一张图片",
        availableTools=[
            ToolDescriptor(
                toolCode="kling_image_v21",
                toolName="可灵生图 V2.1",
                description="图片生成，文生图",
                autoCallable=True,
            )
        ],
    )

    result = await loop.run(context)

    assert result.intent is None
    assert result.rejected is True
    assert result.rejection_reason == "tool_not_available"
    assert TOOL_CALL_REJECTED in [event.eventType for _, event in backend.events]


@pytest.mark.asyncio
async def test_product_tool_call_loop_rejects_output_modality_mismatch():
    backend = FakeToolLoopBackend()
    model = FakeProductToolModel(
        ChatTurnResult(
            tool_calls=[ChatToolCall(id="call_video", name="agent_tool__kling_video", arguments={})],
            finish_reason="tool_calls",
        )
    )
    loop = ProductToolCallLoopExecutor(backend=backend, model=model)
    context = RunContext(
        runId=43,
        sessionId=1,
        userId=2,
        message="生成一张图片",
        availableTools=[
            ToolDescriptor(
                toolCode="kling_video",
                toolName="可灵视频",
                description="视频生成，文生视频",
                autoCallable=True,
            )
        ],
    )

    result = await loop.run(context)

    assert result.intent is None
    assert result.rejected is True
    assert result.rejection_reason == "output_modality_mismatch"
    rejected_events = [event for _, event in backend.events if event.eventType == TOOL_CALL_REJECTED]
    assert rejected_events[0].eventJson["requestedOutputModality"] == "image"
