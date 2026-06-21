from app.core.schemas import AgentFileContext, RunContext
from app.runtime.prompt_policy import (
    PromptMode,
    reference_edit_prompt,
    resolve_prompt_mode,
    should_skip_tool_memory_injection,
)
from app.tools.registry import ToolDescriptor


def _image_tool() -> ToolDescriptor:
    return ToolDescriptor(
        toolCode="gpt_image2",
        toolName="GPT Image",
        description="image generation",
        inputSchema={"properties": {"prompt": {"type": "string"}, "image": {"type": "array"}}},
        fields=[],
        hints={"capability": "image_generation"},
    )


def test_reference_edit_delta_for_uploaded_image_and_short_adjustment():
    context = RunContext(
        runId=1,
        sessionId=10,
        userId=2,
        message="调整图片，人物画面占比更大一些，人物的服饰色调更加贴近画面环境风格",
        agentFiles=[
            AgentFileContext(
                id=1,
                originalFilename="ref.png",
                contentType="image/png",
                fileSize=100,
                downloadUrl="/generated/uploads/ref.png",
                status="READY",
            )
        ],
    )
    tool = _image_tool()

    assert resolve_prompt_mode(context, tool) == PromptMode.REFERENCE_EDIT_DELTA
    assert should_skip_tool_memory_injection(context, tool) is True
    prompt = reference_edit_prompt(context.message)
    assert "调整图片" in prompt
    assert "张继科" not in prompt
    assert "科比" not in prompt


def test_text_to_image_when_no_reference_image():
    context = RunContext(
        runId=2,
        sessionId=11,
        userId=2,
        message="生成一张赛博朋克城市夜景",
    )
    tool = _image_tool()
    assert resolve_prompt_mode(context, tool) == PromptMode.TEXT_TO_IMAGE
    assert should_skip_tool_memory_injection(context, tool) is False
