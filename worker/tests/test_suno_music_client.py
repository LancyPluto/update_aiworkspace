import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKER_ROOT))

from client.suno_music_client import SunoMusicClient, SunoMusicError, _extract_tracks, _read_audio_bytes


class FakeCreateResponse:
    status_code = 200
    text = ""
    reason = "OK"

    def json(self):
        return {"code": 200, "msg": "success", "data": {"taskId": "task-1"}}


class FakeRecordResponse:
    status_code = 200
    text = ""
    reason = "OK"

    def json(self):
        return {
            "code": 200,
            "msg": "success",
            "data": {
                "taskId": "task-1",
                "status": "SUCCESS",
                "response": {
                    "taskId": "task-1",
                    "sunoData": [
                        {"id": "a", "title": "Song A", "audioUrl": "https://cdn.example/a.mp3", "duration": 180},
                        {"id": "b", "title": "Song B", "audioUrl": "https://cdn.example/b.mp3", "duration": 181},
                    ],
                },
            },
        }


class FakeLegacyRecordResponse(FakeRecordResponse):
    def json(self):
        return {
            "code": 200,
            "msg": "success",
            "data": {
                "taskId": "task-1",
                "status": "SUCCESS",
                "sunoData": [
                    {"id": "a", "title": "Song A", "audioUrl": "https://cdn.example/a.mp3", "duration": 180},
                ],
            },
        }


class FakeValidationErrorResponse:
    status_code = 413
    text = "prompt too long"
    reason = "Payload Too Large"


