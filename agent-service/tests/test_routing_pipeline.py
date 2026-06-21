import pytest

from app.routing.policy_validator import PolicyValidator
from app.routing.types import Intent, IntentResult
from app.core.schemas import RunContext, ToolDescriptor


def _context(message: str) -> RunContext:
    return RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message=message,
        availableTools=[
            ToolDescriptor(
                toolCode="ofox_gpt_image2",
                toolName="GPT-image2",
                description="image generation",
                autoCallable=True,
            ),
        ],
    )


def test_policy_maps_disabled_file_analysis_to_unsupported():
    intent = IntentResult(
        intent=Intent.FILE_ANALYSIS,
        confidence=0.9,
        reason="llm_classifier",
    )
    result = PolicyValidator().validate(_context("总结附件"), intent)
    assert result.intent == Intent.UNSUPPORTED
    assert "capability_file_analysis_disabled" in result.reason


@pytest.mark.asyncio
async def test_decision_pipeline_runs_llm_for_attachment_reference():
    from app.routing.decision_pipeline import DecisionPipeline

    ctx = _context("参考我上传这张图片生成更自然的神情")
    from app.core.schemas import AgentFileContext

    ctx.agentFiles = [
        AgentFileContext(id=1, originalFilename="ref.png", contentType="image/png", status="READY"),
    ]

    async def llm_classifier(context, guard_intent):
        return IntentResult(
            intent=Intent.TOOL_USE,
            confidence=0.93,
            selectedToolCode="ofox_gpt_image2",
            candidateToolCodes=["ofox_gpt_image2"],
            reason="reference_image_generation",
            decisionSource="llm_classifier",
            attachmentUsage="reference_for_generation",
        )

    decision = await DecisionPipeline().decide(ctx, llm_classifier=llm_classifier)
    assert decision.intent == Intent.TOOL_USE
    assert decision.selectedToolCode == "ofox_gpt_image2"
    assert any(signal["source"] == "llm_classifier" for signal in decision.signals)
