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


def test_registry_ranks_candidates_by_score():
    registry = ToolRegistry(
        RunContext(
            runId=1,
            sessionId=1,
            userId=1,
            message="帮我写一篇小红书种草笔记",
            availableTools=[
                ToolDescriptor(
                    toolCode="xiaohongshu_copywriting",
                    toolName="Xiaohongshu",
                    description="小红书 种草 笔记",
                    autoCallable=True,
                ),
                ToolDescriptor(
                    toolCode="moments_copywriting_generator",
                    toolName="Moments",
                    description="朋友圈 私域文案",
                    autoCallable=True,
                ),
            ],
        )
    )

    matches = registry.rank_by_intent("帮我写一篇小红书种草笔记")

    assert [match.tool.toolCode for match in matches] == ["xiaohongshu_copywriting"]
    assert matches[0].score > 0


def test_registry_keeps_multiple_weak_candidates_for_router_to_disambiguate():
    registry = ToolRegistry(
        RunContext(
            runId=1,
            sessionId=1,
            userId=1,
            message="帮我优化这个商品内容",
            availableTools=[
                ToolDescriptor(
                    toolCode="product_title_optimizer",
                    toolName="标题优化",
                    description="商品 标题 优化",
                    autoCallable=True,
                ),
                ToolDescriptor(
                    toolCode="wechat_longform_generator",
                    toolName="长文生成",
                    description="商品 长文 内容",
                    autoCallable=True,
                ),
            ],
        )
    )

    matches = registry.rank_by_intent("帮我优化这个商品内容")

    assert len(matches) == 2
    assert matches[0].score >= matches[1].score


def test_registry_unknown_tool_returns_none():
    registry = ToolRegistry(RunContext(runId=1, sessionId=1, userId=1, message="hello"))

    assert registry.get("missing") is None
