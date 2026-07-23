import base64
import sys
import io
import tarfile
import unittest
from pathlib import Path
from unittest.mock import patch

import requests


WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKER_ROOT))

from client.text_to_speech_client import TextToSpeechClient, TextToSpeechError


class FakeJsonResponse:
    status_code = 200
    text = ""

    def raise_for_status(self):
        return None

    def json(self):
        return {
            "base_resp": {"status_code": 0},
            "data": {"audio": "617564696f"},
            "trace_id": "provider-trace",
        }


class FakeAsyncCreateResponse:
    status_code = 200
    text = ""

    def raise_for_status(self):
        return None

    def json(self):
        return {
            "base_resp": {"status_code": 0},
            "task_id": "task-1",
            "usage_characters": 5,
        }


class FakeAsyncQueryResponse:
    status_code = 200
    text = ""

    def raise_for_status(self):
        return None

    def json(self):
        return {
            "base_resp": {"status_code": 0},
            "status": "success",
            "file_id": "file-1",
        }


class FakeAudioFileResponse:
    status_code = 200
    text = ""
    content = b"async-audio"
    headers = {"Content-Type": "application/octet-stream"}

    def raise_for_status(self):
        return None


class FakeDashScopeResponse:
    status_code = 200
    text = ""

    def raise_for_status(self):
        return None

    def json(self):
        return {
            "output": {
                "audio": {
                    "url": "https://dashscope-result.example/audio.wav",
                }
            },
            "request_id": "dashscope-request-1",
            "usage": {"characters": 13},
        }


class FakeDashScopeSpeechSynthesizerDataResponse:
    status_code = 200
    text = ""

    def raise_for_status(self):
        return None

    def json(self):
        return {
            "output": {
                "audio": {
                    "data": base64.b64encode(b"qwen-audio").decode("ascii"),
                    "id": "audio-117",
                }
            },
            "request_id": "dashscope-request-117",
            "usage": {"characters": 5},
        }


class FakeDashScopeSpeechSynthesizerUrlResponse:
    status_code = 200
    text = ""

    def raise_for_status(self):
        return None

    def json(self):
        return {
            "output": {
                "audio": {
                    "url": "https://dashscope-result.example/qwen-audio.mp3",
                    "id": "audio-url-117",
                }
            },
            "request_id": "dashscope-request-url-117",
        }


