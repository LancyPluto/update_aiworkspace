import pytest

from tests.conftest import disable_unified_graph_router

pytestmark = pytest.mark.usefixtures("disable_unified_graph_router")

from app.clients.model_client import ChatToolCall, ChatTurnResult
from app.core.event_types import (
    MESSAGE_COMPLETED,
    PLAN_UPDATED,
    SKILL_HYDRATED,
    TOOL_CALL_LOOP_COMPLETED,
    TOOL_CONFIRMATION_REQUIRED,
    TOOL_SELECTED,
)
from app.core.schemas import AgentSkillDescriptor, RunContext, TaskDetailResponse, TaskResultResponse, ToolDescriptor
from app.runtime.agent_graph import AgentGraphEngine


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
    def __init__(self, task_id, status="PENDING"):
        self.taskId = task_id
        self.status = status


class FakeBackend:
    def __init__(self, task_detail=None, task_details=None):
        self.events = []
        self.completed_runs = []
        self.failed_runs = []
        self.streaming = []
        self.tool_calls = []
        self.tasks = []
        self.completed_tool_calls = []
        self.failed_tool_calls = []
        self._task_detail = task_detail
        self._task_details = list(task_details) if task_details else None
        self.checkpoint = None
        self.checkpoint_saves = 0
        self.checkpoint_cleared = False

    async def save_graph_checkpoint(self, run_id, checkpoint_json):
        self.checkpoint = checkpoint_json
        self.checkpoint_saves += 1

    async def load_graph_checkpoint(self, run_id):
        return self.checkpoint

    async def clear_graph_checkpoint(self, run_id):
        self.checkpoint = None
        self.checkpoint_cleared = True

    async def append_event(self, run_id, event):
        self.events.append(event)

    async def complete_run(self, run_id, completion):
        self.completed_runs.append(completion)

    async def fail_run(self, run_id, failure):
        self.failed_runs.append(failure)

    async def upsert_streaming_answer(self, run_id, answer):
        self.streaming.append(answer)

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
        if self._task_details:
            return self._task_details.pop(0)
        return self._task_detail

    async def complete_tool_call(self, tool_call_id, payload):
        self.completed_tool_calls.append(payload)

    async def fail_tool_call(self, tool_call_id, payload):
        self.failed_tool_calls.append(payload)

    async def get_agent_skill(self, skill_code):
        if skill_code == "music_generation":
            return {
                "skillCode": "music_generation",
                "displayName": "音乐生成",
                "version": 1,
                "sopRules": (
                    "When customMode=false, prompt must be a compact music brief of 500 characters or fewer. "
                    "Use customMode=true for long lyrics. Do not copy melody, lyrics, vocal identity, or recordings."
                ),
                "examples": [
                    {
                        "user": "模仿 Owl City good time 风格，创作日系女团歌曲",
                        "tool": "suno_music",
                        "arguments": {
                            "customMode": False,
                            "prompt": "Upbeat Japanese girl-group electropop, original melody and lyrics only.",
                        },
                    }
                ],
            }
        raise AssertionError(f"unexpected skill lookup: {skill_code}")


def _event_types(backend):
    return [event.eventType for event in backend.events]


def _v2_image_tool():
    return ToolDescriptor(
        toolCode="gpt_image2",
        toolName="GPT Image 2",
        description="Generate an image",
        autoCallable=True,
        outputModality="image",
        inputSchema={
            "type": "object",
            "properties": {
                "operation": {"type": "string"},
                "generation_prompt": {"type": "string"},
                "base_image_ref": {"type": "string"},
                "references": {"type": "array"},
            },
        },
    )


def _suno_music_tool():
    return ToolDescriptor(
        toolCode="suno_music",
        toolName="Suno Music",
        description="Generate music with Suno",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "required": ["prompt"],
            "properties": {
                "prompt": {"type": "string"},
                "customMode": {"type": "boolean"},
                "style": {"type": "string"},
                "title": {"type": "string"},
                "model": {"type": "string", "enum": ["V5_5", "V5"]},
            },
        },
    )


