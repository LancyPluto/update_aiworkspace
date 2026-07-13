import json

import pytest

from tests.conftest import legacy_llm_router_settings

pytestmark = pytest.mark.usefixtures("legacy_llm_router_settings")

from app.clients.model_client import ChatToolCall, ChatTurnResult
from app.core.budget_guard import BudgetGuard, BudgetState
from app.core.schemas import RecentToolCallContext, RunContext, RuntimeSettings, ToolDescriptor, WorkspaceMemoryItem
from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine
from app.runtime.product_tool_call_loop import _alias_for_tool_code


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

    async def bind_tool_call_task(self, tool_call_id, task_id):
        self.tool_calls.append(("bind_task", tool_call_id, task_id))
        return type("ToolCall", (), {"id": tool_call_id, "taskId": task_id})()

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

    async def retrieve_workspace_memory(
        self,
        workspace_id: int,
        query: str,
        limit: int,
        view: str | None = None,
        memory_ids=None,
        session_id=None,
    ):
        self.memory_requests.append((workspace_id, query, limit, view))
        return self.memory_items

    async def create_run_artifact(self, run_id: int, filename: str, content: str, content_type: str):
        return {"id": 31, "filename": filename, "contentType": content_type}


class FakeModel:
    def __init__(self, response: str = ""):
        self.response = response
        self.calls = []
        self.turn_calls = []

    async def chat(self, messages, tools=None):
        self.calls.append((messages, tools))
        return self.response

    async def chat_turn(self, messages, tools=None, tool_choice=None):
        self.turn_calls.append((messages, tools, tool_choice))
        self.calls.append((messages, tools))
        if tool_choice == "none":
            return ChatTurnResult(content=self.response)
        tool_answer = _last_tool_content_text(messages)
        if tool_answer:
            return ChatTurnResult(content=tool_answer)
        parsed = self._parse_response()
        selected = parsed.get("selectedToolCode") if parsed else None
        if selected:
            return ChatTurnResult(
                tool_calls=[
                    ChatToolCall(
                        id="call_product",
                        name=_alias_for_tool_code(str(selected)),
                        arguments=parsed.get("arguments") or {"userRequest": _last_user_message(messages)},
                    )
                ],
                finish_reason="tool_calls",
            )
        if self.response:
            return ChatTurnResult(content=self.response)
        tool_name = _first_product_tool_name(tools)
        if tool_name and _looks_like_generation_request(_last_user_message(messages)):
            return ChatTurnResult(
                tool_calls=[
                    ChatToolCall(
                        id="call_product",
                        name=tool_name,
                        arguments={"userRequest": _last_user_message(messages)},
                    )
                ],
                finish_reason="tool_calls",
            )
        return ChatTurnResult(content="")

    def _parse_response(self):
        try:
            parsed = json.loads(self.response)
        except Exception:
            return None
        return parsed if isinstance(parsed, dict) else None

    @property
    def chat_stream(self):
        return None

    @property
    def model_name(self):
        return "test-model"


def _last_user_message(messages) -> str:
    for message in reversed(messages or []):
        role = getattr(message, "role", None)
        content = getattr(message, "content", None)
        if isinstance(message, dict):
            role = message.get("role")
            content = message.get("content")
        if str(role).lower() == "user":
            return str(content or "")
    return ""


def _last_tool_content_text(messages) -> str:
    for message in reversed(messages or []):
        role = getattr(message, "role", None)
        content = getattr(message, "content", None)
        if isinstance(message, dict):
            role = message.get("role")
            content = message.get("content")
        if str(role).lower() != "tool":
            continue
        try:
            parsed = json.loads(str(content or ""))
        except Exception:
            return str(content or "")
        data = parsed.get("data") if isinstance(parsed, dict) else None
        content_text = data.get("contentText") if isinstance(data, dict) else None
        if content_text:
            return str(content_text)
        result_text = parsed.get("result") if isinstance(parsed, dict) else None
        if isinstance(result_text, str) and result_text:
            return result_text
        return str(content or "")
    return ""


def _task_call_for(backend, tool_code: str):
    for call in backend.tool_calls:
        if call[0] == "task" and call[1] == tool_code:
            return call
    raise AssertionError(f"task call for {tool_code!r} not found: {backend.tool_calls!r}")


