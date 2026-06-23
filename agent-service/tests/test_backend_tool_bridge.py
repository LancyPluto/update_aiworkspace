"""BackendToolBridge: required-field detection must not be short-circuited by placeholder defaults."""

import pytest

from app.config import settings
from app.core.schemas import AgentFileContext, ChatMessage, RecentToolCallContext, ReferenceMention, RunContext, RuntimeSettings, TaskDetailResponse, ToolDescriptor
from app.runtime.tool_orchestrator import _raise_image_prompt_schema_validation_if_needed
from app.tools.backend_tool import BackendToolBridge, ToolExecutionError, _with_attached_file_defaults, compile_v2_lite_image_task_params, enforce_locked_field_defaults, finalize_generation_arguments


def _xiaohongshu_like_schema() -> dict:
    return {
        "type": "object",
        "required": ["productName", "targetCustomer", "style", "sellingPoints"],
        "properties": {
            "userRequest": {"type": "string"},
            "productName": {"type": "string", "title": "产品/服务名称"},
            "targetCustomer": {"type": "string", "title": "目标用户"},
            "style": {"type": "string", "title": "风格"},
            "sellingPoints": {"type": "string", "title": "卖点"},
            "extraInfo": {"type": "string"},
        },
    }


def _v2_lite_image_tool() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="gpt_image2",
        toolName="GPT-image2",
        description="图片生成",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "properties": {
                "operation": {"type": "string"},
                "generation_prompt": {"type": "string"},
                "base_image_ref": {"type": "string"},
                "base_prompt": {"type": "string"},
                "modification_prompt": {"type": "string"},
                "references": {"type": "array"},
            },
        },
    )


def test_missing_required_skips_xiaohongshu_placeholders():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="小红书",
        description="种草",
        autoCallable=True,
        inputSchema=_xiaohongshu_like_schema(),
    )
    ctx = RunContext(runId=1, sessionId=1, userId=1, message="帮我写一篇小红书种草笔记")
    missing = bridge.missing_required_arguments(ctx, tool)
    assert set(missing) == {"productName", "targetCustomer", "style", "sellingPoints"}


def test_missing_respects_labeled_fields():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="小红书",
        description="种草",
        autoCallable=True,
        inputSchema=_xiaohongshu_like_schema(),
    )
    msg = "帮我写笔记 productName: 防晒喷雾 targetCustomer: 年轻女性 style: 种草 sellingPoints: 清爽不油腻"
    ctx = RunContext(runId=1, sessionId=1, userId=1, message=msg)
    assert bridge.missing_required_arguments(ctx, tool) == []


def test_v2_lite_multi_reference_requires_structured_routing():
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="为图2女性角色生成图1动作构图风格的电影海报",
        referenceMentions=[
            ReferenceMention(token="@图1", refLabel="@图片1-pose.png", url="/generated/uploads/pose.png", kind="image"),
            ReferenceMention(token="@图2", refLabel="@图片2-face.png", url="/generated/uploads/face.png", kind="image"),
        ],
    )

    with pytest.raises(ToolExecutionError) as exc:
        _raise_image_prompt_schema_validation_if_needed(
            ctx,
            _v2_lite_image_tool(),
            {
                "operation": "composite",
                "generation_prompt": "A cinematic poster with a young woman.",
            },
        )

    assert exc.value.error_code == "SCHEMA_VALIDATION"
    assert "structured multi-reference routing" in str(exc.value)


def test_v2_lite_multi_reference_accepts_structured_routing():
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="为图2女性角色生成图1动作构图风格的电影海报",
        referenceMentions=[
            ReferenceMention(token="@图1", refLabel="@图片1-pose.png", url="/generated/uploads/pose.png", kind="image"),
            ReferenceMention(token="@图2", refLabel="@图片2-face.png", url="/generated/uploads/face.png", kind="image"),
        ],
    )

    _raise_image_prompt_schema_validation_if_needed(
        ctx,
        _v2_lite_image_tool(),
        {
            "operation": "composite",
            "generation_prompt": "A cinematic poster using the second reference for identity and the first reference for pose.",
            "references": [
                {"id": "pose_ref_1", "role": "composition_ref", "source_ref": "[当前参考图_1]", "notes": "pose and framing only"},
                {"id": "face_ref_1", "role": "face_ref", "source_ref": "[当前参考图_2]", "notes": "character identity only"},
            ],
        },
    )


def test_v2_lite_compiler_adds_reference_role_preservation_block():
    params = compile_v2_lite_image_task_params(
        {
            "operation": "composite",
            "generation_prompt": "A cinematic movie poster.",
            "base_image_ref": "https://example.test/identity.png",
            "references": [
                {"id": "pose_ref_1", "role": "composition_ref", "source_ref": "https://example.test/pose.png", "notes": "pose only"},
                {"id": "face_ref_1", "role": "face_ref", "source_ref": "https://example.test/face.png", "notes": "identity only"},
            ],
        }
    )

    assert "REFERENCE ROUTING:" in params["prompt"]
    assert "STRICT REFERENCE ROLE PRESERVATION:" in params["prompt"]
    assert "Do not swap identity/face references" in params["prompt"]
    assert params["reference_images"] == ["https://example.test/face.png", "https://example.test/pose.png"]
    assert "base_image_url" not in params


