from app.core.intent_router import Intent, IntentResult
from app.core.schemas import RunContext, ToolDescriptor, ToolPreference
from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine


def test_user_tool_preference_skips_confirmation_even_for_expensive_tool():
    engine = DeepAgentsRuntimeEngine(backend_client=object(), model_client=object())
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        workspaceId=1,
        message="生成一张图片",
        toolPreferences=[ToolPreference(toolCode="gpt_image2", autoCallEnabled=True)],
    )
    tool = ToolDescriptor(
        toolCode="gpt_image2",
        toolName="GPT-image2",
        estimatedCreditCost=50,
        autoCallable=False,
    )
    intent = IntentResult(
        intent=Intent.TOOL_USE,
        confidence=1.0,
        selectedToolCode="gpt_image2",
        reason="test_preference_override",
        requiresConfirmation=True,
    )

    assert engine._should_auto_call(context, tool, intent=intent) is True
