from app.tools.stream_preview import extract_stream_preview


def test_extract_stream_preview():
    assert extract_stream_preview("STREAM_PREVIEW:你好世界") == "你好世界"
    assert extract_stream_preview("AI is processing") == ""
    assert extract_stream_preview(None) == ""