def _first_product_tool_name(tools) -> str | None:
    for tool in tools or []:
        function = tool.get("function", {}) if isinstance(tool, dict) else {}
        name = function.get("name")
        if not name:
            continue
        if name in {"finish", "memory_add", "memory_replace", "memory_remove", "expand_tool"}:
            continue
        return str(name)
    return None


def _looks_like_generation_request(message: str) -> bool:
    text = (message or "").lower()
    return any(token in text for token in ("生成", "画", "海报", "图片", "照片", "cosplay", "image", "use "))


class FakeProductToolModel(FakeModel):
    def __init__(self, tool_name: str | None = None, arguments: dict | None = None, response: str = ""):
        super().__init__(response=response)
        self.tool_name = tool_name
        self.arguments = arguments or {}
        self.turn_calls = []

    async def chat_turn(self, messages, tools=None, tool_choice=None):
        self.turn_calls.append((messages, tools, tool_choice))
        if not self.tool_name:
            return ChatTurnResult(content="")
        return ChatTurnResult(
            tool_calls=[
                ChatToolCall(
                    id="call_product",
                    name=self.tool_name,
                    arguments=self.arguments,
                )
            ],
            finish_reason="tool_calls",
        )


@pytest.mark.asyncio
async def test_graph_dispatches_selected_tool_when_tool_cost_exceeds_agent_run_budget():
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

    assert any(
        call[0] == 7 and call[1] == "expensive_tool" and call[2]["userRequest"] == "please use expensive_tool"
        for call in backend.tool_calls
    )
    task_call = _task_call_for(backend, "expensive_tool")
    assert task_call[2]["userRequest"] == "please use expensive_tool"
    assert task_call[3] == "agent-run-7-tool-call-99"
    assert backend.completed
    assert backend.failed == []


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

    assert ("bind_task", 99, 123) in backend.tool_calls
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
    message = "我要生成一张漫展写真照片"
    router_json = (
        '{"intent":"tool_use","selectedToolCode":"kling_image_v21",'
        '"candidateToolCodes":["kling_image_v21"],"confidence":0.9,'
        '"reason":"image_request","arguments":{"userRequest":"'
        + message
        + '"}}'
    )
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=router_json))
    context = RunContext(
        runId=12,
        sessionId=1,
        userId=1,
        message=message,
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

    task_call = _task_call_for(backend, "kling_image_v21")
    assert task_call[2]["userRequest"] == message
    assert task_call[3] == "agent-run-12-tool-call-99"
    assert backend.completed == [(12, "# Generated copy", "tool_use")]


@pytest.mark.asyncio
async def test_image_generation_request_executes_when_tool_is_auto_callable():
    backend = FakeBackend()
    message = "我要生成一张漫展写真照片"
    router_json = (
        '{"intent":"tool_use","selectedToolCode":"kling_image_v21",'
        '"candidateToolCodes":["kling_image_v21"],"confidence":0.9,'
        '"reason":"image_request","arguments":{"userRequest":"'
        + message
        + '"}}'
    )
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=router_json))
    context = RunContext(
        runId=13,
        sessionId=1,
        userId=1,
        message=message,
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

    task_call = _task_call_for(backend, "kling_image_v21")
    assert task_call[2]["userRequest"] == message
    assert task_call[3] == "agent-run-13-tool-call-99"
    assert backend.completed == [(13, "# Generated copy", "tool_use")]


@pytest.mark.asyncio
async def test_llm_router_routes_explicit_image_request():
    backend = FakeBackend(resource_type="IMAGE", content_text='{"images":[{"url":"/generated/images/24/image-1.png"}]}')
    message = "张雪峰的技能应该融合马拉松和巧乐兹元素"
    router_json = (
        '{"intent":"tool_use","selectedToolCode":"ofox_gpt_image2",'
        '"candidateToolCodes":["ofox_gpt_image2"],"confidence":0.92,'
        '"reason":"image_request","arguments":{"userRequest":"'
        + message
        + '"}}'
    )
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=router_json))
    context = RunContext(
        runId=24,
        sessionId=1,
        userId=1,
        message=message,
        creditBudget=20,
        availableTools=[
            ToolDescriptor(
                toolCode="ofox_gpt_image2",
                toolName="GPT-image2.0",
                description="图片生成工具，支持文生图、写真、海报、插画",
                estimatedCreditCost=3,
                autoCallable=True,
                hints={"modality": "image_generation"},
            ),
        ],
    )

    await engine.run(context)

    task_call = _task_call_for(backend, "ofox_gpt_image2")
    assert task_call[2]["userRequest"] == message
    assert task_call[3] == "agent-run-24-tool-call-99"
    requested_events = [event for event in backend.events if event[1] == "tool_call.requested"]
    assert requested_events


