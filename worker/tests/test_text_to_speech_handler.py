import json
import sys
import unittest
from pathlib import Path


WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKER_ROOT))

from client.text_to_speech_client import SpeechGenerationResult
from handlers.text_to_speech_handler import TextToSpeechHandler
from task_queue.redis_consumer import TaskHandlerRouter


class FakeBackend:
    def __init__(self, context):
        self.context = context
        self.processing = []
        self.successes = []
        self.failures = []

    def get_execution_context(self, task_id, trace_id=None):
        return self.context

    def mark_processing(self, task_id, *, progress=None, progress_message=None, trace_id=None):
        self.processing.append((task_id, progress, progress_message, trace_id))
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
            metadata={"providerTrace": "trace"},
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


if __name__ == "__main__":
    unittest.main()