def test_execute_arguments_fill_xiaohongshu_placeholders():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="小红书",
        description="种草",
        autoCallable=True,
        inputSchema=_xiaohongshu_like_schema(),
    )
    ctx = RunContext(runId=1, sessionId=1, userId=1, message="随便写点")
    args = bridge.build_arguments(ctx, tool, apply_placeholder_defaults=True)
    for key in ("productName", "targetCustomer", "style", "sellingPoints"):
        assert args.get(key), f"missing filled {key}"


def test_defaultable_execution_required_field_does_not_trigger_clarification():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="image_generation",
        toolName="图片生成",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "required": ["aspectRatio"],
            "properties": {
                "userRequest": {"type": "string"},
                "aspectRatio": {
                    "type": "string",
                    "title": "画面比例",
                    "default": "3:4",
                    "x-user-required": False,
                    "x-agent-fill-strategy": "default",
                },
            },
        },
        fields=[
            {
                "fieldKey": "aspectRatio",
                "fieldName": "画面比例",
                "fieldType": "radio",
                "required": True,
                "executionRequired": True,
                "userRequired": False,
                "defaultValue": "3:4",
                "agentFillStrategy": "default",
                "riskLevel": "LOW",
            },
        ],
    )
    ctx = RunContext(runId=1, sessionId=1, userId=1, message="我要生成一张石原里美的图片")

    assert bridge.missing_required_arguments(ctx, tool) == []
    assert bridge.build_arguments(ctx, tool)["aspectRatio"] == "3:4"


def test_generation_tool_timeout_uses_modality_specific_floor():
    bridge = BackendToolBridge(backend_client=None, timeout_seconds=120)  # type: ignore[arg-type]

    assert bridge._timeout_for_tool("ofox_gpt_image2") == 600
    assert bridge._timeout_for_tool("kling_image_to_video") == 900
    assert bridge._timeout_for_tool("suno_music") == 900
    assert bridge._timeout_for_tool("xiaohongshu_copywriting") == 120


def test_generation_tool_timeout_uses_run_level_runtime_settings_without_mutation():
    bridge = BackendToolBridge(backend_client=None, timeout_seconds=120, poll_interval_seconds=2.0)  # type: ignore[arg-type]
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="生成一张图片",
        runtimeSettings=RuntimeSettings(
            toolExecutionTimeoutSeconds=10,
            imageToolExecutionTimeoutSeconds=700,
            videoToolExecutionTimeoutSeconds=1200,
            musicToolExecutionTimeoutSeconds=1500,
            toolPollIntervalSeconds=0.01,
        ),
    )

    assert bridge._timeout_for_tool("ofox_gpt_image2", context) == 700
    assert bridge._timeout_for_tool("kling_image_to_video", context) == 1200
    assert bridge._timeout_for_tool("suno_music", context) == 1500
    assert bridge._timeout_for_tool("xiaohongshu_copywriting", context) == 10
    assert bridge.timeout_seconds == 120
    assert bridge.poll_interval_seconds == 2.0


def test_generation_prompt_field_is_derived_from_short_user_request():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="image_generation",
        toolName="图片生成",
        description="根据提示词生成图片",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "required": ["prompt", "aspectRatio", "count"],
            "properties": {
                "userRequest": {"type": "string"},
                "prompt": {
                    "type": "string",
                    "title": "提示词",
                    "description": "图片生成提示词",
                    "x-user-required": False,
                    "x-agent-fill-strategy": "derive",
                },
                "aspectRatio": {
                    "type": "string",
                    "title": "画面比例",
                    "enum": ["1:1", "3:4", "16:9"],
                    "x-user-required": False,
                    "x-agent-fill-strategy": "default",
                },
                "count": {
                    "type": "integer",
                    "title": "生成张数",
                    "x-user-required": False,
                    "x-agent-fill-strategy": "default",
                },
            },
        },
        fields=[
            {
                "fieldKey": "prompt",
                "fieldName": "提示词",
                "required": True,
                "executionRequired": True,
                "userRequired": False,
                "agentFillStrategy": "derive",
                "riskLevel": "LOW",
            },
            {
                "fieldKey": "aspectRatio",
                "fieldName": "画面比例",
                "required": True,
                "executionRequired": True,
                "userRequired": False,
                "agentFillStrategy": "default",
                "riskLevel": "LOW",
            },
            {
                "fieldKey": "count",
                "fieldName": "生成张数",
                "required": True,
                "executionRequired": True,
                "userRequired": False,
                "agentFillStrategy": "default",
                "riskLevel": "LOW",
            },
        ],
    )
    ctx = RunContext(runId=1, sessionId=1, userId=1, message="生成美女")

    assert bridge.missing_required_arguments(ctx, tool) == []
    args = bridge.build_arguments(ctx, tool, apply_placeholder_defaults=True)
    assert "生成美女" in args["prompt"]
    assert len(args["prompt"]) > len("生成美女")
    assert args["aspectRatio"] == "1:1"
    assert args["count"] == 1