class TextToSpeechClientTest(unittest.TestCase):
    def test_qwen_audio_tts_plus_uses_speech_synthesizer_contract(self):
        client = TextToSpeechClient()
        with patch(
            "client.text_to_speech_client.requests.post",
            return_value=FakeDashScopeSpeechSynthesizerDataResponse(),
        ) as post:
            result = client.generate(
                provider="dashscope_qwen_tts",
                model="qwen-audio-3.0-tts-plus",
                text="你好世界",
                base_url="https://ws-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                api_key="dashscope-secret",
                params={
                    "voice": "longanlingxin",
                    "format": "opus",
                    "sampleRate": 22050,
                    "volume": 60,
                    "rate": 1.1,
                    "bitRate": 128,
                    "pitch": 0.9,
                    "seed": 117,
                    "languageHints": ["zh", "en"],
                    "instruction": "温柔自然",
                    "enableSsml": True,
                },
            )

        self.assertEqual(
            post.call_args.args[0],
            "https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer",
        )
        self.assertEqual(
            post.call_args.kwargs["json"],
            {
                "model": "qwen-audio-3.0-tts-plus",
                "input": {
                    "text": "你好世界",
                    "voice": "longanlingxin",
                    "format": "opus",
                    "sample_rate": 22050,
                    "volume": 60,
                    "rate": 1.1,
                    "bit_rate": 128,
                    "pitch": 0.9,
                    "seed": 117,
                    "language_hints": ["zh", "en"],
                    "instruction": "温柔自然",
                    "enable_ssml": True,
                },
            },
        )
        self.assertEqual(result.audio_bytes, b"qwen-audio")
        self.assertEqual(result.content_type, "audio/ogg")
        self.assertEqual(result.extension, "opus")
        self.assertEqual(result.metadata["providerRequestId"], "dashscope-request-117")
        self.assertEqual(result.metadata["audioId"], "audio-117")
        self.assertEqual(result.metadata["billableUnits"], 5)

    def test_qwen_audio_tts_plus_accepts_output_audio_url(self):
        with patch(
            "client.text_to_speech_client.requests.post",
            return_value=FakeDashScopeSpeechSynthesizerUrlResponse(),
        ):
            result = TextToSpeechClient().generate(
                provider="dashscope_qwen_tts",
                model="qwen-audio-3.0-tts-plus",
                text="Hello product",
                base_url="https://dashscope.aliyuncs.com",
                api_key="dashscope-secret",
                params={"format": "wav", "voice": "custom-voice-id"},
            )

        self.assertEqual(result.audio_url, "https://dashscope-result.example/qwen-audio.mp3")
        self.assertEqual(result.extension, "mp3")
        self.assertEqual(result.metadata["audioId"], "audio-url-117")

    def test_qwen_audio_id_mapping_is_metadata_not_audio_content(self):
        client = TextToSpeechClient()
        model_config = {
            "responseMappingJson": {
                "version": "1",
                "audioPaths": ["artifact.data", "artifact.id"],
                "audioIdPath": "artifact.id",
            }
        }
        with patch.object(
            client,
            "_post_json",
            return_value={
                "artifact": {
                    "data": base64.b64encode(b"mapped-audio").decode("ascii"),
                    "id": "mapped-audio-id",
                }
            },
        ):
            result = client.generate(
                provider="dashscope_qwen_tts",
                model="qwen-audio-3.0-tts-plus",
                text="Hello product",
                base_url="https://dashscope.aliyuncs.com",
                api_key="dashscope-secret",
                params={"format": "wav", "voice": "custom-voice-id"},
                model_config=model_config,
            )

        self.assertEqual(result.audio_bytes, b"mapped-audio")
        self.assertEqual(result.metadata["audioId"], "mapped-audio-id")

        with patch.object(
            client,
            "_post_json",
            return_value={"artifact": {"id": "mapped-audio-id"}},
        ):
            with self.assertRaisesRegex(TextToSpeechError, "missing output.audio.url or data"):
                client.generate(
                    provider="dashscope_qwen_tts",
                    model="qwen-audio-3.0-tts-plus",
                    text="Hello product",
                    base_url="https://dashscope.aliyuncs.com",
                    api_key="dashscope-secret",
                    params={"format": "wav", "voice": "custom-voice-id"},
                    model_config=model_config,
                )

    def test_qwen_audio_tts_plus_validates_voice_and_opus_bit_rate(self):
        client = TextToSpeechClient()
        with self.assertRaisesRegex(TextToSpeechError, "voice is required"):
            client.generate(
                provider="dashscope_qwen_tts",
                model="qwen-audio-3.0-tts-plus",
                text="Hello product",
                base_url="https://dashscope.aliyuncs.com",
                api_key="dashscope-secret",
                params={"format": "wav"},
            )

        with self.assertRaisesRegex(TextToSpeechError, "only supported for opus"):
            client.generate(
                provider="dashscope_qwen_tts",
                model="qwen-audio-3.0-tts-plus",
                text="Hello product",
                base_url="https://dashscope.aliyuncs.com",
                api_key="dashscope-secret",
                params={"format": "wav", "voice": "custom-voice-id", "bitRate": 128},
            )

        for bit_rate in (5, 511):
            with self.subTest(bit_rate=bit_rate):
                with self.assertRaisesRegex(TextToSpeechError, "between 6 and 510"):
                    client.generate(
                        provider="dashscope_qwen_tts",
                        model="qwen-audio-3.0-tts-plus",
                        text="Hello product",
                        base_url="https://dashscope.aliyuncs.com",
                        api_key="dashscope-secret",
                        params={"format": "opus", "voice": "custom-voice-id", "bitRate": bit_rate},
                    )

    def test_dashscope_qwen_tts_uses_official_multimodal_generation_contract(self):
        client = TextToSpeechClient()
        with patch(
            "client.text_to_speech_client.requests.post",
            return_value=FakeDashScopeResponse(),
        ) as post:
            result = client.generate(
                provider="dashscope_qwen_tts",
                model="qwen3-tts-flash",
                text="Hello product",
                base_url="https://dashscope.aliyuncs.com/",
                api_key="dashscope-secret",
                params={},
            )

        self.assertEqual(
            post.call_args.args[0],
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
        )
        self.assertEqual(post.call_args.kwargs["headers"]["Authorization"], "Bearer dashscope-secret")
        self.assertEqual(
            post.call_args.kwargs["json"],
            {
                "model": "qwen3-tts-flash",
                "input": {
                    "text": "Hello product",
                    "voice": "Cherry",
                    "language_type": "Chinese",
                },
            },
        )
        self.assertEqual(result.audio_url, "https://dashscope-result.example/audio.wav")
        self.assertEqual(result.extension, "wav")
        self.assertEqual(result.metadata["providerRequestId"], "dashscope-request-1")
        self.assertEqual(result.metadata["billableUnits"], 13)
        self.assertEqual(result.metadata["voice"], "Cherry")
        self.assertEqual(result.metadata["languageType"], "Chinese")

    def test_dashscope_qwen_tts_does_not_replay_ambiguous_post(self):
        client = TextToSpeechClient()
        client.max_transport_attempts = 3
        with patch(
            "client.text_to_speech_client.requests.post",
            side_effect=requests.exceptions.ConnectionError("connection dropped"),
        ) as post:
            with self.assertRaises(TextToSpeechError):
                client.generate(
                    provider="dashscope_qwen_tts",
                    model="qwen3-tts-flash",
                    text="Hello product",
                    base_url="https://dashscope.aliyuncs.com",
                    api_key="dashscope-secret",
                    params={},
                )

        self.assertEqual(post.call_count, 1)

    def test_dashscope_qwen_tts_replaces_compatible_workspace_with_official_origin(self):
        client = TextToSpeechClient()
        with patch(
            "client.text_to_speech_client.requests.post",
            return_value=FakeDashScopeResponse(),
        ) as post:
            client.generate(
                provider="dashscope_qwen_tts",
                model="qwen3-tts-flash",
                text="Hello product",
                base_url="https://ws-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
                api_key="dashscope-secret",
                params={},
            )

        self.assertEqual(
            post.call_args.args[0],
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
        )

    def test_minimax_async_create_query_and_downloads_file(self):
        client = TextToSpeechClient()
        with patch("client.text_to_speech_client.time.sleep"), patch(
            "client.text_to_speech_client.requests.post",
            return_value=FakeAsyncCreateResponse(),
        ) as post, patch(
            "client.text_to_speech_client.requests.request",
            side_effect=[FakeAsyncQueryResponse(), _fake_tar_audio_response(b"async-audio")],
        ) as request:
            result = client.generate(
                provider="minimax_speech",
                model="speech-2.8-hd",
                text="hello",
                base_url="https://api.minimaxi.com",
                api_key="secret",
                params={"minimaxGroupId": "group-secret"},
            )

        self.assertEqual(result.audio_bytes, b"async-audio")
        self.assertEqual(result.extension, "mp3")
        self.assertEqual(result.metadata["taskId"], "task-1")
        self.assertEqual(result.metadata["fileId"], "file-1")
        self.assertEqual(result.metadata["voice"], "English_expressive_narrator")
        self.assertIn("/v1/t2a_async_v2", post.call_args.args[0])
        self.assertIn("/v1/query/t2a_async_query_v2", request.call_args_list[0].args[1])
        self.assertIn("/v1/files/retrieve_content", request.call_args_list[1].args[1])
        self.assertNotIn("group-secret", request.call_args_list[1].args[1].split("?", 1)[0])

    def test_minimax_sync_retries_retryable_ssl_error_once(self):
        client = TextToSpeechClient()
        with patch("client.text_to_speech_client.time.sleep"), patch(
            "client.text_to_speech_client.requests.post",
            side_effect=[requests.exceptions.SSLError("EOF occurred in violation of protocol"), FakeJsonResponse()],
        ) as post:
            result = client.generate(
                provider="minimax_speech",
                model="speech-2.8-hd",
                text="hello",
                base_url="https://api.minimax.io",
                api_key="secret",
                params={"ttsMode": "sync"},
            )

        self.assertEqual(post.call_count, 2)
        self.assertEqual(result.audio_bytes, b"audio")
        self.assertEqual(result.metadata["traceId"], "provider-trace")
        self.assertEqual(result.metadata["voice"], "English_expressive_narrator")
        headers = post.call_args.kwargs["headers"]
        self.assertEqual(headers["Connection"], "close")
        self.assertEqual(headers["User-Agent"], "ai-tool-market-worker/tts")

    def test_minimax_sync_transport_error_message_contains_safe_debug_context(self):
        client = TextToSpeechClient()
        with patch(
            "client.text_to_speech_client.requests.post",
            side_effect=requests.exceptions.SSLError("EOF occurred in violation of protocol"),
        ):
            with self.assertRaises(TextToSpeechError) as raised:
                client.generate(
                    provider="minimax_speech",
                    model="speech-2.8-hd",
                    text="hello",
                    base_url="https://api.minimax.io",
                    api_key="secret",
                    params={"minimaxGroupId": "group-secret", "ttsMode": "sync"},
                )

        message = str(raised.exception)
        self.assertIn("url=https://api.minimax.io/v1/t2a_v2", message)
        self.assertIn("errorType=SSLError", message)
        self.assertNotIn("group-secret", message)

    def test_siliconflow_speech_reports_actual_default_voice(self):
        client = TextToSpeechClient()
        with patch(
            "client.text_to_speech_client.requests.post",
            return_value=FakeAudioFileResponse(),
        ) as post:
            result = client.generate(
                provider="siliconflow_speech",
                model="FunAudioLLM/CosyVoice2-0.5B",
                text="hello",
                base_url="https://api.siliconflow.cn",
                api_key="secret",
                params={"speed": 1.1, "sampleRate": 24000, "gain": 2.5},
            )

        self.assertEqual(result.metadata["voice"], "FunAudioLLM/CosyVoice2-0.5B:alex")
        self.assertEqual(post.call_args.kwargs["json"]["speed"], 1.1)
        self.assertEqual(post.call_args.kwargs["json"]["sample_rate"], 24000)
        self.assertEqual(post.call_args.kwargs["json"]["gain"], 2.5)


def _fake_tar_audio_response(audio_bytes: bytes):
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as archive:
        info = tarfile.TarInfo("content.mp3")
        info.size = len(audio_bytes)
        archive.addfile(info, io.BytesIO(audio_bytes))
    response = FakeAudioFileResponse()
    response.content = buffer.getvalue()
    return response


if __name__ == "__main__":
    unittest.main()
