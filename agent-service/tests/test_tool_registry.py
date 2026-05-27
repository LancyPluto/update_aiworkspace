from app.core.schemas import RunContext, ToolDescriptor
from app.tools.registry import ToolRegistry, requested_output_modality, tool_supports_modality


def test_registry_matches_agent_enabled_tools_even_when_confirmation_is_required():
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

    tool = registry.match_by_intent("帮我写小红书笔记")
    assert tool is not None
    assert tool.toolCode == "xiaohongshu_copywriting"


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


def test_registry_matches_image_generation_by_modality():
    registry = ToolRegistry(
        RunContext(
            runId=1,
            sessionId=1,
            userId=1,
            message="我要生成一张漫展写真照片",
            availableTools=[
                ToolDescriptor(
                    toolCode="kling_image_v21",
                    toolName="可灵生图 V2.1",
                    description="高质量图片生成",
                    autoCallable=False,
                )
            ],
        )
    )

    tool = registry.match_by_intent("我要生成一张漫展写真照片")
    assert tool is not None
    assert tool.toolCode == "kling_image_v21"


def test_modality_helpers_distinguish_image_from_video_tools():
    image_tool = ToolDescriptor(
        toolCode="z_image_turbo",
        toolName="Z-image-Turbo",
        description="图片生成，文生图，写真，海报",
    )
    video_tool = ToolDescriptor(
        toolCode="kling_video_v26",
        toolName="可灵生视频V2.6",
        description="视频生成，文生视频，短视频",
    )

    assert requested_output_modality("我要生成一张写真照片") == "image"
    assert tool_supports_modality(image_tool, "image") is True
    assert tool_supports_modality(video_tool, "image") is False
