from app.tools.backend_tool import _agent_visible_content, _tool_result


def test_digital_human_agent_result_hides_large_temporary_assets():
    raw = """## 数字人视频生成结果

- 最终成片：/generated/digital-human/33/final.mp4
- 字幕文件：/generated/digital-human/33/subtitle.srt
- 原始视频链接：https://example.com/raw.mp4?X-Tos-Signature=secret
- 请求 ID：cgt-123
- 状态：succeeded
- 视频供应商：seedance
- 视频模型：doubao-seedance-1-5-pro-251215
- 视频清晰度：480p

## 生成素材

- 数字人参考图：https://s3.siliconflow.cn/image.png?X-Amz-Security-Token=very-long-token
<audio src="data:audio/wav;base64,qqqqqqqq"></audio>

## 生成信息

- 视频主题：新品护肤套装
- 画面比例：16：9
- 视频时长要求：5s
"""

    visible = _agent_visible_content("digital_human_agent", raw)

    assert visible == "视频已生成，可直接播放或下载：/generated/digital-human/33/final.mp4"
    assert "X-Tos-Signature" not in visible
    assert "X-Amz-Security-Token" not in visible
    assert "data:audio" not in visible


def test_tool_result_omits_inline_base64_media_from_context_payload():
    raw = (
        '{"images":[{"url":"/generated/images/1/image-1.png",'
        '"sourceUrl":"data:image/png;base64,'
        + "A" * 5000
        + '"}]}'
    )

    result = _tool_result("ofox_gpt_image2", 11, {"prompt": "photo"}, 22, "SUCCESS", raw, "IMAGE")

    content = result["data"]["contentText"]
    assert "/generated/images/1/image-1.png" in content
    assert "data:image/png;base64" not in content
    assert "AAAA" not in content
    assert "[inline-media-base64-omitted]" in content