@pytest.mark.asyncio
async def test_graph_engine_chat_only_completes_run():
    backend = FakeBackend()
    model = FakeModel([ChatTurnResult(content="你好，我可以帮你做什么？", tool_calls=[])])
    engine = AgentGraphEngine(backend, model)
    context = RunContext(runId=1, sessionId=2, userId=3, message="你好")

    await engine.run(context)

    assert backend.completed_runs, "run should complete"
    assert backend.completed_runs[0].finalAnswer == "你好，我可以帮你做什么？"
    assert backend.completed_runs[0].intent == "agent_graph"
    assert MESSAGE_COMPLETED in _event_types(backend)
    assert TOOL_CALL_LOOP_COMPLETED in _event_types(backend)
    assert not backend.failed_runs


@pytest.mark.asyncio
async def test_graph_engine_executes_auto_tool_then_finalizes():
    detail = TaskDetailResponse(
        taskId=100,
        status="SUCCESS",
        result=TaskResultResponse(resourceType="image", contentText="https://cdn.example/img.png"),
    )
    backend = FakeBackend(task_detail=detail)
    tool = ToolDescriptor(
        toolCode="image_gen",
        toolName="Image Generator",
        description="Generate an image",
        autoCallable=True,
        inputSchema={"type": "object", "required": ["prompt"], "properties": {"prompt": {"type": "string"}}},
    )
    context = RunContext(
        runId=2, sessionId=2, userId=3, message="生成一张猫的图片", availableTools=[tool], creditBudget=100,
    )
    alias = "agent_tool__image_gen"
    model = FakeModel([
        ChatTurnResult(content="", tool_calls=[ChatToolCall(id="c1", name=alias, arguments={"prompt": "a cat"})]),
        ChatTurnResult(content="图片已生成：https://cdn.example/img.png", tool_calls=[]),
    ])
    engine = AgentGraphEngine(backend, model)

    await engine.run(context)

    assert TOOL_SELECTED in _event_types(backend)
    assert backend.completed_tool_calls, "tool call should complete via backend bridge"
    assert backend.completed_runs[0].finalAnswer.startswith("图片已生成")
    # Two model turns: tool selection then finalization.
    assert len(model.calls) == 2


@pytest.mark.asyncio
async def test_graph_engine_forces_retry_when_schema_validation_reply_has_no_tool_call():
    backend = FakeBackend(
        task_detail=TaskDetailResponse(
            taskId=101,
            status="SUCCESS",
            result=TaskResultResponse(
                resourceType="image",
                contentText='{"images":["https://cdn.example/fixed.png"]}',
            ),
        )
    )
    context = RunContext(
        runId=22,
        sessionId=2,
        userId=3,
        message="同样生成一张",
        availableTools=[_v2_image_tool()],
        creditBudget=100,
    )
    alias = "agent_tool__gpt_image2"
    model = FakeModel(
        [
            ChatTurnResult(content="", tool_calls=[ChatToolCall(id="c1", name=alias, arguments={"operation": "generate"})]),
            ChatTurnResult(content="我需要补全 generation_prompt 后才能继续。", tool_calls=[]),
            ChatTurnResult(
                content="",
                tool_calls=[
                    ChatToolCall(
                        id="c2",
                        name=alias,
                        arguments={"operation": "generate", "generation_prompt": "完整视觉提示词，冷蓝电影海报，主体清晰。"},
                    )
                ],
            ),
            ChatTurnResult(content="图片已生成：https://cdn.example/fixed.png", tool_calls=[]),
        ]
    )
    engine = AgentGraphEngine(backend, model)

    await engine.run(context)

    assert backend.completed_runs[0].finalAnswer.startswith("图片已生成")
    assert backend.tool_calls[-1][1]["generation_prompt"] == "完整视觉提示词，冷蓝电影海报，主体清晰。"
    assert len(model.calls) == 4