@pytest.mark.asyncio
@pytest.mark.parametrize("message", ["你是谁", "刚才那张图是哪个工具做的？"])
async def test_llm_router_general_chat_skips_tool_dispatch(message):
    backend = FakeBackend()
    router_json = (
        '{"intent":"general_chat","selectedToolCode":null,'
        '"candidateToolCodes":[],"confidence":0.9,"reason":"meta_or_greeting"}'
    )
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=router_json))
    context = RunContext(
        runId=26,
        sessionId=1,
        userId=1,
        message=message,
        creditBudget=20,
        availableTools=[
            ToolDescriptor(
                toolCode="kling_image_v21",
                toolName="可灵生图 V2.1",
                description="图片生成，写真，海报，文生图",
                estimatedCreditCost=3,
                autoCallable=True,
            ),
        ],
    )

    await engine.run(context)

    assert not any(call[0] == "task" for call in backend.tool_calls)
    assert backend.completed[-1][2] == "general_chat"


@pytest.mark.asyncio
async def test_visual_cosplay_shoot_request_prefers_image_tool_over_text_tool():
    backend = FakeBackend()
    message = "生成科比布莱恩特穿着海贼王的大将披风cos黄猿的日常远景拍摄"
    router_json = (
        '{"intent":"tool_use","selectedToolCode":"kling_image_v21",'
        '"candidateToolCodes":["kling_image_v21"],"confidence":0.93,'
        '"reason":"image_request","arguments":{"userRequest":"'
        + message
        + '"}}'
    )
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=router_json))
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

    task_call = _task_call_for(backend, "kling_image_v21")
    assert task_call[2]["userRequest"] == message
    assert task_call[3] == "agent-run-15-tool-call-99"
    assert not any(call[0] == "task" and call[1] == "deepseek_text_generation" for call in backend.tool_calls)
    assert backend.completed == [(15, "# Generated copy", "tool_use")]


@pytest.mark.asyncio
async def test_llm_router_can_select_tool_when_rule_match_is_weak():
    backend = FakeBackend()
    router_json = (
        '{"intent":"tool_use","selectedToolCode":"kling_image_v21",'
        '"candidateToolCodes":["kling_image_v21"],"confidence":0.92,'
        '"reason":"用户要做主视觉画面","clarifyingQuestion":null}'
    )
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=router_json))
    message = "帮我做一个赛博风主视觉，人物站在霓虹街头"
    context = RunContext(
        runId=16,
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

    task_call = _task_call_for(backend, "kling_image_v21")
    assert task_call[2]["userRequest"] == message
    assert task_call[3] == "agent-run-16-tool-call-99"
    assert not any(call[0] == "task" and call[1] == "deepseek_text_generation" for call in backend.tool_calls)
    requested_events = [event for event in backend.events if event[1] == "tool_call.requested"]
    assert requested_events


@pytest.mark.asyncio
async def test_followup_image_request_inherits_previous_tool_arguments_and_dispatches_task():
    backend = FakeBackend(resource_type="IMAGE", content_text='{"images":[{"url":"/generated/images/124/image-1.png"}]}')
    router_json = (
        '{"intent":"tool_use","selectedToolCode":"kling_image_v21",'
        '"candidateToolCodes":["kling_image_v21"],"confidence":0.95,'
        '"reason":"followup_image_request",'
        '"arguments":{"userRequest":"给科比也来一张","prompt":"科比在漫展穿着火影忍者晓袍的远景写真","aspectRatio":"3:4"},'
        '"missingFields":[]}'
    )
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=router_json))
    context = RunContext(
        runId=18,
        sessionId=1,
        userId=1,
        message="给科比也来一张",
        creditBudget=20,
        recentToolCalls=[
            RecentToolCallContext(
                id=77,
                runId=17,
                toolCode="kling_image_v21",
                taskId=123,
                argumentsJson={
                    "prompt": "石原里美在漫展穿着火影忍者晓袍的远景写真",
                    "aspectRatio": "3:4",
                },
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=["/generated/images/123/image-1.png"],
            )
        ],
        availableTools=[
            ToolDescriptor(
                toolCode="kling_image_v21",
                toolName="可灵生图 V2.1",
                description="图片生成，写真，海报",
                estimatedCreditCost=3,
                autoCallable=False,
                inputSchema={
                    "type": "object",
                    "required": ["prompt"],
                    "properties": {
                        "prompt": {"type": "string", "title": "提示词"},
                        "aspectRatio": {"type": "string", "title": "比例"},
                    },
                },
            ),
        ],
    )

    await engine.run(context)

    task_calls = [call for call in backend.tool_calls if call[0] == "task"]
    assert len(task_calls) == 1
    params = task_calls[0][2]
    assert params["aspectRatio"] == "3:4"
    assert "科比" in params["prompt"]
    assert params["userRequest"] == "给科比也来一张"


