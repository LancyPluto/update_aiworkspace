import pytest

from app.clients.model_client import ChatTurnResult
from app.core.schemas import ChatMessage
from app.observability.model_request_audit import AuditedModelClient, ModelRequestAuditRecorder, model_audit_scope


class FakeBackend:
    def __init__(self, fail=False):
        self.fail = fail
        self.batches = []

    async def record_model_request_snapshots(self, run_id, snapshots):
        if self.fail:
            raise RuntimeError("audit storage unavailable")
        self.batches.append((run_id, snapshots))


class FakeModel:
    model_provider = "test-provider"
    model_name = "test-model"

    async def chat(self, messages, tools=None):
        return "ok"

    async def chat_turn(self, messages, tools=None, tool_choice=None):
        return ChatTurnResult(content="ok")


@pytest.mark.asyncio
async def test_records_ordered_model_requests_and_hydrated_skill_metadata():
    backend = FakeBackend()
    recorder = ModelRequestAuditRecorder(backend, 42, flush_delay_seconds=0)
    model = AuditedModelClient(FakeModel(), recorder)
    messages = [ChatMessage(role="system", content='<SkillHydration skill_code="image_edit" version="3">rules</SkillHydration>')]

    with model_audit_scope("tool.retry", 2):
        await model.chat_turn(messages, tools=[{"type": "function"}], tool_choice="auto")
    await model.chat([ChatMessage(role="user", content="hello")])
    await model.flush_audit()

    snapshots = backend.batches[0][1]
    assert [item["requestSequence"] for item in snapshots] == [1, 2]
    assert snapshots[0]["requestStage"] == "tool.retry"
    assert snapshots[0]["iterationNo"] == 2
    assert snapshots[0]["skillCodes"] == ["image_edit"]
    assert snapshots[0]["payload"]["contentSha256"]


@pytest.mark.asyncio
async def test_audit_write_failure_does_not_fail_model_result():
    backend = FakeBackend(fail=True)
    model = AuditedModelClient(FakeModel(), ModelRequestAuditRecorder(backend, 9, flush_delay_seconds=0))

    assert await model.chat([ChatMessage(role="user", content="still generate")]) == "ok"
    await model.flush_audit()
