import pytest

from app.clients.model_client import ModelClientError
from app.core.schemas import ChatMessage
from app.runtime.deep_agents_engine import DeepAgentsRuntimeEngine
from app.tools.stream_preview import extract_stream_preview


def test_extract_stream_preview():
    assert extract_stream_preview("STREAM_PREVIEW:你好世界") == "你好世界"
    assert extract_stream_preview("AI is processing") == ""
    assert extract_stream_preview(None) == ""


class PartialStreamBackend:
    def __init__(self):
        self.events = []
        self.streaming_answers = []

    async def append_event(self, run_id, event):
        self.events.append((run_id, event.eventType, event.eventText, event.eventJson))

    async def upsert_streaming_answer(self, run_id, answer):
        self.streaming_answers.append((run_id, answer))


class FailingStreamModel:
    model_name = "failing-stream-model"

    async def chat_stream(self, messages, tools=None):
        yield "第一段"
        yield "第二段"
        raise ModelClientError("model stream failed: incomplete chunked read")


@pytest.mark.asyncio
async def test_partial_stream_is_persisted_before_model_stream_failure():
    backend = PartialStreamBackend()
    engine = DeepAgentsRuntimeEngine(backend, FailingStreamModel())

    with pytest.raises(ModelClientError):
        await engine._stream_model_answer(1, [ChatMessage(role="user", content="hello")])

    assert [event[1] for event in backend.events] == ["message.delta", "message.delta"]
    assert backend.streaming_answers == [(1, "第一段第二段")]