@pytest.mark.asyncio
async def test_llm_router_is_primary_for_media_tool_selection():
    backend = FakeBackend(resource_type="IMAGE", content_text='{"images":[{"url":"/generated/image.png"}]}')
    router_json = (
        '{"intent":"tool_use","selectedToolCode":"gpt_image",'
        '"candidateToolCodes":["gpt_image"],"confidence":0.95,'
        '"reason":"image_output_request","clarifyingQuestion":null}'
    )
    model = FakeModel(response=router_json)
    engine = DeepAgentsRuntimeEngine(backend, model)
    message = "生成一张08年一家人除夕夜合影的老照片"
    context = RunContext(
        runId=17,
        sessionId=1,
        userId=1,
        message=message,
        creditBudget=20,
        availableTools=[
            ToolDescriptor(
                toolCode="kling_image_to_video",
                toolName="可灵生视频V2.6",
                description="图生视频，视频生成，image_to_video",
                estimatedCreditCost=5,
                autoCallable=True,
            ),
            ToolDescriptor(
                toolCode="gpt_image",
                toolName="GPT-image2.0",
                description="图片生成，照片生成，文生图，image generation",
                estimatedCreditCost=3,
                autoCallable=True,
            ),
        ],
    )

    await engine.run(context)

    task_call = _task_call_for(backend, "gpt_image")
    assert task_call[2]["userRequest"] == message
    assert task_call[3] == "agent-run-17-tool-call-99"
    assert not any(call[0] == "task" and call[1] == "kling_image_to_video" for call in backend.tool_calls)
    requested_events = [event for event in backend.events if event[1] == "tool_call.requested"]
    assert requested_events


@pytest.mark.asyncio
async def test_tool_use_applies_workspace_memory_quality_preference():
    backend = FakeBackend(resource_type="IMAGE", content_text='{"images":[{"url":"/generated/image.png"}]}')
    backend.memory_items = [
        WorkspaceMemoryItem(
            id=11,
            title="GPT 生图质量偏好",
            content="记住以后 gpt 生图一定用质量 low，不要默认 high。",
            memoryType="preference",
            score=10,
        )
    ]
    engine = DeepAgentsRuntimeEngine(backend, FakeModel())
    context = RunContext(
        runId=18,
        sessionId=1,
        userId=1,
        workspaceId=7,
        message="生成一张电影海报",
        creditBudget=20,
        preferredToolCode="gpt_image2",
        availableTools=[
            ToolDescriptor(
                toolCode="gpt_image2",
                toolName="GPT-image2",
                description="GPT 图片生成工具，图片生成，文生图",
                autoCallable=True,
                inputSchema={
                    "type": "object",
                    "properties": {
                        "prompt": {"type": "string", "title": "提示词"},
                        "quality": {"type": "string", "enum": ["low", "medium", "high"], "default": "high"},
                    },
                },
            ),
        ],
    )

    await engine.run(context)

    task_calls = [call for call in backend.tool_calls if call[0] == "task"]
    assert len(task_calls) == 1
    params = task_calls[0][2]
    assert params["quality"] == "low"
    assert backend.memory_requests[0] == (7, "生成一张电影海报", 10, "router")
    frozen_events = [event for event in backend.events if event[1] == "memory.context_frozen"]
    assert frozen_events
    assert frozen_events[0][3]["source"] == "agent_executor"