@pytest.mark.asyncio
async def test_graph_engine_hydrates_music_skill_before_executing_suno_music():
    detail = TaskDetailResponse(
        taskId=1154,
        status="SUCCESS",
        result=TaskResultResponse(
            resourceType="AUDIO",
            contentText='{"audios":[{"url":"https://cdn.example/song.mp3"}]}',
        ),
    )
    backend = FakeBackend(task_detail=detail)
    context = RunContext(
        runId=54,
        sessionId=2,
        userId=3,
        message="模仿 Owl City good time 风格，创作一首日系女团歌曲",
        availableTools=[_suno_music_tool()],
        availableSkills=[
            AgentSkillDescriptor(
                skillCode="music_generation",
                displayName="音乐生成",
                description="Suno music generation",
                toolCodes=["suno_music", "suno", "music_generation"],
                version=1,
            )
        ],
        creditBudget=100,
    )
    model = FakeModel(
        [
            ChatTurnResult(
                content="",
                tool_calls=[
                    ChatToolCall(
                        id="c1",
                        name="agent_tool__suno_music",
                        arguments={
                            "customMode": False,
                            "prompt": "x" * 700,
                            "model": "V5_5",
                        },
                    )
                ],
            ),
            ChatTurnResult(
                content="",
                tool_calls=[
                    ChatToolCall(
                        id="c2",
                        name="agent_tool__suno_music",
                        arguments={
                            "customMode": False,
                            "prompt": (
                                "Upbeat Japanese girl-group electropop with bright synths, handclaps, sunny summer "
                                "energy, catchy chorus, clean youthful vocals, original melody and lyrics only."
                            ),
                            "model": "V5_5",
                        },
                    )
                ],
            ),
            ChatTurnResult(content="音乐已生成：https://cdn.example/song.mp3", tool_calls=[]),
        ]
    )
    engine = AgentGraphEngine(backend, model)

    await engine.run(context)

    assert SKILL_HYDRATED in _event_types(backend)
    assert len(backend.tool_calls) == 1
    tool_code, arguments = backend.tool_calls[0]
    assert tool_code == "suno_music"
    assert arguments["customMode"] is False
    assert len(arguments["prompt"]) <= 500
    assert "original melody and lyrics only" in arguments["prompt"]
    second_call_system_messages = [m.content for m in model.calls[1]["messages"] if m.role == "system"]
    assert any("music_generation" in text and "500 characters" in text for text in second_call_system_messages)
    assert backend.completed_runs[0].finalAnswer.startswith("音乐已生成")


@pytest.mark.asyncio
async def test_graph_engine_pauses_for_confirmation_on_non_auto_tool():
    backend = FakeBackend()
    tool = ToolDescriptor(
        toolCode="video_gen",
        toolName="Video Generator",
        description="Generate a video",
        autoCallable=False,
        estimatedCreditCost=50,
        inputSchema={"type": "object", "required": ["prompt"], "properties": {"prompt": {"type": "string"}}},
    )
    context = RunContext(
        runId=3, sessionId=2, userId=3, message="给我个东西", availableTools=[tool], creditBudget=100,
    )
    alias = "agent_tool__video_gen"
    model = FakeModel([
        ChatTurnResult(content="", tool_calls=[ChatToolCall(id="c1", name=alias, arguments={"prompt": "a dog"})]),
    ])
    engine = AgentGraphEngine(backend, model)

    await engine.run(context)

    assert TOOL_CONFIRMATION_REQUIRED in _event_types(backend)
    # Paused runs are not completed; backend marks WAITING on the confirmation event.
    assert not backend.completed_runs
    assert not backend.completed_tool_calls


