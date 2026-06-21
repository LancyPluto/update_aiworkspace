from app.core.schemas import RunContext, ToolDescriptor
from app.routing.tool_index import ToolEmbeddingIndex, build_recall_query, tool_embedding_text


def _tools() -> list[ToolDescriptor]:
    return [
        ToolDescriptor(
            toolCode="happyhorse_reference_to_video",
            toolName="参考图生视频",
            description="reference image to video generation 图生视频",
            autoCallable=True,
        ),
        ToolDescriptor(
            toolCode="ofox_gpt_image2",
            toolName="GPT-image2",
            description="text to image generation 文生图",
            autoCallable=True,
        ),
    ]


def test_tool_embedding_text_includes_modality_hints():
    text = tool_embedding_text(_tools()[0])
    assert "happyhorse_reference_to_video" in text
    assert "video" in text.lower() or "图生视频" in text


def test_embedding_index_prefers_video_tool_for_first_frame_query():
    ctx = RunContext(
        runId=1,
        sessionId=1,
        userId=1,
        message="用刚生成的图作首帧，帮我生成一段视频",
        availableTools=_tools(),
    )
    query = build_recall_query(ctx)
    index = ToolEmbeddingIndex.from_context(ctx)
    ranked = index.rank(query, limit=2)

    assert ranked[0] == "happyhorse_reference_to_video"
