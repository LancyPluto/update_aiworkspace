import pytest

from app.core.budget_guard import BudgetGuard, BudgetState
from app.core.schemas import RunContext, ToolDescriptor
from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine


class FakeBackend:
    def __init__(self, *, resource_type: str = "MARKDOWN", content_text: str = "# Generated copy"):
        self.events = []
        self.completed = []
        self.failed = []
        self.tool_calls = []
        self.memory_items = []
        self.memory_requests = []
        self.resource_type = resource_type
        self.content_text = content_text

    async def append_event(self, run_id, event):
        self.events.append((run_id, event.eventType, event.eventText, event.eventJson))

    async def create_tool_call(self, run_id, request):
        self.tool_calls.append((run_id, request.toolCode, request.argumentsJson))
        return type("ToolCall", (), {"id": 99, "toolCode": request.toolCode})()

    async def complete_tool_call(self, tool_call_id, request):
        self.tool_calls.append(("complete", tool_call_id, request.resultJson))

    async def fail_tool_call(self, tool_call_id, request):
        self.tool_calls.append(("fail", tool_call_id, request.errorCode, request.errorMessage))

    async def create_task(self, request):
        self.tool_calls.append(("task", request.toolCode, request.params, request.clientRequestId))
        return type("TaskStatus", (), {"taskId": 123, "status": "QUEUED"})

    async def get_task_detail(self, user_id, task_id):
        result = type("TaskResult", (), {"resourceType": self.resource_type, "contentText": self.content_text})
        return type(
            "TaskDetail",
            (),
            {
                "taskId": task_id,
                "status": "SUCCESS",
                "progress": 100,
                "progressMessage": "done",
                "errorCode": None,
                "errorMessage": None,
                "result": result,
            },
        )

    async def get_run_context(self, run_id):
        return type("RunContextStatus", (), {"status": "RUNNING"})

    async def cancel_task(self, user_id, task_id):
        self.tool_calls.append(("cancel_task", user_id, task_id))

    async def complete_run(self, run_id, request):
        self.completed.append((run_id, request.finalAnswer, request.intent))

    async def fail_run(self, run_id, request):
        self.failed.append((run_id, request.errorCode))

    async def retrieve_workspace_memory(self, workspace_id: int, query: str, limit: int):
        self.memory_requests.append((workspace_id, query, limit))
        return self.memory_items

    async def create_run_artifact(self, run_id: int, filename: str, content: str, content_type: str):
        return {"id": 31, "filename": filename, "contentType": content_type}


class FakeModel:
    def __init__(self, response: str = ""):
        self.response = response

    async def chat(self, messages, tools=None):
        return self.response

    @property
    def chat_stream(self):
        return None

    @property
    def model_name(self):
        return "test-model"


@pytest.mark.asyncio
async def test_graph_fails_when_selected_tool_exceeds_credit_budget():
    backend = FakeBackend()
    engine = DeepAgentsRuntimeEngine(backend, FakeModel())
    context = RunContext(
        runId=7,
        sessionId=1,
        userId=1,
        message="please use expensive_tool",
        creditBudget=2,
        availableTools=[
            ToolDescriptor(
                toolCode="expensive_tool",
                toolName="Expensive Tool",
                description="expensive_tool copywriting",
                estimatedCreditCost=5,
                autoCallable=True,
            )
        ],
    )

    await engine.run_confirmed_tool(context, "expensive_tool")

    assert backend.tool_calls == []
    assert backend.completed == []
    assert backend.failed == [(7, "AGENT_RUN_BUDGET_EXCEEDED")]


@pytest.mark.asyncio
async def test_confirmed_tool_passthrough_tool_output_even_when_summary_model_hallucinates():
    backend = FakeBackend()
    engine = DeepAgentsRuntimeEngine(
        backend,
        FakeModel(response="好的！已根据你提供的信息生成了一份种草风格的文案。"),
    )
    context = RunContext(
        runId=10,
        sessionId=1,
        userId=1,
        message="产品/服务名称：五一肩颈护理套餐",
        creditBudget=20,
        availableTools=[
            ToolDescriptor(
                toolCode="video_tool",
                toolName="Video Tool",
                description="video generation",
                estimatedCreditCost=3,
                autoCallable=True,
            )
        ],
    )

    await engine.run_confirmed_tool(context, "video_tool")

    assert backend.completed == [(10, "# Generated copy", "tool_use")]
    completed_events = [event for event in backend.events if event[1] == "message.completed"]
    assert completed_events[-1][2] == "# Generated copy"


@pytest.mark.asyncio
async def test_confirmed_tool_uses_tool_output_when_summary_model_returns_empty():
    backend = FakeBackend()
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=""))
    context = RunContext(
        runId=9,
        sessionId=1,
        userId=1,
        message="use video_tool",
        creditBudget=20,
        availableTools=[
            ToolDescriptor(
                toolCode="video_tool",
                toolName="Video Tool",
                description="video generation",
                estimatedCreditCost=3,
                autoCallable=True,
            )
        ],
    )

    await engine.run_confirmed_tool(context, "video_tool")

    assert backend.completed == [(9, "# Generated copy", "tool_use")]
    completed_events = [event for event in backend.events if event[1] == "message.completed"]
    assert completed_events[-1][2] == "# Generated copy"