@pytest.mark.asyncio
async def test_graph_engine_checkpoints_on_pause_and_resumes_on_confirm():
    detail = TaskDetailResponse(
        taskId=200,
        status="SUCCESS",
        result=TaskResultResponse(resourceType="video", contentText="https://cdn.example/v.mp4"),
    )
    backend = FakeBackend(task_detail=detail)
    tool = ToolDescriptor(
        toolCode="video_gen",
        toolName="Video Generator",
        description="Generate a video",
        autoCallable=False,
        estimatedCreditCost=50,
        inputSchema={"type": "object", "required": ["prompt"], "properties": {"prompt": {"type": "string"}}},
    )
    context = RunContext(runId=9, sessionId=2, userId=3, message="帮我处理一下这个需求", availableTools=[tool], creditBudget=100)
    alias = "agent_tool__video_gen"

    pause_model = FakeModel([
        ChatTurnResult(content="", tool_calls=[ChatToolCall(id="p1", name="update_plan", arguments={"steps": [{"title": "生成视频", "status": "in_progress"}]})]),
        ChatTurnResult(content="", tool_calls=[ChatToolCall(id="c1", name=alias, arguments={"prompt": "a dog"})]),
    ])
    engine = AgentGraphEngine(backend, pause_model)
    await engine.run(context)

    assert backend.checkpoint is not None, "a checkpoint should be persisted on pause"
    assert backend.checkpoint_saves == 1
    assert not backend.completed_runs

    # User confirms -> resume on a fresh engine instance (simulates a new request).
    resume_model = FakeModel([ChatTurnResult(content="视频已生成：https://cdn.example/v.mp4", tool_calls=[])])
    resume_engine = AgentGraphEngine(backend, resume_model)
    await resume_engine.run_confirmed_tool(context, "video_gen")

    assert backend.completed_tool_calls, "confirmed tool should execute on resume"
    assert backend.checkpoint_cleared
    assert backend.completed_runs[0].finalAnswer.startswith("视频已生成")


@pytest.mark.asyncio
async def test_graph_engine_backfills_artifact_for_chaining():
    detail = TaskDetailResponse(
        taskId=100,
        status="SUCCESS",
        result=TaskResultResponse(resourceType="image", contentText="https://cdn.example/cat.png"),
    )
    backend = FakeBackend(task_detail=detail)
    image_tool = ToolDescriptor(
        toolCode="image_gen", toolName="Image", description="img", autoCallable=True,
        inputSchema={"type": "object", "required": ["prompt"], "properties": {"prompt": {"type": "string"}}},
    )
    video_tool = ToolDescriptor(
        toolCode="video_gen", toolName="Video", description="vid", autoCallable=True,
        inputSchema={"type": "object", "required": ["image_url"], "properties": {
            "image_url": {"type": "string"},
            "prompt": {"type": "string"},
        }},
    )
    context = RunContext(
        runId=11, sessionId=2, userId=3, message="先生成猫图再做成视频",
        availableTools=[image_tool, video_tool], creditBudget=100,
    )
    model = FakeModel([
        ChatTurnResult(content="", tool_calls=[ChatToolCall(id="c1", name="agent_tool__image_gen", arguments={"prompt": "a cat"})]),
        # Note: model omits image_url; engine must backfill it from the image artifact.
        ChatTurnResult(content="", tool_calls=[ChatToolCall(id="c2", name="agent_tool__video_gen", arguments={"prompt": "make it move"})]),
        ChatTurnResult(content="完成", tool_calls=[]),
    ])
    engine = AgentGraphEngine(backend, model)

    await engine.run(context)

    # The second tool call (video_gen) must have received the chained image URL.
    video_args = [args for code, args in backend.tool_calls if code == "video_gen"]
    assert video_args, "video tool should have been invoked"
    assert video_args[0].get("image_url") == "https://cdn.example/cat.png"


@pytest.mark.asyncio
async def test_graph_engine_emits_plan_then_completes():
    backend = FakeBackend()
    model = FakeModel([
        ChatTurnResult(content="", tool_calls=[ChatToolCall(id="p1", name="update_plan", arguments={"steps": [
            {"title": "step one", "status": "in_progress"},
            {"title": "step two", "status": "pending"},
        ]})]),
        ChatTurnResult(content="计划已就绪", tool_calls=[]),
    ])
    engine = AgentGraphEngine(backend, model)
    context = RunContext(runId=4, sessionId=2, userId=3, message="帮我规划")

    await engine.run(context)

    assert PLAN_UPDATED in _event_types(backend)
    assert backend.completed_runs[0].finalAnswer == "计划已就绪"
