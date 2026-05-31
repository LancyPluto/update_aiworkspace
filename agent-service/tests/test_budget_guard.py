import pytest

from app.core.budget_guard import BudgetGuard, BudgetState
from app.core.schemas import RecentToolCallContext, RunContext, ToolDescriptor
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

    assert ("task", "kling_image_v21", {"userRequest": message}, "agent-run-16-tool-call-99") in backend.tool_calls
    assert not any(call[0] == "task" and call[1] == "deepseek_text_generation" for call in backend.tool_calls)
    intent_events = [event for event in backend.events if event[1] == "intent.detected"]
    assert intent_events[-1][3]["decisionSource"] == "llm_router"


@pytest.mark.asyncio
async def test_followup_image_request_inherits_previous_tool_arguments_and_dispatches_task():
    backend = FakeBackend(resource_type="IMAGE", content_text='{"images":[{"url":"/generated/images/124/image-1.png"}]}')
    router_json = (
        '{"intent":"tool_use","selectedToolCode":"kling_image_v21",'
        '"candidateToolCodes":["kling_image_v21"],"confidence":0.95,'
        '"reason":"followup_image_request","arguments":{},"missingFields":[]}'
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
    event_types = [event[1] for event in backend.events]
    assert "followup.inherited" in event_types
    assert "arguments.merged" in event_types


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

    assert ("task", "gpt_image", {"userRequest": message}, "agent-run-17-tool-call-99") in backend.tool_calls
    assert not any(call[0] == "task" and call[1] == "kling_image_to_video" for call in backend.tool_calls)
    intent_events = [event for event in backend.events if event[1] == "intent.detected"]
    assert intent_events[-1][3]["decisionSource"] == "llm_router"


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