@pytest.mark.asyncio
async def test_tool_router_prompt_includes_workspace_memory_for_preference_requests():
    backend = FakeBackend(resource_type="IMAGE", content_text='{"images":[{"url":"/generated/image.png"}]}')
    backend.memory_items = [
        WorkspaceMemoryItem(
            id=21,
            title="用户审美画像",
            content="审美关键词：新中式、克制、留白、真实摄影。",
            memoryType="preference",
            score=0,
        )
    ]
    router_json = (
        '{"intent":"tool_use","selectedToolCode":"gpt_image2",'
        '"candidateToolCodes":["gpt_image2"],"confidence":0.95,'
        '"reason":"image_preference_request","arguments":{"userRequest":"根据我的喜好重构图片"}}'
    )
    model = FakeModel(response=router_json)
    engine = DeepAgentsRuntimeEngine(backend, model)
    context = RunContext(
        runId=20,
        sessionId=1,
        userId=1,
        workspaceId=7,
        message="根据我的喜好重构图片",
        creditBudget=20,
        availableTools=[
            ToolDescriptor(
                toolCode="gpt_image2",
                toolName="GPT-image2",
                description="GPT 图片生成工具，图片生成，文生图",
                autoCallable=True,
            ),
        ],
    )

    await engine.run(context)

    model_prompt = "\n".join(message.content for message in model.turn_calls[0][0])
    assert "frozen memory snapshot" in model_prompt
    assert "新中式" in model_prompt
    assert backend.memory_requests[0] == (7, "根据我的喜好重构图片", 10, "chat")


@pytest.mark.asyncio
async def test_tool_use_merges_workspace_memory_into_generation_prompt():
    backend = FakeBackend(resource_type="IMAGE", content_text='{"images":[{"url":"/generated/image.png"}]}')
    backend.memory_items = [
        WorkspaceMemoryItem(
            id=22,
            title="用户审美画像",
            content="审美关键词：新中式、克制、留白、真实摄影，避免夸张动漫质感。",
            memoryType="preference",
            score=0,
        )
    ]
    router_json = (
        '{"intent":"tool_use","selectedToolCode":"gpt_image2",'
        '"candidateToolCodes":["gpt_image2"],"confidence":0.95,'
        '"reason":"image_preference_request","arguments":{"userRequest":"根据我的喜好重构图片"}}'
    )
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=router_json))
    context = RunContext(
        runId=21,
        sessionId=1,
        userId=1,
        workspaceId=7,
        message="根据我的喜好重构图片",
        creditBudget=20,
        availableTools=[
            ToolDescriptor(
                toolCode="gpt_image2",
                toolName="GPT-image2",
                description="GPT 图片生成工具，图片生成，文生图",
                autoCallable=True,
                inputSchema={
                    "type": "object",
                    "properties": {
                        "prompt": {"type": "string", "title": "提示词"},
                        "quality": {"type": "string", "enum": ["low", "medium", "high"], "default": "high"},
                    },
                },
            ),
        ],
    )

    await engine.run(context)

    task_calls = [call for call in backend.tool_calls if call[0] == "task"]
    assert len(task_calls) == 1
    params = task_calls[0][2]
    assert "新中式" in params["prompt"]
    assert "长期偏好参考" in params["prompt"]