def test_generation_prompt_includes_explicit_reference_roles():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="ofox_gpt_image2",
        toolName="GPT-image2",
        description="图片生成",
        autoCallable=True,
        outputModality="image",
        inputSchema={
            "type": "object",
            "properties": {
                "prompt": {"type": "string"},
                "image": {"type": "array", "items": {"type": "string"}},
            },
        },
    )
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="为 @立绘_夕_1 生成 @图片5 风格的二次元插画",
        referenceMentions=[
            ReferenceMention(token="@立绘_夕_1", refLabel="@图片1-立绘_夕_1.png", url="/generated/uploads/char.png"),
            ReferenceMention(token="@图片5", refLabel="@图片5-style_ref.png", url="/generated/images/style.png"),
        ],
    )

    args = bridge.build_arguments(ctx, tool, apply_placeholder_defaults=True)

    assert "主体身份参考" in args["prompt"]
    assert "@图片1-立绘_夕_1.png" in args["prompt"]
    assert "@图片5-style_ref.png" in args["prompt"]
    assert "不可交换" in args["prompt"]


def test_finalize_generation_arguments_restores_roles_after_llm_overwrite():
    tool = ToolDescriptor(
        toolCode="ofox_gpt_image2",
        toolName="GPT-image2",
        description="图片生成",
        autoCallable=True,
        outputModality="image",
        inputSchema={
            "type": "object",
            "properties": {
                "prompt": {"type": "string"},
                "image": {"type": "array", "items": {"type": "string"}},
            },
        },
    )
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="为 @立绘_夕_1 生成 @图片5 风格的二次元插画",
        referenceMentions=[
            ReferenceMention(token="@立绘_夕_1", refLabel="@图片1-立绘_夕_1.png", url="/generated/uploads/char.png"),
            ReferenceMention(token="@图片5", refLabel="@图片5-style_ref.png", url="/generated/images/style.png"),
        ],
    )
    overwritten = {
        "prompt": "二次元插画风格，白发少女角色，柔和光影，精细上色，干净线条，唯美背景",
        "userRequest": ctx.message,
        "image": ["/generated/uploads/char.png", "/generated/images/style.png"],
    }

    finalized = finalize_generation_arguments(ctx, tool, overwritten)

    assert "主体身份参考" in finalized["prompt"]
    assert "不可交换" in finalized["prompt"]
    assert "@图片1-立绘_夕_1.png" in finalized["prompt"]


def test_generation_prompt_uses_action_composition_role_for_second_reference():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="ofox_gpt_image2",
        toolName="GPT-image2",
        description="图片生成",
        autoCallable=True,
        outputModality="image",
        inputSchema={
            "type": "object",
            "properties": {
                "prompt": {"type": "string"},
                "image": {"type": "array", "items": {"type": "string"}},
            },
        },
    )
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="让 @图片1 中的人物做出 @图片2 的动作，保持形象、画风不变，构图改成图2的样式",
        positionalPrompt="让 {asset-a} 中的人物做出 {asset-b} 的动作，保持形象、画风不变，构图改成图2的样式",
        referenceMentions=[
            ReferenceMention(token="@图片1", refLabel="@图片1-人物.png", assetKey="asset-a", url="/generated/uploads/char.png"),
            ReferenceMention(token="@图片2", refLabel="@图片2-动作.png", assetKey="asset-b", url="/generated/uploads/pose.png"),
        ],
    )

    args = bridge.build_arguments(ctx, tool, apply_placeholder_defaults=True)

    assert "动作与构图参考" in args["prompt"]
    assert "动作和构图主要来自2号参考" in args["prompt"]
    assert "风格主要来自2号参考" not in args["prompt"]


def test_finalize_generation_arguments_is_idempotent():
    tool = ToolDescriptor(
        toolCode="ofox_gpt_image2",
        toolName="GPT-image2",
        autoCallable=True,
        outputModality="image",
        inputSchema={"type": "object", "properties": {"prompt": {"type": "string"}}},
    )
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="参考 @图片1-角色 生成",
        referenceMentions=[
            ReferenceMention(token="@图片1-角色", refLabel="@图片1-角色.png", url="/generated/uploads/char.png"),
        ],
    )
    args = {"prompt": "基础描述。参考图角色约束：@图片1-角色.png 为主体参考图。"}

    once = finalize_generation_arguments(ctx, tool, args)
    twice = finalize_generation_arguments(ctx, tool, once)

    assert once["prompt"] == twice["prompt"]


def _v2_lite_image_tool() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="ofox_gpt_image2",
        toolName="GPT-image2",
        autoCallable=True,
        outputModality="image",
        inputSchema={
            "type": "object",
            "required": ["operation"],
            "properties": {
                "operation": {"type": "string", "enum": ["generate", "edit", "variation", "composite"], "default": "generate"},
                "generation_prompt": {"type": "string", "x-user-required": False, "x-agent-fill-strategy": "derive"},
                "base_image_ref": {"type": "string", "x-user-required": False, "x-agent-fill-strategy": "llm"},
                "base_prompt": {"type": "string", "x-user-required": False, "x-agent-fill-strategy": "llm"},
                "modification_prompt": {"type": "string", "x-user-required": False, "x-agent-fill-strategy": "llm"},
                "references": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "id": {"type": "string"},
                            "role": {"type": "string"},
                            "source_ref": {"type": "string"},
                            "notes": {"type": "string"},
                        },
                    },
                },
                "aspect_ratio": {"type": "string", "enum": ["auto", "1:1", "16:9"], "default": "auto"},
                "count": {"type": "integer", "default": 1},
            },
        },
    )


