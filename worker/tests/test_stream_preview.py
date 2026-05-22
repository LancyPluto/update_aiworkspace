from tools.stream_preview import build_stream_progress_message, extract_stream_preview


def test_build_and_extract_stream_preview():
    message = build_stream_progress_message("五一肩颈护理套餐")
    assert message.startswith("STREAM_PREVIEW:")
    assert extract_stream_preview(message) == "五一肩颈护理套餐"
