import json
import sys
import unittest
from pathlib import Path


WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKER_ROOT))

from client.suno_music_client import SunoGenerationResult, SunoMusicError, SunoMusicInputError, SunoTrack
from handlers.music_generation_handler import MusicGenerationHandler
from task_queue.task_handler_router import TaskHandlerRouter


class FakeBackend:
    def __init__(self, context):
        self.context = context
        self.processing = []
        self.successes = []
        self.failures = []
        self.checkpoints = []
        self.callback_registrations = []

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

    def register_provider_callback(self, task_id, provider_code, trace_id=None):
        self.callback_registrations.append((task_id, provider_code, trace_id))
        return {"callbackUrl": f"https://wlcloudai.com/api/v1/provider-callbacks/suno/music/{'a' * 64}"}

    def get_provider_callback(self, task_id, provider_code, trace_id=None):
        return {}

    def save_provider_checkpoint(self, task_id, checkpoint, expected_version, trace_id=None):
        self.checkpoints.append((task_id, checkpoint, expected_version, trace_id))
        return {"version": expected_version + 1}


class FakeMusicClient:
    def __init__(self, result=None, error=None):
        self.result = result
        self.error = error
        self.calls = []

    def generate(self, **kwargs):
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        if self.result is not None and kwargs.get("submitted_callback"):
            kwargs["submitted_callback"](self.result.task_id)
        return self.result


class FakePersister:
    def __init__(self):
        self.calls = []

    def persist_audio_url(self, **kwargs):
        self.calls.append(kwargs)
        index = kwargs.get("index") or 1
        return {
            "url": f"/generated/audio/{kwargs['task_id']}/audio-{index}.mp3",
            "sourceUrl": kwargs["source_url"],
            "contentType": "audio/mpeg",
        }


class RecordingHandler:
    def __init__(self):
        self.messages = []

    def handle(self, message):
        self.messages.append(message)
        return {"status": "ROUTED"}