@pytest.mark.asyncio
async def test_empty_tool_memory_does_not_emit_frozen_snapshot():
    backend = FakeBackend(resource_type="IMAGE", content_text='{"images":[{"url":"/generated/image.png"}]}')
    router_json = (
        '{"intent":"tool_use","selectedToolCode":"gpt_image2",'
        '"candidateToolCodes":["gpt_image2"],"confidence":0.95,'
        '"reason":"image_request","arguments":{"userRequest":"生成一张图"}}'
    )
    engine = DeepAgentsRuntimeEngine(backend, FakeModel(response=router_json))
    context = RunContext(
        runId=22,
        sessionId=1,
        userId=1,
        workspaceId=7,
        message="生成一张图",
        creditBudget=20,
        availableTools=[
            ToolDescriptor(
                toolCode="gpt_image2",
                toolName="GPT-image2",
                description="GPT 图片生成工具，图片生成，文生图",
                autoCallable=True,
            ),
        ],
    )

    await engine.run(context)

    retrieved_events = [event for event in backend.events if event[1] == "memory.retrieved"]
    frozen_events = [event for event in backend.events if event[1] == "memory.context_frozen"]
    assert retrieved_events
    assert retrieved_events[0][3]["count"] == 0
    assert retrieved_events[0][3]["view"] == "router"
    assert frozen_events == []


@pytest.mark.asyncio
async def test_confirmed_tool_execution_applies_workspace_memory_quality_preference():
    backend = FakeBackend(resource_type="IMAGE", content_text='{"images":[{"url":"/generated/image.png"}]}')
    backend.memory_items = [
        WorkspaceMemoryItem(
            id=12,
            title="GPT 生图质量偏好",
            content="GPT 生图一律使用 low 档。",
            memoryType="preference",
            score=10,
        )
    ]
    engine = DeepAgentsRuntimeEngine(backend, FakeModel())
    context = RunContext(
        runId=19,
        sessionId=1,
        userId=1,
        workspaceId=7,
        message="生成一张电影海报",
        creditBudget=20,
        availableTools=[
            ToolDescriptor(
                toolCode="gpt_image2",
                toolName="GPT-image2",
                description="GPT 图片生成工具，图片生成，文生图",
                autoCallable=False,
                inputSchema={
                    "type": "object",
                    "properties": {
                        "prompt": {"type": "string", "title": "提示词"},
                        "quality": {"type": "string", "enum": ["low", "medium", "high"], "default": "high"},
                    },
                },
            ),
        ],
    )

    await engine.run_confirmed_tool(context, "gpt_image2")

    task_calls = [call for call in backend.tool_calls if call[0] == "task"]
    assert len(task_calls) == 1
    assert task_calls[0][2]["quality"] == "low"
    merged_events = [event for event in backend.events if event[1] == "arguments.merged"]
    assert merged_events[-1][3]["arguments"]["quality"] == "low"


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


@pytest.mark.asyncio
async def test_unsupported_rule_can_fallback_to_chat_without_copywriting_helper():
    backend = FakeBackend()
    engine = DeepAgentsRuntimeEngine(backend, FakeModel("可以，我先给你一版工作流文案。"))
    context = RunContext(
        runId=29,
        sessionId=1,
        userId=1,
        message="帮我写一个工作流介绍文案",
        creditBudget=20,
    )

    await engine.run(context)

    assert backend.completed == [(29, "可以，我先给你一版工作流文案。", "general_chat")]
    assert not any(call[0] == "task" for call in backend.tool_calls)


@pytest.mark.asyncio
async def test_runtime_settings_do_not_mutate_shared_engine_limits():
    backend = FakeBackend()
    engine = DeepAgentsRuntimeEngine(backend, FakeModel("ok"))
    original_model_limit = engine.budget_guard.max_model_calls
    original_tool_limit = engine.budget_guard.max_tool_calls
    context = RunContext(
        runId=18,
        sessionId=1,
        userId=1,
        message="tell me about this platform",
        creditBudget=20,
        runtimeSettings=RuntimeSettings(
            maxModelCalls=1,
            maxToolCalls=2,
        ),
    )

    await engine.run(context)

    assert engine.budget_guard.max_model_calls == original_model_limit
    assert engine.budget_guard.max_tool_calls == original_tool_limit
    runtime_events = [event for event in backend.events if event[1] == "runtime_settings.applied"]
    assert runtime_events[-1][3]["maxModelCalls"] == 1
    assert runtime_events[-1][3]["maxToolCalls"] == 2


def test_budget_state_records_consumed_credit_without_exceeding_budget():
    guard = BudgetGuard(model_call_cost=2)
    state = BudgetState(credit_budget=5)

    guard.reserve_model_call(state)

    assert state.model_calls == 1
    assert state.consumed_credits == 2
