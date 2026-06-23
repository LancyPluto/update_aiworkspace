import pytest

from app.core.budget_guard import BudgetGuard, BudgetState
from app.core.schemas import RecentToolCallContext, RunContext, ToolDescriptor
from app.runtime.tool_orchestrator import ToolOrchestrator
from app.tools.backend_tool import ToolExecutionError


class FakeBridge:
    def __init__(self):
        self.executed = False
        self.arguments = None

    def build_arguments(self, context, tool, *, apply_placeholder_defaults=True):
        return {"userRequest": context.message}

    def conversation_argument_text(self, context):
        return context.message

    async def enrich_arguments(self, message, tool, existing_args=None, context=None):
        return existing_args or {}

    async def execute_with_args(self, context, tool, arguments):
        self.executed = True
        self.arguments = arguments
        return {"success": True}


@pytest.mark.asyncio
async def test_tool_orchestrator_missing_execution_arguments_does_not_dispatch_bridge():
    bridge = FakeBridge()
    guard = BudgetGuard(max_tool_calls=1)
    orchestrator = ToolOrchestrator(bridge, lambda: guard)  # type: ignore[arg-type]
    context = RunContext(runId=1, sessionId=1, userId=1, message="执行工具", creditBudget=10)
    tool = ToolDescriptor(
        toolCode="requires_account",
        toolName="高风险工具",
        estimatedCreditCost=1,
        inputSchema={
            "type": "object",
            "required": ["accountId"],
            "properties": {"accountId": {"type": "string"}},
        },
    )

    result = await orchestrator.execute_with_guard(context, tool, BudgetState(credit_budget=10))

    assert result == {"missing_tool_arguments": ["accountId"]}
    assert bridge.executed is False


@pytest.mark.asyncio
async def test_tool_orchestrator_allows_tool_cost_above_agent_run_budget():
    bridge = FakeBridge()
    guard = BudgetGuard(max_tool_calls=1)
    orchestrator = ToolOrchestrator(bridge, lambda: guard)  # type: ignore[arg-type]
    context = RunContext(runId=1, sessionId=1, userId=1, message="生成一段音乐", creditBudget=20)
    tool = ToolDescriptor(
        toolCode="suno",
        toolName="Suno",
        estimatedCreditCost=55,
        inputSchema={"type": "object", "properties": {}},
    )
    budget = BudgetState(credit_budget=20)

    result = await orchestrator.execute_with_guard(context, tool, budget)

    assert result == {"success": True}
    assert bridge.executed is True
    assert budget.tool_calls == 1
    assert budget.consumed_credits == 0


@pytest.mark.asyncio
async def test_tool_orchestrator_v2_image_empty_prompt_raises_schema_validation():
    bridge = FakeBridge()
    guard = BudgetGuard(max_tool_calls=1)
    orchestrator = ToolOrchestrator(bridge, lambda: guard)  # type: ignore[arg-type]
    context = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="同样生成一张",
        creditBudget=10,
        recentToolCalls=[
            RecentToolCallContext(
                id=7,
                toolCode="gpt_image2",
                taskId=701,
                argumentsJson={"generation_prompt": "冷蓝电影海报，柔焦真实光影。"},
                resultJson={},
                resourceType="IMAGE",
                mediaUrls=["/generated/images/701/image-1.png"],
            )
        ],
    )
    tool = ToolDescriptor(
        toolCode="ofox_gpt_image2",
        toolName="GPT-image2",
        estimatedCreditCost=1,
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

    with pytest.raises(ToolExecutionError) as exc:
        await orchestrator.execute_with_guard(
            context,
            tool,
            BudgetState(credit_budget=10),
            arguments={"operation": "generate", "generation_prompt": ""},
        )

    assert exc.value.error_code == "SCHEMA_VALIDATION"
    assert "Missing field: `generation_prompt`" in str(exc.value)
    assert "The backend will not infer or compose image prompts" in str(exc.value)
    assert getattr(exc.value, "details")["latestGeneratedImagePromptPreview"] == "冷蓝电影海报，柔焦真实光影。"
    assert bridge.executed is False


@pytest.mark.asyncio
async def test_tool_orchestrator_v2_image_ignores_legacy_prompt_field_requiredness():
    bridge = FakeBridge()
    guard = BudgetGuard(max_tool_calls=1)
    orchestrator = ToolOrchestrator(bridge, lambda: guard)  # type: ignore[arg-type]
    context = RunContext(runId=1, sessionId=1, userId=1, message="生成一张图", creditBudget=10)
    tool = ToolDescriptor(
        toolCode="ofox_gpt_image2",
        toolName="GPT-image2",
        estimatedCreditCost=1,
        inputSchema={
            "type": "object",
            "properties": {
                "operation": {"type": "string"},
                "generation_prompt": {"type": "string"},
                "base_image_ref": {"type": "string"},
                "references": {"type": "array"},
            },
        },
        fields=[
            {
                "fieldKey": "prompt",
                "fieldName": "画面描述",
                "fieldType": "textarea",
                "required": True,
                "executionRequired": True,
                "userRequired": True,
                "agentFillStrategy": "ask_user",
            }
        ],
    )

    result = await orchestrator.execute_with_guard(
        context,
        tool,
        BudgetState(credit_budget=10),
        arguments={"operation": "generate", "generation_prompt": "完整视觉提示词。"},
    )

    assert result == {"success": True}
    assert bridge.executed is True
    assert bridge.arguments["generation_prompt"] == "完整视觉提示词。"