def test_v2_lite_generate_does_not_backend_compose_generation_prompt():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = _v2_lite_image_tool()
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="生成一张冷蓝电影海报",
        recentToolCalls=[
            RecentToolCallContext(
                id=7,
                toolCode="gpt_image2",
                taskId=701,
                argumentsJson={"prompt": "previous prompt"},
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=["/generated/images/701/image-1.png"],
            )
        ],
    )

    args = bridge.build_arguments(ctx, tool, apply_placeholder_defaults=True)

    assert args["operation"] == "generate"
    assert "generation_prompt" not in args
    assert args["aspect_ratio"] == "auto"
    assert args["count"] == 1


def test_v2_lite_edit_finalize_does_not_backend_compose_prompt_fields():
    tool = _v2_lite_image_tool()
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="把刚刚那张图背景换成赛博朋克，但保留图1的脸",
        recentToolCalls=[
            RecentToolCallContext(
                id=7,
                toolCode="gpt_image2",
                taskId=701,
                argumentsJson={"generation_prompt": "original cinematic poster prompt"},
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=["/generated/images/701/image-1.png"],
            )
        ],
    )

    finalized = finalize_generation_arguments(ctx, tool, {"operation": "edit"})

    assert finalized["operation"] == "edit"
    assert finalized["base_image_ref"] == "latest_generated_image.url"
    assert finalized["base_prompt"] == ""
    assert finalized["modification_prompt"] == ""


def test_v2_lite_edit_finalize_sanitizes_base_prompt_and_namespaces_current_refs():
    tool = _v2_lite_image_tool()
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="刚刚的人物模特换成这张参考图中的女性，人物自然融入场景 @图片1",
        referenceMentions=[
            ReferenceMention(
                token="@图片1",
                refLabel="@图片1-new-face.png",
                assetKey="asset-new-face",
                url="/generated/uploads/new-face.png",
                kind="image",
                source="current_turn",
            )
        ],
        recentToolCalls=[
            RecentToolCallContext(
                id=7,
                toolCode="gpt_image2",
                taskId=701,
                argumentsJson={
                    "generation_prompt": (
                        "冷蓝电影海报，柔焦真实光影。 "
                        "参考图角色约束（必须严格执行，不可交换）：1) 主体身份参考：@图片1。"
                        "最终输出需明确保证：主体来自1号参考。"
                    )
                },
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=["/generated/images/701/image-1.png"],
            )
        ],
    )

    finalized = finalize_generation_arguments(
        ctx,
        tool,
        {
            "operation": "edit",
            "base_prompt": (
                "冷蓝电影海报，柔焦真实光影。 "
                "参考图角色约束（必须严格执行，不可交换）：1) 主体身份参考：@图片1。"
                "最终输出需明确保证：主体来自1号参考。"
            ),
            "generation_prompt": "LLM incorrectly rewrote a full prompt",
            "prompt": "legacy toxic prompt",
            "modification_prompt": "将人物模特替换为 @图片1 中的女性，人物自然融入场景。",
            "references": [
                {"id": "face_ref_1", "role": "face_ref", "source_ref": "@图片1", "notes": "使用 @图片1 的脸"}
            ],
        },
    )

    assert "参考图角色约束" not in finalized["base_prompt"]
    assert "冷蓝电影海报" in finalized["base_prompt"]
    assert finalized["modification_prompt"] == "将人物模特替换为 [当前参考图_1] 中的女性，人物自然融入场景。"
    assert finalized["references"][0]["source_ref"] == "[当前参考图_1]"
    assert finalized["references"][0]["notes"] == "使用 [当前参考图_1] 的脸"
    assert "generation_prompt" not in finalized
    assert "prompt" not in finalized


def test_compile_v2_lite_image_task_params_generate_uses_physical_prompt_and_references():
    from app.tools.backend_tool import compile_v2_lite_image_task_params

    params = compile_v2_lite_image_task_params(
        {
            "operation": "generate",
            "generation_prompt": "冷蓝电影海报，柔焦真实光影。",
            "references": [
                {"id": "style_ref_1", "role": "style_ref", "source_ref": "/style.png", "notes": "只参考色调"},
                {"id": "face_ref_1", "role": "face_ref", "source_ref": "/face.png", "notes": "保留这张脸"},
            ],
            "aspect_ratio": "9:16",
            "count": 2,
            "quality": "low",
            "routing_notes": "图1管脸",
        }
    )

    assert params["prompt"].startswith("冷蓝电影海报")
    assert "REFERENCE ROUTING:" in params["prompt"]
    assert "face_ref_1 (/face.png): face_ref" in params["prompt"]
    assert params["reference_images"] == ["/face.png", "/style.png"]
    assert params["aspectRatio"] == "9:16"
    assert params["count"] == 2
    assert params["quality"] == "low"
    assert "generation_prompt" not in params
    assert "references" not in params


