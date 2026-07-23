import json
from unittest.mock import Mock, patch

import pytest

from client.agnes_video_client import AgnesVideoClient
from client.dashscope_video_client import DashScopeVideoClient
from client.openai_images_client import OpenAIImagesClient
from client.siliconflow_video_client import SiliconFlowVideoClient
from client.suno_music_client import SunoMusicClient
from client.text_to_speech_client import TextToSpeechClient
from utils.model_contract import (
    ModelContractResponseError,
    parse_response_mapping,
    read_json_path,
    read_response_value,
)
from utils.outbound_http import OutboundRequestsClient


def _model_config(mapping: dict) -> dict:
    return {"responseMappingJson": json.dumps(mapping)}


def test_safe_json_path_supports_indexes_and_exact_filters() -> None:
    payload = {
        "choices": [{"message": {"content": "hello"}}],
        "content": [
            {"type": "image", "url": "https://example.com/a.png"},
            {"type": "text", "text": "answer"},
        ],
    }

    assert read_json_path(payload, "choices[0].message.content") == "hello"
    assert read_json_path(payload, "content[type=text].text") == "answer"
    with pytest.raises(ModelContractResponseError, match="invalid response mapping path"):
        read_json_path(payload, "choices[*].message.content")


def test_declared_response_path_does_not_silently_use_legacy_fallback() -> None:
    assert parse_response_mapping({"responseMappingJson": "{ }"}) == {}
    mapping = parse_response_mapping(
        _model_config({"version": "1", "statusPath": "job.state"})
    )

    assert read_response_value(
        {"status": "SUCCESS"},
        mapping,
        "statusPath",
        fallback_paths=("status",),
    ) is None


def test_image_clients_read_mapped_items_urls_and_usage() -> None:
    client = OpenAIImagesClient(
        base_url="https://images.example/v1",
        api_key="test-key",
        model_config=_model_config(
            {
                "version": "1",
                "itemsPath": "result.assets",
                "urlPath": "download.href",
                "usagePath": "metrics",
            }
        ),
    )
    payload = {
        "result": {"assets": [{"download": {"href": "https://cdn.example/image.png"}}]},
        "metrics": {"input_tokens": 11, "output_tokens": 7},
    }

    assert client._extract_image_urls(payload) == ["https://cdn.example/image.png"]
    assert client._resolve_usage(payload, {}, 1) == {
        "promptTokens": 11,
        "completionTokens": 7,
        "totalTokens": 18,
    }

    siliconflow = SiliconFlowVideoClient(
        api_key="test-key",
        model_config=_model_config(
            {"version": "1", "itemsPath": "result.assets", "urlPath": "download.href"}
        ),
    )
    assert siliconflow._extract_image_urls(payload) == ["https://cdn.example/image.png"]


def test_async_video_clients_read_mapped_create_and_terminal_payloads() -> None:
    mapping = _model_config(
        {
            "version": "1",
            "requestIdPath": "job.identifier",
            "statusPath": "job.state",
            "urlPath": "job.asset.href",
            "usagePath": "job.billing",
        }
    )
    dashscope = DashScopeVideoClient(api_key="test-key", model_config=mapping)
    payload = {
        "job": {
            "identifier": "video-1",
            "state": "SUCCEEDED",
            "asset": {"href": "https://cdn.example/video.mp4"},
            "billing": {"output_video_duration": 6},
        }
    }

    assert dashscope._response_task_id(payload) == "video-1"
    assert dashscope._response_status(payload) == "SUCCEEDED"
    assert dashscope._response_video_url(payload) == "https://cdn.example/video.mp4"
    assert dashscope._response_usage(payload) == {"output_video_duration": 6}

    agnes = AgnesVideoClient(api_key="test-key", model_config=mapping)
    assert agnes._response_video_url_or_empty({"job": {"state": "processing"}}) == ""

    agnes_video_url = AgnesVideoClient(
        api_key="test-key",
        model_config=_model_config(
            {"version": "1", "videoUrlPath": "video_url"}
        ),
    )
    assert (
        agnes_video_url._response_video_url_or_empty(
            {"video_url": "https://cdn.example/agnes.mp4"}
        )
        == "https://cdn.example/agnes.mp4"
    )


def test_qwen_and_minimax_tts_read_mapped_audio_fields() -> None:
    qwen = TextToSpeechClient()
    qwen._post_json = Mock(
        return_value={
            "artifact": {"href": "https://cdn.example/speech.wav"},
            "meta": {"request": "req-qwen"},
            "meter": {"characters": 37},
        }
    )
    qwen_result = qwen.generate(
        provider="dashscope_qwen_tts",
        model="qwen3-tts-flash",
        text="mapped audio",
        base_url="https://dashscope.example",
        api_key="test-key",
        params={},
        model_config=_model_config(
            {
                "version": "1",
                "audioPath": "artifact.href",
                "requestIdPath": "meta.request",
                "usagePath": "meter.characters",
            }
        ),
    )
    assert qwen_result.audio_url == "https://cdn.example/speech.wav"
    assert qwen_result.metadata["providerRequestId"] == "req-qwen"
    assert qwen_result.metadata["billableUnits"] == 37

    minimax = TextToSpeechClient()
    minimax._post_json = Mock(
        return_value={
            "base_resp": {"status_code": 0},
            "artifact": {"hex": "617564696f"},
            "meta": {"request": "req-minimax"},
        }
    )
    minimax_result = minimax.generate(
        provider="minimax_speech",
        model="speech-2.8-hd",
        text="mapped audio",
        base_url="https://minimax.example",
        api_key="test-key",
        params={"ttsMode": "sync"},
        model_config=_model_config(
            {
                "version": "1",
                "sync": {
                    "audioPath": "artifact.hex",
                    "requestIdPath": "meta.request",
                },
            }
        ),
    )
    assert minimax_result.audio_bytes == b"audio"
    assert minimax_result.metadata["traceId"] == "req-minimax"


def test_suno_reads_mapped_task_status_items_and_audio_url() -> None:
    create_response = Mock(status_code=200, text="", reason="OK")
    create_response.json.return_value = {
        "code": 200,
        "job": {"identifier": "music-1"},
    }
    record_response = Mock(status_code=200, text="", reason="OK")
    record_response.json.return_value = {
        "code": 200,
        "job": {
            "state": "SUCCESS",
            "outputs": [
                {
                    "title": "Mapped song",
                    "download": {"href": "https://cdn.example/song.mp3"},
                }
            ],
        },
    }
    config = _model_config(
        {
            "version": "1",
            "requestIdPath": "job.identifier",
            "statusPath": "job.state",
            "itemsPath": "job.outputs",
            "urlPath": "download.href",
        }
    )

    with patch.object(
        OutboundRequestsClient,
        "request",
        autospec=True,
        side_effect=[create_response, record_response],
    ):
        result = SunoMusicClient().generate(
            model="V5",
            prompt="mapped music",
            base_url="https://suno.example",
            api_key="test-key",
            params={},
            model_config=config,
        )

    assert result.task_id == "music-1"
    assert result.tracks[0].audio_url == "https://cdn.example/song.mp3"
    assert result.tracks[0].title == "Mapped song"
