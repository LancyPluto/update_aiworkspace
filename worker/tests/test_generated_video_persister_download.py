from contextlib import nullcontext
from unittest.mock import patch

import requests

from handlers.generated_video_persister import GeneratedVideoPersistError, GeneratedVideoPersister


class FakeResponse:
    def __init__(
        self,
        chunks: list[bytes],
        *,
        fail_after_chunks: bool = False,
        content_length: str | None = None,
    ) -> None:
        self.headers = {"Content-Type": "video/mp4"}
        if content_length is not None:
            self.headers["Content-Length"] = content_length
        self._chunks = chunks
        self._fail_after_chunks = fail_after_chunks

    def raise_for_status(self) -> None:
        return None

    def iter_content(self, chunk_size: int):
        yield from self._chunks
        if self._fail_after_chunks:
            raise requests.exceptions.ChunkedEncodingError("incomplete read")


def test_generated_video_download_retries_after_incomplete_read() -> None:
    incomplete = FakeResponse([b"partial"], fail_after_chunks=True)
    complete = FakeResponse([b"complete-video"])
    persister = GeneratedVideoPersister()
    assert persister.session.trust_env is False

    with (
        patch(
            "handlers.generated_video_persister.safe_get",
            side_effect=[nullcontext(incomplete), nullcontext(complete)],
        ) as safe_get,
        patch("handlers.generated_video_persister.time.sleep") as sleep,
    ):
        content, content_type = persister._download("https://cdn.klingai.com/result.mp4")

    assert content == b"complete-video"
    assert content_type == "video/mp4"
    assert safe_get.call_count == 2
    assert safe_get.call_args.kwargs["session"] is persister.session
    sleep.assert_called_once()


def test_generated_video_download_rejects_content_length_over_50mb() -> None:
    response = FakeResponse([], content_length=str(50 * 1024 * 1024 + 1))
    persister = GeneratedVideoPersister()

    with patch("handlers.generated_video_persister.safe_get", return_value=nullcontext(response)):
        try:
            persister._download("https://cdn.klingai.com/oversized.mp4")
        except GeneratedVideoPersistError as exc:
            assert "exceeds 50MB" in str(exc)
        else:
            raise AssertionError("expected GeneratedVideoPersistError")


def test_generated_video_download_stops_when_stream_exceeds_limit() -> None:
    response = FakeResponse([b"123456", b"78901"])
    persister = GeneratedVideoPersister()

    with (
        patch.object(GeneratedVideoPersister, "_MAX_DOWNLOAD_BYTES", 10),
        patch("handlers.generated_video_persister.safe_get", return_value=nullcontext(response)),
    ):
        try:
            persister._download("https://cdn.klingai.com/no-length.mp4")
        except GeneratedVideoPersistError as exc:
            assert "exceeds 50MB" in str(exc)
        else:
            raise AssertionError("expected GeneratedVideoPersistError")