def test_compile_v2_lite_image_task_params_edit_compiles_base_and_delta():
    from app.tools.backend_tool import compile_v2_lite_image_task_params

    params = compile_v2_lite_image_task_params(
        {
            "operation": "edit",
            "base_image_ref": "/generated/images/701/image-1.png",
            "base_prompt": "冷蓝电影海报，柔焦真实光影。STRICT PRESERVATION: old rule",
            "modification_prompt": "将人物模特替换为参考图中的女性。",
            "references": [
                {"id": "face_ref_1", "role": "face_ref", "source_ref": "/new-face.png", "notes": "新脸部参考"}
            ],
        }
    )

    assert params["base_image_url"] == "/generated/images/701/image-1.png"
    assert params["reference_images"] == ["/new-face.png"]
    assert params["prompt"].startswith("冷蓝电影海报，柔焦真实光影。")
    assert "old rule" not in params["prompt"]
    assert "EDIT INSTRUCTION:" in params["prompt"]
    assert "将人物模特替换为参考图中的女性。" in params["prompt"]
    assert "Allow identity and face to change" in params["prompt"]
    assert "base_prompt" not in params
    assert "modification_prompt" not in params
    assert "base_image_ref" not in params


@pytest.mark.asyncio
async def test_execute_stores_v2_arguments_but_dispatches_physical_image_params():
    class Backend:
        def __init__(self) -> None:
            self.tool_call_args = None
            self.task_params = None
            self.events = []
            self.completed = []

        async def create_tool_call(self, run_id: int, request):
            self.tool_call_args = request.argumentsJson
            return type("ToolCall", (), {"id": 77, "toolCode": request.toolCode})()

        async def create_task(self, request):
            self.task_params = request.params
            return type("TaskStatus", (), {"taskId": 177, "status": "QUEUED"})()

        async def bind_tool_call_task(self, tool_call_id: int, task_id: int):
            return type("ToolCall", (), {"id": tool_call_id, "taskId": task_id})()

        async def append_event(self, run_id: int, event) -> None:
            self.events.append((run_id, event.eventType, event.eventJson))

        async def get_task_detail(self, user_id: int, task_id: int) -> TaskDetailResponse:
            return TaskDetailResponse(
                taskId=task_id,
                status="SUCCESS",
                progress=100,
                progressMessage="done",
                result={"resourceType": "IMAGE", "contentText": "image url"},
            )

        async def get_run_context(self, run_id: int) -> RunContext:
            return RunContext(runId=run_id, sessionId=1, userId=1, message="generate image", status="RUNNING")

        async def complete_tool_call(self, tool_call_id: int, request) -> None:
            self.completed.append((tool_call_id, request.resultJson))

        async def fail_tool_call(self, tool_call_id: int, request) -> None:
            raise AssertionError("unexpected failure")

        async def cancel_task(self, user_id: int, task_id: int) -> None:
            raise AssertionError("unexpected cancel")

    backend = Backend()
    bridge = BackendToolBridge(backend_client=backend, timeout_seconds=1, poll_interval_seconds=0.01)  # type: ignore[arg-type]
    tool = _v2_lite_image_tool()
    context = RunContext(
        runId=88,
        sessionId=1,
        userId=7,
        message="生成图片 @图片1",
        status="RUNNING",
        referenceMentions=[
            ReferenceMention(
                token="@图片1",
                refLabel="@图片1-face.png",
                url="/generated/uploads/face.png",
                kind="image",
                source="current_turn",
            )
        ],
    )

    await bridge.execute_with_args(
        context,
        tool,
        {
            "operation": "generate",
            "generation_prompt": "冷蓝电影海报，柔焦真实光影。",
            "references": [{"id": "face_ref_1", "role": "face_ref", "source_ref": "[当前参考图_1]"}],
        },
    )

    assert backend.tool_call_args["generation_prompt"] == "冷蓝电影海报，柔焦真实光影。"
    assert "generation_prompt" not in backend.task_params
    assert backend.task_params["prompt"].startswith("冷蓝电影海报")
    assert len(backend.task_params["reference_images"]) == 1
    assert backend.task_params["reference_images"][0].endswith("/generated/uploads/face.png")
    assert backend.completed


def test_attached_ready_image_fills_reference_image_field():
    tool = ToolDescriptor(
        toolCode="ofox_gpt_image2",
        toolName="GPT-image2.0",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "properties": {
                "prompt": {"type": "string"},
                "referenceImageUrl": {"type": "string"},
            },
        },
    )
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="把这张作为参考图生成 Suno 吉祥物",
        agentFiles=[
            AgentFileContext(
                id=1,
                originalFilename="ref.jpg",
                contentType="image/jpeg",
                status="READY",
                downloadUrl="/api/v1/agent/sessions/1/files/1/content",
            )
        ],
    )

    args = _with_attached_file_defaults(ctx, tool, {"prompt": "Suno 吉祥物"})

    assert args["referenceImageUrl"].endswith("/api/v1/agent/sessions/1/files/1/content")