class MusicGenerationHandlerTest(unittest.TestCase):
    def test_handler_generates_persists_two_tracks_and_marks_audio_success(self):
        context = {
            "taskId": 201,
            "traceId": "trace-201",
            "status": "QUEUED",
            "toolCode": "music_demo",
            "toolType": "MUSIC_GENERATION",
            "params": {
                "prompt": "warm cinematic pop about a city sunrise",
                "instrumental": True,
            },
            "modelConfig": {
                "provider": "suno_music",
                "modelName": "V5",
                "baseUrl": "https://api.sunoapi.org",
                "apiKey": "secret",
                "extraAuthJson": '{"proxyUrl":"http://127.0.0.1:7890"}',
            },
        }
        result = SunoGenerationResult(
            task_id="suno-task-1",
            tracks=[
                SunoTrack(audio_url="https://cdn.example/a.mp3", title="Sunrise A", source_audio_id="a"),
                SunoTrack(audio_url="https://cdn.example/b.mp3", title="Sunrise B", source_audio_id="b"),
            ],
            metadata={"record": {"status": "SUCCESS"}},
        )
        backend = FakeBackend(context)
        music_client = FakeMusicClient(result=result)
        persister = FakePersister()
        handler = MusicGenerationHandler(backend_client=backend, music_client=music_client, audio_persister=persister)

        handled = handler.handle({"taskId": 201})

        self.assertEqual(handled["status"], "SUCCESS")
        self.assertEqual(handled["externalTaskId"], "suno-task-1")
        self.assertEqual(music_client.calls[0]["prompt"], "warm cinematic pop about a city sunrise")
        self.assertEqual(music_client.calls[0]["model_config"]["extraAuthJson"], '{"proxyUrl":"http://127.0.0.1:7890"}')
        self.assertTrue(music_client.calls[0]["callback_url"].startswith("https://wlcloudai.com/"))
        self.assertEqual(backend.checkpoints[0][1]["taskId"], "suno-task-1")
        self.assertEqual(persister.calls[0]["index"], 1)
        self.assertEqual(persister.calls[1]["index"], 2)
        success_payload = backend.successes[0][1]
        self.assertEqual(success_payload["resourceType"], "AUDIO")
        self.assertEqual(success_payload["billableUnits"], 1)
        content = json.loads(success_payload["contentText"])
        self.assertEqual(content["type"], "AUDIO")
        self.assertEqual(content["provider"], "suno_music")
        self.assertEqual(content["externalTaskId"], "suno-task-1")
        self.assertEqual(content["audios"][0]["url"], "/generated/audio/201/audio-1.mp3")
        self.assertEqual(content["audios"][1]["url"], "/generated/audio/201/audio-2.mp3")
        self.assertEqual(len(content["audios"]), 2)

    def test_handler_marks_failed_when_provider_returns_empty_audio(self):
        context = {
            "taskId": 202,
            "status": "QUEUED",
            "toolType": "MUSIC_GENERATION",
            "params": {"prompt": "ambient piano"},
            "modelConfig": {"provider": "suno_music", "modelName": "V5", "apiKey": "secret"},
        }
        backend = FakeBackend(context)
        music_client = FakeMusicClient(result=SunoGenerationResult(task_id="empty", tracks=[]))
        handler = MusicGenerationHandler(backend_client=backend, music_client=music_client, audio_persister=FakePersister())

        handled = handler.handle({"taskId": 202})

        self.assertEqual(handled["status"], "FAILED")
        self.assertEqual(backend.failures[0][1]["errorCode"], "MODEL_CALL_FAILED")

    def test_handler_applies_model_request_mapping(self):
        context = {
            "taskId": 204,
            "status": "QUEUED",
            "toolType": "MUSIC_GENERATION",
            "params": {"lyricsInput": "mapped sunrise lyrics"},
            "modelConfig": {
                "provider": "suno_music",
                "modelName": "V5",
                "apiKey": "secret",
                "requestMappingJson": '{"version":"1","fieldMap":{"lyricsInput":"prompt"}}',
            },
        }
        generation = SunoGenerationResult(
            task_id="mapped-task",
            tracks=[SunoTrack(audio_url="https://cdn.example/mapped.mp3")],
        )
        backend = FakeBackend(context)
        music_client = FakeMusicClient(result=generation)
        handler = MusicGenerationHandler(
            backend_client=backend,
            music_client=music_client,
            audio_persister=FakePersister(),
        )

        handled = handler.handle({"taskId": 204})

        self.assertEqual(handled["status"], "SUCCESS")
        self.assertEqual(music_client.calls[0]["prompt"], "mapped sunrise lyrics")

    def test_invalid_model_request_mapping_is_a_parameter_error(self):
        context = {
            "taskId": 205,
            "status": "QUEUED",
            "toolType": "MUSIC_GENERATION",
            "params": {"prompt": "ambient piano"},
            "modelConfig": {
                "provider": "suno_music",
                "modelName": "V5",
                "apiKey": "secret",
                "requestMappingJson": "{invalid",
            },
        }
        backend = FakeBackend(context)
        music_client = FakeMusicClient()
        handler = MusicGenerationHandler(
            backend_client=backend,
            music_client=music_client,
            audio_persister=FakePersister(),
        )

        handled = handler.handle({"taskId": 205})

        self.assertEqual(handled["errorCode"], "INVALID_TASK_PARAMS")
        self.assertEqual(music_client.calls, [])

    def test_suno_input_error_is_a_parameter_error(self):
        context = {
            "taskId": 206,
            "status": "QUEUED",
            "toolType": "MUSIC_GENERATION",
            "params": {"generationMode": "replace_section", "prompt": "replace chorus"},
            "modelConfig": {
                "provider": "suno_music",
                "modelName": "V5_5",
                "apiKey": "secret",
            },
        }
        backend = FakeBackend(context)
        music_client = FakeMusicClient(error=SunoMusicInputError("Suno request requires taskId"))
        handler = MusicGenerationHandler(
            backend_client=backend,
            music_client=music_client,
            audio_persister=FakePersister(),
        )

        handled = handler.handle({"taskId": 206})

        self.assertEqual(handled["errorCode"], "INVALID_TASK_PARAMS")
        self.assertEqual(backend.failures[0][1]["errorCode"], "INVALID_TASK_PARAMS")

    def test_router_sends_music_generation_tool_type_to_music_handler(self):
        context = {
            "taskId": 203,
            "status": "QUEUED",
            "toolType": "MUSIC_GENERATION",
        }
        backend = FakeBackend(context)
        music_handler = RecordingHandler()
        router = TaskHandlerRouter(
            text_handler=RecordingHandler(),
            digital_human_handler=RecordingHandler(),
            image_generation_handler=RecordingHandler(),
            music_generation_handler=music_handler,
            text_to_speech_handler=RecordingHandler(),
            video_generation_handler=RecordingHandler(),
            backend_client=backend,
        )

        handled = router.handle({"taskId": 203})

        self.assertEqual(handled["status"], "ROUTED")
        self.assertEqual(len(music_handler.messages), 1)
        self.assertEqual(music_handler.messages[0]["__executionContext"], context)


if __name__ == "__main__":
    unittest.main()
