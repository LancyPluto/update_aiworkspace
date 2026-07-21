from pathlib import Path
from unittest.mock import MagicMock

import base64
import pytest

from handlers.digital_human_postprocessor import DigitalHumanPostprocessError, DigitalHumanPostprocessor
from handlers.workflow_step_handler import _persist_audio_data_url


def test_persist_audio_data_url_writes_mp3(monkeypatch):
    mock_storage = MagicMock()
    mock_storage.put_bytes.return_value = "https://assets.example/audio/42/voice.mp3"
    monkeypatch.setattr("handlers.workflow_step_handler.asset_storage", mock_storage)

    payload = base64.b64encode(b"fake-mp3-bytes").decode("ascii")
    url = _persist_audio_data_url(42, f"data:audio/mpeg;base64,{payload}")
    assert url == "https://assets.example/audio/42/voice.mp3"
    mock_storage.put_bytes.assert_called_once_with("audio/42/voice.mp3", b"fake-mp3-bytes", "audio/mpeg")


def test_validate_video_file_rejects_html(tmp_path):
    bad = tmp_path / "bad.mp4"
    bad.write_bytes(b"<html>403</html>")
    with pytest.raises(DigitalHumanPostprocessError, match="not a valid mp4"):
        DigitalHumanPostprocessor._validate_video_file(bad)


def test_validate_video_file_accepts_mp4_header(tmp_path):
    good = tmp_path / "good.mp4"
    good.write_bytes(b"\x00\x00\x00\x18ftypisom\x00\x00\x00\x00")
    DigitalHumanPostprocessor._validate_video_file(good)


def test_validate_video_file_does_not_read_entire_asset(tmp_path, monkeypatch):
    good = tmp_path / "large.mp4"
    good.write_bytes(b"\x00\x00\x00\x18ftypisom" + b"x" * 1024)
    monkeypatch.setattr(
        Path,
        "read_bytes",
        lambda _path: (_ for _ in ()).throw(AssertionError("full file read is forbidden")),
    )

    DigitalHumanPostprocessor._validate_video_file(good)