@pytest.mark.asyncio
async def test_media_tool_output_emits_single_completed_event_without_delta_fanout():
    media_result = '{"provider":"kling_video","model":"kling-v2-1","images":[{"url":"/generated/images/79/image-1.png"}]}'
    backend = FakeBackend(resource_type="IMAGE", content_text=media_result)
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=""))
    context = RunContext(
        runId=14,
        sessionId=1,
        userId=1,
        message="generate an image",
        creditBudget=20,
        availableTools=[
            ToolDescriptor(
                toolCode="kling_image_v21",
                toolName="Kling Image",
                description="image generation",
                estimatedCreditCost=3,
                autoCallable=True,
            )
        ],
    )

    await engine.run_confirmed_tool(context, "kling_image_v21")

    message_events = [event for event in backend.events if event[1].startswith("message.")]
    assert [(event[1], event[2]) for event in message_events] == [("message.completed", media_result)]
    assert backend.completed == [(14, media_result, "tool_use")]


@pytest.mark.asyncio
async def test_general_chat_empty_answer_still_completes_run():
    backend = FakeBackend()
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=""))
    context = RunContext(
        runId=11,
        sessionId=1,
        userId=1,
        message="你好",
        creditBudget=5,
        availableTools=[],
    )

    await engine.run(context)

    assert backend.failed == []
    assert backend.completed == [(11, "抱歉，本次未能生成有效回复，请换个说法或补充更多信息后再试。", "general_chat")]


@pytest.mark.asyncio
async def test_direct_image_generation_request_executes_even_without_auto_callable_flag():
    backend = FakeBackend()
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response="不应该走普通问答"))
    context = RunContext(
        runId=12,
        sessionId=1,
        userId=1,
        message="我要生成一张漫展写真照片",
        creditBudget=20,
        availableTools=[
            ToolDescriptor(
                toolCode="kling_image_v21",
                toolName="可灵生图 V2.1",
                description="高质量图片生成，适合照片、写真、海报、文生图",
                estimatedCreditCost=3,
                autoCallable=False,
            )
        ],
    )

    await engine.run(context)

    assert ("task", "kling_image_v21", {"userRequest": "我要生成一张漫展写真照片"}, "agent-run-12-tool-call-99") in backend.tool_calls
    assert backend.completed == [(12, "# Generated copy", "tool_use")]


@pytest.mark.asyncio
async def test_image_generation_request_executes_when_tool_is_auto_callable():
    backend = FakeBackend()
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=""))
    context = RunContext(
        runId=13,
        sessionId=1,
        userId=1,
        message="我要生成一张漫展写真照片",
        creditBudget=20,
        availableTools=[
            ToolDescriptor(
                toolCode="kling_image_v21",
                toolName="可灵生图 V2.1",
                description="高质量图片生成，适合照片、写真、海报、文生图",
                estimatedCreditCost=3,
                autoCallable=True,
            )
        ],
    )

    await engine.run(context)

    assert ("task", "kling_image_v21", {"userRequest": "我要生成一张漫展写真照片"}, "agent-run-13-tool-call-99") in backend.tool_calls
    assert backend.completed == [(13, "# Generated copy", "tool_use")]


@pytest.mark.asyncio
async def test_visual_cosplay_shoot_request_prefers_image_tool_over_text_tool():
    backend = FakeBackend()
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=""))
    message = "生成科比布莱恩特穿着海贼王的大将披风cos黄猿的日常远景拍摄"
    context = RunContext(
        runId=15,
        sessionId=1,
        userId=1,
        message=message,
        creditBudget=20,
        availableTools=[
            ToolDescriptor(
                toolCode="deepseek_text_generation",
                toolName="文本生成-DeepSeek-V4-flash",
                description="文本生成，适合文案、标题、脚本、通用问答",
                estimatedCreditCost=1,
                autoCallable=True,
            ),
            ToolDescriptor(
                toolCode="kling_image_v21",
                toolName="可灵生图 V2.1",
                description="高质量图片生成，适合照片、写真、海报、文生图、摄影拍摄",
                estimatedCreditCost=3,
                autoCallable=True,
            ),
        ],
    )

    await engine.run(context)

    assert ("task", "kling_image_v21", {"userRequest": message}, "agent-run-15-tool-call-99") in backend.tool_calls
    assert not any(call[0] == "task" and call[1] == "deepseek_text_generation" for call in backend.tool_calls)
    assert backend.completed == [(15, "# Generated copy", "tool_use")]


@pytest.mark.asyncio
async def test_graph_fails_when_model_call_limit_is_exceeded():
    backend = FakeBackend()
    engine = DeepAgentsRuntimeEngine(
        backend,
        FakeModel(),
        budget_guard=BudgetGuard(max_model_calls=0),
    )
    context = RunContext(runId=8, sessionId=1, userId=1, message="tell me about this platform", creditBudget=20)

    await engine.run(context)

    assert backend.completed == []
    assert backend.failed == [(8, "AGENT_MODEL_CALL_LIMIT")]


def test_budget_state_records_consumed_credit_without_exceeding_budget():
    guard = BudgetGuard(model_call_cost=2)
    state = BudgetState(credit_budget=5)

    guard.reserve_model_call(state)

    assert state.model_calls == 1
    assert state.consumed_credits == 2