class SunoMusicClientTest(unittest.TestCase):
    def test_generate_creates_task_polls_and_extracts_tracks(self):
        client = SunoMusicClient()
        with patch("client.suno_music_client.requests.request", side_effect=[FakeCreateResponse(), FakeRecordResponse()]) as request:
            result = client.generate(
                model="V5",
                prompt="city sunrise pop",
                base_url="https://api.sunoapi.org",
                api_key="secret",
                params={"instrumental": True, "style": "cinematic pop"},
            )

        self.assertEqual(result.task_id, "task-1")
        self.assertEqual(len(result.tracks), 2)
        self.assertEqual(result.tracks[0].audio_url, "https://cdn.example/a.mp3")
        self.assertEqual(result.tracks[1].title, "Song B")
        create_payload = request.call_args_list[0].kwargs["json"]
        self.assertEqual(create_payload["model"], "V5")
        self.assertEqual(create_payload["instrumental"], True)
        self.assertEqual(create_payload["callBackUrl"], "https://example.com/suno-callback")
        self.assertNotIn("style", create_payload)
        self.assertEqual(request.call_args_list[1].kwargs["params"]["taskId"], "task-1")

    def test_extract_tracks_reads_nested_response_suno_data(self):
        detail = {
            "taskId": "task-1",
            "status": "SUCCESS",
            "response": {
                "taskId": "task-1",
                "sunoData": [
                    {"id": "8551", "audioUrl": "https://cdn.example/song.mp3", "title": "Iron Man", "duration": 198.44},
                ],
            },
        }
        tracks = _extract_tracks(detail)
        self.assertEqual(len(tracks), 1)
        self.assertEqual(tracks[0].audio_url, "https://cdn.example/song.mp3")
        self.assertEqual(tracks[0].title, "Iron Man")

    def test_generate_supports_legacy_flat_suno_data(self):
        client = SunoMusicClient()
        with patch("client.suno_music_client.requests.request", side_effect=[FakeCreateResponse(), FakeLegacyRecordResponse()]):
            result = client.generate(
                model="V5",
                prompt="city sunrise pop",
                base_url="https://api.sunoapi.org",
                api_key="secret",
                params={},
            )
        self.assertEqual(len(result.tracks), 1)

    def test_simple_mode_strips_advanced_fields(self):
        client = SunoMusicClient()
        payload = client._build_payload(
            model="V5_5",
            prompt="short relaxing tune",
            params={
                "customMode": False,
                "style": "cinematic pop",
                "title": "Should Drop",
                "negativeTags": "noise",
                "vocalGender": "male",
                "styleWeight": 0.8,
                "personaId": "persona_123",
            },
            api_key="secret",
        )
        self.assertFalse(payload["customMode"])
        self.assertEqual(payload["prompt"], "short relaxing tune")
        self.assertEqual(payload["model"], "V5_5")
        self.assertEqual(payload["callBackUrl"], "https://example.com/suno-callback")
        self.assertNotIn("style", payload)
        self.assertNotIn("title", payload)
        self.assertNotIn("negativeTags", payload)
        self.assertNotIn("vocalGender", payload)
        self.assertNotIn("styleWeight", payload)
        self.assertNotIn("personaId", payload)

    def test_callback_url_can_be_configured(self):
        client = SunoMusicClient()
        with patch("client.suno_music_client.settings") as mock_settings:
            mock_settings.suno_callback_url = "https://worker.example/suno/hook"
            mock_settings.suno_music_model = "V5"
            payload = client._build_payload(model="V5", prompt="test", params={}, api_key="secret")
        self.assertEqual(payload["callBackUrl"], "https://worker.example/suno/hook")

    def test_advanced_mode_maps_persona_and_vocal_gender(self):
        client = SunoMusicClient()
        payload = client._build_payload(
            model="V5_5",
            prompt="[Verse] Night city lights",
            params={
                "customMode": True,
                "instrumental": False,
                "style": "electronic pop",
                "title": "Night City",
                "vocalGender": "female",
                "personaId": "voice_123",
                "personaModel": "voice_persona",
                "styleWeight": 0.65,
            },
            api_key="secret",
        )
        self.assertTrue(payload["customMode"])
        self.assertEqual(payload["style"], "electronic pop")
        self.assertEqual(payload["title"], "Night City")
        self.assertEqual(payload["vocalGender"], "f")
        self.assertEqual(payload["personaId"], "voice_123")
        self.assertEqual(payload["personaModel"], "voice_persona")
        self.assertEqual(payload["styleWeight"], 0.65)

    def test_vocal_gender_auto_is_omitted(self):
        client = SunoMusicClient()
        payload = client._build_payload(
            model="V5",
            prompt="lyrics",
            params={"customMode": True, "style": "pop", "title": "Song", "vocalGender": "auto"},
            api_key="secret",
        )
        self.assertNotIn("vocalGender", payload)

    def test_validation_error_is_reported_without_retry_loop(self):
        client = SunoMusicClient()
        with patch("client.suno_music_client.requests.request", return_value=FakeValidationErrorResponse()) as request:
            with self.assertRaises(SunoMusicError) as raised:
                client.generate(
                    model="V5",
                    prompt="city sunrise pop",
                    base_url="https://api.sunoapi.org",
                    api_key="secret",
                    params={},
                )

        self.assertIn("validation failed", str(raised.exception))
        self.assertEqual(request.call_count, 1)

    @patch("client.suno_music_client._ensure_suno_upload_url", return_value="https://tempfile.redpandaai.co/ref.mp3")
    def test_upload_cover_payload_includes_upload_url(self, _mock_upload):
        client = SunoMusicClient()
        payload = client._build_payload(
            model="V5_5",
            prompt="turn into jazz ballad",
            params={
                "generationType": "upload_cover",
                "referenceAudio": "/generated/audio/ref.mp3",
                "customMode": False,
            },
            api_key="secret",
        )
        self.assertEqual(payload["uploadUrl"], "https://tempfile.redpandaai.co/ref.mp3")
        self.assertEqual(payload["prompt"], "turn into jazz ballad")
        self.assertFalse(payload["customMode"])

    @patch("client.suno_music_client._ensure_suno_upload_url", return_value="https://tempfile.redpandaai.co/ref.mp3")
    def test_upload_cover_uses_upload_cover_endpoint(self, _mock_upload):
        client = SunoMusicClient()
        with patch("client.suno_music_client.requests.request", side_effect=[FakeCreateResponse(), FakeRecordResponse()]) as request:
            client.generate(
                model="V5_5",
                prompt="rock remix",
                base_url="https://api.sunoapi.org",
                api_key="secret",
                params={
                    "generationType": "upload_cover",
                    "referenceAudio": "https://cdn.example.com/original.mp3",
                },
            )
        create_url = request.call_args_list[0].args[1]
        self.assertTrue(create_url.endswith("/api/v1/generate/upload-cover"))
        create_payload = request.call_args_list[0].kwargs["json"]
        self.assertEqual(create_payload["uploadUrl"], "https://tempfile.redpandaai.co/ref.mp3")

    def test_upload_cover_requires_reference_audio(self):
        client = SunoMusicClient()
        with self.assertRaises(SunoMusicError) as raised:
            client.generate(
                model="V5_5",
                prompt="rock remix",
                base_url="https://api.sunoapi.org",
                api_key="secret",
                params={"generationType": "upload_cover"},
            )
        self.assertIn("reference audio", str(raised.exception).lower())

    def test_read_audio_bytes_maps_backend_generated_url_to_local_file(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            audio_path = Path(temp_dir) / "uploads" / "20260606" / "ref.mp3"
            audio_path.parent.mkdir(parents=True)
            audio_path.write_bytes(b"fake mp3")

            with patch("client.suno_music_client.settings.generated_media_dir", temp_dir):
                content, content_type = _read_audio_bytes("http://backend:8080/generated/uploads/20260606/ref.mp3")

        self.assertEqual(content, b"fake mp3")
        self.assertEqual(content_type, "audio/mpeg")


if __name__ == "__main__":
    unittest.main()