def test_user_required_field_still_triggers_clarification():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="account_binding",
        toolName="账号绑定",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "required": ["accountId"],
            "properties": {"accountId": {"type": "string", "title": "账号 ID", "x-user-required": True}},
        },
        fields=[
            {
                "fieldKey": "accountId",
                "fieldName": "账号 ID",
                "required": True,
                "executionRequired": True,
                "userRequired": True,
                "agentFillStrategy": "ask_user",
                "riskLevel": "HIGH",
            },
        ],
    )
    ctx = RunContext(runId=1, sessionId=1, userId=1, message="帮我绑定账号")

    assert bridge.missing_required_arguments(ctx, tool) == ["accountId"]


def test_missing_skipped_when_user_accepts_builtin_examples():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="小红书",
        description="种草",
        autoCallable=True,
        inputSchema=_xiaohongshu_like_schema(),
    )
    ctx = RunContext(runId=1, sessionId=1, userId=1, message="你全部按照你给的例子来输入，把输出结果给我")
    assert bridge.missing_required_arguments(ctx, tool) == []


def _moments_schema() -> dict:
    return {
        "type": "object",
        "required": ["topic", "targetAudience", "tone", "scene", "sellingPoints", "lengthLevel"],
        "properties": {
            "topic": {"type": "string", "title": "文案主题"},
            "targetAudience": {"type": "string", "title": "目标人群"},
            "tone": {"type": "string", "title": "文案风格"},
            "scene": {"type": "string", "title": "发布场景"},
            "sellingPoints": {"type": "string", "title": "核心卖点"},
            "lengthLevel": {"type": "string", "title": "文案长度"},
        },
    }


def test_build_arguments_merges_recent_user_followups_with_chinese_labels():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="moments_copywriting_generator",
        toolName="AI 朋友圈文案生成器",
        description="朋友圈文案",
        autoCallable=True,
        inputSchema=_moments_schema(),
    )
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="目标人群：久坐上班族、老会员、附近新客\n文案风格：亲切\n发布场景：活动宣传\n核心卖点：限时体验价、到店即用、适合上班族放松\n文案长度：短",
        history=[
            ChatMessage(role="user", content="给朋友圈生成一段新品文案"),
            ChatMessage(role="assistant", content="如果想使用「AI 朋友圈文案生成器」，请在同一条或下一条消息里按下面补充（可直接复制条目改写成你的内容）：\n\n• 文案主题：例如：周末肩颈放松活动"),
            ChatMessage(role="user", content="文案主题：周末肩颈放松活动"),
            ChatMessage(role="assistant", content="如果想使用「AI 朋友圈文案生成器」，请在同一条或下一条消息里按下面补充（可直接复制条目改写成你的内容）：\n\n• 目标人群：例如：久坐上班族"),
        ],
    )

    arguments = bridge.build_arguments(ctx, tool, apply_placeholder_defaults=False)

    assert arguments["topic"] == "周末肩颈放松活动"
    assert arguments["targetAudience"] == "久坐上班族、老会员、附近新客"
    assert arguments["tone"] == "亲切"
    assert arguments["scene"] == "活动宣传"
    assert arguments["sellingPoints"] == "限时体验价、到店即用、适合上班族放松"
    assert arguments["lengthLevel"] == "短"


def test_extract_strips_example_prefix_from_chinese_labels():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="AI 小红书文案生成器",
        description="种草",
        autoCallable=True,
        inputSchema=_xiaohongshu_like_schema(),
    )
    msg = (
        "产品/服务名称：例如：五一肩颈护理套餐\n"
        "• 目标用户：例如：年轻女性、宝妈\n"
        "• 文案风格：例如：种草\n"
        "• 核心卖点：例如：价格划算、效果明显"
    )
    ctx = RunContext(runId=1, sessionId=1, userId=1, message=msg)
    args = bridge.build_arguments(ctx, tool, apply_placeholder_defaults=False)
    assert args["productName"] == "五一肩颈护理套餐"
    assert args["targetCustomer"] == "年轻女性、宝妈"
    assert args["style"] == "种草"
    assert args["sellingPoints"] == "价格划算、效果明显"


def test_build_arguments_injects_uploaded_image_and_duration():
    bridge = BackendToolBridge(backend_client=None)  # type: ignore[arg-type]
    tool = ToolDescriptor(
        toolCode="kling_image_to_video_v3",
        toolName="可灵 V3 图生视频",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "required": ["image", "duration"],
            "properties": {
                "image": {"type": "string", "title": "首帧图片"},
                "duration": {"type": "string", "title": "时长"},
            },
        },
    )
    ctx = RunContext(
        runId=1,
        sessionId=9,
        userId=1,
        message="请用这张图片生成 5 秒视频",
        agentFiles=[
            AgentFileContext(
                id=12,
                originalFilename="frame.png",
                contentType="image/png",
                status="READY",
                extractedText="[用户已上传图片：frame.png]",
                downloadUrl="/api/v1/agent/sessions/9/files/12/content",
            )
        ],
    )
    args = bridge.build_arguments(ctx, tool, apply_placeholder_defaults=True)
    expected_image = f"{settings.backend_internal_base_url.rstrip('/')}/api/v1/agent/sessions/9/files/12/content"
    assert args["image"] == expected_image
    assert args["duration"] == "5"


