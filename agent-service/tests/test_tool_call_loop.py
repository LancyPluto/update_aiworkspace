import pytest

from app.clients.model_client import ChatToolCall, ChatTurnResult
from app.core.event_types import MEMORY_SAVED, TOOL_CALL_EXECUTED, TOOL_CALL_LOOP_COMPLETED, TOOL_CALL_REQUESTED
from app.core.schemas import ChatMessage
from app.runtime.tool_call_loop import AgentToolCallLoopExecutor
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
