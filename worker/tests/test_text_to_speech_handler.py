import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKER_ROOT))

from client.text_to_speech_client import SpeechGenerationResult
from handlers.generated_audio_persister import GeneratedAudioPersister
from handlers.text_to_speech_handler import TextToSpeechHandler
from task_queue.redis_consumer import TaskHandlerRouter
from utils.tts_config import merge_tts_params


class FakeBackend:
    def __init__(self, context):
        self.context = context
        self.processing = []
        self.successes = []
        self.failures = []
        self.claims = []
        self.renews = []
        self.claim_response = {"claimed": True, "status": "PROCESSING", "reason": "claimed"}

    def claim_task(self, task_id, *, worker_id, claim_token, trace_id=None):
        self.claims.append((task_id, worker_id, claim_token, trace_id))
        return dict(self.claim_response, taskId=task_id, claimToken=claim_token)

    def renew_lease(self, task_id, *, worker_id, claim_token, trace_id=None):
        self.renews.append((task_id, worker_id, claim_token, trace_id))
        return {"claimed": True, "taskId": task_id, "claimToken": claim_token}

    def get_execution_context(self, task_id, trace_id=None):
        return self.context

    def mark_processing(self, task_id, *, progress=None, progress_message=None, trace_id=None, claim_token=None):
        self.processing.append((task_id, progress, progress_message, trace_id, claim_token))
        return {}

    def mark_success(self, task_id, payload, trace_id=None):
        self.successes.append((task_id, payload, trace_id))
        return {}

    def mark_failed(self, task_id, payload, trace_id=None):
        self.failures.append((task_id, payload, trace_id))
        return {}


class FakeTtsClient:
    def __init__(self):
        self.calls = []

    def generate(self, **kwargs):
        self.calls.append(kwargs)
        return SpeechGenerationResult(
            audio_bytes=b"audio-bytes",
            content_type="audio/mpeg",
            extension="mp3",
            metadata={"providerTrace": "trace", "usageCharacters": 14},
        )


class FakePersister:
    def __init__(self):
        self.calls = []

    def persist_audio_bytes(self, **kwargs):
        self.calls.append(kwargs)
        return {
            "url": "/generated/audio/101/audio-1.mp3",
            "sourceUrl": "generated",
            "contentType": kwargs.get("content_type") or "",
        }

    def persist_audio_url(self, **kwargs):
        self.calls.append(kwargs)
        return {"url": kwargs["source_url"], "sourceUrl": kwargs["source_url"]}


class RecordingHandler:
    def __init__(self):
        self.messages = []

    def handle(self, message):
        self.messages.append(message)
        return {"status": "ROUTED"}