@pytest.mark.asyncio
async def test_wait_for_task_keeps_polling_if_run_is_already_success():
    class Backend:
        def __init__(self) -> None:
            self.polls = 0

        async def get_run_context(self, run_id: int) -> RunContext:
            return RunContext(runId=run_id, sessionId=1, userId=1, message="generate image", status="SUCCESS")

        async def get_task_detail(self, user_id: int, task_id: int) -> TaskDetailResponse:
            self.polls += 1
            return TaskDetailResponse(
                taskId=task_id,
                status="SUCCESS",
                progress=100,
                progressMessage="done",
            )

    backend = Backend()
    bridge = BackendToolBridge(backend_client=backend, timeout_seconds=1, poll_interval_seconds=0.01)  # type: ignore[arg-type]
    context = RunContext(runId=9, sessionId=1, userId=1, message="generate image", status="RUNNING")

    detail = await bridge._wait_for_task(context, "image_generation", 71)

    assert detail.status == "SUCCESS"
    assert backend.polls == 1


@pytest.mark.asyncio
async def test_wait_for_task_reports_task_failure_before_run_abort():
    class Backend:
        async def get_run_context(self, run_id: int) -> RunContext:
            return RunContext(runId=run_id, sessionId=1, userId=1, message="generate image", status="FAILED")

        async def get_task_detail(self, user_id: int, task_id: int) -> TaskDetailResponse:
            return TaskDetailResponse(
                taskId=task_id,
                status="FAILED",
                progress=20,
                progressMessage="request failed",
                errorCode="MODEL_TIMEOUT",
                errorMessage="Connection to api.ofox.ai timed out",
            )

    bridge = BackendToolBridge(backend_client=Backend(), timeout_seconds=1, poll_interval_seconds=0.01)  # type: ignore[arg-type]
    context = RunContext(runId=70, sessionId=1, userId=1, message="generate image", status="RUNNING")

    detail = await bridge._wait_for_task(context, "ofox_gpt_image2", 84)

    assert detail.status == "FAILED"
    assert detail.errorCode == "MODEL_TIMEOUT"


@pytest.mark.asyncio
async def test_wait_for_task_returns_timeout_task_as_terminal():
    class Backend:
        async def get_task_detail(self, user_id: int, task_id: int) -> TaskDetailResponse:
            return TaskDetailResponse(
                taskId=task_id,
                status="TIMEOUT",
                progress=100,
                progressMessage="model timed out",
                errorCode="MODEL_TIMEOUT",
                errorMessage="model timed out",
            )

    bridge = BackendToolBridge(backend_client=Backend(), timeout_seconds=1, poll_interval_seconds=0.01)  # type: ignore[arg-type]
    context = RunContext(runId=70, sessionId=1, userId=1, message="generate image", status="RUNNING")

    detail = await bridge._wait_for_task(context, "ofox_gpt_image2", 84)

    assert detail.status == "TIMEOUT"
    assert detail.errorCode == "MODEL_TIMEOUT"


@pytest.mark.asyncio
async def test_wait_for_task_abort_message_includes_last_task_detail():
    class Backend:
        async def get_run_context(self, run_id: int) -> RunContext:
            return RunContext(runId=run_id, sessionId=1, userId=1, message="generate image", status="FAILED")

        async def get_task_detail(self, user_id: int, task_id: int) -> TaskDetailResponse:
            return TaskDetailResponse(
                taskId=task_id,
                status="PROCESSING",
                progress=30,
                progressMessage="waiting provider",
            )

        async def append_event(self, run_id: int, event) -> None:
            return None

    bridge = BackendToolBridge(backend_client=Backend(), timeout_seconds=1, poll_interval_seconds=0.01)  # type: ignore[arg-type]
    context = RunContext(runId=70, sessionId=1, userId=1, message="generate image", status="RUNNING")

    with pytest.raises(ToolExecutionError) as exc:
        await bridge._wait_for_task(context, "ofox_gpt_image2", 84)

    message = str(exc.value)
    assert "tool=ofox_gpt_image2" in message
    assert "taskId=84" in message
    assert "runStatus=FAILED" in message
    assert "taskStatus=PROCESSING" in message
    assert "waiting provider" in message


