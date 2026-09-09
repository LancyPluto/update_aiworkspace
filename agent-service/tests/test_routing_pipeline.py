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
