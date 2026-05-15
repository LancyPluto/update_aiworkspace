from app.core.schemas import ToolDescriptor
from app.tools.missing_argument_hints import format_missing_tool_arguments_message


def test_formats_from_input_schema_titles_and_placeholders():
    tool = ToolDescriptor(
        toolCode="moments_copywriting_generator",
        toolName="AI 朋友圈文案生成器",
        description="朋友圈",
        autoCallable=True,
        inputSchema={
            "type": "object",
            "required": ["topic", "tone"],
            "properties": {
                "topic": {
                    "type": "string",
                    "title": "文案主题",
                    "description": "例如：周末肩颈放松活动",
                },
                "tone": {
                    "type": "string",
                    "title": "文案风格",
                    "enum": ["亲切", "种草", "专业"],
                },
            },
        },
    )
    text = format_missing_tool_arguments_message(tool, ["topic", "tone"])
    assert "AI 朋友圈文案生成器" in text
    assert "• 文案主题：例如：周末肩颈放松活动" in text
    assert "• 文案风格：可从「" in text and "亲切" in text


def test_fallback_when_schema_minimal():
    tool = ToolDescriptor(
        toolCode="xiaohongshu_copywriting",
        toolName="小红书",
        autoCallable=True,
        inputSchema={"type": "object", "required": ["topic"], "properties": {"topic": {"type": "string"}}},
    )
    text = format_missing_tool_arguments_message(tool, ["topic"])
    assert "主题" in text
    assert "例如" in text