@pytest.mark.asyncio
async def test_execute_continues_when_task_binding_endpoint_is_missing():
    class Backend:
        def __init__(self) -> None:
            self.events = []
            self.completed = []
            self.failed = []
            self.cancelled = []
            self.bind_attempts = []

        async def create_tool_call(self, run_id: int, request):
            return type("ToolCall", (), {"id": 46, "toolCode": request.toolCode})()

        async def create_task(self, request):
            return type("TaskStatus", (), {"taskId": 130, "status": "QUEUED"})()

        async def bind_tool_call_task(self, tool_call_id: int, task_id: int):
            self.bind_attempts.append((tool_call_id, task_id))
            raise RuntimeError("status=404")

        async def append_event(self, run_id: int, event) -> None:
            self.events.append((run_id, event.eventType, event.eventJson))

        async def get_task_detail(self, user_id: int, task_id: int) -> TaskDetailResponse:
            return TaskDetailResponse(
                taskId=task_id,
                status="SUCCESS",
                progress=100,
                progressMessage="done",
                result={"resourceType": "IMAGE", "contentText": "image url"},
            )

        async def get_run_context(self, run_id: int) -> RunContext:
            return RunContext(runId=run_id, sessionId=1, userId=1, message="generate image", status="RUNNING")

        async def complete_tool_call(self, tool_call_id: int, request) -> None:
            self.completed.append((tool_call_id, request.resultJson))

        async def fail_tool_call(self, tool_call_id: int, request) -> None:
            self.failed.append((tool_call_id, request.errorCode, request.errorMessage))

        async def cancel_task(self, user_id: int, task_id: int) -> None:
            self.cancelled.append((user_id, task_id))

    backend = Backend()
    bridge = BackendToolBridge(backend_client=backend, timeout_seconds=1, poll_interval_seconds=0.01)  # type: ignore[arg-type]
    context = RunContext(runId=88, sessionId=1, userId=7, message="generate image", status="RUNNING")
    tool = ToolDescriptor(
        toolCode="ofox_gpt_image2",
        toolName="GPT-image2.0",
        autoCallable=True,
        inputSchema={"type": "object", "properties": {"userRequest": {"type": "string"}}},
    )

    result = await bridge.execute_with_args(context, tool, {"userRequest": context.message})

    assert result["taskId"] == 130
    assert backend.bind_attempts == [(46, 130)]
    assert backend.failed == []
    assert backend.cancelled == []
    assert backend.completed
    dispatch = next(evt for evt in backend.events if evt[1] == "tool.task_dispatched")
    assert dispatch[2]["taskId"] == 130
    assert dispatch[2]["bindStatus"] == "FAILED"


@pytest.mark.asyncio
async def test_execute_propagates_task_error_code_to_runtime_boundary():
    class Backend:
        def __init__(self) -> None:
            self.failed = []

        async def create_tool_call(self, run_id: int, request):
            return type("ToolCall", (), {"id": 47, "toolCode": request.toolCode})()

        async def create_task(self, request):
            return type("TaskStatus", (), {"taskId": 131, "status": "QUEUED"})()

        async def bind_tool_call_task(self, tool_call_id: int, task_id: int):
            return type("ToolCall", (), {"id": tool_call_id, "taskId": task_id})()

        async def append_event(self, run_id: int, event) -> None:
            pass

        async def get_task_detail(self, user_id: int, task_id: int) -> TaskDetailResponse:
            return TaskDetailResponse(
                taskId=task_id,
                status="FAILED",
                progress=100,
                progressMessage="risk rejected",
                errorCode="MODEL_RISK_CONTROL_REJECTED",
                errorMessage="Failure to pass the risk control system",
            )

        async def get_run_context(self, run_id: int) -> RunContext:
            return RunContext(runId=run_id, sessionId=1, userId=1, message="generate video", status="RUNNING")

        async def fail_tool_call(self, tool_call_id: int, request) -> None:
            self.failed.append((tool_call_id, request.errorCode, request.errorMessage))

        async def cancel_task(self, user_id: int, task_id: int) -> None:
            pass

    backend = Backend()
    bridge = BackendToolBridge(backend_client=backend, timeout_seconds=1, poll_interval_seconds=0.01)  # type: ignore[arg-type]
    context = RunContext(runId=89, sessionId=1, userId=7, message="generate video", status="RUNNING")
    tool = ToolDescriptor(toolCode="kling_image_to_video", toolName="可灵生视频V2.6", autoCallable=True)

    with pytest.raises(ToolExecutionError) as exc:
        await bridge.execute_with_args(context, tool, {"prompt": context.message})

    assert exc.value.error_code == "MODEL_RISK_CONTROL_REJECTED"
    assert backend.failed[0][1] == "MODEL_RISK_CONTROL_REJECTED"


def test_enforce_locked_field_defaults_overrides_router_quality():
    tool = ToolDescriptor(
        toolCode="gpt_image2",
        toolName="GPT-image2",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "required": ["quality"],
            "properties": {
                "quality": {
                    "type": "string",
                    "enum": ["low", "medium", "high"],
                    "default": "low",
                    "x-agent-fill-strategy": "default",
                }
            },
        },
        fields=[
            {
                "fieldKey": "quality",
                "fieldName": "质量",
                "fieldType": "select",
                "defaultValue": "low",
                "agentFillStrategy": "default",
                "options": {"options": [{"label": "低", "value": "low"}, {"label": "高", "value": "high"}]},
            }
        ],
    )
    locked = enforce_locked_field_defaults(
        tool,
        {"quality": "high", "prompt": "test"},
        user_message="帮我生成一张写真",
    )
    assert locked["quality"] == "low"
    assert locked["prompt"] == "test"


def test_enforce_locked_field_defaults_respects_explicit_user_quality():
    tool = ToolDescriptor(
        toolCode="gpt_image2",
        toolName="GPT-image2",
        autoCallable=True,
        fields=[
            {
                "fieldKey": "quality",
                "fieldName": "质量",
                "fieldType": "select",
                "defaultValue": "low",
                "agentFillStrategy": "default",
                "options": {"options": [{"label": "低", "value": "low"}, {"label": "高", "value": "high"}]},
            }
        ],
    )
    locked = enforce_locked_field_defaults(
        tool,
        {"quality": "medium"},
        user_message="这次用high生成",
    )
    assert locked["quality"] == "high"
