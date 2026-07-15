import threading

from handlers.generated_video_persister import GeneratedVideoPersister


def test_large_video_preview_runs_outside_the_caller(monkeypatch):
    persister = GeneratedVideoPersister()
    started = threading.Event()
    release = threading.Event()

    def fake_generate(*, original_url, original_bytes):
        assert original_url.endswith("video.mp4")
        assert len(original_bytes) > persister._PREVIEW_THRESHOLD_BYTES
        started.set()
        release.wait(timeout=2)

    monkeypatch.setattr(persister, "_maybe_generate_preview", fake_generate)
    persister._schedule_preview(
        original_url="https://cdn.example/video.mp4",
        original_bytes=b"x" * (persister._PREVIEW_THRESHOLD_BYTES + 1),
    )

    assert started.wait(timeout=1)
    release.set()
