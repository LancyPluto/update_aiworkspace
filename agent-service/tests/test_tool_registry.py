from app.core.schemas import RunContext, ToolDescriptor
from app.tools.registry import ToolRegistry


def test_registry_only_matches_auto_callable_tools():
    registry = ToolRegistry(
        RunContext(
            runId=1,
            sessionId=1,
            userId=1,
            message="帮我写小红书笔记",
            availableTools=[
                ToolDescriptor(
                    toolCode="xiaohongshu_copywriting",
                    toolName="Xiaohongshu",
                    description="小红书 笔记",
                    autoCallable=False,
                )
            ],
        )
    )

    assert registry.match_by_intent("帮我写小红书笔记") is None


def test_registry_returns_matching_available_tool():
    registry = ToolRegistry(
        RunContext(
            runId=1,
            sessionId=1,
            userId=1,
            message="帮我优化商品标题",
            availableTools=[
                ToolDescriptor(
                    toolCode="product_title_optimizer",
                    toolName="Title",
                    description="商品标题 标题优化",
                    autoCallable=True,
                )
            ],
        )
    )

    tool = registry.match_by_intent("帮我优化商品标题")
    assert tool is not None
    assert tool.toolCode == "product_title_optimizer"


def test_registry_unknown_tool_returns_none():
    registry = ToolRegistry(RunContext(runId=1, sessionId=1, userId=1, message="hello"))

    assert registry.get("missing") is None
