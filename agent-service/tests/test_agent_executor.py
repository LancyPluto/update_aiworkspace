import json

import pytest

from app.clients.model_client import ChatToolCall, ChatTurnResult
from app.core.event_types import (
    MESSAGE_COMPLETED,
    TOOL_CALL_EXECUTED,
    TOOL_CALL_LOOP_COMPLETED,
    TOOL_CALL_LOOP_STARTED,
    TOOL_CONFIRMATION_REQUIRED,
)
from app.core.schemas import RunContext, TaskDetailResponse, TaskResultResponse, ToolDescriptor
from app.runtime.agent_executor import AgentExecutor
from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine
from app.runtime.product_tool_call_loop import _alias_for_tool_code


class FakeModel:
    model_name = "fake-model"

    def __init__(self, turns):
        self._turns = list(turns)
        self.usage = {"promptTokens": 3, "completionTokens": 5}
        self.calls = []

    async def chat_turn(self, messages, tools=None, tool_choice=None):
        self.calls.append({"messages": messages, "tools": tools, "tool_choice": tool_choice})
        if self._turns:
            return self._turns.pop(0)
        return ChatTurnResult(content="done", tool_calls=[])


class _ToolCall:
    def __init__(self, call_id):
        self.id = call_id


class _Task:
    def __init__(self, task_id, status="SUCCESS"):
        self.taskId = task_id
        self.status = status


class FakeBackend:
    def __init__(self, task_detail=None):
        self.events = []
        self.completed_runs = []
        self.failed_runs = []
        self.tool_calls = []
        self.tasks = []
        self.completed_tool_calls = []
        self.checkpoint = None
        self._task_detail = task_detail

    async def save_graph_checkpoint(self, run_id, checkpoint_json):
        self.checkpoint = checkpoint_json

    async def load_graph_checkpoint(self, run_id):
        return self.checkpoint

    async def clear_graph_checkpoint(self, run_id):
        self.checkpoint = None

    async def append_event(self, run_id, event):
        self.events.append(event)

    async def complete_run(self, run_id, completion):
        self.completed_runs.append(completion)

    async def fail_run(self, run_id, failure):
        self.failed_runs.append(failure)

    async def create_tool_call(self, run_id, payload):
        call = _ToolCall(len(self.tool_calls) + 1)
        self.tool_calls.append((payload.toolCode, payload.argumentsJson))
        return call

    async def create_task(self, payload):
        task = _Task(len(self.tasks) + 100, status="SUCCESS")
        self.tasks.append(payload)
        return task

    async def bind_tool_call_task(self, tool_call_id, task_id):
        return None

    async def get_task_detail(self, user_id, task_id):
        return self._task_detail

    async def complete_tool_call(self, tool_call_id, payload):
        self.completed_tool_calls.append(payload)

    async def fail_tool_call(self, tool_call_id, payload):
        return None

    async def retrieve_workspace_memory(self, workspace_id, query, limit, view=None, memory_ids=None, session_id=None):
        return []


def _image_tool():
    return ToolDescriptor(
        toolCode="image_gen",
        toolName="Image Gen",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "required": ["prompt"],
            "properties": {"prompt": {"type": "string"}},
        },
    )


def _event_types(backend):
    return [event.eventType for event in backend.events]


@pytest.mark.asyncio
async def test_agent_executor_chat_only_completes_without_tools():
    backend = FakeBackend()
    model = FakeModel([ChatTurnResult(content="你好，我可以帮你做什么？", tool_calls=[])])
    executor = AgentExecutor(backend, model)
    context = RunContext(runId=1, sessionId=2, userId=3, message="你好")

    result = await executor.run(context)

    assert result.final_answer == "你好，我可以帮你做什么？"
    assert result.stop_reason == "final_answer"
    assert TOOL_CALL_LOOP_STARTED in _event_types(backend)
    assert TOOL_CALL_LOOP_COMPLETED in _event_types(backend)