class TextToSpeechHandlerTest(unittest.TestCase):
    def test_tts_params_keep_legacy_minimax_group_id_as_model_default(self):
        params = merge_tts_params(
            {
                "minimaxGroupId": "legacy-group",
                "executionOptionsJson": json.dumps({"voice": "English_narrator"}),
            },
            {"speed": 1.1},
        )

        self.assertEqual(
            params,
            {
                "minimaxGroupId": "legacy-group",
                "voice": "English_narrator",
                "speed": 1.1,
            },
        )

    def test_audio_persister_uses_explicit_wav_extension_and_content_type(self):
        persister = GeneratedAudioPersister()
        with patch("handlers.generated_audio_persister.asset_storage") as storage:
            storage.put_bytes_public.return_value = "/generated/audio/106/audio-1.wav"
            storage.local_path.return_value = Path("C:/tmp/audio-1.wav")
            result = persister.persist_audio_bytes(
                task_id=106,
                audio_bytes=b"RIFF-wave",
                content_type="application/octet-stream",
                extension="wav",
            )

        self.assertEqual(result["url"], "/generated/audio/106/audio-1.wav")
        self.assertEqual(result["contentType"], "audio/wav")
        self.assertEqual(storage.put_bytes_public.call_args.args[0], "audio/106/audio-1.wav")
        self.assertEqual(storage.put_bytes_public.call_args.args[2], "audio/wav")

    def test_handler_generates_and_persists_audio(self):
        context = {
            "taskId": 101,
            "traceId": "trace-101",
            "status": "QUEUED",
            "toolCode": "tts_demo",
            "toolType": "TEXT_TO_SPEECH",
            "params": {
                "text": "Hello from TTS",
                "voice": "English_expressive_narrator",
            },
            "modelConfig": {
                "provider": "minimax_speech",
                "modelName": "speech-2.8-hd",
                "baseUrl": "https://api.minimax.io",
                "apiKey": "secret",
            },
        }
        backend = FakeBackend(context)
        tts_client = FakeTtsClient()
        persister = FakePersister()
        handler = TextToSpeechHandler(backend_client=backend, tts_client=tts_client, audio_persister=persister)

        result = handler.handle({"taskId": 101})

        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(tts_client.calls[0]["provider"], "minimax_speech")
        self.assertEqual(tts_client.calls[0]["text"], "Hello from TTS")
        self.assertEqual(persister.calls[0]["audio_bytes"], b"audio-bytes")
        self.assertEqual(backend.successes[0][0], 101)
        success_payload = backend.successes[0][1]
        self.assertEqual(success_payload["resourceType"], "AUDIO")
        self.assertEqual(success_payload["billableUnits"], 1)
        content = json.loads(success_payload["contentText"])
        self.assertEqual(content["audios"][0]["url"], "/generated/audio/101/audio-1.mp3")
        self.assertEqual(content["metadata"]["providerTrace"], "trace")

    def test_handler_applies_model_request_mapping(self):
        context = {
            "taskId": 107,
            "status": "QUEUED",
            "toolType": "TEXT_TO_SPEECH",
            "params": {"scriptInput": "Mapped narration"},
            "modelConfig": {
                "provider": "minimax_speech",
                "modelName": "speech-2.8-hd",
                "apiKey": "secret",
                "requestMappingJson": '{"version":"1","fieldMap":{"scriptInput":"text"}}',
            },
        }
        backend = FakeBackend(context)
        tts_client = FakeTtsClient()
        handler = TextToSpeechHandler(
            backend_client=backend,
            tts_client=tts_client,
            audio_persister=FakePersister(),
        )

        result = handler.handle({"taskId": 107})

        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(tts_client.calls[0]["text"], "Mapped narration")

    def test_handler_reports_character_units_for_per_character_model(self):
        class CharacterUsageTtsClient(FakeTtsClient):
            def generate(self, **kwargs):
                self.calls.append(kwargs)
                return SpeechGenerationResult(
                    audio_url="https://provider.example/qwen-audio.wav",
                    extension="wav",
                    metadata={
                        "providerRequestId": "dashscope-request-105",
                        "billableUnits": 14,
                    },
                )

        context = {
            "taskId": 105,
            "traceId": "trace-105",
            "status": "QUEUED",
            "toolCode": "qwen_tts_demo",
            "toolType": "TEXT_TO_SPEECH",
            "params": {
                "text": "Hello from TTS",
                "voiceId": "Ethan",
                "language": "English",
                "responseFormat": "wav",
                "groupId": "runtime-group",
            },
            "modelConfig": {
                "provider": "dashscope_qwen_tts",
                "modelName": "qwen3-tts-flash",
                "baseUrl": "https://dashscope.aliyuncs.com",
                "apiKey": "secret",
                "billingUnit": "PER_CHARACTER",
                "executionOptionsJson": json.dumps(
                    {
                        "voice": "Cherry",
                        "languageType": "Chinese",
                        "speed": 0.9,
                        "format": "mp3",
                        "minimaxGroupId": "default-group",
                    }
                ),
            },
        }
        backend = FakeBackend(context)
        tts_client = CharacterUsageTtsClient()
        persister = FakePersister()
        handler = TextToSpeechHandler(
            backend_client=backend,
            tts_client=tts_client,
            audio_persister=persister,
        )

        result = handler.handle({"taskId": 105})

        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(backend.successes[0][1]["billableUnits"], 14)
        self.assertEqual(backend.successes[0][1]["providerRequestId"], "dashscope-request-105")
        self.assertEqual(tts_client.calls[0]["params"]["voice"], "Ethan")
        self.assertEqual(tts_client.calls[0]["params"]["languageType"], "English")
        self.assertEqual(tts_client.calls[0]["params"]["speed"], 0.9)
        self.assertEqual(tts_client.calls[0]["params"]["format"], "wav")
        self.assertEqual(tts_client.calls[0]["params"]["minimaxGroupId"], "runtime-group")
        self.assertNotIn("voiceId", tts_client.calls[0]["params"])
        self.assertNotIn("language", tts_client.calls[0]["params"])
        self.assertNotIn("responseFormat", tts_client.calls[0]["params"])
        self.assertNotIn("groupId", tts_client.calls[0]["params"])
        self.assertEqual(persister.calls[0]["extension"], "wav")

    def test_router_sends_text_to_speech_tool_type_to_tts_handler(self):
        context = {
            "taskId": 102,
            "status": "QUEUED",
            "toolType": "TEXT_TO_SPEECH",
        }
        backend = FakeBackend(context)
        tts_handler = RecordingHandler()
        router = TaskHandlerRouter(
            text_handler=RecordingHandler(),
            digital_human_handler=RecordingHandler(),
            image_generation_handler=RecordingHandler(),
            text_to_speech_handler=tts_handler,
            video_generation_handler=RecordingHandler(),
            backend_client=backend,
        )

        result = router.handle({"taskId": 102})

        self.assertEqual(result["status"], "ROUTED")
        self.assertEqual(len(tts_handler.messages), 1)
        self.assertEqual(tts_handler.messages[0]["__executionContext"], context)
        self.assertEqual(len(backend.claims), 1)
        self.assertTrue(tts_handler.messages[0]["__claimToken"])

    def test_router_skips_when_task_claim_is_denied(self):
        context = {
            "taskId": 103,
            "status": "QUEUED",
            "toolType": "TEXT_TO_SPEECH",
        }
        backend = FakeBackend(context)
        backend.claim_response = {"claimed": False, "status": "PROCESSING", "reason": "already_claimed"}
        tts_handler = RecordingHandler()
        router = TaskHandlerRouter(
            text_handler=RecordingHandler(),
            digital_human_handler=RecordingHandler(),
            image_generation_handler=RecordingHandler(),
            text_to_speech_handler=tts_handler,
            video_generation_handler=RecordingHandler(),
            backend_client=backend,
        )

        result = router.handle({"taskId": 103, "traceId": "trace-claim-denied"})

        self.assertEqual(result["status"], "SKIPPED")
        self.assertEqual(result["reason"], "already_claimed")
        self.assertEqual(tts_handler.messages, [])

    def test_backend_client_context_adds_claim_token_to_callbacks(self):
        from client.backend_client import BackendClient, backend_claim_context

        class FakeResponse:
            status_code = 200
            text = "{}"

            def raise_for_status(self):
                return None

            def json(self):
                return {"code": "SUCCESS", "data": {}}

        class CapturingBackendClient(BackendClient):
            def __init__(self):
                self.requests = []
                self.timeout = (3, 15)

            def _request(self, method, path, *, json_body=None, timeout=None, trace_id=None):
                self.requests.append({"method": method, "path": path, "json": json_body, "traceId": trace_id})
                return FakeResponse()

        backend = CapturingBackendClient()

        with backend_claim_context("claim-token-104"):
            backend.mark_success(104, {"resourceType": "AUDIO", "contentText": "{}"}, trace_id="trace-104")
            backend.mark_failed(104, {"errorCode": "MODEL_TIMEOUT", "errorMessage": "timeout"}, trace_id="trace-104")

        self.assertEqual(backend.requests[0]["json"]["claimToken"], "claim-token-104")
        self.assertEqual(backend.requests[1]["json"]["claimToken"], "claim-token-104")
        self.assertEqual(backend.requests[1]["json"]["errorCode"], "MODEL_004")
        self.assertEqual(backend.requests[1]["json"]["failureStage"], "PROVIDER_POLLING")


if __name__ == "__main__":
    unittest.main()