@pytest.mark.asyncio
async def test_agent_executor_runs_tool_then_final_answer():
    alias = _alias_for_tool_code("image_gen")
    media_payload = json.dumps({"images": ["https://cdn.example/img.png"]}, ensure_ascii=False)
    task_detail = TaskDetailResponse(
        taskId=101,
        status="SUCCESS",
        result=TaskResultResponse(
            resourceType="image",
            contentText=media_payload,
        ),
    )
    backend = FakeBackend(task_detail=task_detail)
    model = FakeModel(
        [
            ChatTurnResult(content="", tool_calls=[ChatToolCall(id="c1", name=alias, arguments={"prompt": "a cat"})]),
            ChatTurnResult(content="图片已生成", tool_calls=[]),
        ]
    )
    executor = AgentExecutor(backend, model)
    context = RunContext(
        runId=2,
        sessionId=3,
        userId=4,
        message="画一只猫",
        availableTools=[_image_tool()],
    )

    result = await executor.run(context)

    assert result.stop_reason == "tool_media_result"
    assert "https://cdn.example/img.png" in result.final_answer
    assert backend.completed_tool_calls
    assert TOOL_CALL_EXECUTED in _event_types(backend)
    assert len(model.calls) == 1


@pytest.mark.asyncio
async def test_agent_executor_retries_on_pseudo_tool_call_text():
    backend = FakeBackend()
    model = FakeModel(
        [
            ChatTurnResult(content="请稍等，正在调用工具生成 invoke name=\"image_gen\"", tool_calls=[]),
            ChatTurnResult(content="这是最终回答。", tool_calls=[]),
        ]
    )
    executor = AgentExecutor(backend, model)
    context = RunContext(runId=3, sessionId=4, userId=5, message="画一只猫", availableTools=[_image_tool()])

    result = await executor.run(context)

    assert result.final_answer == "这是最终回答。"
    assert len(model.calls) == 2


@pytest.mark.asyncio
async def test_agent_executor_pauses_for_confirmation_when_not_auto_callable():
    alias = _alias_for_tool_code("image_gen")
    backend = FakeBackend()
    model = FakeModel(
        [ChatTurnResult(content="", tool_calls=[ChatToolCall(id="c1", name=alias, arguments={"prompt": "a dog"})])]
    )
    executor = AgentExecutor(backend, model)
    tool = ToolDescriptor(
        toolCode="image_gen",
        toolName="Image Gen",
        autoCallable=False,
        inputSchema={
            "type": "object",
            "required": ["prompt"],
            "properties": {"prompt": {"type": "string"}},
        },
    )
    context = RunContext(
        runId=4,
        sessionId=5,
        userId=6,
        message="画一只狗",
        availableTools=[tool],
    )

    result = await executor.run(context)

    assert result.pending_confirmation is not None
    assert result.pending_confirmation.tool_code == "image_gen"
    assert TOOL_CONFIRMATION_REQUIRED in _event_types(backend)
    assert backend.checkpoint is not None
    checkpoint = json.loads(backend.checkpoint)
    assert checkpoint["kind"] == "agent_executor"


@pytest.mark.asyncio
async def test_deep_agents_default_path_uses_agent_executor_for_image_request():
    alias = _alias_for_tool_code("image_gen")
    media_payload = json.dumps({"images": ["https://cdn.example/generated.png"]}, ensure_ascii=False)
    task_detail = TaskDetailResponse(
        taskId=102,
        status="SUCCESS",
        result=TaskResultResponse(
            resourceType="image",
            contentText=media_payload,
        ),
    )
    backend = FakeBackend(task_detail=task_detail)
    model = FakeModel(
        [
            ChatTurnResult(content="", tool_calls=[ChatToolCall(id="c1", name=alias, arguments={"prompt": "a cat"})]),
        ]
    )
    engine = DeepAgentsRuntimeEngine(backend, model)
    context = RunContext(
        runId=10,
        sessionId=11,
        userId=12,
        message="帮我画一只猫",
        availableTools=[_image_tool()],
    )

    await engine.run(context)

    assert backend.completed_runs
    assert "https://cdn.example/generated.png" in backend.completed_runs[0].finalAnswer
    assert TOOL_CALL_LOOP_STARTED in _event_types(backend)
    completed_events = [event for event in backend.events if event.eventType == MESSAGE_COMPLETED]
    assert completed_events
